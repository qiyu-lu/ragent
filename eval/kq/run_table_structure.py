#!/usr/bin/env python3
"""Evaluate the table-structure plan (docs/iron-ore-rag/table-structure-plan-2026-10-10.md §4.3-4.6), unattended.

Excel layout detection (T1) and content-defined table chunks on top of it (T2) only change how the survey
workbook is chunked; the standard PDFs keep their S2b chunks (their tables are HTML tables, which neither
change touches). So both arms are copies of ``ragent_eval_kq_s2b`` with only the survey re-chunked.

Start it from the repository root in the terminal that has the three keys exported, with ports 9095 and 9096
free and nothing connected to ``ragent_eval_kq_s2b``:

    python3 eval/kq/run_table_structure.py --plan   # show what would run, start nothing
    python3 eval/kq/run_table_structure.py          # about 60 minutes

In order, with nothing to type in between:

1. builds the jar from the committed tree (refuses uncommitted code: runs record HEAD as the server commit);
2. copies ``ragent_eval_kq_s2b`` into ``ragent_eval_kq_t1`` and ``ragent_eval_kq_t2`` (existing copies are kept)
   and writes their configs from the S2b config: port 9096, Redis 11 / 12, own RocketMQ names; the survey
   file is read from the S2b bucket;
3. starts S2b unchanged and replays the 12 table questions three times (the first run records their
   sub-questions with the online rewrite; the others and both arms replay them);
4. for T1 (layout on) and T2 (layout and content-defined chunks on): starts the instance, re-chunks only the
   survey, checks that the new layout took effect, exports the chunks, replays the tune split and the table
   questions three times each; a run with more than 8 empty vector-channel sub-questions is redone;
5. compares T1 and T2 with the existing S2b-gate tune runs, and T2 with T1, under the fixed kq thresholds,
   and picks the final configuration (plan §4.4: T2, else T1, else none);
6. replays the test split three times on S2b and on the final configuration (report only);
7. writes ``runs/table-structure-summary-*.json``.

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
from evalkit import ApiClient, load_jsonl, read_json, sha256_file, utc_now_iso, write_json  # noqa: E402

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DATA = REPO_ROOT / "local-data/kq-eval"
RUNS = DATA / "runs"
JAR = REPO_ROOT / "bootstrap/target/bootstrap-0.0.1-SNAPSHOT.jar"
S2B_CONFIG = DATA / "config/application-kq-s2b.yaml"
QUESTIONS = DATA / "questions/questions-v1.jsonl"
TABLE_QUESTIONS = DATA / "questions/questions-table-v1.jsonl"
TABLE_SUB_QUESTIONS = DATA / "questions/sub-questions-table-v1.jsonl"
THRESHOLDS = HERE / "manifests/kq-thresholds.json"
TABLE_QUESTIONS_MANIFEST = HERE / "manifests/table-questions-v1.json"
PG_CONTAINER = "ragent-iron-ore-dev-postgres-1"
SOURCE_DATABASE = "ragent_eval_kq_s2b"
KB_NAME = "kq-s2b"
REPEATS = (1, 2, 3)
MAX_EMPTY = 8
MAX_ATTEMPTS = 3
REQUIRED_KEYS = ("BAILIAN_API_KEY", "SILICONFLOW_API_KEY", "MINERU_API_KEY")
CODE_PATHS = ("bootstrap", "framework", "infra-ai", "pom.xml")
OFF_STAGES = ("post-MetadataBoost", "channel-FullTextSearch", "post-RerankThreshold", "post-ScoreBlend")
LAYOUT_FLAG = "--rag.ingestion.excel-layout.enabled=true"
CDC_FLAG = "--rag.ingestion.table-chunking.content-defined=true"

BASELINE = "S2b-gate"
T1 = "T1-layout"
T2 = "T2-layout-cdc"
ARMS: Dict[str, dict] = {
    BASELINE: {"database": SOURCE_DATABASE, "config": S2B_CONFIG, "port": 9095, "flags": []},
    T1: {"database": "ragent_eval_kq_t1", "config": DATA / "config/application-kq-t1.yaml", "port": 9096,
         "redis": 11, "unique": "_kq_t1", "rewrites": "rewrites-t1.jsonl", "flags": [LAYOUT_FLAG]},
    T2: {"database": "ragent_eval_kq_t2", "config": DATA / "config/application-kq-t2.yaml", "port": 9096,
         "redis": 12, "unique": "_kq_t2", "rewrites": "rewrites-t2.jsonl", "flags": [LAYOUT_FLAG, CDC_FLAG]},
}

SURVEY_SQL = ("SELECT d.id FROM t_knowledge_document d JOIN t_knowledge_base kb ON kb.id = d.kb_id "
              f"WHERE d.deleted = 0 AND kb.name = '{KB_NAME}' AND d.doc_name LIKE '%调研%'")
CHUNK_LOG_COUNT_SQL = "SELECT count(*) FROM t_knowledge_document_chunk_log WHERE doc_id = '{doc}'"
CHUNK_LOG_SQL = ("SELECT status || '|' || coalesce(chunk_count, -1) || '|' || coalesce(embed_cache_hits, -1) || '|' "
                 "|| coalesce(embed_cache_misses, -1) "
                 "FROM t_knowledge_document_chunk_log WHERE doc_id = '{doc}' ORDER BY create_time DESC, id DESC LIMIT 1")
# old layout: the sheet title became the header of the equipment sheet; new layout: each lab's table carries its title
LAYOUT_SQL = ("SELECT count(*) FILTER (WHERE content LIKE '%| 铁矿石实验室调研设备清单 | 铁矿石实验室调研设备清单%') || '|' "
              "|| count(*) FILTER (WHERE content LIKE '二、研究型中心实验室' || chr(10) || '%') || '|' || count(*) "
              "FROM t_knowledge_chunk WHERE deleted = 0 AND doc_id = '{doc}'")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--plan", action="store_true", help="print what would run and exit without starting anything")
    parser.add_argument("--skip-build", action="store_true", help="use the existing jar (it must come from HEAD)")
    parser.add_argument("--startup-timeout", type=int, default=300, help="seconds to wait for an instance")
    parser.add_argument("--rechunk-timeout", type=int, default=900, help="seconds to wait for the survey re-chunk")
    return parser.parse_args()


def log(message: str) -> None:
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {message}", flush=True)


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=str(REPO_ROOT), capture_output=True, text=True).stdout.strip()


def label(arm: str, kind: str, repeat: int) -> str:
    return f"{arm}-{kind}-r{repeat}"


def base_url(arm: str) -> str:
    return f"http://127.0.0.1:{ARMS[arm]['port']}/api/ragent"


# ---------------------------------------------------------------------------
# Config, databases, build
# ---------------------------------------------------------------------------

def arm_config(s2b_text: str, arm: str) -> str:
    """The S2b config on the arm's database, port, Redis database, RocketMQ names and rewrite log; buckets stay."""

    spec = ARMS[arm]
    replacements = (
        ("port: 9095", f"port: {spec['port']}"),
        ("unique-name: _kq_s2b\n", f"unique-name: {spec['unique']}\n"),
        ("/ragent_eval_kq_s2b?", f"/{spec['database']}?"),
        ("database: 15", f"database: {spec['redis']}"),
        ("rewrites-s2b.jsonl", spec["rewrites"]),
    )
    text = s2b_text
    for old, new in replacements:
        if text.count(old) != 1:
            raise RuntimeError(f"{S2B_CONFIG.name}: expected {old!r} exactly once, found {text.count(old)}")
        text = text.replace(old, new)
    header = (f"# Generated by eval/kq/run_table_structure.py from {S2B_CONFIG.name} for {arm}: a copy of the S2b\n"
              "# database with only the survey re-chunked; the survey file is read from the S2b bucket. The table\n"
              f"# switches come from the command line: {' '.join(spec['flags'])}\n")
    return header + text


