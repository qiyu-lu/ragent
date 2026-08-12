#!/usr/bin/env python3

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

import copy
import datetime
import json
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

import actionlib
import rospy
from actionlib_msgs.msg import GoalStatus

from iron_ore_robot_demo.msg import ExecuteMissionAction
from iron_ore_robot_demo.msg import ExecuteMissionGoal
from iron_ore_robot_demo.msg import RobotSkill


ACTION_NAME = "/iron_ore/execute_mission"
MAX_BODY_BYTES = 64 * 1024
SAFE_IDENTIFIER = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
PLAN_HASH = re.compile(r"^[0-9a-f]{64}$")
MISSION_PATH = re.compile(r"^/missions/([A-Za-z0-9_-]{1,64})$")
CANCEL_PATH = re.compile(r"^/missions/([A-Za-z0-9_-]{1,64})/cancel$")
SKILL_PARAMETERS = {
    "NAVIGATE_TO_STATION": {"station_id"},
    "TRANSPORT_CONTAINER": {"container_id", "target_station_id"},
}
ACTIVE_STATUSES = {"DISPATCHED", "RUNNING"}
TERMINAL_STATUSES = {"SUCCEEDED", "FAILED", "CANCELED"}


class ProtocolError(ValueError):
    def __init__(self, message, status_code=400):
        super().__init__(message)
        self.status_code = status_code


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def require_identifier(payload, name):
    value = payload.get(name)
    if not isinstance(value, str) or not SAFE_IDENTIFIER.match(value):
        raise ProtocolError("无效的 %s" % name)
    return value


