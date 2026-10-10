#!/usr/bin/env python3
"""Run the rest of the stage-2 evaluation unattended (eval/kq/README.md, stage 2, steps 8-14).

Start it from the repository root in the terminal that has the model keys exported, after stopping any
instance on port 9094. For every configuration it starts the S2 instance itself (boost off, or boost
with the arm's beta), waits until it is up, fills in the missing repeats, checks each run with
verify_boost_beta (boost setting, beta, at most 8 empty-channel sub-questions), sets an invalid run
aside and repeats it, and stops the instance again. Afterwards it compares the boost arms with S2-gate
on the tune split, picks the beta by the rule fixed in the status file, runs that beta on the test
split, compares S2-gate with S1-base on the test split, and writes a summary. Runs that already exist
and pass the check are kept, so the script can be started again after an interruption.

    python3 eval/kq/run_stage2_remaining.py --plan   # show what would run, start nothing
    python3 eval/kq/run_stage2_remaining.py          # about 40 minutes
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import shutil
import socket
import subprocess
import sys
import time
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Mapping, Optional, Sequence, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parent))

import verify_boost_beta as vb  # noqa: E402
from evalkit import read_json, utc_now_iso, write_json  # noqa: E402

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
RUNS = REPO_ROOT / "local-data/kq-eval/runs"
JAR = REPO_ROOT / "bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar"
CONFIG = REPO_ROOT / "local-data/kq-eval/config/application-kq-s2.yaml"
QUESTIONS = REPO_ROOT / "local-data/kq-eval/questions/questions-v1.jsonl"
THRESHOLDS = REPO_ROOT / "eval/kq/manifests/kq-thresholds.json"
PORT = 9094
BASE = f"http://127.0.0.1:{PORT}/api/ragent"
BETAS = (0.1, 0.2, 0.3)
REPEATS = (1, 2, 3)
MAX_EMPTY = 8
MAX_ATTEMPTS = 3
REQUIRED_KEYS = ("BAILIAN_API_KEY", "SILICONFLOW_API_KEY")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--plan", action="store_true", help="print what would run and exit without starting anything")
    parser.add_argument("--startup-timeout", type=int, default=300, help="seconds to wait for the instance")
    return parser.parse_args()


def log(message: str) -> None:
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {message}", flush=True)


def label(arm: str, split: str, repeat: int) -> str:
    return f"{arm}-{split}-r{repeat}"


def boost_arm(beta: float) -> str:
    return f"S2-boost-{beta:g}"


# ---------------------------------------------------------------------------
# Run checks
# ---------------------------------------------------------------------------

class Checker:
    def __init__(self) -> None:
        with (REPO_ROOT / "bootstrap/src/main/resources/kq/terms.csv").open(encoding="utf-8") as handle:
            self.terms = vb.Terms(list(csv.DictReader(handle)))
        metadata_path = REPO_ROOT / "local-data/kq-eval/metadata/doc-metadata-s2-confirmed.json"
        self.metadata = (read_json(metadata_path).get("documents") or {}) if metadata_path.is_file() else {}

    def state(self, run_label: str) -> Tuple[str, str]:
        """('missing' | 'ok' | 'empty' | 'dead' | 'config', detail).

        'dead' means most sub-questions got nothing back from the channel: not the occasional 15 s
        timeout but a missing embedding key or an embedding service that is down, so retrying is useless.
        """

        path = RUNS / run_label / "retrieval.json"
        if not path.is_file():
            return "missing", ""
        report = read_json(path)
        sub_questions = sum(len((item.get("raw_response") or {}).get("results") or [])
                            for item in report.get("details") or [])
        empty = vb.empty_channels(report)
        if sub_questions and empty * 2 > sub_questions:
            return "dead", f"{empty} of {sub_questions} sub-questions got no chunks back"
        config_ok, line = vb.check_run(report, self.terms, self.metadata, list(BETAS), 10 ** 9)
        if not config_ok:
            return "config", line
        if empty > MAX_EMPTY:
            return "empty", f"{empty} empty-channel sub-questions > {MAX_EMPTY}"
        return "ok", line


def set_aside(run_label: str, reason: str) -> Path:
    target = RUNS / f"invalid-{run_label}-{reason}-{datetime.now().strftime('%m%d%H%M%S')}"
    shutil.move(str(RUNS / run_label), str(target))
    return target


# ---------------------------------------------------------------------------
# Instance
# ---------------------------------------------------------------------------

def port_open() -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(1)
        return sock.connect_ex(("127.0.0.1", PORT)) == 0


class Instance:
    """The S2 instance for one configuration; stopped on exit whatever happens."""

    def __init__(self, beta: Optional[float], startup_timeout: int) -> None:
        self.beta = beta
        self.startup_timeout = startup_timeout
        self.process: Optional[subprocess.Popen] = None
        name = "gate" if beta is None else f"boost-{beta:g}"
        self.log_path = RUNS / f"instance-{name}-{datetime.now().strftime('%m%d%H%M%S')}.log"

    def __enter__(self) -> "Instance":
        command = ["java", "-jar", str(JAR), f"--spring.config.additional-location=file:{CONFIG}"]
        if self.beta is not None:
            command += ["--rag.search.metadata-boost.enabled=true", f"--rag.search.metadata-boost.beta={self.beta:g}"]
        log(f"starting instance ({'boost off' if self.beta is None else f'boost beta={self.beta:g}'}), log {self.log_path.name}")
        handle = self.log_path.open("w", encoding="utf-8")
        self.process = subprocess.Popen(command, cwd=str(REPO_ROOT), stdout=handle, stderr=subprocess.STDOUT)
        deadline = time.monotonic() + self.startup_timeout
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise RuntimeError(f"instance exited during startup; last lines of {self.log_path}:\n" + tail(self.log_path))
            if "Started RagentApplication" in self.log_path.read_text(encoding="utf-8", errors="replace"):
                log("instance is up")
                return self
            time.sleep(2)
        raise RuntimeError(f"instance not up after {self.startup_timeout}s; see {self.log_path}")

    def __exit__(self, *exc) -> None:
        if self.process and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        for _ in range(30):
            if not port_open():
                break
            time.sleep(1)
        log("instance stopped")


def tail(path: Path, lines: int = 30) -> str:
    return "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-lines:])


# ---------------------------------------------------------------------------
# Steps
# ---------------------------------------------------------------------------

def run_once(arm: str, split: str, repeat: int) -> None:
    run_label = label(arm, split, repeat)
    commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=str(REPO_ROOT), capture_output=True, text=True).stdout.strip()
    log(f"running {run_label}")
    result = subprocess.run([sys.executable, str(HERE / "run_retrieval.py"), "--base", BASE, "--label", run_label,
                             "--arm", arm, "--split", split, "--repeat-index", str(repeat),
                             "--server-commit", commit, "--questions", str(QUESTIONS)],
                            cwd=str(REPO_ROOT), capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"run_retrieval.py exited {result.returncode} for {run_label} "
                           f"(2 = rerank or rewrite fell back: the keys are not reaching the instance)\n"
                           + "\n".join((result.stdout + result.stderr).splitlines()[-15:]))


def ensure_repeats(checker: Checker, arm: str, split: str) -> None:
    for repeat in REPEATS:
        run_label = label(arm, split, repeat)
        for attempt in range(MAX_ATTEMPTS + 1):
            status, detail = checker.state(run_label)
            if status == "ok":
                log(f"{run_label}: OK  {detail}")
                break
            if status == "dead":
                moved = set_aside(run_label, "no-chunks")
                raise RuntimeError(f"{run_label}: {detail} (moved to {moved.name}); the embedding channel is not "
                                   "answering: check SILICONFLOW_API_KEY in this terminal and the embedding service")
            if status == "config":
                if attempt > 0:
                    raise RuntimeError(f"{run_label} came out with the wrong boost setting from an instance this "
                                       f"script started: {detail}")
                moved = set_aside(run_label, "wrong-boost")
                log(f"{run_label}: left over with the wrong boost setting; moved to {moved.name}")
            if status == "empty":
                moved = set_aside(run_label, "empty-channels")
                log(f"{run_label}: {detail}; moved to {moved.name}")
            if attempt == MAX_ATTEMPTS:
                raise RuntimeError(f"{run_label} still invalid after {MAX_ATTEMPTS} attempts (channel timeouts); "
                                   "start the script again later")
            run_once(arm, split, repeat)


def missing_or_invalid(checker: Checker, arm: str, split: str) -> List[str]:
    return [label(arm, split, r) for r in REPEATS if checker.state(label(arm, split, r))[0] != "ok"]


def reports(arm: str, split: str) -> List[str]:
    return [str(RUNS / label(arm, split, r) / "retrieval.json") for r in REPEATS]


def compare(arms: Sequence[Tuple[str, str]], split: str, output_name: str, baseline: str,
            thresholds: bool) -> dict:
    output = RUNS / output_name
    if output.exists():
        output = RUNS / output_name.replace(".json", f"-{datetime.now().strftime('%m%d%H%M%S')}.json")
    command = [sys.executable, str(HERE / "compare_retrieval_repeats.py"), "--baseline", baseline]
    command += ["--thresholds", str(THRESHOLDS)] if thresholds else ["--max-empty-channel", str(MAX_EMPTY)]
    for arm, arm_split in arms:
        command += ["--arm", arm] + reports(arm, arm_split)
    command += ["--output", str(output)]
    result = subprocess.run(command, cwd=str(REPO_ROOT), capture_output=True, text=True)
    print(result.stdout, end="", flush=True)
    if result.returncode != 0:
        raise RuntimeError(f"comparison failed:\n{result.stdout}{result.stderr}")
    return {**read_json(output), "_output": str(output)}


def choose_beta(comparison: Mapping) -> Optional[float]:
    """Status-file rule: overall MRR improved, overall and numeric Hit@5 not regressed; best overall MRR wins."""

    passed = []
    for arm, block in (comparison.get("comparisons") or {}).items():
        overall = block.get("overall_answerable") or {}
        numeric = (block.get("by_type") or {}).get("numeric") or {}
        if (overall.get("mrr") or {}).get("verdict") == "improved" \
                and (overall.get("hit@5") or {}).get("verdict") != "regressed" \
                and (numeric.get("hit@5") or {}).get("verdict") != "regressed":
            passed.append((overall["mrr"]["candidate_mean"], float(arm.rsplit("-", 1)[1])))
    return max(passed)[1] if passed else None


# ---------------------------------------------------------------------------

def preflight() -> List[str]:
    problems = []
    if port_open():
        problems.append(f"port {PORT} is in use: stop the running instance (Ctrl-C in its terminal) first")
    for key in REQUIRED_KEYS:
        if not os.environ.get(key):
            problems.append(f"{key} is not set in this terminal: run the script where the keys are exported")
    for path in (JAR, CONFIG, QUESTIONS, THRESHOLDS):
        if not path.is_file():
            problems.append(f"missing {path}")
    if shutil.which("java") is None:
        problems.append("java is not on PATH")
    return problems


def main() -> int:
    args = parse_args()
    checker = Checker()
    todo = {f"tune {boost_arm(beta)}": missing_or_invalid(checker, boost_arm(beta), "tune") for beta in BETAS}
    todo["test S2-gate"] = missing_or_invalid(checker, "S2-gate", "test")
    log("plan: " + "; ".join(f"{name}: {', '.join(labels) or 'complete'}" for name, labels in todo.items()))
    log("then: compare boost arms with S2-gate (tune), pick beta, run it on the test split, compare the test split")
    if args.plan:
        problems = preflight()
        print("\n".join(f"  ! {p}" for p in problems) if problems else "  preflight OK")
        return 0
    problems = preflight()
    if problems:
        print("cannot start:\n  - " + "\n  - ".join(problems))
        return 1

    summary: Dict[str, object] = {"kind": "kq-stage2-remaining", "started_at": utc_now_iso()}
    try:
        for beta in BETAS:
            if missing_or_invalid(checker, boost_arm(beta), "tune"):
                with Instance(beta, args.startup_timeout):
                    ensure_repeats(checker, boost_arm(beta), "tune")
            else:
                log(f"{boost_arm(beta)} tune: all repeats OK, nothing to run")

        log("comparing boost arms with S2-gate on the tune split")
        boost_tune = compare([("S2-gate", "tune")] + [(boost_arm(b), "tune") for b in BETAS], "tune",
                             "S2-boost-vs-S2-gate-tune.json", "S2-gate", thresholds=True)
        chosen = choose_beta(boost_tune)
        summary["boost_tune_comparison"] = boost_tune["_output"]
        summary["chosen_beta"] = chosen
        log("no beta passed the boost threshold: boost stays off, no boost test run" if chosen is None
            else f"beta {chosen:g} passed with the best overall MRR")

        if missing_or_invalid(checker, "S2-gate", "test"):
            with Instance(None, args.startup_timeout):
                ensure_repeats(checker, "S2-gate", "test")
        log("comparing S2-gate with S1-base on the test split")
        summary["gate_test_comparison"] = compare([("S1-base", "test"), ("S2-gate", "test")], "test",
                                                  "S2-gate-vs-S1-base-test.json", "S1-base", thresholds=False)["_output"]

        if chosen is not None:
            if missing_or_invalid(checker, boost_arm(chosen), "test"):
                with Instance(chosen, args.startup_timeout):
                    ensure_repeats(checker, boost_arm(chosen), "test")
            log(f"comparing {boost_arm(chosen)} with S2-gate on the test split")
            summary["boost_test_comparison"] = compare(
                [("S2-gate", "test"), (boost_arm(chosen), "test")], "test",
                f"{boost_arm(chosen)}-vs-S2-gate-test.json", "S2-gate", thresholds=False)["_output"]
    except (RuntimeError, OSError) as exc:
        log(f"stopped: {exc}")
        log("fix the cause and start the script again; finished runs are kept")
        return 1
    except KeyboardInterrupt:
        log("interrupted; finished runs are kept, start the script again to continue")
        return 1
    summary["finished_at"] = utc_now_iso()
    summary_path = RUNS / f"stage2-remaining-summary-{datetime.now().strftime('%m%d%H%M%S')}.json"
    write_json(summary_path, summary)
    log(f"all stage-2 runs done; summary {summary_path.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