def docker_psql(database: str, sql: str) -> str:
    result = subprocess.run(["docker", "exec", "-i", PG_CONTAINER, "sh", "-c",
                             'exec psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -Atq', "sh", database],
                            input=sql, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"psql on {database} failed: {result.stderr.strip()}")
    return result.stdout.strip()


def database_exists(database: str) -> bool:
    return docker_psql("postgres", f"SELECT 1 FROM pg_database WHERE datname = '{database}'") == "1"


def ensure_copy(arm: str) -> str:
    database = ARMS[arm]["database"]
    if database_exists(database):
        log(f"{database} exists, kept")
        return "kept"
    created = subprocess.run(["docker", "exec", PG_CONTAINER, "sh", "-c",
                              'exec createdb -U "$POSTGRES_USER" -T "$1" "$2"', "sh", SOURCE_DATABASE, database],
                             capture_output=True, text=True)
    if created.returncode != 0:
        raise RuntimeError(f"createdb {database} -T {SOURCE_DATABASE} failed (is something connected to "
                           f"{SOURCE_DATABASE}?): {created.stderr.strip()}")
    log(f"{database} copied from {SOURCE_DATABASE}")
    return "copied"


def ensure_config(arm: str) -> None:
    path = ARMS[arm]["config"]
    wanted = arm_config(S2B_CONFIG.read_text(encoding="utf-8"), arm)
    if not path.is_file() or path.read_text(encoding="utf-8") != wanted:
        path.write_text(wanted, encoding="utf-8")
        log(f"config written: {path.relative_to(REPO_ROOT)}")


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