class MissionGateway:
    def __init__(self):
        self._client = actionlib.SimpleActionClient(ACTION_NAME, ExecuteMissionAction)
        self._lock = threading.RLock()
        self._missions = {}
        self._active_mission_id = None

    def dispatch(self, payload):
        normalized = self._validate(payload)
        mission_id = normalized["missionId"]
        with self._lock:
            existing = self._missions.get(mission_id)
            if existing is not None:
                return copy.deepcopy(existing)
            if self._active_mission_id:
                active = self._missions.get(self._active_mission_id)
                if active and active["status"] in ACTIVE_STATUSES:
                    raise ProtocolError("已有机器人任务正在执行", 409)

        if not self._client.wait_for_server(rospy.Duration(2.0)):
            raise ProtocolError("ROS1 Action Server 不可用", 503)

        snapshot = {
            "missionId": mission_id,
            "status": "DISPATCHED",
            "currentStep": 0,
            "totalSteps": len(normalized["steps"]),
            "currentSkillId": None,
            "message": "ROS1 网关已接收任务，等待 Action Server 执行。",
            "events": [],
        }
        self._append_event(snapshot, "DISPATCHED", "", snapshot["message"])
        with self._lock:
            self._missions[mission_id] = snapshot
            self._active_mission_id = mission_id

        goal = self._to_goal(normalized)
        try:
            self._client.send_goal(
                goal,
                done_cb=lambda state, result: self._done(mission_id, state, result),
                active_cb=lambda: self._active(mission_id),
                feedback_cb=lambda feedback: self._feedback(mission_id, feedback),
            )
        except Exception:
            with self._lock:
                self._missions.pop(mission_id, None)
                self._active_mission_id = None
            raise
        return self.get(mission_id)

    def get(self, mission_id):
        with self._lock:
            snapshot = self._missions.get(mission_id)
            if snapshot is None:
                raise ProtocolError("机器人任务不存在", 404)
            return copy.deepcopy(snapshot)

    def cancel(self, mission_id):
        with self._lock:
            snapshot = self._missions.get(mission_id)
            if snapshot is None:
                raise ProtocolError("机器人任务不存在", 404)
            if snapshot["status"] in TERMINAL_STATUSES:
                return copy.deepcopy(snapshot)
            if self._active_mission_id != mission_id:
                raise ProtocolError("机器人任务不是当前活动任务", 409)
            snapshot["message"] = "已请求取消 ROS1 Action，等待服务器确认。"
            self._append_event(snapshot, "CANCEL_REQUESTED", snapshot.get("currentSkillId") or "", snapshot["message"])
        self._client.cancel_goal()
        return self.get(mission_id)

    def _validate(self, payload):
        if not isinstance(payload, dict):
            raise ProtocolError("请求体必须是 JSON 对象")
        mission_id = require_identifier(payload, "missionId")
        task_template_id = require_identifier(payload, "taskTemplateId")
        robot_id = require_identifier(payload, "robotId")
        if payload.get("missionType") != "SAMPLE_TRANSPORT":
            raise ProtocolError("只支持 SAMPLE_TRANSPORT")
        if payload.get("dryRun") is not True:
            raise ProtocolError("ROS1 Demo 强制要求 dryRun=true")
        plan_hash = payload.get("planHash")
        if not isinstance(plan_hash, str) or not PLAN_HASH.match(plan_hash):
            raise ProtocolError("无效的 planHash")
        steps = payload.get("steps")
        if not isinstance(steps, list) or not 1 <= len(steps) <= 10:
            raise ProtocolError("技能步骤数量必须在 1 到 10 之间")

        normalized_steps = []
        for expected_order, step in enumerate(steps, start=1):
            if not isinstance(step, dict) or step.get("order") != expected_order:
                raise ProtocolError("技能顺序必须从 1 连续递增")
            skill_id = step.get("skillId")
            required = SKILL_PARAMETERS.get(skill_id)
            if required is None:
                raise ProtocolError("技能不在白名单：%s" % skill_id)
            parameters = step.get("parameters")
            if not isinstance(parameters, dict) or set(parameters.keys()) != required:
                raise ProtocolError("技能 %s 参数不符合协议" % skill_id)
            if not all(isinstance(value, str) and SAFE_IDENTIFIER.match(value) for value in parameters.values()):
                raise ProtocolError("技能参数包含非法标识符")
            timeout = step.get("timeoutSeconds")
            if not isinstance(timeout, int) or timeout <= 0 or timeout > 600:
                raise ProtocolError("技能超时必须在 1 到 600 秒之间")
            normalized_steps.append({
                "order": expected_order,
                "skillId": skill_id,
                "parameters": parameters,
                "timeoutSeconds": timeout,
            })

        return {
            "missionId": mission_id,
            "taskTemplateId": task_template_id,
            "documentVersion": str(payload.get("documentVersion") or ""),
            "missionType": "SAMPLE_TRANSPORT",
            "robotId": robot_id,
            "dryRun": True,
            "planHash": plan_hash,
            "steps": normalized_steps,
        }

    @staticmethod
    def _to_goal(payload):
        goal = ExecuteMissionGoal()
        goal.mission_id = payload["missionId"]
        goal.task_template_id = payload["taskTemplateId"]
        goal.document_version = payload["documentVersion"]
        goal.mission_type = payload["missionType"]
        goal.robot_id = payload["robotId"]
        goal.dry_run = payload["dryRun"]
        goal.plan_hash = payload["planHash"]
        for item in payload["steps"]:
            skill = RobotSkill()
            skill.order = item["order"]
            skill.skill_id = item["skillId"]
            skill.parameter_keys = list(item["parameters"].keys())
            skill.parameter_values = list(item["parameters"].values())
            skill.timeout_seconds = item["timeoutSeconds"]
            goal.steps.append(skill)
        return goal

    def _active(self, mission_id):
        self._update(mission_id, status="RUNNING", message="ROS1 Action Server 已开始执行。")

    def _feedback(self, mission_id, feedback):
        self._update(
            mission_id,
            status=feedback.status or "RUNNING",
            current_step=feedback.current_step,
            total_steps=feedback.total_steps,
            skill_id=feedback.skill_id,
            message=feedback.message,
            add_event=True,
        )

    def _done(self, mission_id, state, result):
        if result is not None and result.status in TERMINAL_STATUSES:
            status = result.status
            message = result.message
        elif state == GoalStatus.SUCCEEDED:
            status, message = "SUCCEEDED", "ROS1 Action 执行成功。"
        elif state in (GoalStatus.PREEMPTED, GoalStatus.RECALLED):
            status, message = "CANCELED", "ROS1 Action 已取消。"
        else:
            status, message = "FAILED", "ROS1 Action 执行失败，状态码 %s。" % state
        self._update(mission_id, status=status, message=message, add_event=True)
        with self._lock:
            if self._active_mission_id == mission_id:
                self._active_mission_id = None

    def _update(self, mission_id, status=None, current_step=None, total_steps=None,
                skill_id=None, message=None, add_event=False):
        with self._lock:
            snapshot = self._missions.get(mission_id)
            if snapshot is None:
                return
            if status:
                snapshot["status"] = status
            if current_step is not None:
                snapshot["currentStep"] = int(current_step)
            if total_steps is not None:
                snapshot["totalSteps"] = int(total_steps)
            if skill_id is not None:
                snapshot["currentSkillId"] = skill_id or None
            if message is not None:
                snapshot["message"] = message
            if add_event:
                self._append_event(snapshot, snapshot["status"], skill_id or "", message or "")

    @staticmethod
    def _append_event(snapshot, status, skill_id, message):
        snapshot["events"].append({
            "sequence": len(snapshot["events"]),
            "status": status,
            "skillId": skill_id or None,
            "message": message,
            "timestamp": utc_now(),
        })


