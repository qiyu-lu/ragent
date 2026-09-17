#!/usr/bin/env python3
"""Fixed A/B/C paid evaluation and offline scoring, with append-only attempts."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import subprocess
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.append(str(REPO / "eval/context-selection"))
from start_pooled import idea_environment
from datasetkit import QUERY_KEYS, iter_jsonl, sha256_file
from scoring import aggregate, prediction, score_one


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n")


def rows(path):
    return list(iter_jsonl(path)) if path.exists() else []


def query_file(prepared, dataset, profile):
    scope = "qasper-validation" if dataset == "qasper" else "musique-dev"
    return prepared / scope / ("queries.jsonl" if profile == "full" else "queries." + profile + ".jsonl")


def prepare_cases(prepared, profile, config, limit=None):
    cases, queries = [], {}
    for dataset in ("qasper", "musique"):
        items = rows(query_file(prepared, dataset, profile))
        if limit is not None:
            items = items[:limit]
        for item in items:
            if set(item) != QUERY_KEYS or item["dataset"] != dataset or item["retrieval_mode"] != config["scope"][dataset]:
                raise ValueError("Query fields/scope do not match the gold-free evaluation contract")
            identifier = item["id"]
            if identifier in queries:
                raise ValueError("Duplicate fixed question ID")
            queries[identifier] = item
            cases.append({"id": identifier, "collection": "rs_" + ("qasper_validation" if dataset == "qasper" else "musique_dev") + "_v1_full",
                          "sourceDocumentIds": item["document_ids"], "goal": item["question"], "outputType": "REPORT",
                          "cancelAfterMillis": 0, "reply": None, "constraints": []})
    return cases, queries


def fingerprints():
    paths = list((REPO / "bootstrap/src/main/java/com/nageoffer/ai/ragent/research").rglob("*.java"))
    paths += list((REPO / "bootstrap/src/main/resources/prompts").glob("research-*.txt"))
    paths += list(Path(__file__).resolve().parent.glob("*.py"))
    paths += [REPO / p for p in (
        "pom.xml", "bootstrap/pom.xml", "framework/pom.xml", "infra-ai/pom.xml", "resources/database/schema_pg.sql",
        "bootstrap/src/main/resources/application.yaml",
        "bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/config/SearchChannelProperties.java",
        "bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/MultiChannelRetrievalEngine.java",
        "bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/channel/VectorSearchChannel.java",
        "bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/retrieval/channel/RetrievalScopeResolver.java",
        "bootstrap/src/main/java/com/nageoffer/ai/ragent/rag/core/vector/PgVectorRetrieverService.java",
        "infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/config/AIModelProperties.java",
        "infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/embedding/SiliconFlowEmbeddingClient.java",
        "infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/embedding/EmbeddingUsageCapture.java",
        "infra-ai/src/main/java/com/nageoffer/ai/ragent/infra/token/HeuristicTokenCounterService.java")]
    return {str(p.relative_to(REPO)): sha256_file(p) for p in sorted(paths)}


def full_execution_boundaries(profile, queries, full_ids, modes, recorded):
    full_scope = profile == "full" and set(queries) == set(full_ids)
    complete = recorded == len(queries) * len(modes)
    return {"full_scope_selected": full_scope, "full_split_executed": full_scope and complete,
            "full_abc_executed": full_scope and complete and set(modes) == set("ABC")}


def call_cost(call, config):
    if call.get("usageStatus") != "provider":
        return None
    input_tokens, output_tokens = call["inputTokens"], call["outputTokens"]
    for cap, input_rate, output_rate in config["prices"]["chat_tiers"]:
        if input_tokens <= cap:
            return (input_tokens * input_rate + output_tokens * output_rate) / 1_000_000
    raise ValueError("Provider usage exceeds the documented pricing table")


def expected_budget(config):
    names = {"model_calls": "maxModelCalls", "tool_calls": "maxToolCalls", "duration_seconds": "maxDurationSeconds",
             "model_timeout_seconds": "modelCallTimeoutSeconds", "tool_timeout_seconds": "toolTimeoutSeconds",
             "max_input_tokens": "maxInputTokens", "max_output_tokens": "maxOutputTokens",
             "finalization_reserve": "reservedFinalizationModelCalls", "worker_concurrency": "maxConcurrentWorkers", "worker_total": "maxTotalWorkers"}
    return {names[key]: value for key, value in config["runtime_budget"].items()}


def resource_summary(directory, config):
    calls, embeddings = {}, {}
    for path in sorted(directory.glob("attempts/*/usage.jsonl")):
        for row in rows(path):
            call = row["call"]
            calls[call["callId"]] = call
    for path in sorted(directory.glob("attempts/*/embedding-usage.jsonl")):
        for row in rows(path):
            embeddings[row["call_id"]] = row
    known_cost = sum(call_cost(c, config) or 0 for c in calls.values())
    unknown = sum(c.get("usageStatus") != "provider" for c in calls.values())
    return {"model_requests": len(calls), "known_input_tokens": sum(c.get("inputTokens", 0) for c in calls.values()),
            "known_output_tokens": sum(c.get("outputTokens", 0) for c in calls.values()), "model_usage_unknown": unknown,
            "known_generation_cost_estimate_cny": known_cost,
            "budget_reserve_cny": known_cost + unknown * 0.03,
            "actual_models": sorted({c["model"] for c in calls.values()}),
            "embedding_requests": len(embeddings),
            "embedding_known_total_tokens": sum((c.get("usage") or {}).get("total_tokens", 0) for c in embeddings.values()),
            "embedding_usage_unknown": sum(c.get("usage_status") != "provider" for c in embeddings.values()),
            "embedding_currency_cost": None, "invoice_verified": False, "prices": config["prices"]}


def run_job(args, attempt, job):
    env = dict(os.environ)
    if not env.get("BAILIAN_API_KEY") or not env.get("SILICONFLOW_API_KEY"):
        env.update(idea_environment(args.idea, "RagentApplication"))
    if not env.get("BAILIAN_API_KEY") or not env.get("SILICONFLOW_API_KEY"):
        raise ValueError("Existing BAILIAN_API_KEY and SILICONFLOW_API_KEY are required")

    def docker(*arguments, **kwargs):
        return subprocess.check_output(["docker", "exec", "-i", args.container, *arguments], text=True, **kwargs).strip()

    database = "research_p3_" + datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S") + "_" + os.urandom(4).hex()
    created, removed = False, False
    record = {"database": database, "corpus_database": args.corpus_database, "started_at": datetime.now(timezone.utc).isoformat()}
    try:
        docker("sh", "-c", 'exec createdb -U "$POSTGRES_USER" "$1"', "sh", database)
        created = True
        docker("sh", "-c", 'exec psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1', "sh", database,
               input=(REPO / "resources/database/schema_pg.sql").read_text())
        port = subprocess.check_output(["docker", "inspect", "--format", '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}', args.container], text=True).strip()
        env.update({"RESEARCH_TEST_PG_USER": docker("sh", "-c", 'printf "%s" "$POSTGRES_USER"'),
                    "RESEARCH_TEST_PG_PASSWORD": docker("sh", "-c", 'printf "%s" "$POSTGRES_PASSWORD"'),
                    "RESEARCH_P3_TEST_URL": "jdbc:postgresql://127.0.0.1:" + port + "/" + database,
                    "RAGENT_POSTGRES_URL": "jdbc:postgresql://127.0.0.1:" + port + "/" + args.corpus_database})
        resolved = subprocess.check_output(["./mvnw", "-o", "-pl", "bootstrap", "dependency:build-classpath", "-Dmdep.outputAbsoluteArtifactFilename=true"], cwd=REPO, text=True)
        paths = [line.strip() for line in resolved.splitlines() if line.startswith("/") and ".jar" in line]
        if len(paths) != 1:
            raise ValueError("Classpath resolution failed")
        classpath = os.pathsep.join([str(REPO / p / "target/classes") for p in ("bootstrap", "framework", "infra-ai")] + paths)
        with (attempt / "java.log").open("w") as log:
            process = subprocess.run(["java", "-Xmx1g", "-cp", classpath, "com.nageoffer.ai.ragent.research.eval.ResearchRunCommand", str(attempt / "job.json")],
                                     cwd=REPO, env=env, stdout=log, stderr=subprocess.STDOUT)
        record["exit_code"] = process.returncode
        return process.returncode
    finally:
        if created:
            docker("sh", "-c", 'exec dropdb -U "$POSTGRES_USER" "$1"', "sh", database)
            removed = True
        record.update(finished_at=datetime.now(timezone.utc).isoformat(), isolated_database_removed=removed)
        write_json(attempt / "execution.json", record)


def collect(directory, queries):
    predictions, seen = [], set()
    for path in sorted(directory.glob("attempts/*/predictions.jsonl")):
        for envelope in rows(path):
            key = envelope["caseId"], envelope["mode"]
            if key in seen:
                raise ValueError("Repeated completed sample; a new run directory is required for re-evaluation")
            seen.add(key)
            predictions.append(prediction(envelope, queries[key[0]]))
    return predictions


def score_run(directory, prepared, profile, queries, config, modes):
    predicted = collect(directory, queries)
    questions, corpus = {}, {}
    for dataset in ("qasper", "musique"):
        scope = "qasper-validation" if dataset == "qasper" else "musique-dev"
        name = "questions.jsonl" if profile == "full" else "questions." + profile + ".jsonl"
        questions.update({r["id"]: r for r in rows(prepared / scope / name) if r["id"] in queries})
        # Scorer-only loading starts after model execution; these labels never enter job.json.
        corpus.update({r["id"]: r["text"] for r in iter_jsonl(prepared / scope / "corpus.jsonl")})
    scores = [score_one(questions[p["questionId"]], p, corpus) for p in predicted]
    for name, items in (("predictions.jsonl", predicted), ("scores.jsonl", scores)):
        (directory / name).write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in items))
    resources = resource_summary(directory, config)
    by_mode = defaultdict(list)
    for p in predicted:
        by_mode[p["mode"]].append(p)
    latency = {}
    for mode, items in by_mode.items():
        values = sorted(p["elapsedMillis"] for p in items)
        latency[mode] = {"p50_ms": values[math.ceil(len(values) * .50) - 1], "p95_ms": values[math.ceil(len(values) * .95) - 1],
                         "failed_and_timed_out_included": True,
                         "workers_created": sum(p["usage"].get("workersCreated", 0) for p in items),
                         "runs_with_delegation": sum(p["usage"].get("workersCreated", 0) > 0 for p in items)}
    full_ids = {item["id"] for dataset in ("qasper", "musique") for item in rows(query_file(prepared, dataset, "full"))} if profile == "full" else set()
    summary = {"profile": profile, "fixed_questions": len(queries), "expected_tasks": len(queries) * len(modes),
               "recorded_tasks": len(predicted), "unexecuted_tasks": len(queries) * len(modes) - len(predicted),
               "quality": aggregate(scores), "resources": resources, "execution": latency,
               "boundaries": {**full_execution_boundaries(profile, queries, full_ids, modes, len(predicted)), "production_chat_pipeline": False,
                              "A_path": "one scoped knowledge retrieval + pinned CHUNK context + shared artifact generation; no rewrite/intent/MCP/fallback",
                              "paired_musique_sufficiency": False, "judge_model_used": False, "semantic_citation_support_proven": False}}
    write_json(directory / "summary.json", summary)
    lines = ["# P7 fixed evaluation", "", "Profile: {}. Fixed questions: {}. Recorded tasks: {}/{}.".format(profile, len(queries), len(predicted), summary["expected_tasks"]), "",
             "| Dataset / mode | Records | Answer-scored | EM | Answer F1 | Evidence F1 | Answerability |", "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
    for key, value in summary["quality"].items():
        fmt = lambda name: "{:.4f}".format(value[name]) if value[name] is not None else "n/a"
        lines.append("| {} | {} | {} | {} | {} | {} | {} |".format(key, value["records"], value["answer_scored_records"], fmt("answer_em"), fmt("answer_f1"), fmt("evidence_f1"), fmt("answerability_accuracy")))
    lines += ["", "Failed/timed-out records remain in the relevant denominators. MuSiQue answer/support metrics use answerable records only; answerability uses all records. No paired sufficiency score is reported.", "",
              "Generation cost is an estimate from known provider usage, excluding cache discounts, embedding invoice costs and unknown usage. See summary.json. Evidence identity/paragraph coverage does not establish semantic support.", "",
              "A uses the project's scoped retrieval components with a fixed model and shared generator; it is not an end-to-end measurement of the production chat pipeline."]
    (directory / "report.md").write_text("\n".join(lines) + "\n")
    return summary


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--prepared", type=Path, default=Path("local-data/agentic-research/prepared/research-data-v1"))
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("configs") / "p7.json")
    parser.add_argument("--profile", choices=("smoke", "regression", "full"), default="smoke")
    parser.add_argument("--mode", choices=("all", "A", "B", "C"), default="all")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--container", default="ragent-iron-ore-dev-postgres-1")
    parser.add_argument("--corpus-database", default="research_corpus_v1")
    parser.add_argument("--idea", type=Path, default=Path(".idea/workspace.xml"))
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--score-only", action="store_true")
    args = parser.parse_args()
    if not args.corpus_database.startswith("research_corpus_") or not args.corpus_database.replace("_", "").isalnum():
        raise ValueError("Dedicated local research corpus required")
    if args.limit is not None and args.limit < 1:
        raise ValueError("Sample limit must be positive")
    args.run_dir, args.prepared = args.run_dir.resolve(), args.prepared.resolve()
    config = json.loads(args.config.read_text())
    cases, queries = prepare_cases(args.prepared, args.profile, config, args.limit)
    modes = list("ABC") if args.mode == "all" else [args.mode]
    identity = {"profile": args.profile, "modes": modes, "query_ids": list(queries), "config": config,
                "source_sha256": fingerprints(),
                "query_sha256": {dataset: sha256_file(query_file(args.prepared, dataset, args.profile)) for dataset in ("qasper", "musique")},
                "prepared_sha256": {str(path.relative_to(args.prepared)): sha256_file(path)
                    for scope in ("qasper-validation", "musique-dev")
                    for path in (args.prepared / scope / "manifest.json", args.prepared / scope / "corpus.jsonl",
                                 args.prepared / scope / ("questions.jsonl" if args.profile == "full" else "questions." + args.profile + ".jsonl"))}}
    record_path = args.run_dir / "run.json"
    if args.resume or args.score_only:
        record = json.loads(record_path.read_text())
        if identity != record["identity"]:
            raise ValueError("Resume/scoring requires the exact frozen config, queries and source; use a new directory")
    else:
        args.run_dir.mkdir(parents=True, exist_ok=False)
        record = {"schema_version": "research-evaluation-run-v1", "identity": identity, "command": sys.argv,
                  "started_at": datetime.now(timezone.utc).isoformat(), "gold_sent_to_model": False,
                  "git_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
                  "git_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=REPO, text=True))}
        write_json(record_path, record)
        snapshot = args.run_dir / "source-snapshot"
        for relative in identity["source_sha256"]:
            target = snapshot / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes((REPO / relative).read_bytes())
    (args.run_dir / "attempts").mkdir(exist_ok=True)
    write_json(args.run_dir / "requests.json", {"cases": cases, "modes": modes})
    if not args.execute and not args.score_only:
        print("Prepared {} fixed questions / {} tasks. No API or database calls.".format(len(queries), len(queries) * len(modes)))
        return
    if args.execute and not args.score_only:
        completed = {(p["questionId"], p["mode"]) for p in collect(args.run_dir, queries)}
        for mode in modes:
            pending = [c for c in cases if (c["id"], mode) not in completed]
            if not pending:
                continue
            resources = resource_summary(args.run_dir, config)
            remaining = config["max_generation_cost_cny"] - resources["budget_reserve_cny"]
            if remaining <= .3:
                record["stopped_reason"] = "EVALUATION_COST_LIMIT"
                break
            attempt = args.run_dir / "attempts" / ("{:04d}_{}".format(len(list((args.run_dir / "attempts").iterdir())), mode))
            attempt.mkdir()
            job = {"runDir": str(attempt), "cases": pending, "generateArtifacts": True, "evaluationMode": mode,
                   "concurrency": config["concurrency"], "maxCostCny": remaining,
                   "generationInstruction": config["generation_instruction"],
                   "expectedModel": config["model_id"], "expectedBudget": expected_budget(config)}
            write_json(attempt / "job.json", job)
            print("Running mode {}: {} fixed tasks; generation allowance {:.2f} CNY.".format(mode, len(pending), remaining), flush=True)
            code = run_job(args, attempt, job)
            if code:
                record["stopped_reason"] = "JAVA_EXIT_" + str(code)
                break
    summary = score_run(args.run_dir, args.prepared, args.profile, queries, config, modes)
    record.update(finished_at=datetime.now(timezone.utc).isoformat(), recorded_tasks=summary["recorded_tasks"], unexecuted_tasks=summary["unexecuted_tasks"])
    write_json(record_path, record)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