def port_open(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(1)
        return sock.connect_ex(("127.0.0.1", port)) == 0


def tail(path: Path, lines: int = 30) -> str:
    return "\n".join(path.read_text(encoding="utf-8", errors="replace").splitlines()[-lines:])


class Instance:
    """One arm's instance; stopped on exit whatever happens."""

    def __init__(self, arm: str, startup_timeout: int) -> None:
        self.arm = arm
        self.port = ARMS[arm]["port"]
        self.startup_timeout = startup_timeout
        self.process: Optional[subprocess.Popen] = None
        self.log_path = RUNS / f"instance-{arm}-{datetime.now().strftime('%m%d%H%M%S')}.log"

    def __enter__(self) -> "Instance":
        command = ["java", "-jar", str(JAR), f"--spring.config.additional-location=file:{ARMS[self.arm]['config']}",
                   *ARMS[self.arm]["flags"]]
        log(f"starting {self.arm} ({' '.join(ARMS[self.arm]['flags']) or 'table switches off'}), log {self.log_path.name}")
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
            if not port_open(self.port):
                break
            time.sleep(1)
        log(f"{self.arm} stopped")


def run_script(name: str, *args: str) -> None:
    result = subprocess.run([sys.executable, str(HERE / name), *args], cwd=str(REPO_ROOT), capture_output=True, text=True)
    print(result.stdout, end="", flush=True)
    if result.returncode != 0:
        raise RuntimeError(f"{name} exited {result.returncode}:\n" + "\n".join((result.stdout + result.stderr).splitlines()[-20:]))


# ---------------------------------------------------------------------------
# Re-chunking the survey
# ---------------------------------------------------------------------------

def layout_check(values: Sequence[str], arm: str) -> dict:
    """Old title-as-header chunks must be gone and the research lab's chunks must start with their table title."""

    old_header, captioned, chunks = (int(value) for value in values)
    return {"chunks": chunks, "old_title_header_chunks": old_header, "research_lab_captioned_chunks": captioned,
            "passed": old_header == 0 and captioned > 0, "arm": arm}


def rechunk_marker(arm: str) -> Path:
    return RUNS / f"{arm}-rechunk.json"


def rechunk_survey(arm: str, timeout: int) -> dict:
    marker = rechunk_marker(arm)
    head = git("rev-parse", "HEAD")
    if marker.is_file() and read_json(marker).get("server_commit") == head and read_json(marker)["layout"]["passed"]:
        log(f"{arm}: survey re-chunked earlier at HEAD ({marker.name}), kept")
        return read_json(marker)
    database = ARMS[arm]["database"]
    doc_id = docker_psql(database, SURVEY_SQL)
    if not doc_id or "\n" in doc_id:
        raise RuntimeError(f"{database}: expected exactly one survey document in {KB_NAME}, got {doc_id!r}")
    client = ApiClient(base_url(arm), timeout=180)
    client.login("admin", "admin")
    # a re-chunk adds one chunk-log row; counting rows avoids comparing database and local clocks
    logs_before = int(docker_psql(database, CHUNK_LOG_COUNT_SQL.format(doc=doc_id)))
    log(f"{arm}: re-chunking the survey ({doc_id})")
    client.request_json(f"/knowledge-base/docs/{doc_id}/chunk", method="POST")
    deadline = time.monotonic() + timeout
    while True:
        document = client.request_json(f"/knowledge-base/docs/{doc_id}")
        logs_now = int(docker_psql(database, CHUNK_LOG_COUNT_SQL.format(doc=doc_id)))
        entry = docker_psql(database, CHUNK_LOG_SQL.format(doc=doc_id)).split("|")
        if logs_now > logs_before and entry[0] == "failed":
            raise RuntimeError(f"{arm}: survey re-chunk failed; see the chunk log of {doc_id}")
        if logs_now > logs_before and entry[0] == "success" and document.get("status") == "success":
            break
        if time.monotonic() >= deadline:
            raise RuntimeError(f"{arm}: survey re-chunk not finished after {timeout}s (status={document.get('status')})")
        time.sleep(3)
    layout = layout_check(docker_psql(database, LAYOUT_SQL.format(doc=doc_id)).split("|"), arm)
    if not layout["passed"]:
        raise RuntimeError(f"{arm}: the new layout did not take effect ({layout}); check the flags in the instance log")
    result = {"arm": arm, "server_commit": head, "doc_id": doc_id, "finished_at": utc_now_iso(),
              "chunk_log": {"status": entry[0], "chunk_count": int(entry[1]), "embed_cache_hits": int(entry[2]),
                            "embed_cache_misses": int(entry[3])},
              "layout": layout}
    write_json(marker, result)
    log(f"{arm}: {layout['chunks']} survey chunks, embedding cache {entry[2]} hits / {entry[3]} misses")
    return result


def export_chunks(arm: str) -> str:
    output = RUNS / f"{arm}-chunks" / "chunks.jsonl"
    if output.is_file():
        return str(output.relative_to(REPO_ROOT))
    if output.parent.exists():
        shutil.move(str(output.parent), str(RUNS / f"invalid-{arm}-chunks-partial-{datetime.now().strftime('%m%d%H%M%S')}"))
    run_script("audit_chunks.py", "--base", base_url(arm), "--kb-name", KB_NAME, "--output", str(output))
    return str(output.relative_to(REPO_ROOT))


# ---------------------------------------------------------------------------
# Retrieval runs
# ---------------------------------------------------------------------------

def check_report(report: Mapping) -> Tuple[str, str]:
    """('ok' | 'dead' | 'config' | 'empty', detail) for one run."""

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


def report_path(run_label: str) -> Path:
    return RUNS / run_label / "retrieval.json"


def state(run_label: str) -> Tuple[str, str]:
    path = report_path(run_label)
    if not path.is_file():
        return "missing", ""
    return check_report(read_json(path))


def set_aside(run_label: str, reason: str) -> Path:
    target = RUNS / f"invalid-{run_label}-{reason}-{datetime.now().strftime('%m%d%H%M%S')}"
    shutil.move(str(RUNS / run_label), str(target))
    return target


def table_sub_questions_complete() -> bool:
    if not TABLE_SUB_QUESTIONS.is_file():
        return False
    stored = {row["id"] for row in load_jsonl(TABLE_SUB_QUESTIONS)}
    return {row["id"] for row in load_jsonl(TABLE_QUESTIONS)} <= stored


def run_once(arm: str, kind: str, repeat: int) -> None:
    run_label = label(arm, kind, repeat)
    command = [sys.executable, str(HERE / "run_retrieval.py"), "--base", base_url(arm), "--label", run_label,
               "--arm", arm, "--repeat-index", str(repeat), "--server-commit", git("rev-parse", "HEAD")]
    if kind == "table":
        command += ["--split", "all", "--questions", str(TABLE_QUESTIONS), "--sub-questions", str(TABLE_SUB_QUESTIONS)]
        if not table_sub_questions_complete():
            command.append("--record-sub-questions")
    else:
        command += ["--split", kind, "--questions", str(QUESTIONS)]
    log(f"running {run_label}")
    result = subprocess.run(command, cwd=str(REPO_ROOT), capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"run_retrieval.py exited {result.returncode} for {run_label} "
                           f"(2 = rerank or rewrite fell back: the keys are not reaching the instance)\n"
                           + "\n".join((result.stdout + result.stderr).splitlines()[-15:]))


def ensure_repeats(arm: str, kind: str) -> None:
    for repeat in REPEATS:
        run_label = label(arm, kind, repeat)
        for attempt in range(MAX_ATTEMPTS + 1):
            status, detail = state(run_label)
            if status == "ok":
                log(f"{run_label}: OK  {detail}")
                break
            if status == "dead":
                moved = set_aside(run_label, "no-vector")
                raise RuntimeError(f"{run_label}: {detail} (moved to {moved.name}); the embedding channel is not "
                                   "answering: check SILICONFLOW_API_KEY in this terminal and the embedding service")
            if status == "config":
                moved = set_aside(run_label, "wrong-config")
                raise RuntimeError(f"{run_label}: {detail} (moved to {moved.name}); check {ARMS[arm]['config'].name}")
            if status == "empty":
                moved = set_aside(run_label, "empty-channels")
                log(f"{run_label}: {detail}; moved to {moved.name}")
            if attempt == MAX_ATTEMPTS:
                raise RuntimeError(f"{run_label} still invalid after {MAX_ATTEMPTS} attempts (channel timeouts); "
                                   "start the script again later")
            run_once(arm, kind, repeat)


def missing(arm: str, kind: str) -> List[str]:
    return [label(arm, kind, r) for r in REPEATS if state(label(arm, kind, r))[0] != "ok"]


def reports(arm: str, kind: str) -> List[str]:
    return [str(report_path(label(arm, kind, r))) for r in REPEATS]


# ---------------------------------------------------------------------------
# Comparisons and the decision
# ---------------------------------------------------------------------------

def compare(baseline: str, candidates: Sequence[str], kind: str, output_name: str, thresholds: bool) -> dict:
    output = RUNS / output_name
    if output.exists():
        output = RUNS / output_name.replace(".json", f"-{datetime.now().strftime('%m%d%H%M%S')}.json")
    command = [sys.executable, str(HERE / "compare_retrieval_repeats.py"), "--baseline", baseline]
    command += ["--thresholds", str(THRESHOLDS)] if thresholds else ["--max-empty-channel", str(MAX_EMPTY)]
    for arm in (baseline, *candidates):
        command += ["--arm", arm] + reports(arm, kind)
    command += ["--output", str(output)]
    result = subprocess.run(command, cwd=str(REPO_ROOT), capture_output=True, text=True)
    print(result.stdout, end="", flush=True)
    if result.returncode != 0:
        raise RuntimeError(f"comparison failed:\n{result.stdout}{result.stderr}")
    return {**read_json(output), "_output": str(output.relative_to(REPO_ROOT))}


def overall(comparison: Mapping, arm: str) -> dict:
    block = ((comparison.get("comparisons") or {}).get(arm) or {}).get("overall_answerable") or {}
    return {metric: {key: (block.get(metric) or {}).get(key)
                     for key in ("baseline_mean", "candidate_mean", "delta", "threshold", "verdict")}
            for metric in ("hit@5", "mrr")}


def regressed(comparison: Mapping, arm: str) -> bool:
    return any(entry.get("verdict") == "regressed" for entry in overall(comparison, arm).values())


def decide(t1_vs_base: Mapping, t2_vs_base: Mapping, t2_vs_t1: Mapping) -> dict:
    """Plan §4.4: T2 unless it regresses against S2b-gate or T1; then T1 unless it regresses; else none."""

    reasons = {"T1_regressed_vs_S2b": regressed(t1_vs_base, T1), "T2_regressed_vs_S2b": regressed(t2_vs_base, T2),
               "T2_regressed_vs_T1": regressed(t2_vs_t1, T2)}
    if not reasons["T2_regressed_vs_S2b"] and not reasons["T2_regressed_vs_T1"]:
        final = T2
    elif not reasons["T1_regressed_vs_S2b"]:
        final = T1
    else:
        final = None
    return {"final": final, **reasons}


def test_check(comparison: Mapping, arm: str, test_range: Mapping[str, float]) -> dict:
    """Report only: a fall larger than the S1-base test-split range counts as 'test regressed' (plan §4.4)."""

    result = {}
    for metric, entry in overall(comparison, arm).items():
        delta = entry.get("delta")
        limit = float(test_range[metric])
        result[metric] = {**entry, "test_range": limit, "test_regressed": delta is not None and -delta > limit + cmp.EPSILON}
    result["test_regressed"] = any(result[m]["test_regressed"] for m in ("hit@5", "mrr"))
    return result


def per_question(report_files: Sequence[str]) -> Dict[str, dict]:
    """Mean Hit@5 and MRR per question over the repeats, for the table-question report."""

    sums: Dict[str, dict] = {}
    for path in report_files:
        for item in read_json(Path(path)).get("details") or []:
            score = item.get("score") or {}
            entry = sums.setdefault(item["id"], {"hit@5": 0.0, "mrr": 0.0, "runs": 0})
            entry["hit@5"] += float(score.get("hit@5") or 0)
            entry["mrr"] += float(score.get("mrr") or 0)
            entry["runs"] += 1
    return {qid: {"hit@5": round(v["hit@5"] / v["runs"], 3), "mrr": round(v["mrr"] / v["runs"], 3)}
            for qid, v in sorted(sums.items())}


# ---------------------------------------------------------------------------

def preflight(skip_build: bool) -> List[str]:
    problems = []
    for port in sorted({spec["port"] for spec in ARMS.values()}):
        if port_open(port):
            problems.append(f"port {port} is in use: stop whatever runs there first")
    for key in REQUIRED_KEYS:
        if not os.environ.get(key):
            problems.append(f"{key} is not set in this terminal: run the script where the keys are exported")
    dirty = git("status", "--porcelain", "--", *CODE_PATHS)
    if dirty:
        problems.append("uncommitted code changes (runs record HEAD as the server commit):\n" + dirty)
    for path in (S2B_CONFIG, QUESTIONS, TABLE_QUESTIONS, THRESHOLDS, TABLE_QUESTIONS_MANIFEST):
        if not path.is_file():
            problems.append(f"missing {path}")
    if TABLE_QUESTIONS.is_file() and TABLE_QUESTIONS_MANIFEST.is_file():
        frozen = read_json(TABLE_QUESTIONS_MANIFEST)["questions"]["sha256"]
        if sha256_file(TABLE_QUESTIONS) != frozen:
            problems.append(f"{TABLE_QUESTIONS.name} differs from its frozen hash in {TABLE_QUESTIONS_MANIFEST.name}")
    if skip_build and not JAR.is_file():
        problems.append(f"missing {JAR} (drop --skip-build)")
    for tool in ("java", "docker"):
        if shutil.which(tool) is None:
            problems.append(f"{tool} is not on PATH")
    if shutil.which("docker"):
        running = subprocess.run(["docker", "ps", "--format", "{{.Names}}"], capture_output=True, text=True).stdout
        for name in (PG_CONTAINER, "rocketmq-broker", "rocketmq-nameserver"):
            if name not in running:
                problems.append(f"container {name} is not running (re-chunking goes through RocketMQ)")
    for path in reports(BASELINE, "tune"):
        if not Path(path).is_file():
            problems.append(f"missing baseline run {path}")
    return problems


def main() -> int:
    args = parse_args()
    try:
        copies = {arm: ("keep" if database_exists(ARMS[arm]["database"]) else "copy") for arm in (T1, T2)}
    except (RuntimeError, OSError):
        copies = {arm: "check failed (is docker up?)" for arm in (T1, T2)}
    todo = [run for arm in (BASELINE, T1, T2) for run in missing(arm, "table")]
    todo += [run for arm in (T1, T2) for run in missing(arm, "tune")]
    log(f"plan: build; databases {copies}; survey re-chunk: "
        f"{ {arm: 'kept' if rechunk_marker(arm).is_file() else 'do' for arm in (T1, T2)} }; runs to do: "
        f"{', '.join(todo) or 'none'}; then decide, test split for S2b-gate and the final configuration")
    problems = preflight(args.skip_build)
    if args.plan:
        print("\n".join(f"  ! {p}" for p in problems) if problems else "  preflight OK")
        return 0
    if problems:
        print("cannot start:\n  - " + "\n  - ".join(problems))
        return 1

    thresholds = read_json(THRESHOLDS)
    summary: Dict[str, object] = {"kind": "table-structure-eval", "started_at": utc_now_iso(),
                                  "server_commit": git("rev-parse", "HEAD"),
                                  "arms": {arm: {"database": spec["database"], "flags": spec["flags"]}
                                           for arm, spec in ARMS.items()}}
    try:
        if not args.skip_build:
            build()
        summary["databases"] = {arm: ensure_copy(arm) for arm in (T1, T2)}
        for arm in (T1, T2):
            ensure_config(arm)
        with Instance(BASELINE, args.startup_timeout):
            ensure_repeats(BASELINE, "table")
        rechunk: Dict[str, dict] = {}
        for arm in (T1, T2):
            with Instance(arm, args.startup_timeout):
                rechunk[arm] = rechunk_survey(arm, args.rechunk_timeout)
                rechunk[arm]["chunks_file"] = export_chunks(arm)
                ensure_repeats(arm, "tune")
                ensure_repeats(arm, "table")
        summary["rechunk"] = rechunk

        log("comparing T1 and T2 with S2b-gate, and T2 with T1, on the tune split (fixed thresholds)")
        versus_base = compare(BASELINE, (T1, T2), "tune", "table-structure-vs-S2b-tune.json", thresholds=True)
        t2_vs_t1 = compare(T1, (T2,), "tune", "table-structure-T2-vs-T1-tune.json", thresholds=True)
        decision = decide(versus_base, versus_base, t2_vs_t1)
        summary["tune"] = {"file": versus_base["_output"], "t2_vs_t1_file": t2_vs_t1["_output"],
                           T1: overall(versus_base, T1), T2: overall(versus_base, T2),
                           "T2_vs_T1": overall(t2_vs_t1, T2), "decision": decision}
        log(f"decision: final configuration {decision['final'] or 'none (both regressed)'}")

        log("table questions: T1 and T2 against S2b-gate (report only)")
        table = compare(BASELINE, (T1, T2), "table", "table-structure-table-questions.json", thresholds=False)
        summary["table_questions"] = {"file": table["_output"], T1: overall(table, T1), T2: overall(table, T2),
                                      "per_question": {arm: per_question(reports(arm, "table")) for arm in (BASELINE, T1, T2)}}

        final = decision["final"]
        if final:
            for arm in (BASELINE, final):
                with Instance(arm, args.startup_timeout):
                    ensure_repeats(arm, "test")
            test = compare(BASELINE, (final,), "test", "table-structure-test.json", thresholds=False)
            summary["test"] = {"file": test["_output"], "final": final,
                               **test_check(test, final, thresholds["test_reproduction"]["overall_answerable"])}
        else:
            summary["test"] = {"skipped": "both arms regressed on the tune split"}
    except (RuntimeError, OSError) as exc:
        log(f"stopped: {exc}")
        log("fix the cause and start the script again; finished steps are kept")
        return 1
    except KeyboardInterrupt:
        log("interrupted; finished steps are kept, start the script again to continue")
        return 1
    summary["finished_at"] = utc_now_iso()
    summary_path = RUNS / f"table-structure-summary-{datetime.now().strftime('%m%d%H%M%S')}.json"
    write_json(summary_path, summary)
    log(f"done; summary {summary_path.relative_to(REPO_ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
