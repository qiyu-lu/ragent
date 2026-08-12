#!/usr/bin/env python3

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements. See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0.

import re
import time

import actionlib
import rospy

from iron_ore_robot_demo.msg import ExecuteMissionAction
from iron_ore_robot_demo.msg import ExecuteMissionFeedback
from iron_ore_robot_demo.msg import ExecuteMissionResult


ACTION_NAME = "/iron_ore/execute_mission"
SAFE_IDENTIFIER = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
SKILL_PARAMETERS = {
    "NAVIGATE_TO_STATION": {"station_id"},
    "TRANSPORT_CONTAINER": {"container_id", "target_station_id"},
}


class MissionValidationError(ValueError):
    pass


class RobotActionServer:
    def __init__(self):
        self._step_duration = max(0.05, float(rospy.get_param("~step_duration_seconds", 1.0)))
        self._server = actionlib.SimpleActionServer(
            ACTION_NAME, ExecuteMissionAction, execute_cb=self._execute, auto_start=False
        )
        self._server.start()
        rospy.loginfo("ROS1 dry-run Action server ready: %s", ACTION_NAME)

    def _execute(self, goal):
        try:
            steps = self._validate(goal)
        except MissionValidationError as exc:
            result = ExecuteMissionResult(False, "FAILED", str(exc))
            self._server.set_aborted(result, str(exc))
            return

        total = len(steps)
        self._publish(0, total, "RUNNING", "", "任务已接收；仅执行 dry-run 仿真。")
        for step in steps:
            if self._server.is_preempt_requested() or rospy.is_shutdown():
                result = ExecuteMissionResult(False, "CANCELED", "任务已取消；未控制真实设备。")
                self._server.set_preempted(result, result.message)
                return

            parameters = dict(zip(step.parameter_keys, step.parameter_values))
            message = self._describe(step.skill_id, parameters)
            self._publish(step.order, total, "RUNNING", step.skill_id, message)
            if not self._wait_step():
                result = ExecuteMissionResult(False, "CANCELED", "任务已取消；未控制真实设备。")
                self._server.set_preempted(result, result.message)
                return

        result = ExecuteMissionResult(True, "SUCCEEDED", "ROS1 搬运任务仿真完成；未控制真实设备。")
        self._server.set_succeeded(result, result.message)

    def _validate(self, goal):
        if not goal.dry_run:
            raise MissionValidationError("Action Server 拒绝 dry_run=false")
        if goal.mission_type != "SAMPLE_TRANSPORT":
            raise MissionValidationError("只支持 SAMPLE_TRANSPORT")
        for field_name, value in (
            ("mission_id", goal.mission_id),
            ("task_template_id", goal.task_template_id),
            ("robot_id", goal.robot_id),
        ):
            if not SAFE_IDENTIFIER.match(value):
                raise MissionValidationError("无效的 %s" % field_name)
        if not re.match(r"^[0-9a-f]{64}$", goal.plan_hash):
            raise MissionValidationError("无效的 plan_hash")
        if not goal.steps or len(goal.steps) > 10:
            raise MissionValidationError("技能步骤数量必须在 1 到 10 之间")

        expected_order = 1
        for step in goal.steps:
            if step.order != expected_order:
                raise MissionValidationError("技能顺序必须从 1 连续递增")
            expected_order += 1
            required = SKILL_PARAMETERS.get(step.skill_id)
            if required is None:
                raise MissionValidationError("技能不在白名单：%s" % step.skill_id)
            if len(step.parameter_keys) != len(step.parameter_values):
                raise MissionValidationError("技能参数键值数量不一致")
            if len(set(step.parameter_keys)) != len(step.parameter_keys):
                raise MissionValidationError("技能参数键重复")
            parameters = dict(zip(step.parameter_keys, step.parameter_values))
            if set(parameters.keys()) != required:
                raise MissionValidationError("技能 %s 参数不符合协议" % step.skill_id)
            if not all(SAFE_IDENTIFIER.match(value) for value in parameters.values()):
                raise MissionValidationError("技能参数包含非法标识符")
            if step.timeout_seconds <= 0:
                raise MissionValidationError("技能超时必须为正数")
        return goal.steps

    def _publish(self, current, total, status, skill_id, message):
        feedback = ExecuteMissionFeedback()
        feedback.current_step = current
        feedback.total_steps = total
        feedback.status = status
        feedback.skill_id = skill_id
        feedback.message = message
        self._server.publish_feedback(feedback)

    def _wait_step(self):
        deadline = time.monotonic() + self._step_duration
        while time.monotonic() < deadline and not rospy.is_shutdown():
            if self._server.is_preempt_requested():
                return False
            time.sleep(min(0.05, max(0.0, deadline - time.monotonic())))
        return not rospy.is_shutdown()

    @staticmethod
    def _describe(skill_id, parameters):
        if skill_id == "NAVIGATE_TO_STATION":
            return "仿真导航到工位 %s" % parameters["station_id"]
        return "仿真搬运容器 %s 到工位 %s" % (
            parameters["container_id"],
            parameters["target_station_id"],
        )


if __name__ == "__main__":
    rospy.init_node("iron_ore_robot_action_server")
    RobotActionServer()
    rospy.spin()
