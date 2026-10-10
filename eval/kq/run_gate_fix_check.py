#!/usr/bin/env python3
"""Re-check the stage-2 gate after the 2026-10-10 review fixes, unattended (eval/kq/README.md, "审查修正复核").

The review fixes changed what gets stored, not the thresholds: the fallback parse no longer fails the whole
document, images are uploaded and described only for the chosen parse, the audit drops compound-word slot
matches and real-text "<", and stored text keeps Chinese punctuation and superscripts (full NFKC only for
matching and the full-text index). This script shows whether the stage-2 results still hold on the fixed code.

Start it from the repository root in the terminal that has the three keys exported, with nothing on port 9095:

    python3 eval/kq/run_gate_fix_check.py --plan   # show what would run, start nothing
    python3 eval/kq/run_gate_fix_check.py          # about 30 minutes

In order, with nothing to type in between:

1. builds the jar from the committed tree (refuses uncommitted code: runs record HEAD as the server commit);
2. creates ``ragent_eval_kq_s2b`` (schema + init data) and its config from the S2 example
   (port 9095, Redis 15, own buckets and RocketMQ names); an existing database is reused;
3. starts the instance, ingests the same 7 documents through the API (MinerU, gate and normalization on;
   about 10 minutes, two documents get a second OCR parse), audits the chunks and computes the parse-layer
   metrics (metric v2, the same script as S1 and S2);
4. checks against the S2 ingestion: the same gate verdict per document, normalization version 2, and that
   the survey sheet kept its Chinese punctuation and superscripts;
5. replays the tune split three times (boost, full text, threshold and blend off; the same sub-questions);
   a run with more than 8 empty vector-channel sub-questions is moved aside and redone;
6. compares S2b-gate with S1-base under the fixed thresholds and with S2-gate (report only), stops the
   instance and writes ``runs/gate-fix-check-summary-*.json``.

The test split stays sealed: it was used once for this change (S2-gate) and the fixes do not touch thresholds.
Finished steps are kept, so after an interruption the same command continues where it stopped.
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
from evalkit import read_json, utc_now_iso, write_json  # noqa: E402

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DATA = REPO_ROOT / "local-data/kq-eval"
RUNS = DATA / "runs"
JAR = REPO_ROOT / "bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar"
CONFIG_EXAMPLE = HERE / "config/application-kq-s2.example.yaml"
CONFIG = DATA / "config/application-kq-s2b.yaml"
QUESTIONS = DATA / "questions/questions-v1.jsonl"
FACTS = DATA / "questions/numeric-facts-v1.jsonl"
THRESHOLDS = HERE / "manifests/kq-thresholds.json"
SCHEMA = REPO_ROOT / "resources/database/schema_pg.sql"
INIT_DATA = REPO_ROOT / "resources/database/init_data_pg.sql"
SETUP = RUNS / "setup-s2b.json"  # resumed ingestions write setup-s2b-resume-*.json next to it
CHUNKS_DIR = RUNS / "S2b-chunks"
CHUNKS = CHUNKS_DIR / "chunks.jsonl"
PARSE_METRICS = CHUNKS_DIR / "parse-metrics.json"
S1_PARSE_METRICS = RUNS / "S1-chunks/parse-metrics-v2.json"
S2_PARSE_METRICS = RUNS / "S2-chunks/parse-metrics.json"
PG_CONTAINER = "ragent-iron-ore-dev-postgres-1"
DATABASE = "ragent_eval_kq_s2b"
S2_DATABASE = "ragent_eval_kq_s2"
PORT = 9095
BASE = f"http://127.0.0.1:{PORT}/api/ragent"
ARM = "S2b-gate"
REPEATS = (1, 2, 3)
MAX_EMPTY = 8
MAX_ATTEMPTS = 3
REQUIRED_KEYS = ("BAILIAN_API_KEY", "SILICONFLOW_API_KEY", "MINERU_API_KEY")
CODE_PATHS = ("bootstrap", "framework", "infra-ai", "pom.xml")
OFF_STAGES = ("post-MetadataBoost", "channel-FullTextSearch", "post-RerankThreshold", "post-ScoreBlend")

# (S2 example, S2b): every pair must occur exactly once in the example
CONFIG_REPLACEMENTS = (
    ("port: 9094", "port: 9095"),
    ("unique-name: _kq_s2\n", "unique-name: _kq_s2b\n"),
    ("/ragent_eval_kq_s2?", "/ragent_eval_kq_s2b?"),
    ("database: 14", "database: 15"),
    ("rewrites-s2.jsonl", "rewrites-s2b.jsonl"),
    ("kb-bucket: ragent-sources-kq-s2 ", "kb-bucket: ragent-sources-kq-s2b "),
    ("asset-bucket: ragent-assets-kq-s2\n", "asset-bucket: ragent-assets-kq-s2b\n"),
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--plan", action="store_true", help="print what would run and exit without starting anything")
    parser.add_argument("--skip-build", action="store_true", help="use the existing jar (it must come from HEAD)")
    parser.add_argument("--startup-timeout", type=int, default=300, help="seconds to wait for the instance")
    return parser.parse_args()


def log(message: str) -> None:
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {message}", flush=True)


def label(split: str, repeat: int) -> str:
    return f"{ARM}-{split}-r{repeat}"


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=str(REPO_ROOT), capture_output=True, text=True).stdout.strip()


# ---------------------------------------------------------------------------
# Config, database, build
# ---------------------------------------------------------------------------

def s2b_config(example: str) -> str:
    """The S2 example with the S2b database, port, Redis database, buckets and RocketMQ names."""

    text = example
    for old, new in CONFIG_REPLACEMENTS:
        if text.count(old) != 1:
            raise RuntimeError(f"{CONFIG_EXAMPLE.name}: expected {old!r} exactly once, found {text.count(old)}")
        text = text.replace(old, new)
    header = ("# Generated by eval/kq/run_gate_fix_check.py from eval/kq/config/application-kq-s2.example.yaml:\n"
              "# the S2 configuration on its own database, port, Redis database, buckets and RocketMQ names.\n")
    return header + text


def docker_psql(database: str, sql: str) -> str:
    result = subprocess.run(["docker", "exec", "-i", PG_CONTAINER, "sh", "-c",
                             'exec psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -Atq', "sh", database],
                            input=sql, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"psql on {database} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def database_exists() -> bool:
    return docker_psql("postgres", f"SELECT 1 FROM pg_database WHERE datname = '{DATABASE}'") == "1"


def ensure_database() -> str:
    if database_exists():
        log(f"{DATABASE} exists, reused")
        return "reused"
    created = subprocess.run(["docker", "exec", PG_CONTAINER, "sh", "-c", 'exec createdb -U "$POSTGRES_USER" "$1"',
                              "sh", DATABASE], capture_output=True, text=True)
    if created.returncode != 0:
        raise RuntimeError(f"createdb {DATABASE} failed: {created.stderr.strip()}")
    for script in (SCHEMA, INIT_DATA):
        docker_psql(DATABASE, script.read_text(encoding="utf-8"))
    log(f"{DATABASE} created from {SCHEMA.name} and {INIT_DATA.name}")
    return "created"


def ensure_config() -> None:
    CONFIG.parent.mkdir(parents=True, exist_ok=True)
    wanted = s2b_config(CONFIG_EXAMPLE.read_text(encoding="utf-8"))
    if not CONFIG.is_file() or CONFIG.read_text(encoding="utf-8") != wanted:
        CONFIG.write_text(wanted, encoding="utf-8")
        log(f"config written: {CONFIG.relative_to(REPO_ROOT)}")


def build() -> None:
    log("building the jar (offline maven, tests skipped)")
    result = subprocess.run(["./mvnw", "-o", "-q", "-pl", "bootstrap", "-am", "-DskipTests", "clean", "package"],
                            cwd=str(REPO_ROOT), capture_output=True, text=True)
    if result.returncode != 0 or not JAR.is_file():
        raise RuntimeError("build failed:\n" + "\n".join((result.stdout + result.stderr).splitlines()[-25:]))
    log("jar built")


# ---------------------------------------------------------------------------
# Instance
# ---------------------------------------------------------------------------

def port_open() -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(1)
        return sock.connect_ex(("127.0.0.1", PORT)) == 0


def tail(path: Path, lines: int = 30) -> str:
    return "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-lines:])


class Instance:
    """The S2b instance; stopped on exit whatever happens."""

    def __init__(self, startup_timeout: int) -> None:
        self.startup_timeout = startup_timeout
        self.process: Optional[subprocess.Popen] = None
        self.log_path = RUNS / f"instance-S2b-{datetime.now().strftime('%m%d%H%M%S')}.log"

    def __enter__(self) -> "Instance":
        command = ["java", "-jar", str(JAR), f"--spring.config.additional-location=file:{CONFIG}"]
        log(f"starting the S2b instance, log {self.log_path.name}")
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


def run_script(name: str, *args: str) -> None:
    result = subprocess.run([sys.executable, str(HERE / name), *args], cwd=str(REPO_ROOT), capture_output=True, text=True)
    print(result.stdout, end="", flush=True)
    if result.returncode != 0:
        raise RuntimeError(f"{name} exited {result.returncode}:\n" + "\n".join((result.stdout + result.stderr).splitlines()[-20:]))


# ---------------------------------------------------------------------------
# Ingestion and parse-layer checks
# ---------------------------------------------------------------------------

def latest_setup() -> Optional[Path]:
    setups = sorted(RUNS.glob("setup-s2b*.json"), key=lambda path: path.stat().st_mtime)
    return setups[-1] if setups else None


def ingestion_complete(setup: Optional[Mapping]) -> bool:
    documents = (setup or {}).get("documents") or []
    return bool(documents) and not (setup or {}).get("failures") \
        and all(document.get("status") == "success" for document in documents)


def ingest() -> None:
    previous = latest_setup()
    if previous is not None and ingestion_complete(read_json(previous)):
        log(f"ingestion finished earlier ({previous.name}), skipped")
        return
    # prepare_kb.py never overwrites its manifest; --resume creates the KB when it is missing and otherwise
    # skips successful documents, re-chunks failed ones and uploads missing ones
    output = SETUP if previous is None else RUNS / f"setup-s2b-resume-{datetime.now().strftime('%m%d%H%M%S')}.json"
    log("ingesting the 7 documents (about 10 minutes)")
    run_script("prepare_kb.py", "--base", BASE, "--kb-name", "kq-s2b", "--collection-name", "kq_s2b_v1",
               "--output", str(output), "--continue-on-failure", "--resume")


def audit_chunks() -> None:
    if PARSE_METRICS.is_file():
        log(f"parse metrics exist: {PARSE_METRICS.relative_to(REPO_ROOT)}")
        return
    if not CHUNKS.is_file():
        if CHUNKS_DIR.exists():
            aside = RUNS / f"invalid-S2b-chunks-partial-{datetime.now().strftime('%m%d%H%M%S')}"
            shutil.move(str(CHUNKS_DIR), str(aside))
            log(f"partial chunk audit moved to {aside.name}")
        run_script("audit_chunks.py", "--base", BASE, "--kb-name", "kq-s2b", "--output", str(CHUNKS))
    run_script("parse_metrics.py", "--facts", str(FACTS), "--chunks", str(CHUNKS), "--label", ARM,
               "--output", str(PARSE_METRICS))


def found_by_doc(metrics: Mapping) -> Dict[str, int]:
    return {row["doc"]: int(row["facts_found_raw"]) for row in metrics.get("documents") or []}


def parse_layer_check(s2b: Mapping, s1: Mapping, s2: Mapping, minimum_total: int, max_loss: int) -> dict:
    """The stage-2 main threshold on S2b (status file): at least ``minimum_total`` facts in total and no document
    losing ``max_loss + 1`` or more against S1-base; plus the per-document difference to S2."""

    found, base, previous = found_by_doc(s2b), found_by_doc(s1), found_by_doc(s2)
    losses = {doc: base[doc] - found.get(doc, 0) for doc in base if base[doc] - found.get(doc, 0) > max_loss}
    total = sum(found.values())
    return {"facts_found_raw": total, "facts_total": sum(int(r["facts_total"]) for r in s2b.get("documents") or []),
            "s1_found_raw": sum(base.values()), "s2_found_raw": sum(previous.values()),
            "minimum_total": minimum_total, "documents_losing_vs_s1": losses,
            "difference_vs_s2": {doc: found.get(doc, 0) - previous.get(doc, 0) for doc in previous
                                 if found.get(doc, 0) != previous.get(doc, 0)},
            "passed": total >= minimum_total and not losses}


VERDICT_SQL = """
SELECT doc_name || '|' || coalesce(doc_metadata->'parseAudit'->>'verdict', '-') || '|'
       || coalesce(doc_metadata->'normalization'->>'version', '-')
