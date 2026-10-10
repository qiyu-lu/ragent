#!/usr/bin/env python3
"""Run the whole stage-3 evaluation unattended (knowledge-quality plan §6; eval/kq/README.md, stage 3).

Start it from the repository root in the terminal that has the model keys exported, with nothing listening
on port 9093:

    python3 eval/kq/run_stage3.py --plan   # show what would run, start nothing
    python3 eval/kq/run_stage3.py          # about 80 minutes

What it does, in order, without anything to type in between:

1. builds the jar from the committed tree (it refuses a dirty bootstrap/framework/infra-ai tree, because
   every run records ``git rev-parse HEAD`` as the server commit);
2. snapshots ``ragent_eval_kq_s1`` once into ``local-data/kq-eval/snapshots/`` and applies the full-text
   upgrade (additive and repeatable: a tsvector column, two indexes, two statistics tables);
3. for every arm starts the S1 instance (same configuration as S1-base) with that arm's switches, rebuilds
   the full-text index the first time, fills in the missing tune repeats and checks each run: the arm's
   stages are present, no kept chunk scores under the arm's threshold, the blend order matches the arm's
   alpha, the vector channel is empty in at most 8 sub-questions. Invalid runs are moved aside and redone;
4. compares the arms by the rule written in the status file before any stage-3 code (S3-rrf and S3-blend
   against S1-base, S3-thr against S3-rrf), picks at most one arm, runs it on the test split and compares
   it with S1-base there;
5. stops the instance and writes ``runs/stage3-summary-*.json``.

Finished valid runs are kept, so after an interruption the same command continues where it stopped.
"""

from __future__ import annotations

import argparse
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

import compare_retrieval_repeats as cmp  # noqa: E402
from evalkit import ApiClient, ApiError, read_json, utc_now_iso, write_json  # noqa: E402

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
RUNS = REPO_ROOT / "local-data/kq-eval/runs"
SNAPSHOTS = REPO_ROOT / "local-data/kq-eval/snapshots"
JAR = REPO_ROOT / "bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar"
CONFIG = REPO_ROOT / "local-data/kq-eval/config/application-kq-s1.yaml"
QUESTIONS = REPO_ROOT / "local-data/kq-eval/questions/questions-v1.jsonl"
THRESHOLDS = REPO_ROOT / "eval/kq/manifests/kq-thresholds.json"
UPGRADE = REPO_ROOT / "resources/database/upgrades/v1.1.0/261010_knowledge_chunk_full_text.sql"
PG_CONTAINER = "ragent-iron-ore-dev-postgres-1"
DATABASE = "ragent_eval_kq_s1"
PORT = 9093
BASE = f"http://127.0.0.1:{PORT}/api/ragent"
REPEATS = (1, 2, 3)
MAX_EMPTY = 8
MAX_ATTEMPTS = 3
REQUIRED_KEYS = ("BAILIAN_API_KEY", "SILICONFLOW_API_KEY")
CODE_PATHS = ("bootstrap", "framework", "infra-ai", "pom.xml")

FULL_TEXT = "--rag.search.channels.full-text.enabled=true"
THRESHOLDS_GRID = (0.1, 0.2, 0.3)
ALPHAS = (0.3, 0.5, 0.7)

# Fixed on 2026-10-10 before any stage-3 code or run (status file, "阶段 3 的臂与选臂"). The RRF weight
# stays 1.0: with recall 20 per channel and a rerank pool limit of 40 the weight cannot change the pool.
ARMS: Dict[str, List[str]] = {"S3-rrf": [FULL_TEXT]}
for _t in THRESHOLDS_GRID:
    ARMS[f"S3-thr-{_t:g}"] = [FULL_TEXT, "--rag.search.rerank-threshold.enabled=true",
                              f"--rag.search.rerank-threshold.min-score={_t:g}"]
for _a in ALPHAS:
    ARMS[f"S3-blend-{_a:g}"] = [FULL_TEXT, "--rag.search.score-blend.enabled=true",
                                f"--rag.search.score-blend.alpha={_a:g}"]

