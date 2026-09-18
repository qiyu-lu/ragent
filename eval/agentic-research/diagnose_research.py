#!/usr/bin/env python3
"""Offline failure/coverage hints from immutable P7 scores and real traces."""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path

from evaluate_research import rows, write_json


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise ValueError("Use a new diagnostic output; previous records are preserved")
    predicted = {(p["questionId"], p["mode"]): p for p in rows(args.run_dir / "predictions.jsonl")}
    events = defaultdict(list)
    for attempt in args.run_dir.glob("attempts/*"):
        mode = attempt.name.rsplit("_", 1)[-1]
        for row in rows(attempt / "traces.jsonl"):
            events[(row["caseId"], mode)].append(row["event"])
    diagnostics, counts = [], defaultdict(Counter)
    for score in rows(args.run_dir / "scores.jsonl"):
        key = score["questionId"], score["mode"]
        p = predicted[key]
        trace = events[key]
        ended = [e["payload"] for e in trace if e["type"] == "MODEL_ENDED"]
        if p["status"] not in {"COMPLETED", "PARTIAL"}:
            category = "execution/" + (p["error"] or p["status"])
        elif p["answer_format_error"]:
            category = "answer_format_error"
        elif score["answer_scored"] and score["answer_f1"] == 0:
            category = "zero_answer_f1/no_annotated_read_coverage" if score["read_evidence_recall"] == 0 else "zero_answer_f1/annotated_read_coverage_present"
        else:
            category = "nonzero_or_unscored_answer"
        group = score["dataset"] + "/" + score["mode"]
        counts[group][category] += 1
        diagnostics.append({"questionId": key[0], "mode": key[1], "status": p["status"], "error": p["error"],
                            "category": category, "answer_f1": score["answer_f1"], "read_evidence_recall": score["read_evidence_recall"],
                            "last_model_event": ended[-1] if ended else None,
                            "tool_failures": sum(e["type"] == "TOOL_FAILED" for e in trace),
                            "context_trims": sum(e["type"] in ("CONTEXT_TRIMMED", "CONTEXT_COMPACTED") for e in trace),
                            "workers_created": p["usage"].get("workersCreated", 0)})
    result = {"run_directory": str(args.run_dir), "recorded_tasks": len(diagnostics),
              "categories": {k: dict(v) for k, v in sorted(counts.items())}, "tasks": diagnostics,
              "limits": ["coverage categories are diagnostic hints, not causal or semantic proof", "unexecuted tasks remain in the evaluation summary", "no new API calls or overwritten predictions"]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    write_json(args.output, result)
    print(json.dumps(result["categories"], indent=2))


if __name__ == "__main__":
    main()