FROM t_knowledge_document WHERE deleted = 0 ORDER BY doc_name
"""

SURVEY_SQL = """
SELECT count(*) || '|' || count(*) FILTER (WHERE c.content ~ '[，：；（）]') || '|'
       || count(*) FILTER (WHERE c.content ~ '[²³⁺⁻]')
FROM t_knowledge_chunk c JOIN t_knowledge_document d ON d.id = c.doc_id
WHERE c.deleted = 0 AND d.deleted = 0 AND d.doc_name LIKE '%调研%'
"""


def rows(text: str) -> List[List[str]]:
    return [line.split("|") for line in text.splitlines() if line.strip()]


def ingestion_check(s2b_rows: Sequence[Sequence[str]], s2_rows: Sequence[Sequence[str]],
                    survey: Sequence[str]) -> dict:
    """Same gate verdict per document as the S2 ingestion, normalization version 2 everywhere, and the survey
    sheet (parsed by POI, never by MinerU) still has Chinese punctuation and superscripts."""

    verdicts = {row[0]: row[1] for row in s2b_rows}
    expected = {row[0]: row[1] for row in s2_rows}
    mismatched = {doc: {"s2": expected.get(doc), "s2b": verdicts.get(doc)}
                  for doc in sorted(set(verdicts) | set(expected)) if verdicts.get(doc) != expected.get(doc)}
    old_form = sorted(row[0] for row in s2b_rows if row[2] != "2")
    chunks, punctuated, superscripted = (int(value) for value in survey)
    return {"verdicts": verdicts, "verdicts_match_s2": not mismatched, "verdict_mismatches": mismatched,
            "documents_without_normalization_v2": old_form,
            "survey": {"chunks": chunks, "with_chinese_punctuation": punctuated, "with_superscripts": superscripted},
            "passed": not mismatched and not old_form and punctuated > 0 and superscripted > 0}


# ---------------------------------------------------------------------------
# Retrieval runs
# ---------------------------------------------------------------------------

def check_report(report: Mapping) -> Tuple[str, str]:
    """('ok' | 'dead' | 'config' | 'empty', detail) for one S2b-gate run."""

    sub_questions = sum(len((item.get("raw_response") or {}).get("results") or []) for item in report.get("details") or [])
    empty = cmp.empty_channel_sub_questions(report)
    if sub_questions and empty * 2 > sub_questions:
        return "dead", f"vector channel empty in {empty} of {sub_questions} sub-questions"
    stages = {str(stage.get("stage")) for item in report.get("details") or []
              for stage in (item.get("raw_response") or {}).get("stages") or []}
    switched_on = sorted(stage for stage in OFF_STAGES if stage in stages)
    if switched_on:
        return "config", f"stages that must be off ran: {switched_on}"
    if empty > MAX_EMPTY:
        return "empty", f"{empty} empty vector-channel sub-questions > {MAX_EMPTY}"
    return "ok", f"vector-empty={empty}"


def state(split: str, repeat: int) -> Tuple[str, str]:
    path = RUNS / label(split, repeat) / "retrieval.json"
    if not path.is_file():
        return "missing", ""
    return check_report(read_json(path))


def set_aside(run_label: str, reason: str) -> Path:
    target = RUNS / f"invalid-{run_label}-{reason}-{datetime.now().strftime('%m%d%H%M%S')}"
    shutil.move(str(RUNS / run_label), str(target))
    return target


def run_once(split: str, repeat: int) -> None:
    run_label = label(split, repeat)
    log(f"running {run_label}")
    result = subprocess.run([sys.executable, str(HERE / "run_retrieval.py"), "--base", BASE, "--label", run_label,
                             "--arm", ARM, "--split", split, "--repeat-index", str(repeat),
                             "--server-commit", git("rev-parse", "HEAD"), "--questions", str(QUESTIONS)],
                            cwd=str(REPO_ROOT), capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"run_retrieval.py exited {result.returncode} for {run_label} "
                           f"(2 = rerank or rewrite fell back: the keys are not reaching the instance)\n"
                           + "\n".join((result.stdout + result.stderr).splitlines()[-15:]))


def missing_or_invalid(split: str) -> List[str]:
    return [label(split, r) for r in REPEATS if state(split, r)[0] != "ok"]


def ensure_repeats(split: str) -> None:
    for repeat in REPEATS:
        run_label = label(split, repeat)
        for attempt in range(MAX_ATTEMPTS + 1):
            status, detail = state(split, repeat)
            if status == "ok":
                log(f"{run_label}: OK  {detail}")
                break
            if status == "dead":
                moved = set_aside(run_label, "no-vector")
                raise RuntimeError(f"{run_label}: {detail} (moved to {moved.name}); the embedding channel is not "
                                   "answering: check SILICONFLOW_API_KEY in this terminal and the embedding service")
            if status == "config":
                moved = set_aside(run_label, "wrong-config")
                raise RuntimeError(f"{run_label}: {detail} (moved to {moved.name}); check {CONFIG.name}")
            if status == "empty":
                moved = set_aside(run_label, "empty-channels")
                log(f"{run_label}: {detail}; moved to {moved.name}")
            if attempt == MAX_ATTEMPTS:
                raise RuntimeError(f"{run_label} still invalid after {MAX_ATTEMPTS} attempts (channel timeouts); "
                                   "start the script again later")
            run_once(split, repeat)


def reports(arm: str) -> List[str]:
    return [str(RUNS / f"{arm}-tune-r{r}" / "retrieval.json") for r in REPEATS]


def compare(baseline: str, output_name: str, thresholds: bool) -> dict:
    output = RUNS / output_name
    if output.exists():
        output = RUNS / output_name.replace(".json", f"-{datetime.now().strftime('%m%d%H%M%S')}.json")
    command = [sys.executable, str(HERE / "compare_retrieval_repeats.py"), "--baseline", baseline]
    command += ["--thresholds", str(THRESHOLDS)] if thresholds else ["--max-empty-channel", str(MAX_EMPTY)]
    for arm in (baseline, ARM):
        command += ["--arm", arm] + reports(arm)
    command += ["--output", str(output)]
    result = subprocess.run(command, cwd=str(REPO_ROOT), capture_output=True, text=True)
    print(result.stdout, end="", flush=True)
    if result.returncode != 0:
        raise RuntimeError(f"comparison failed:\n{result.stdout}{result.stderr}")
    return {**read_json(output), "_output": str(output.relative_to(REPO_ROOT))}


def headline(comparison: Mapping) -> dict:
    """Overall and numeric Hit@5 / MRR of S2b-gate against the baseline of a comparison file."""

    block = (comparison.get("comparisons") or {}).get(ARM) or {}
    out = {}
    for scope, metrics in (("overall_answerable", block.get("overall_answerable") or {}),
                           ("numeric", ((block.get("by_type") or {}).get("numeric") or {}))):
        for metric in ("hit@5", "mrr"):
            entry = metrics.get(metric) or {}
            out[f"{scope}.{metric}"] = {key: entry.get(key) for key in ("baseline_mean", "candidate_mean", "delta", "verdict")}
    return out


# ---------------------------------------------------------------------------

def preflight(skip_build: bool) -> List[str]:
    problems = []
    if port_open():
        problems.append(f"port {PORT} is in use: stop whatever runs there first")
    for key in REQUIRED_KEYS:
        if not os.environ.get(key):
            problems.append(f"{key} is not set in this terminal: run the script where the keys are exported")
    dirty = git("status", "--porcelain", "--", *CODE_PATHS)
    if dirty:
        problems.append("uncommitted code changes (runs record HEAD as the server commit):\n" + dirty)
    for path in (CONFIG_EXAMPLE, QUESTIONS, FACTS, THRESHOLDS, SCHEMA, INIT_DATA, S1_PARSE_METRICS, S2_PARSE_METRICS):
        if not path.is_file():
            problems.append(f"missing {path}")
    if skip_build and not JAR.is_file():
        problems.append(f"missing {JAR} (drop --skip-build)")
    for tool in ("java", "docker"):
        if shutil.which(tool) is None:
            problems.append(f"{tool} is not on PATH")
    if shutil.which("docker"):
        running = subprocess.run(["docker", "ps", "--format", "{{.Names}}"], capture_output=True, text=True).stdout
        for name in (PG_CONTAINER, "rocketmq-broker", "rocketmq-nameserver"):
            if name not in running:
                problems.append(f"container {name} is not running (ingestion goes through RocketMQ)")
    for arm in ("S1-base", "S2-gate"):
        for path in reports(arm):
            if not Path(path).is_file():
                problems.append(f"missing baseline run {path}")
    return problems


def main() -> int:
    args = parse_args()
    try:
        database = "reuse" if database_exists() else "create"
    except (RuntimeError, OSError):
        database = "check failed (is docker up?)"
    previous = latest_setup()
    ingestion = "done" if previous is not None and ingestion_complete(read_json(previous)) else \
        ("resume" if previous is not None else "all 7 documents")
    log(f"plan: build; {DATABASE}: {database}; ingestion: {ingestion}; parse metrics: "
        f"{'kept' if PARSE_METRICS.is_file() else 'compute'}; tune runs to do: "
        f"{', '.join(missing_or_invalid('tune')) or 'none'}; then compare with S1-base (thresholds) and S2-gate")
    problems = preflight(args.skip_build)
    if args.plan:
        print("\n".join(f"  ! {p}" for p in problems) if problems else "  preflight OK")
        return 0
    if problems:
        print("cannot start:\n  - " + "\n  - ".join(problems))
        return 1

    thresholds = read_json(THRESHOLDS)
    summary: Dict[str, object] = {"kind": "kq-gate-fix-check", "started_at": utc_now_iso(),
                                  "server_commit": git("rev-parse", "HEAD"), "database": DATABASE}
    try:
        if not args.skip_build:
            build()
        summary["database_state"] = ensure_database()
        ensure_config()
        with Instance(args.startup_timeout):
            ingest()
            audit_chunks()
            ingestion = ingestion_check(rows(docker_psql(DATABASE, VERDICT_SQL)), rows(docker_psql(S2_DATABASE, VERDICT_SQL)),
                                        docker_psql(DATABASE, SURVEY_SQL).split("|"))
            summary["ingestion_check"] = ingestion
            log(f"ingestion check: {'OK' if ingestion['passed'] else 'NOT OK'} "
                f"{json.dumps({k: ingestion[k] for k in ('verdict_mismatches', 'survey')}, ensure_ascii=False)}")
            parse = parse_layer_check(read_json(PARSE_METRICS), read_json(S1_PARSE_METRICS), read_json(S2_PARSE_METRICS),
                                      int(thresholds["parse"]["coverage_raw_min_facts"]),
                                      int(thresholds["parse"]["per_document_max_fact_loss"]))
            summary["parse_layer"] = {**parse, "metrics": str(PARSE_METRICS.relative_to(REPO_ROOT))}
            log(f"parse layer: {parse['facts_found_raw']}/{parse['facts_total']} facts (S1 {parse['s1_found_raw']}, "
                f"S2 {parse['s2_found_raw']}); main threshold {'passed' if parse['passed'] else 'NOT passed'}")
            ensure_repeats("tune")
        log("comparing S2b-gate with S1-base on the tune split (fixed thresholds)")
        versus_s1 = compare("S1-base", "S2b-gate-vs-S1-base-tune.json", thresholds=True)
        log("comparing S2b-gate with S2-gate on the tune split (report only)")
        versus_s2 = compare("S2-gate", "S2b-gate-vs-S2-gate-tune.json", thresholds=False)
        summary["versus_s1_base"] = {"file": versus_s1["_output"], "headline": headline(versus_s1)}
        summary["versus_s2_gate"] = {"file": versus_s2["_output"], "headline": headline(versus_s2)}
    except (RuntimeError, OSError) as exc:
        log(f"stopped: {exc}")
        log("fix the cause and start the script again; finished steps are kept")
        return 1
    except KeyboardInterrupt:
        log("interrupted; finished steps are kept, start the script again to continue")
        return 1
    summary["finished_at"] = utc_now_iso()
    summary_path = RUNS / f"gate-fix-check-summary-{datetime.now().strftime('%m%d%H%M%S')}.json"
    write_json(summary_path, summary)
    log(f"done; summary {summary_path.relative_to(REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