STAGE_FULL_TEXT = "channel-FullTextSearch"
STAGE_THRESHOLD = "post-RerankThreshold"
STAGE_BLEND = "post-ScoreBlend"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--plan", action="store_true", help="print what would run and exit without starting anything")
    parser.add_argument("--skip-build", action="store_true", help="use the existing jar (it must come from HEAD)")
    parser.add_argument("--startup-timeout", type=int, default=300, help="seconds to wait for the instance")
    return parser.parse_args()


def log(message: str) -> None:
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {message}", flush=True)


def label(arm: str, split: str, repeat: int) -> str:
    return f"{arm}-{split}-r{repeat}"


def arm_kind(arm: str) -> Tuple[str, Optional[float]]:
    """('rrf' | 'thr' | 'blend', parameter)."""

    if arm.startswith("S3-thr-"):
        return "thr", float(arm.rsplit("-", 1)[1])
    if arm.startswith("S3-blend-"):
        return "blend", float(arm.rsplit("-", 1)[1])
    return "rrf", None


# ---------------------------------------------------------------------------
# Run checks
# ---------------------------------------------------------------------------

def stage_names(report: Mapping) -> List[set]:
    """Per question, the stage names the server recorded."""

    return [{str(stage.get("stage")) for stage in (item.get("raw_response") or {}).get("stages") or []}
            for item in report.get("details") or []]


def model_scored(result: Mapping) -> bool:
    """False for a sub-question whose rerank fell back to noop: threshold and blend skip those by design."""

    return bool(result.get("rerankScored", 1))


def blended_order_holds(result: Mapping, alpha: float, tolerance: float = 1e-5) -> Optional[bool]:
    """Whether the pool order is non-increasing in alpha*bm25/max + (1-alpha)*rerank; None if it can't tell."""

    if not model_scored(result):
        return None
    candidates = [c for c in result.get("candidates") or [] if c.get("rerankScore") is not None]
    if len(candidates) < 2:
        return None
    bm25 = [float((c.get("channelScores") or {}).get("FullTextSearch") or 0.0) for c in candidates]
    top = max(bm25)
    blended = [alpha * (b / top if top > 0 else 0.0) + (1 - alpha) * float(c["rerankScore"])
               for b, c in zip(bm25, candidates)]
    return all(blended[i] >= blended[i + 1] - tolerance for i in range(len(blended) - 1))


def infer_alpha(report: Mapping, grid: Sequence[float] = ALPHAS) -> dict:
    """Which alpha of the grid reproduces the blend order in the most sub-questions.

    A sub-question is decisive when some alphas reproduce its order and others don't; the run is attributed
    to an alpha only when the decisive sub-questions agree on a single best one.
    """

    agreement = {alpha: 0 for alpha in grid}
    decisive = 0
    for item in report.get("details") or []:
        for result in (item.get("raw_response") or {}).get("results") or []:
            verdicts = {alpha: blended_order_holds(result, alpha) for alpha in grid}
            if None in verdicts.values() or len(set(verdicts.values())) < 2:
                continue
            decisive += 1
            for alpha, holds in verdicts.items():
                agreement[alpha] += bool(holds)
    best = max(agreement.values()) if decisive else 0
    winners = [alpha for alpha, count in agreement.items() if count == best]
    return {"decisive": decisive, "agreement": agreement,
            "alpha": winners[0] if decisive and len(winners) == 1 else None}


def below_threshold(report: Mapping, threshold: float) -> int:
    """Pool candidates whose rerank score is under the threshold: a threshold run must have none."""

    return sum(1 for item in report.get("details") or []
               for result in (item.get("raw_response") or {}).get("results") or [] if model_scored(result)
               for c in result.get("candidates") or []
               if c.get("rerankScore") is not None and c["rerankScore"] < threshold - 1e-6)


