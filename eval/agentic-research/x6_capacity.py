#!/usr/bin/env python3
"""Experiment X6: how fast 1, 2 or 3 research executors drain a backlog that was submitted to one of them.

Each cell gets a fresh random run database and a fresh stub upstream (no provider calls, no API cost). Executors
are separate JVMs running ResearchExecutorCommand serve, i.e. the production ResearchRunService. All instances start
and idle first (idle polling rate), then instance a creates every task through ResearchRunService.create() — the
same path as the HTTP endpoint: the local pool takes what it can, the rest stays QUEUED in the database and is
claimed by whichever instance polls with a free slot.

Per cell: drain throughput (tasks / minute, first create to last terminal event, database clock), queue wait
(create to first RUN_STARTED), tasks rejected at submit, duplicate claims (a task started more than once, taken
over, released, or breaking the X2 per-run invariants), claim statement calls and application-side time (includes
taking a pooled connection and the transaction), and per-instance idle polling rate.

  x6_capacity.py --run-dir DIR [--tasks 300] [--instances 1,2,3] [--repeat 3] [--poll 5] [--control-poll 1 --control-repeat 1]
  x6_capacity.py --run-dir DIR --report-only

Cells already holding result.json are skipped, so an interrupted run continues where it stopped.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import statistics
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[1]
sys.path.insert(0, str(HERE))
from stub_corpus import classpath  # noqa: E402
from x2_takeover import Scenario, TERMINAL, goal  # noqa: E402
from x3_upstream import percentile  # noqa: E402

NAMES = "abc"


class Cell(Scenario):
    def __init__(self, directory: Path, args, cp: str, instances: int, poll: int):
        super().__init__("X6", directory, args, cp)
        self.database = "research_p3_x6_" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S") + "_" + os.urandom(3).hex()
        self.instances, self.poll = instances, poll

    def start(self, name: str, submit_when: Path | None = None) -> subprocess.Popen:
        command = ["java", "-Xmx768m", "-cp", self.cp, "com.nageoffer.ai.ragent.research.eval.ResearchExecutorCommand", "serve",
                   "--lease-seconds", str(self.args.lease), "--heartbeat-seconds", str(self.args.heartbeat),
                   "--poll-seconds", str(self.poll), "--max-concurrent-runs", str(self.args.slots),
                   "--shutdown-grace-seconds", str(self.args.grace)]
        if submit_when is not None:
            command += ["--submit-when", str(submit_when)]
        log = self.directory / (name + ".log")
        process = subprocess.Popen(command, cwd=REPO, env=self.env, stdout=log.open("a"), stderr=subprocess.STDOUT)
        self.executors[name] = process
        self.wait(lambda: " polling; lease " in log.read_text(), 60, name + " did not start")
        self.ids[name] = next(l for l in log.read_text().splitlines() if " polling; lease " in l).split()[1]
        return process

    def lines(self, name: str, prefix: str) -> list:
        return [json.loads(l[len(prefix):]) for l in (self.directory / (name + ".log")).read_text().splitlines() if l.startswith(prefix)]

    def open_runs(self) -> tuple[int, int]:
        row = self.rows("SELECT count(*) AS total, count(*) FILTER (WHERE status NOT IN ({})) AS open FROM t_research_run".format(
            ", ".join("'{}'".format(s) for s in sorted(TERMINAL))))[0]
        return row["total"], row["open"]

    def run_rows(self) -> list:
        terminal = ", ".join("'{}'".format(s) for s in sorted(TERMINAL))
        return self.rows("""
            SELECT r.id, r.status, r.epoch, r.takeover_count, extract(epoch FROM r.create_time) AS created,
                   s.starts, s.first_start, s.first_executor, s.executors, s.terminals, s.terminal_at, s.artifacts,
                   s.released, s.events, s.max_seq, s.distinct_seq
            FROM t_research_run r CROSS JOIN LATERAL (
                SELECT count(*) FILTER (WHERE event_type = 'RUN_STARTED') AS starts,
                       min(extract(epoch FROM create_time)) FILTER (WHERE event_type = 'RUN_STARTED') AS first_start,
                       (array_agg(payload->>'executorId' ORDER BY sequence_no) FILTER (WHERE event_type = 'RUN_STARTED'))[1] AS first_executor,
                       count(DISTINCT payload->>'executorId') FILTER (WHERE event_type = 'RUN_STARTED') AS executors,
                       count(*) FILTER (WHERE event_type IN ({0})) AS terminals,
                       max(extract(epoch FROM create_time)) FILTER (WHERE event_type IN ({0})) AS terminal_at,
                       count(*) FILTER (WHERE event_type = 'ARTIFACT') AS artifacts,
                       count(*) FILTER (WHERE event_type = 'RUN_RELEASED') AS released,
                       count(*) AS events, max(sequence_no) AS max_seq, count(DISTINCT sequence_no) AS distinct_seq
                FROM t_research_event e WHERE e.run_id = r.id) s""".format(terminal))


def stats_at(samples: list, at: float) -> dict | None:
    """Latest cumulative claim-stats line printed at or before `at` (epoch seconds)."""
    before = [s for s in samples if s["at"] / 1000 <= at]
    return before[-1] if before else None


def rate(samples: list, since: float, until: float) -> float | None:
    """Claim calls per second between the first and last claim-stats lines printed inside [since, until]."""
    inside = [s for s in samples if since <= s["at"] / 1000 <= until]
    first, last = (inside[0], inside[-1]) if inside else (None, None)
    if first is None or last["at"] <= first["at"]:
        return None
    return round((last["calls"] - first["calls"]) / ((last["at"] - first["at"]) / 1000), 3)


def run_cell(c: Cell) -> dict:
    cases_path = c.directory / "cases.json"
    c.start("a", submit_when=cases_path)
    for name in NAMES[1:c.instances]:
        c.start(name)
    idle_from = time.time()
    time.sleep(c.args.idle_seconds)
    idle_until = time.time()
    cases = [{"id": "X6-{:03d}".format(i), "goal": goal(i)} for i in range(c.args.tasks)]
    staging = c.directory / "cases.json.tmp"
    staging.write_text(json.dumps(cases, indent=2) + "\n")
    staging.rename(cases_path)
    c.wait(lambda: len(c.lines("a", "submitted ")) >= c.args.tasks, 120, "submission on a did not finish")
    submitted = c.lines("a", "submitted ")
    rejected = [row for row in submitted if "error" in row]
    budget = max(600.0, c.args.tasks * 20.0 / (c.instances * c.args.slots) * 2)
    until = time.monotonic() + budget
    while True:
        total, pending = c.open_runs()
        if total == len(submitted) - len(rejected) and pending == 0:
            break
        if time.monotonic() > until:
            raise RuntimeError("backlog not drained within {} s ({} of {} open)".format(int(budget), pending, total))
        time.sleep(2)
    drained = time.time()
    time.sleep(6)  # one more claim-stats line after the drain
    for name, process in c.executors.items():
        process.terminate()
    for process in c.executors.values():
        process.wait(timeout=c.args.grace + 30)

    rows = c.run_rows()
    names = {c.ids[n]: n for n in c.ids}
    runs = []
    for row in rows:
        invariants = {
            "started_once": row["starts"] == 1 and row["executors"] == 1,
            "no_takeover_or_release": row["takeover_count"] == 0 and row["released"] == 0,
            "one_terminal_event": row["terminals"] == 1,
            "at_most_one_artifact": row["artifacts"] <= 1,
            "contiguous_sequence": row["max_seq"] == row["events"] == row["distinct_seq"],
            "completed": row["status"] == "COMPLETED",
        }
        runs.append({"id": row["id"], "status": row["status"], "instance": names.get(row["first_executor"], "?"),
                     "queue_wait_s": round(row["first_start"] - row["created"], 3) if row["first_start"] is not None else None,
                     "service_s": round(row["terminal_at"] - row["first_start"], 3)
                     if row["terminal_at"] is not None and row["first_start"] is not None else None,
                     "created": row["created"], "terminal_at": row["terminal_at"],
                     "failed": [k for k, v in invariants.items() if not v]})
    first_created = min(r["created"] for r in runs)
    last_terminal = max(r["terminal_at"] for r in runs if r["terminal_at"] is not None)
    drain = last_terminal - first_created
    waits = [r["queue_wait_s"] for r in runs if r["queue_wait_s"] is not None]
    service = [r["service_s"] for r in runs if r["service_s"] is not None]
    claims = {}
    for name in c.executors:
        samples = c.lines(name, "claim-stats ")
        final = stats_at(samples, drained + 6) or {"calls": 0, "claimed": 0, "totalMicros": 0, "maxMicros": 0}
        claims[name] = {"calls": final["calls"], "claimed": final["claimed"],
                        "mean_ms": round(final["totalMicros"] / final["calls"] / 1000, 2) if final["calls"] else None,
                        "max_ms": round(final["maxMicros"] / 1000, 2),
                        "idle_calls_per_s": rate(samples, idle_from, idle_until)}
    mean_service = statistics.mean(service) if service else None
    return {
        "instances": c.instances, "poll": c.poll, "tasks": c.args.tasks, "database": c.database,
        "submitted": len(submitted), "rejected_at_submit": len(rejected), "rejections": rejected[:5],
        "drain_seconds": round(drain, 2), "throughput_per_min": round(len(runs) / drain * 60, 2),
        "queue_wait_s": {"p50": percentile(waits, .50), "p95": percentile(waits, .95), "max": max(waits, default=None)},
        "service_s": {"mean": round(mean_service, 3) if mean_service else None, "p50": percentile(service, .50),
                      "p95": percentile(service, .95)},
        # 若只受槽位限制，排空吞吐应接近 实例数 × 槽位 ÷ 单任务服务时长。
        "slot_bound_per_min": round(c.instances * c.args.slots / mean_service * 60, 2) if mean_service else None,
        "runs_by_instance": {n: sum(1 for r in runs if r["instance"] == n) for n in c.executors},
        "status": {s: sum(1 for r in runs if r["status"] == s) for s in sorted({r["status"] for r in runs})},
        "runs_invariant_failed": sum(1 for r in runs if r["failed"]),
        "invariant_failures": {k: sum(1 for r in runs if k in r["failed"]) for k in
                               ["started_once", "no_takeover_or_release", "one_terminal_event", "at_most_one_artifact",
                                "contiguous_sequence", "completed"]},
        "claims": claims,
        "claimed_by_polling": sum(v["claimed"] for v in claims.values()),
        "chat_requests": sum(1 for line in (c.directory / "stub-requests.jsonl").open() if '"endpoint": "chat"' in line),
    }


def cells(args) -> list:
    groups = [(args.poll, args.repeat)]
    if args.control_poll != args.poll:
        groups.append((args.control_poll, args.control_repeat))
    return [(poll, instances, repetition) for poll, repeat in groups for repetition in range(1, repeat + 1)
            for instances in [int(n) for n in args.instances.split(",")]]


def mean_range(values: list) -> dict:
    values = [v for v in values if v is not None]
    if not values:
        return {"n": 0}
    return {"n": len(values), "mean": round(statistics.mean(values), 2), "min": min(values), "max": max(values)}


def report(run_dir: Path) -> None:
    loaded = sorted((json.loads(p.read_text()) for p in run_dir.glob("poll*/n*/r*/result.json")),
                    key=lambda r: (r["settings"]["poll"], r["settings"]["instances"], r["settings"]["repetition"]))
    done = [r for r in loaded if "error" not in r]
    errored = [r for r in loaded if "error" in r]
    lines = ["# X6 backlog drain across instances (stub upstream, not a real provider)", "",
             "Every task is created on instance a through ResearchRunService.create(); instances idle for a while before "
             "the submission. Times are from the database clock. Claim time is application-side (pooled connection + "
             "transaction + statements).", "",
             "| poll s | instances | rep | tasks | rejected | drain s | tasks/min | slot-bound tasks/min | wait P50 / P95 / max s "
             "| service mean s | by instance | by polling | invariant failed | claim calls (mean / max ms) | idle claims/s |",
             "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | --- | ---: | ---: | --- | --- |"]
    for r in done:
        s, w = r["settings"], r["queue_wait_s"]
        lines.append("| {} | {} | {} | {} | {} | {} | {} | {} | {} / {} / {} | {} | {} | {} | {} | {} | {} |".format(
            s["poll"], s["instances"], s["repetition"], r["submitted"], r["rejected_at_submit"], r["drain_seconds"],
            r["throughput_per_min"], r["slot_bound_per_min"], w["p50"], w["p95"], w["max"], r["service_s"]["mean"],
            " ".join("{}={}".format(k, v) for k, v in r["runs_by_instance"].items()), r["claimed_by_polling"],
            r["runs_invariant_failed"],
            " ".join("{}={} ({} / {})".format(k, v["calls"], v["mean_ms"], v["max_ms"]) for k, v in r["claims"].items()),
            " ".join("{}={}".format(k, v["idle_calls_per_s"]) for k, v in r["claims"].items())))
    summary = {}
    for r in done:
        key = "poll{}_n{}".format(r["settings"]["poll"], r["settings"]["instances"])
        summary.setdefault(key, []).append(r)
    aggregated = {}
    for key, group in sorted(summary.items()):
        poll, instances = group[0]["settings"]["poll"], group[0]["settings"]["instances"]
        base = summary.get("poll{}_n1".format(poll))
        base_mean = statistics.mean(g["throughput_per_min"] for g in base) if base else None
        throughput = mean_range([g["throughput_per_min"] for g in group])
        aggregated[key] = {
            "poll": poll, "instances": instances, "repetitions": len(group),
            "throughput_per_min": throughput,
            "speedup_vs_1": round(throughput["mean"] / base_mean, 2) if base_mean else None,
            "slot_bound_per_min": mean_range([g["slot_bound_per_min"] for g in group]),
            "queue_wait_p50_s": mean_range([g["queue_wait_s"]["p50"] for g in group]),
            "queue_wait_p95_s": mean_range([g["queue_wait_s"]["p95"] for g in group]),
            "service_mean_s": mean_range([g["service_s"]["mean"] for g in group]),
            "rejected_at_submit": sum(g["rejected_at_submit"] for g in group),
            "runs": sum(g["submitted"] for g in group),
            "runs_invariant_failed": sum(g["runs_invariant_failed"] for g in group),
            "claim_calls": sum(v["calls"] for g in group for v in g["claims"].values()),
            "claim_mean_ms": mean_range([v["mean_ms"] for g in group for v in g["claims"].values()]),
            "claim_max_ms": max((v["max_ms"] for g in group for v in g["claims"].values()), default=None),
            "idle_claims_per_s_per_instance": mean_range([v["idle_calls_per_s"] for g in group for v in g["claims"].values()]),
        }
    lines += ["", "## Over repetitions (mean [min, max])", "",
              "| poll s | instances | reps | tasks/min | speedup vs 1 | slot-bound tasks/min | wait P50 s | wait P95 s "
              "| rejected | invariant failed / runs | claim mean ms | claim max ms | idle claims/s per instance |",
              "| ---: | ---: | ---: | --- | ---: | --- | --- | --- | ---: | --- | --- | ---: | --- |"]

    def fmt(m):
        return "{} [{}, {}]".format(m["mean"], m["min"], m["max"]) if m.get("n") else "—"
    for row in aggregated.values():
        lines.append("| {} | {} | {} | {} | {} | {} | {} | {} | {} | {} / {} | {} | {} | {} |".format(
            row["poll"], row["instances"], row["repetitions"], fmt(row["throughput_per_min"]), row["speedup_vs_1"],
            fmt(row["slot_bound_per_min"]), fmt(row["queue_wait_p50_s"]), fmt(row["queue_wait_p95_s"]), row["rejected_at_submit"],
            row["runs_invariant_failed"], row["runs"], fmt(row["claim_mean_ms"]), row["claim_max_ms"],
            fmt(row["idle_claims_per_s_per_instance"])))
    lines += ["", "Invariants per run: started exactly once by one executor, no takeover or release, exactly one terminal "
              "event, at most one ARTIFACT, contiguous event sequence, status COMPLETED. Slot-bound tasks/min = instances × "
              "slots ÷ mean service time (first RUN_STARTED to terminal event). Percentiles are nearest-rank."]
    if errored:
        lines += ["", "Errored cells (not retried): " + "; ".join(
            "poll{}/n{}/r{:02d} {}".format(r["settings"]["poll"], r["settings"]["instances"], r["settings"]["repetition"], r["error"])
            for r in errored)]
    (run_dir / "x6-report.md").write_text("\n".join(lines) + "\n")
    (run_dir / "x6-summary.json").write_text(json.dumps({
        "basis": "stub upstream, separate executor JVMs sharing one run database; all tasks created on instance a",
        "settings": done[0]["settings"] if done else None, "aggregated": aggregated,
        "errored": errored, "cells": done}, indent=2) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--tasks", type=int, default=300)
    parser.add_argument("--instances", default="1,2,3")
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--slots", type=int, default=2, help="max-concurrent-runs per instance (production default 2)")
    parser.add_argument("--lease", type=int, default=30)
    parser.add_argument("--heartbeat", type=int, default=10)
    parser.add_argument("--poll", type=int, default=5)
    parser.add_argument("--control-poll", type=int, default=1)
    parser.add_argument("--control-repeat", type=int, default=1)
    parser.add_argument("--grace", type=int, default=20)
    parser.add_argument("--idle-seconds", type=int, default=30)
    parser.add_argument("--chat-latency-ms", dest="chat_latency", default="700:900")
    parser.add_argument("--container", default="ragent-iron-ore-dev-postgres-1")
    parser.add_argument("--keep-databases", action="store_true")
    parser.add_argument("--report-only", action="store_true")
    args = parser.parse_args()
    args.run_dir.mkdir(parents=True, exist_ok=True)
    if not args.report_only:
        cp = classpath(REPO)
        commit = subprocess.check_output(["git", "rev-parse", "--short", "HEAD"], cwd=REPO, text=True).strip()
        for poll, instances, repetition in cells(args):
            directory = args.run_dir / "poll{}".format(poll) / "n{}".format(instances) / "r{:02d}".format(repetition)
            if (directory / "result.json").exists():
                continue
            print("[{}] poll {} s, {} instance(s), repetition {}".format(datetime.now().strftime("%H:%M:%S"), poll, instances,
                                                                         repetition), flush=True)
            try:
                with Cell(directory, args, cp, instances, poll) as cell:
                    result = run_cell(cell)
            except (RuntimeError, subprocess.SubprocessError, OSError) as error:
                # 失败的格如实记录，不重试。
                result = {"error": "{}: {}".format(type(error).__name__, error)}
                print("  errored: " + result["error"], flush=True)
            result["settings"] = {"poll": poll, "instances": instances, "repetition": repetition, "tasks": args.tasks,
                                  "slots": args.slots, "max_concurrent_model_calls": 2,
                                  "lease": args.lease, "heartbeat": args.heartbeat, "idle_seconds": args.idle_seconds,
                                  "chat_latency_ms": args.chat_latency, "commit": commit}
            directory.mkdir(parents=True, exist_ok=True)
            (directory / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    report(args.run_dir)
    print((args.run_dir / "x6-report.md").read_text())


if __name__ == "__main__":
    main()