class GatewayHttpHandler(BaseHTTPRequestHandler):
    server_version = "IronOreROS1Gateway/0.1"

    def do_GET(self):
        match = MISSION_PATH.match(urlparse(self.path).path)
        if not match:
            self._write_json(404, {"error": "接口不存在"})
            return
        self._invoke(lambda: self.server.gateway.get(match.group(1)))

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/missions":
            self._invoke(lambda: self.server.gateway.dispatch(self._read_json()))
            return
        match = CANCEL_PATH.match(path)
        if match:
            self._invoke(lambda: self.server.gateway.cancel(match.group(1)))
            return
        self._write_json(404, {"error": "接口不存在"})

    def _read_json(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise ProtocolError("无效的 Content-Length")
        if length <= 0 or length > MAX_BODY_BYTES:
            raise ProtocolError("请求体大小必须在 1 到 %d 字节之间" % MAX_BODY_BYTES)
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise ProtocolError("请求体不是有效 JSON：%s" % exc)

    def _invoke(self, operation):
        try:
            self._write_json(200, operation())
        except ProtocolError as exc:
            self._write_json(exc.status_code, {"error": str(exc)})
        except Exception as exc:
            rospy.logerr("ROS1 gateway request failed: %s", exc)
            self._write_json(500, {"error": "ROS1 网关内部错误"})

    def _write_json(self, status_code, payload):
        body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        rospy.logdebug("ROS1 gateway HTTP: " + fmt, *args)


class GatewayHttpServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, gateway):
        super().__init__(address, GatewayHttpHandler)
        self.gateway = gateway


if __name__ == "__main__":
    rospy.init_node("iron_ore_robot_gateway")
    host = str(rospy.get_param("~http_host", "127.0.0.1"))
    port = int(rospy.get_param("~http_port", 18081))
    gateway = MissionGateway()
    server = GatewayHttpServer((host, port), gateway)
    thread = threading.Thread(target=server.serve_forever, name="robot-gateway-http", daemon=True)
    thread.start()
    rospy.on_shutdown(server.shutdown)
    rospy.loginfo("ROS1 HTTP gateway listening on http://%s:%d", host, port)
    rospy.spin()
