#!/usr/bin/env python3
"""Run fixed application regressions; export cited text for independent review."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess

from datasetkit import sha256_file
from evaluate_research import REPO, expected_budget, expected_models, fingerprints, resource_summary, rows, run_job, write_json, interleaved_cases
from scoring import source_ids


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("configs") / "applications.json")
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("configs") / "p7.json")
    parser.add_argument("--container", default="ragent-iron-ore-dev-postgres-1")
    parser.add_argument("--corpus-database", default="research_corpus_v1")
    parser.add_argument("--idea", type=Path, default=Path(".idea/workspace.xml"))
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--mode", choices=("all", "A", "B", "C"), default="C")
    parser.add_argument("--case", action="append", help="Select fixed IDs for a demonstration or a targeted new batch")
    args = parser.parse_args()
    if not args.corpus_database.startswith("research_corpus_") or not args.corpus_database.replace("_", "").isalnum():
        raise ValueError("Dedicated corpus required")
    args.run_dir = args.run_dir.resolve()
    args.run_dir.mkdir(parents=True, exist_ok=False)
    application = json.loads(args.cases.read_text())
    config = json.loads(args.config.read_text())
    role_models = expected_models(config)
    if application["gold_used"] or len(application["cases"]) != 24:
        raise ValueError("24 frozen gold-free cases required")
    cases = application["cases"]
    if args.case:
        selected = set(args.case)
        if not selected <= {c["id"] for c in cases}:
            raise ValueError("Unknown fixed application ID")
        cases = [c for c in cases if c["id"] in selected]
    modes = list("ABC") if args.mode == "all" else [args.mode]
    record = {"modes": modes, "started_at": datetime.now(timezone.utc).isoformat(), "case_sha256": sha256_file(args.cases),
              "selected_case_ids": [c["id"] for c in cases],
              "application_launcher_sha256": sha256_file(Path(__file__).resolve()),
              "source_sha256": fingerprints(), "git_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO, text=True).strip(),
              "config": config, "scoring": "independent qualitative source review; no EM/F1 or judge model"}
    snapshot = args.run_dir / "source-snapshot"
    for relative in record["source_sha256"]:
        target = snapshot / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((REPO / relative).read_bytes())
    write_json(args.run_dir / "run.json", record)
    attempt = args.run_dir / "attempts/0000_MIXED"
    attempt.mkdir(parents=True)
    job = {"runDir": str(attempt), "cases": interleaved_cases(cases, modes, set()), "generateArtifacts": True,
           "evaluationMode": "MIXED", "concurrency": config["concurrency"],
           "maxCostCny": config.get("max_generation_cost_cny") if config.get("estimate_generation_cost", False) else None,
           "generationInstruction": "Write in English. Address each requested dimension with actual evidence; missing dimensions remain gaps. A plan is a draft, not an executable or approved procedure.",
           "expectedModel": config["model_id"], "expectedModels": role_models, "expectedBudget": expected_budget(config),
           "thinking": config.get("thinking", False), "rerank": config["retrieval"].get("rerank", False)}
    write_json(attempt / "job.json", job)
    if not args.execute:
        print("Prepared {} fixed application requests; no API/database calls.".format(len(cases) * len(modes)))
        return
    code = run_job(args, attempt, job)
    predictions = rows(attempt / "predictions.jsonl")
    review, summary_cases = [], {}
    for envelope in predictions:
        identifier, run = envelope["caseId"], envelope["run"]
        key = identifier + "/" + envelope["mode"]
        artifact = run.get("artifact") or {}
        citations = artifact.get("citations", [])
        summary_cases[key] = {"status": run["status"], "output_type": run["brief"]["outputType"],
                                     "referenced_documents": len({c["docId"] for c in citations}), "citations": len(citations),
                                     "gaps": artifact.get("gaps", []), "waiting_input_seen": "latestUserInput" in run["state"],
                                     "semantic_review": "pending independent review", "elapsedMillis": envelope["elapsedMillis"]}
        review.extend(["## " + key, "", "Status: {}. References: {} documents. Category: {}.".format(run["status"], summary_cases[key]["referenced_documents"], application["reference"][identifier]["category"]), "",
                       "```json", json.dumps(artifact, ensure_ascii=False, indent=2), "```", ""])
    (args.run_dir / "source-review.md").write_text("# Application outputs and actual cited text\n\n" + "\n".join(review))
    summary = {"fixed_tasks": len(cases) * len(modes), "recorded_tasks": len(predictions), "unexecuted_tasks": len(cases) * len(modes) - len(predictions),
               "cases": summary_cases, "resources": resource_summary(args.run_dir, config), "java_exit": code,
               "semantic_support_proven": False, "full_production_e2e": False}
    write_json(args.run_dir / "summary.json", summary)
    record.update(finished_at=datetime.now(timezone.utc).isoformat(), recorded_tasks=len(predictions), java_exit=code)
    write_json(args.run_dir / "run.json", record)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
