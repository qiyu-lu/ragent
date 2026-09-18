#!/usr/bin/env python3
"""Experiment X2: process-level failures of research executors that share one run database.

Each scenario gets a fresh random run database and a fresh stub upstream (no provider calls, no API
cost). Executors are separate JVMs running ResearchExecutorCommand serve, i.e. the production
ResearchRunService with leases, heartbeats, polling takeover, resume and graceful shutdown.

  T0 baseline      no fault; gives the clean model-call count and duration of the workload
  T1 kill -9       the executor holding the runs dies; another instance takes over after the lease
  T2 new instance  a second instance starts while runs are in progress; nothing may be interrupted
  T3 SIGTERM       graceful shutdown hands the runs back at a step boundary
  T4 SIGSTOP       the holder pauses past its lease, another takes over, the paused one resumes and
                   must stop by itself without any accepted write
  T5 poison        every executor that picks the run up dies; the run must fail as EXECUTOR_LOST

All numbers are from the stub upstream. Recovery time is from the fault to the first RUN_STARTED of
the new owner; both clocks are this host's.

  x2_takeover.py --run-dir DIR [--scenarios T0,T1,T2,T3,T4,T5]
  x2_takeover.py --run-dir DIR --report-only
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
from stub_corpus import DATABASE, classpath  # noqa: E402
from x3_upstream import docker, free_port  # noqa: E402

KEYS = ["SRC-{:03d}".format(i) for i in range(1, 61)]
TERMINAL = {"COMPLETED", "PARTIAL", "FAILED", "CANCELLED", "INTERRUPTED"}


def goal(index: int) -> str:
    keys = KEYS[4 * index % 60: 4 * index % 60 + 4]
    return "Compare the recorded values for {}, {}, {} and {}. Cite the sources.".format(*keys)


class Scenario:
    def __init__(self, name: str, directory: Path, args, cp: str):
        self.name, self.directory, self.args, self.cp = name, directory, args, cp
        self.directory.mkdir(parents=True, exist_ok=True)
        self.database = "research_p3_x2_" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S") + "_" + os.urandom(3).hex()
        self.executors, self.ids, self.faults, self.runs = {}, {}, [], {}

    # ---------------------------------------------------------------- lifecycle
    def __enter__(self):
        port = free_port()
        base = "http://127.0.0.1:{}".format(port)
        self.stub = subprocess.Popen([sys.executable, str(HERE / "stub_upstream.py"), "--port", str(port), "--seed", "7",
                                      "--log", str(self.directory / "stub-requests.jsonl"),
                                      "--chat-latency-ms", self.args.chat_latency],
                                     stdout=(self.directory / "stub.log").open("w"), stderr=subprocess.STDOUT)
        docker(self.args.container, "sh", "-c", 'exec createdb -U "$POSTGRES_USER" "$1"', "sh", self.database)
        docker(self.args.container, "sh", "-c", 'exec psql -q -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1', "sh", self.database,
               input=(REPO / "resources/database/schema_pg.sql").read_text())
        pg_port = subprocess.check_output(["docker", "inspect", "--format", '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}',
                                           self.args.container], text=True).strip()
        self.env = dict(os.environ, SPRING_PROFILES_ACTIVE="stub", STUB_UPSTREAM_URL=base,
                        AI_PROVIDERS_BAILIAN_URL=base, AI_PROVIDERS_SILICONFLOW_URL=base,
                        BAILIAN_API_KEY="stub-only", SILICONFLOW_API_KEY="stub-only",
                        RESEARCH_TEST_PG_USER=docker(self.args.container, "sh", "-c", 'printf "%s" "$POSTGRES_USER"'),
                        RESEARCH_TEST_PG_PASSWORD=docker(self.args.container, "sh", "-c", 'printf "%s" "$POSTGRES_PASSWORD"'),
                        RESEARCH_P3_TEST_URL="jdbc:postgresql://127.0.0.1:{}/{}".format(pg_port, self.database),
                        RAGENT_POSTGRES_URL="jdbc:postgresql://127.0.0.1:{}/{}".format(pg_port, DATABASE))
        return self

    def __exit__(self, *_):
        for process in self.executors.values():
            if process.poll() is None:
                process.send_signal(signal.SIGCONT)
                process.kill()
                process.wait(timeout=10)
        self.stub.terminate()
        self.stub.wait(timeout=10)
        if not self.args.keep_databases:
            docker(self.args.container, "sh", "-c", 'exec dropdb -U "$POSTGRES_USER" "$1"', "sh", self.database)

    def start(self, name: str) -> subprocess.Popen:
        command = ["java", "-Xmx768m", "-cp", self.cp, "com.nageoffer.ai.ragent.research.eval.ResearchExecutorCommand", "serve",
                   "--lease-seconds", str(self.args.lease), "--heartbeat-seconds", str(self.args.heartbeat),
                   "--poll-seconds", str(self.args.poll), "--max-concurrent-runs", "4",
                   "--shutdown-grace-seconds", str(self.args.grace)]
        process = subprocess.Popen(command, cwd=REPO, env=self.env, stdout=(self.directory / (name + ".log")).open("a"),
                                   stderr=subprocess.STDOUT)
        self.executors[name] = process
        log = self.directory / (name + ".log")
        self.wait(lambda: " polling; lease " in log.read_text(), 60, name + " did not start")
        line = next(l for l in log.read_text().splitlines() if " polling; lease " in l)
        self.ids[name] = line.split()[1]
        return process

    def fault(self, name: str, kind: str, action) -> None:
        at = time.time()
        action()
        self.faults.append({"executor": name, "fault": kind, "at": at})

    def submit(self, count: int, offset: int = 0) -> None:
        cases = [{"id": "{}-{:02d}".format(self.name, offset + i), "goal": goal(offset + i)} for i in range(count)]
        path = self.directory / "cases-{}.json".format(offset)
        path.write_text(json.dumps(cases, indent=2) + "\n")
        output = subprocess.check_output(["java", "-cp", self.cp, "com.nageoffer.ai.ragent.research.eval.ResearchExecutorCommand",
                                          "submit", str(path)], cwd=REPO, env=self.env, text=True)
        for line in output.splitlines():
            if line.startswith("{"):
                row = json.loads(line)
                self.runs[row["runId"]] = row["caseId"]

    # ---------------------------------------------------------------- database
    def rows(self, query: str) -> list:
        text = docker(self.args.container, "sh", "-c", 'exec psql -U "$POSTGRES_USER" -d "$1" -Atc "$2"', "sh", self.database,
                      "SELECT row_to_json(t) FROM ({}) t".format(query))
        return [json.loads(line) for line in text.splitlines() if line.strip()]

    def runs_state(self) -> list:
        return self.rows("SELECT id, status, epoch, executor_id, takeover_count, error_summary, usage->'modelCalls' AS model_calls, "
                         "(SELECT count(*) FROM t_research_event e WHERE e.run_id = r.id AND e.event_type = 'TOOL_ENDED') AS tools_ended "
                         "FROM t_research_run r")

    def events(self, run_id: str) -> list:
        return self.rows("SELECT sequence_no, task_id, event_type, payload, extract(epoch FROM create_time) AS at "
                         "FROM t_research_event WHERE run_id = '{}' ORDER BY sequence_no".format(run_id))

    def wait(self, condition, seconds: float, message: str):
        until = time.monotonic() + seconds
        while time.monotonic() < until:
            if condition():
                return
            time.sleep(0.2)
        raise RuntimeError(message)

    def running_with_progress(self, count: int, tools: int = 2) -> bool:
        return sum(1 for r in self.runs_state() if r["status"] == "RUNNING" and r["tools_ended"] >= tools) >= count

    def all_terminal(self) -> bool:
        return all(r["status"] in TERMINAL for r in self.runs_state())

    # ---------------------------------------------------------------- result
    def result(self, extra: dict) -> dict:
        runs = []
        chat_requests = sum(1 for line in (self.directory / "stub-requests.jsonl").open() if '"endpoint": "chat"' in line)
        for state in self.runs_state():
            events = self.events(state["id"])
            starts = [e for e in events if e["event_type"] == "RUN_STARTED"]
            ended = Counter(json.dumps([e["payload"].get("tool"), e["payload"].get("arguments")], sort_keys=True)
                            for e in events if e["event_type"] == "TOOL_STARTED" and e["task_id"] == "main"
                            and any(x["event_type"] == "TOOL_ENDED" and x["payload"].get("toolCallId") == e["payload"].get("toolCallId")
                                    for x in events))
            fault_at = min((f["at"] for f in self.faults), default=None)
            takeover = next((s for s in starts[1:] if fault_at is not None and s["at"] >= fault_at), None)
            reads = self.rows("SELECT document_name FROM t_research_evidence WHERE run_id = '{}' AND read".format(state["id"]))
            keys = [k for k in KEYS if k in goal(int(self.runs[state["id"]].rsplit("-", 1)[1]))]
            runs.append({
                "case": self.runs[state["id"]], "status": state["status"], "error": state["error_summary"],
                "executors": [s["payload"].get("executorId") for s in starts],
                "takeovers": state["takeover_count"], "released": sum(1 for e in events if e["event_type"] == "RUN_RELEASED"),
                "resumed": sum(1 for e in events if e["event_type"] == "RESEARCH_RESUMED"),
                "dropped_tool_calls": sum(e["payload"].get("droppedToolCalls", 0) for e in events if e["event_type"] == "RESEARCH_RESUMED"),
                "model_calls": state["model_calls"],
                "recovery_seconds": round(takeover["at"] - fault_at, 2) if takeover else None,
                "invariants": {
                    "one_terminal_event": sum(1 for e in events if e["event_type"] in TERMINAL) == 1,
                    "at_most_one_artifact": sum(1 for e in events if e["event_type"] == "ARTIFACT") <= 1,
                    "contiguous_sequence": [e["sequence_no"] for e in events] == list(range(1, len(events) + 1)),
                    "finished_steps_not_redone": all(v == 1 for v in ended.values()),
                    "no_interrupted_status": state["status"] != "INTERRUPTED",
                },
                "all_keys_read": all(any(k in r["document_name"] for r in reads) for k in keys),
            })
        return {"scenario": self.name, "database": self.database, "faults": self.faults, "chat_requests": chat_requests,
                "runs": sorted(runs, key=lambda r: r["case"]), **extra}


# -------------------------------------------------------------------- scenarios

def t0(s: Scenario) -> dict:
    s.start("a")
    s.submit(2)
    started = time.monotonic()
    s.wait(s.all_terminal, 300, "baseline did not finish")
    return s.result({"wall_seconds": round(time.monotonic() - started, 1)})


def t1(s: Scenario) -> dict:
    a = s.start("a")
    s.submit(2)
    s.wait(lambda: s.running_with_progress(2), 120, "runs did not start on a")
    s.start("b")
    s.fault("a", "kill -9", a.kill)
    s.wait(s.all_terminal, 300, "runs did not finish after kill -9")
    return s.result({})


def t2(s: Scenario) -> dict:
    s.start("a")
    s.submit(2)
    s.wait(lambda: s.running_with_progress(2), 120, "runs did not start on a")
    s.fault("b", "start", lambda: s.start("b"))
    s.wait(s.all_terminal, 300, "runs did not finish")
    return s.result({})


def t3(s: Scenario) -> dict:
    a = s.start("a")
    s.submit(2)
    s.wait(lambda: s.running_with_progress(2), 120, "runs did not start on a")
    s.start("b")
    begun = time.time()
    s.fault("a", "SIGTERM", a.terminate)
    a.wait(timeout=s.args.grace + 30)
    exited = time.time() - begun
    s.wait(s.all_terminal, 300, "runs did not finish after SIGTERM")
    return s.result({"sigterm_exit_seconds": round(exited, 2), "sigterm_exit_code": a.returncode})


def t4(s: Scenario) -> dict:
    a = s.start("a")
    s.submit(2)
    s.wait(lambda: s.running_with_progress(2), 120, "runs did not start on a")
    s.start("b")
    s.fault("a", "SIGSTOP", lambda: a.send_signal(signal.SIGSTOP))
    time.sleep(s.args.lease + 6)
    s.fault("a", "SIGCONT", lambda: a.send_signal(signal.SIGCONT))
    s.wait(s.all_terminal, 300, "runs did not finish after SIGSTOP")
    time.sleep(s.args.heartbeat * 2 + 1)
    lost = sum(1 for line in (s.directory / "a.log").read_text().splitlines() if "Research lease lost" in line)
    return s.result({"old_executor_lease_lost_logs": lost, "old_executor_alive": a.poll() is None})


def t5(s: Scenario) -> dict:
    s.submit(1)
    kills = 0
    for attempt in range(8):
        if s.all_terminal():
            break
        name = "x{}".format(attempt)
        process = s.start(name)
        try:
            s.wait(lambda: s.all_terminal() or any(r["status"] == "RUNNING" and r["executor_id"] == s.ids[name] for r in s.runs_state()),
                   120, "poison run was not picked up")
        except RuntimeError:
            process.kill()
            raise
        if not s.all_terminal():
            s.fault(name, "kill -9", process.kill)
            kills += 1
            process.wait(timeout=10)
    s.wait(s.all_terminal, 120, "poison run did not fail")
    return s.result({"executors_killed": kills})


SCENARIOS = {"T0": t0, "T1": t1, "T2": t2, "T3": t3, "T4": t4, "T5": t5}


def report(run_dir: Path) -> None:
    results = [json.loads(p.read_text()) for p in sorted(run_dir.glob("T*/result.json"))]
    baseline = next((r for r in results if r["scenario"] == "T0"), None)
    clean = max((run["model_calls"] for run in baseline["runs"]), default=None) if baseline else None
    lines = ["# X2 executor takeover (stub upstream, not a real provider)", "",
             "Clean model calls per run (T0): {}. Duplicate model calls = model calls of the run − clean calls.".format(clean), "",
             "| scenario | case | status | executors | takeovers | released | recovery s | model calls (duplicate) | dropped tool calls | invariants | all keys read |",
             "| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | --- | --- |"]
    for result in results:
        names = {}
        for run in result["runs"]:
            for executor in run["executors"]:
                names.setdefault(executor, "ABCDEFGH"[len(names)])
            # 只对完成的任务计算重复调用；失败的任务（如毒任务）本来就没有走完干净路径。
            duplicate = run["model_calls"] - clean if clean is not None and run["model_calls"] is not None and run["status"] == "COMPLETED" else None
            failed = [k for k, v in run["invariants"].items() if not v]
            lines.append("| {} | {} | {} {} | {} | {} | {} | {} | {} ({}) | {} | {} | {} |".format(
                result["scenario"], run["case"], run["status"], run["error"] or "", "→".join(names[e] for e in run["executors"]),
                run["takeovers"], run["released"], run["recovery_seconds"] if run["recovery_seconds"] is not None else "—",
                run["model_calls"], duplicate if duplicate is not None else "—", run["dropped_tool_calls"],
                "all hold" if not failed else "FAILED: " + ", ".join(failed), "yes" if run["all_keys_read"] else "no"))
    extras = [(r["scenario"], {k: v for k, v in r.items() if k not in ("scenario", "database", "faults", "runs")}) for r in results]
    lines += ["", "Scenario details: " + "; ".join("{} {}".format(name, json.dumps(extra)) for name, extra in extras), "",
              "Invariants per run: exactly one terminal event, at most one ARTIFACT event, contiguous event sequence, "
              "no finished tool call (same tool and arguments) executed twice, no INTERRUPTED status."]
    (run_dir / "x2-report.md").write_text("\n".join(lines) + "\n")
    (run_dir / "x2-summary.json").write_text(json.dumps({"basis": "stub upstream, separate executor JVMs sharing one run database",
                                                         "clean_model_calls": clean, "scenarios": results}, indent=2) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--scenarios", default="T0,T1,T2,T3,T4,T5")
    parser.add_argument("--lease", type=int, default=6)
    parser.add_argument("--heartbeat", type=int, default=2)
    parser.add_argument("--poll", type=int, default=1)
    parser.add_argument("--grace", type=int, default=20)
    parser.add_argument("--chat-latency-ms", dest="chat_latency", default="700:900")
    parser.add_argument("--container", default="ragent-iron-ore-dev-postgres-1")
    parser.add_argument("--keep-databases", action="store_true")
    parser.add_argument("--report-only", action="store_true")
    args = parser.parse_args()
    args.run_dir.mkdir(parents=True, exist_ok=True)
    if not args.report_only:
        cp = classpath(REPO)
        for name in args.scenarios.split(","):
            directory = args.run_dir / name
            if (directory / "result.json").exists():
                continue
            print("[{}] {}".format(datetime.now().strftime("%H:%M:%S"), name), flush=True)
            with Scenario(name, directory, args, cp) as scenario:
                result = SCENARIOS[name](scenario)
            result["settings"] = {"lease": args.lease, "heartbeat": args.heartbeat, "poll": args.poll, "grace": args.grace,
                                  "chat_latency_ms": args.chat_latency, "commit": subprocess.check_output(
                                      ["git", "rev-parse", "--short", "HEAD"], cwd=REPO, text=True).strip()}
            (directory / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    report(args.run_dir)
    print((args.run_dir / "x2-report.md").read_text())


if __name__ == "__main__":
    main()