def check_report(report: Mapping, arm: str) -> Tuple[str, str]:
    """('ok' | 'dead' | 'config' | 'empty', detail) for one run of an S3 arm."""

    sub_questions = sum(len((item.get("raw_response") or {}).get("results") or []) for item in report.get("details") or [])
    empty = cmp.empty_channel_sub_questions(report)
    if sub_questions and empty * 2 > sub_questions:
        return "dead", f"vector channel empty in {empty} of {sub_questions} sub-questions"
    stages = stage_names(report)
    if not stages or not all(STAGE_FULL_TEXT in names for names in stages):
        return "config", "full-text channel missing from some questions"
    kind, value = arm_kind(arm)
    has_threshold = any(STAGE_THRESHOLD in names for names in stages)
    has_blend = any(STAGE_BLEND in names for names in stages)
    if has_threshold != (kind == "thr") or has_blend != (kind == "blend"):
        return "config", f"stages show threshold={has_threshold} blend={has_blend}, arm is {arm}"
    detail = f"vector-empty={empty}"
    if kind == "thr":
        under = below_threshold(report, value)
        if under:
            return "config", f"{under} kept chunks score under {value:g}: the instance ran a lower threshold"
    if kind == "blend":
        # 实例真用了本臂的 alpha 时，它在每个子问题上都成立；别的 alpha 解释得更多，就是实例配错了
        inferred = infer_alpha(report)
        own = next((count for alpha, count in inferred["agreement"].items() if abs(alpha - value) < 1e-9), 0)
        if inferred["decisive"] and own < max(inferred["agreement"].values()):
            return "config", f"blend order fits other alphas better than {value:g} ({inferred['agreement']})"
        detail += f" alpha-check decisive={inferred['decisive']}"
    if empty > MAX_EMPTY:
        return "empty", f"{empty} empty vector-channel sub-questions > {MAX_EMPTY}"
    return "ok", detail


def state(arm: str, split: str, repeat: int) -> Tuple[str, str]:
    path = RUNS / label(arm, split, repeat) / "retrieval.json"
    if not path.is_file():
        return "missing", ""
    return check_report(read_json(path), arm)


def set_aside(run_label: str, reason: str) -> Path:
    target = RUNS / f"invalid-{run_label}-{reason}-{datetime.now().strftime('%m%d%H%M%S')}"
    shutil.move(str(RUNS / run_label), str(target))
    return target


# ---------------------------------------------------------------------------
# Arm selection (status file, fixed 2026-10-10)
# ---------------------------------------------------------------------------

def _verdict(block: Mapping, scope: str, metric: str) -> Optional[str]:
    section = block.get("overall_answerable") if scope == "overall" else (block.get("by_type") or {}).get(scope)
    return ((section or {}).get(metric) or {}).get("verdict")


def _mean(block: Mapping, scope: str, metric: str) -> float:
    section = block.get("overall_answerable") if scope == "overall" else (block.get("by_type") or {}).get(scope)
    return float(((section or {}).get(metric) or {}).get("candidate_mean") or 0.0)


def ranking_arm_passes(block: Mapping) -> bool:
    """S3-rrf / S3-blend against S1-base: numeric MRR improved, overall Hit@5 and MRR not regressed."""

    return (_verdict(block, "numeric", "mrr") == "improved"
            and _verdict(block, "overall", "hit@5") != "regressed"
            and _verdict(block, "overall", "mrr") != "regressed")


def threshold_arm_passes(block: Mapping) -> bool:
    """S3-thr against S3-rrf: context precision improved and overall Hit@5 not regressed."""

    return (_verdict(block, "overall", "context_precision") == "improved"
            and _verdict(block, "overall", "hit@5") != "regressed")


def choose_arm(ranking: Mapping, thresholds: Mapping) -> dict:
    """The one arm for the test split, or None; with the reasons for the summary."""

    blocks = ranking.get("comparisons") or {}
    passing = {arm: block for arm, block in blocks.items() if ranking_arm_passes(block)}
    blends = sorted((arm for arm in passing if arm.startswith("S3-blend-")),
                    key=lambda arm: (-_mean(passing[arm], "numeric", "mrr"), -_mean(passing[arm], "overall", "mrr"),
                                     arm_kind(arm)[1]))
    best_blend = blends[0] if blends else None
    thr_blocks = thresholds.get("comparisons") or {}
    thr_passing = sorted((arm for arm, block in thr_blocks.items() if threshold_arm_passes(block)),
                         key=lambda arm: (-_mean(thr_blocks[arm], "overall", "context_precision"), arm_kind(arm)[1]))
    best_thr = thr_passing[0] if thr_passing else None

    ranking_candidates = [arm for arm in ("S3-rrf", best_blend) if arm and arm in passing]
    chosen = None
    if ranking_candidates:
        # tie on numeric MRR goes to S3-rrf (listed first, max keeps the first maximum)
        chosen = max(ranking_candidates, key=lambda arm: _mean(passing[arm], "numeric", "mrr"))
        if chosen == "S3-rrf" and best_thr:
            chosen = best_thr
    return {"chosen": chosen, "ranking_passed": sorted(passing), "best_blend": best_blend,
            "threshold_passed": thr_passing, "best_threshold": best_thr}


