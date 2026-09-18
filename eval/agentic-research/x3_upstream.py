#!/usr/bin/env python3
"""Experiment X3: research runs against the stub upstream at fixed embedding no-response rates.

Each cell is one checkout × one rate: a fresh stub_upstream.py with that fault rate, a random isolated
run database, and ResearchRunCommand from that checkout's compiled classes over the same scripted tasks
on research_corpus_stub. Checkouts that predate the stub profile are pointed at the stub through
relaxed-binding environment variables (AI_PROVIDERS_*_URL), which every version reads.

All numbers are from the stub upstream, not a real provider. A cell reruns completely when its
predictions are incomplete; finished cells are kept.

  x3_upstream.py --tree before=/path/a --tree after=/path/b --run-dir DIR           # run missing cells, then report
  x3_upstream.py --run-dir DIR --report-only
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
import urllib.request

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from stub_corpus import COLLECTION, DATABASE, DOCUMENTS, classpath  # noqa: E402

MODEL = "qwen3.7-flash-2026-07-15"
INSTRUCTION = "Write in English. Cite only supplied evidence IDs."
SUCCESS, WRONG, HONEST = "success", "wrong_completion", "honest_failure"


def keys_for(index: int) -> list:
    """Tasks 0—24 name one key, 25—49 name two; keys wrap around the 60-document corpus."""
    if index < 25:
        return ["SRC-{:03d}".format(index % DOCUMENTS + 1)]
    first = 2 * (index - 25) % DOCUMENTS + 1
    return ["SRC-{:03d}".format(first), "SRC-{:03d}".format(first % DOCUMENTS + 1)]


def cases(count: int) -> list:
    result = []
    for index in range(count):
        keys = keys_for(index)
        goal = ("What is the recorded value for {}? Cite the source.".format(keys[0]) if len(keys) == 1
                else "Compare the recorded values for {} and {}. Cite the sources.".format(*keys))
        # Only fields every ResearchRunCommand version accepts; the mode comes from the job.
        result.append({"id": "x3-{:02d}".format(index), "collection": COLLECTION, "sourceDocumentIds": [], "goal": goal,
                       "outputType": "REPORT", "cancelAfterMillis": 0, "reply": None, "constraints": []})
    return result


def docker(container: str, *arguments, **kwargs) -> str:
    return subprocess.check_output(["docker", "exec", "-i", container, *arguments], text=True, **kwargs).strip()


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def start_stub(directory: Path, port: int, rate: float, args) -> subprocess.Popen:
    stub = subprocess.Popen([sys.executable, str(HERE / "stub_upstream.py"), "--port", str(port), "--seed", str(args.seed),
                             "--log", str(directory / "stub-requests.jsonl"), "--{}-hang".format(args.target), str(rate)],
                            stdout=(directory / "stub.log").open("a"), stderr=subprocess.STDOUT)
    for _ in range(100):
        try:
            urllib.request.urlopen("http://127.0.0.1:{}/healthz".format(port), timeout=1).read()
            return stub
        except OSError:
            time.sleep(0.1)
    stub.kill()
    raise RuntimeError("stub upstream did not start")


def complete(directory: Path, count: int) -> bool:
    path = directory / "predictions.jsonl"
    return path.exists() and sum(1 for line in path.open() if line.strip()) == count


def run_cell(name: str, tree: Path, rate: float, directory: Path, tasks: list, args) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    for stale in ("predictions.jsonl", "usage.jsonl", "traces.jsonl", "embedding-usage.jsonl", "stub-requests.jsonl"):
        (directory / stale).unlink(missing_ok=True)
    port = free_port()
    base = "http://127.0.0.1:{}".format(port)
    database = "research_p3_x3_" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S") + "_" + os.urandom(3).hex()
    job = {"runDir": str(directory), "cases": tasks, "generateArtifacts": True, "evaluationMode": args.mode, "concurrency": 2,
           "maxCostCny": 1000.0, "generationInstruction": INSTRUCTION, "expectedModel": MODEL, "expectedBudget": {}}
    (directory / "job.json").write_text(json.dumps(job, indent=2) + "\n")
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=tree, text=True).strip()
    record = {"tree": name, "commit": commit, "rate": rate, "target": args.target, "mode": args.mode, "seed": args.seed,
              "tasks": len(tasks), "database": database, "started_at": datetime.now(timezone.utc).isoformat()}
    stub = start_stub(directory, port, rate, args)
    created = False
    try:
        docker(args.container, "sh", "-c", 'exec createdb -U "$POSTGRES_USER" "$1"', "sh", database)
        created = True
        docker(args.container, "sh", "-c", 'exec psql -q -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1', "sh", database,
               input=(tree / "resources/database/schema_pg.sql").read_text())
        pg_port = subprocess.check_output(["docker", "inspect", "--format", '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}',
                                           args.container], text=True).strip()
        env = dict(os.environ, SPRING_PROFILES_ACTIVE="stub", STUB_UPSTREAM_URL=base,
                   AI_PROVIDERS_BAILIAN_URL=base, AI_PROVIDERS_SILICONFLOW_URL=base,
                   AI_PROVIDERS_BAILIAN_API_KEY="stub-only", AI_PROVIDERS_SILICONFLOW_API_KEY="stub-only",
                   BAILIAN_API_KEY="stub-only", SILICONFLOW_API_KEY="stub-only",
                   RESEARCH_TEST_PG_USER=docker(args.container, "sh", "-c", 'printf "%s" "$POSTGRES_USER"'),
                   RESEARCH_TEST_PG_PASSWORD=docker(args.container, "sh", "-c", 'printf "%s" "$POSTGRES_PASSWORD"'),
                   RESEARCH_P3_TEST_URL="jdbc:postgresql://127.0.0.1:{}/{}".format(pg_port, database),
                   RAGENT_POSTGRES_URL="jdbc:postgresql://127.0.0.1:{}/{}".format(pg_port, DATABASE))
        started = time.monotonic()
        with (directory / "java.log").open("w") as log:
            code = subprocess.run(["java", "-Xmx1g", "-cp", classpath(tree), "com.nageoffer.ai.ragent.research.eval.ResearchRunCommand",
                                   str(directory / "job.json")], cwd=tree, env=env, stdout=log, stderr=subprocess.STDOUT).returncode
        record.update(exit_code=code, wall_seconds=round(time.monotonic() - started, 1))
    finally:
        stub.terminate()
        stub.wait(timeout=10)
        if created:
            docker(args.container, "sh", "-c", 'exec dropdb -U "$POSTGRES_USER" "$1"', "sh", database)
        record["finished_at"] = datetime.now(timezone.utc).isoformat()
        (directory / "cell.json").write_text(json.dumps(record, indent=2) + "\n")
    if record.get("exit_code"):
        raise SystemExit("{} exited {}; see {}".format(name, record["exit_code"], directory / "java.log"))


def rows(path: Path) -> list:
    return [json.loads(line) for line in path.open() if line.strip()] if path.exists() else []


def percentile(values: list, q: float):
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * q) - 1)] if values else None


def classify(prediction: dict) -> str:
    run = prediction["run"]
    keys = re.findall(r"SRC-\d{3}", run["brief"]["goal"])
    names = " ".join(source.get("documentName", "") for source in prediction.get("sources") or [])
    covered = all(key in names for key in keys)
    if run["status"] == "COMPLETED":
        return SUCCESS if covered and run.get("artifact") else WRONG
    return HONEST


def summarize_cell(directory: Path) -> dict:
    cell = json.loads((directory / "cell.json").read_text())
    predictions = rows(directory / "predictions.jsonl")
    outcomes = Counter(classify(p) for p in predictions)
    zero_evidence = sum(1 for p in predictions if p["run"]["status"] == "COMPLETED" and not p.get("sources"))
    calls = {p["caseId"]: int(p["run"].get("usage", {}).get("modelCalls") or 0) for p in predictions}
    wasted = sum(calls[p["caseId"]] for p in predictions if classify(p) != SUCCESS)
    embedding = {}
    for row in rows(directory / "embedding-usage.jsonl"):
        embedding[row["call_id"]] = row
    finished = [r for r in embedding.values() if r.get("request_state") == "COMPLETED"]
    stub = Counter((r["endpoint"], r["fault"]) for r in rows(directory / "stub-requests.jsonl"))
    elapsed = [p["elapsedMillis"] for p in predictions]
    total = len(predictions)
    return {"tree": cell["tree"], "commit": cell["commit"][:7], "rate": cell["rate"], "tasks": cell["tasks"], "recorded": total,
            "statuses": dict(Counter(p["run"]["status"] for p in predictions)),
            "success": outcomes[SUCCESS], "wrong_completion": outcomes[WRONG], "honest_failure": outcomes[HONEST],
            "success_rate": outcomes[SUCCESS] / total if total else None,
            "wrong_completion_rate": outcomes[WRONG] / total if total else None,
            "zero_evidence_completed": zero_evidence,
            "p50_ms": percentile(elapsed, .50), "p95_ms": percentile(elapsed, .95),
            "model_calls": sum(calls.values()), "wasted_model_calls": wasted,
            "embedding_requests": len(finished), "embedding_failed": sum(1 for r in finished if not r.get("success")),
            "stub_embedding_requests": sum(v for (endpoint, _), v in stub.items() if endpoint == "embedding"),
            "stub_embedding_no_response": stub[("embedding", "hang")],
            "wall_seconds": cell.get("wall_seconds")}


def report(run_dir: Path) -> None:
    cells = [summarize_cell(d) for d in sorted(run_dir.iterdir()) if (d / "cell.json").exists()]
    cells.sort(key=lambda c: (c["rate"], c["tree"]))
    (run_dir / "x3-summary.json").write_text(json.dumps({"basis": "stub upstream (eval/agentic-research/stub_upstream.py), not a real provider",
                                                         "cells": cells}, indent=2) + "\n")
    lines = ["# X3 upstream faults (stub upstream, not a real provider)", "",
             "| no-response | tree | recorded | COMPLETED/PARTIAL/FAILED | success | wrong completion (zero evidence) | P50 s | P95 s | model calls (wasted) | embedding req (failed) |",
             "| ---: | --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for c in cells:
        s = c["statuses"]
        lines.append("| {:.0%} | {} {} | {}/{} | {}/{}/{} | {:.0%} | {:.0%} ({}) | {:.1f} | {:.1f} | {} ({}) | {} ({}) |".format(
            c["rate"], c["tree"], c["commit"], c["recorded"], c["tasks"], s.get("COMPLETED", 0), s.get("PARTIAL", 0), s.get("FAILED", 0),
            c["success_rate"] or 0, c["wrong_completion_rate"] or 0, c["zero_evidence_completed"],
            (c["p50_ms"] or 0) / 1000, (c["p95_ms"] or 0) / 1000, c["model_calls"], c["wasted_model_calls"],
            c["embedding_requests"], c["embedding_failed"]))
    lines += ["", "success = COMPLETED with an artifact and read evidence from every requested SRC document; "
              "wrong completion = COMPLETED without that evidence (an upstream failure reported as an answer); "
              "other terminal states are honest failures. Wasted model calls are the calls of tasks that did not succeed."]
    (run_dir / "x3-report.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--tree", action="append", default=[], help="NAME=PATH of a compiled checkout; order is the run order")
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--rates", default="0,0.1,0.3,0.5")
    parser.add_argument("--tasks", type=int, default=50)
    parser.add_argument("--mode", choices=("B", "C"), default="B")
    parser.add_argument("--target", choices=("embedding", "chat"), default="embedding")
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--container", default="ragent-iron-ore-dev-postgres-1")
    parser.add_argument("--report-only", action="store_true")
    args = parser.parse_args()
    args.run_dir = args.run_dir.resolve()
    if not args.report_only:
        trees = [(name, Path(path).resolve()) for name, _, path in (t.partition("=") for t in args.tree)]
        tasks = cases(args.tasks)
        args.run_dir.mkdir(parents=True, exist_ok=True)
        for position, rate in enumerate(float(r) for r in args.rates.split(",")):
            # Alternate which checkout goes first at each rate so drift over the run does not favor one side.
            for name, tree in (trees if position % 2 == 0 else trees[::-1]):
                directory = args.run_dir / "{}_{}{:02d}".format(name, args.target, round(rate * 100))
                if complete(directory, len(tasks)) and (directory / "cell.json").exists():
                    continue
                print("[{}] {} at {:.0%} {} no-response".format(datetime.now().strftime("%F %T"), name, rate, args.target), flush=True)
                run_cell(name, tree, rate, directory, tasks, args)
    report(args.run_dir)


if __name__ == "__main__":
    main()