# ---------------------------------------------------------------------------
# Database, build, instance
# ---------------------------------------------------------------------------

def docker_psql(sql: str) -> str:
    result = subprocess.run(["docker", "exec", "-i", PG_CONTAINER, "sh", "-c",
                             'exec psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -Atq', "sh", DATABASE],
                            input=sql, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"psql on {DATABASE} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def ensure_database() -> dict:
    SNAPSHOTS.mkdir(parents=True, exist_ok=True)
    existing = sorted(SNAPSHOTS.glob(f"{DATABASE}-pre-s3-*.dump"))
    if existing:
        snapshot = existing[0]
        log(f"snapshot kept: {snapshot.name}")
    else:
        snapshot = SNAPSHOTS / f"{DATABASE}-pre-s3-{datetime.now().strftime('%m%d%H%M%S')}.dump"
        with snapshot.open("wb") as handle:
            dump = subprocess.run(["docker", "exec", PG_CONTAINER, "sh", "-c",
                                   'exec pg_dump -U "$POSTGRES_USER" -Fc "$1"', "sh", DATABASE], stdout=handle)
        if dump.returncode != 0 or snapshot.stat().st_size == 0:
            snapshot.unlink(missing_ok=True)
            raise RuntimeError(f"pg_dump of {DATABASE} failed")
        log(f"snapshot written: {snapshot.name} ({snapshot.stat().st_size // 1024} KB); "
            f"restore with pg_restore --clean -d {DATABASE}")
    docker_psql(UPGRADE.read_text(encoding="utf-8"))
    log(f"full-text upgrade applied to {DATABASE} (repeatable)")
    return {"snapshot": str(snapshot.relative_to(REPO_ROOT))}


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=str(REPO_ROOT), capture_output=True, text=True).stdout.strip()


def build() -> None:
    log("building the jar (offline maven, tests skipped)")
    result = subprocess.run(["./mvnw", "-o", "-q", "-pl", "bootstrap", "-am", "-DskipTests", "clean", "package"],
                            cwd=str(REPO_ROOT), capture_output=True, text=True)
    if result.returncode != 0 or not JAR.is_file():
        raise RuntimeError("build failed:\n" + "\n".join((result.stdout + result.stderr).splitlines()[-25:]))
    log("jar built")


def port_open() -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(1)
        return sock.connect_ex(("127.0.0.1", PORT)) == 0


def tail(path: Path, lines: int = 30) -> str:
    return "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-lines:])


class Instance:
    """The S1 instance with one arm's switches; stopped on exit whatever happens."""

    def __init__(self, arm: str, startup_timeout: int) -> None:
        self.arm = arm
        self.startup_timeout = startup_timeout
        self.process: Optional[subprocess.Popen] = None
        self.log_path = RUNS / f"instance-{arm}-{datetime.now().strftime('%m%d%H%M%S')}.log"

    def __enter__(self) -> "Instance":
        command = ["java", "-jar", str(JAR), f"--spring.config.additional-location=file:{CONFIG}", *ARMS[self.arm]]
        log(f"starting instance for {self.arm}, log {self.log_path.name}")
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


def rebuild_index() -> dict:
    client = ApiClient(BASE, timeout=600)
    client.login("admin", "admin")
    summary = client.request_json("/admin/full-text/rebuild", method="POST")
    indexed = docker_psql("SELECT count(*) || ' ' || count(content_tsv) FROM t_knowledge_chunk WHERE deleted = 0").split()
    if len(indexed) != 2 or indexed[0] != indexed[1] or indexed[0] == "0":
        raise RuntimeError(f"full-text rebuild left chunks without content_tsv: {indexed} ({summary})")
    log(f"full-text index rebuilt: {summary}; {indexed[1]} chunks indexed")
    return summary


# ---------------------------------------------------------------------------
# Runs and comparisons
# ---------------------------------------------------------------------------

def run_once(arm: str, split: str, repeat: int) -> None:
    run_label = label(arm, split, repeat)
    log(f"running {run_label}")
    result = subprocess.run([sys.executable, str(HERE / "run_retrieval.py"), "--base", BASE, "--label", run_label,
                             "--arm", arm, "--split", split, "--repeat-index", str(repeat),
                             "--server-commit", git("rev-parse", "HEAD"), "--questions", str(QUESTIONS)],
                            cwd=str(REPO_ROOT), capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"run_retrieval.py exited {result.returncode} for {run_label} "
                           f"(2 = rerank or rewrite fell back: the keys are not reaching the instance)\n"
                           + "\n".join((result.stdout + result.stderr).splitlines()[-15:]))


def missing_or_invalid(arm: str, split: str) -> List[str]:
    return [label(arm, split, r) for r in REPEATS if state(arm, split, r)[0] != "ok"]


def ensure_repeats(arm: str, split: str) -> None:
    for repeat in REPEATS:
        run_label = label(arm, split, repeat)
        for attempt in range(MAX_ATTEMPTS + 1):
            status, detail = state(arm, split, repeat)
            if status == "ok":
                log(f"{run_label}: OK  {detail}")
                break
            if status == "dead":
                moved = set_aside(run_label, "no-vector")
                raise RuntimeError(f"{run_label}: {detail} (moved to {moved.name}); the embedding channel is not "
                                   "answering: check SILICONFLOW_API_KEY in this terminal and the embedding service")
            if status == "config":
                moved = set_aside(run_label, "wrong-config")
                if attempt > 0:
                    raise RuntimeError(f"{run_label} came out wrong from an instance this script started: {detail}")
                log(f"{run_label}: {detail}; moved to {moved.name}")
            if status == "empty":
                moved = set_aside(run_label, "empty-channels")
                log(f"{run_label}: {detail}; moved to {moved.name}")
            if attempt == MAX_ATTEMPTS:
                raise RuntimeError(f"{run_label} still invalid after {MAX_ATTEMPTS} attempts (channel timeouts); "
                                   "start the script again later")
            run_once(arm, split, repeat)


def reports(arm: str, split: str) -> List[str]:
    return [str(RUNS / label(arm, split, r) / "retrieval.json") for r in REPEATS]


def compare(arms: Sequence[str], split: str, baseline: str, output_name: str, thresholds: bool) -> dict:
    output = RUNS / output_name
    if output.exists():
        output = RUNS / output_name.replace(".json", f"-{datetime.now().strftime('%m%d%H%M%S')}.json")
    command = [sys.executable, str(HERE / "compare_retrieval_repeats.py"), "--baseline", baseline]
    command += ["--thresholds", str(THRESHOLDS)] if thresholds else ["--max-empty-channel", str(MAX_EMPTY)]
    for arm in arms:
        command += ["--arm", arm] + reports(arm, split)
    command += ["--output", str(output)]
    result = subprocess.run(command, cwd=str(REPO_ROOT), capture_output=True, text=True)
    print(result.stdout, end="", flush=True)
    if result.returncode != 0:
        raise RuntimeError(f"comparison failed:\n{result.stdout}{result.stderr}")
    return {**read_json(output), "_output": str(output.relative_to(REPO_ROOT))}


# ---------------------------------------------------------------------------

def preflight(skip_build: bool) -> List[str]:
    problems = []
    if port_open():
        problems.append(f"port {PORT} is in use: stop the running instance (Ctrl-C in its terminal) first")
    for key in REQUIRED_KEYS:
        if not os.environ.get(key):
            problems.append(f"{key} is not set in this terminal: run the script where the keys are exported")
    dirty = git("status", "--porcelain", "--", *CODE_PATHS)
    if dirty:
        problems.append("uncommitted code changes (runs record HEAD as the server commit):\n" + dirty)
    for path in (CONFIG, QUESTIONS, THRESHOLDS, UPGRADE):
        if not path.is_file():
            problems.append(f"missing {path}")
    if skip_build and not JAR.is_file():
        problems.append(f"missing {JAR} (drop --skip-build)")
    for tool in ("java", "docker"):
        if shutil.which(tool) is None:
            problems.append(f"{tool} is not on PATH")
    for repeat in REPEATS:
        for split in ("tune", "test"):
            if not (RUNS / label("S1-base", split, repeat) / "retrieval.json").is_file():
                problems.append(f"missing baseline run {label('S1-base', split, repeat)}")
    return problems


def main() -> int:
    args = parse_args()
    todo = {arm: missing_or_invalid(arm, "tune") for arm in ARMS}
    log("plan (tune split): " + "; ".join(f"{arm}: {', '.join(r.rsplit('-', 1)[1] for r in runs) or 'complete'}"
                                           for arm, runs in todo.items()))
    log("then: compare (S3-rrf and S3-blend vs S1-base, S3-thr vs S3-rrf), pick at most one arm by the status-file "
        "rule, run it on the test split, compare it with S1-base there")
    problems = preflight(args.skip_build)
    if args.plan:
        print("\n".join(f"  ! {p}" for p in problems) if problems else "  preflight OK")
        return 0
    if problems:
        print("cannot start:\n  - " + "\n  - ".join(problems))
        return 1

    summary: Dict[str, object] = {"kind": "kq-stage3", "started_at": utc_now_iso(), "server_commit": git("rev-parse", "HEAD"),
                                  "arms": {arm: flags for arm, flags in ARMS.items()}}
    try:
        if not args.skip_build:
            build()
        summary["database"] = ensure_database()
        for arm in ARMS:
            if not missing_or_invalid(arm, "tune"):
                log(f"{arm} tune: all repeats OK, nothing to run")
                continue
            with Instance(arm, args.startup_timeout):
                summary["full_text_rebuild"] = rebuild_index()  # 0.4 s on 224 chunks; same result every time
                ensure_repeats(arm, "tune")

        log("comparing S3-rrf and S3-blend with S1-base on the tune split")
        ranking = compare(["S1-base", "S3-rrf"] + [f"S3-blend-{a:g}" for a in ALPHAS], "tune", "S1-base",
                          "S3-vs-S1-base-tune.json", thresholds=True)
        log("comparing S3-thr with S3-rrf on the tune split")
        thresholds = compare(["S3-rrf"] + [f"S3-thr-{t:g}" for t in THRESHOLDS_GRID], "tune", "S3-rrf",
                             "S3-thr-vs-S3-rrf-tune.json", thresholds=True)
        decision = choose_arm(ranking, thresholds)
        summary.update({"ranking_comparison": ranking["_output"], "threshold_comparison": thresholds["_output"],
                        "decision": decision})
        chosen = decision["chosen"]
        log("no arm passed: full text stays off, no test-split run" if chosen is None
            else f"{chosen} goes to the test split ({decision})")

        if chosen is not None:
            if missing_or_invalid(chosen, "test"):
                with Instance(chosen, args.startup_timeout):
                    rebuild_index()
                    ensure_repeats(chosen, "test")
            log(f"comparing {chosen} with S1-base on the test split")
            summary["test_comparison"] = compare(["S1-base", chosen], "test", "S1-base",
                                                 f"{chosen}-vs-S1-base-test.json", thresholds=False)["_output"]
    except (RuntimeError, OSError, ApiError) as exc:
        log(f"stopped: {exc}")
        log("fix the cause and start the script again; finished runs are kept")
        return 1
    except KeyboardInterrupt:
        log("interrupted; finished runs are kept, start the script again to continue")
        return 1
    summary["finished_at"] = utc_now_iso()
    summary_path = RUNS / f"stage3-summary-{datetime.now().strftime('%m%d%H%M%S')}.json"
    write_json(summary_path, summary)
    log(f"all stage-3 runs done; summary {summary_path.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
