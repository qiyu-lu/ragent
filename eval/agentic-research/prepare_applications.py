#!/usr/bin/env python3
"""Freeze 12 comparison / 12 plan requests using corpus/query fields only."""
from collections import defaultdict
import argparse
import hashlib
import json
from pathlib import Path

from datasetkit import iter_jsonl, sha256_file


def prepare(prepared, output):
    scope = prepared / "qasper-validation"
    first = next(iter_jsonl(scope / "queries.jsonl"))["document_ids"]
    documents = {}
    for query in iter_jsonl(scope / "queries.regression.jsonl"):
        document = query["document_ids"][0]
        if document not in first:
            documents.setdefault(document, query["id"])
    chosen = list(documents)[:24]
    if len(chosen) != 24:
        raise ValueError("24 distinct papers required")
    sources = defaultdict(list)
    for row in iter_jsonl(scope / "corpus.jsonl"):
        if row["document_id"] in chosen:
            sources[row["document_id"]].append(row)
    cases, reference = [], {}
    categories = ("conditions", "follow-up", "missing-source", "conflict-applicability", "user-input", "parameters")
    for index in range(12):
        a, b = chosen[index * 2:index * 2 + 2]
        category = categories[index % len(categories)]
        common = {"collection": "rs_qasper_validation_v1_full", "cancelAfterMillis": 0, "reply": None, "constraints": []}
        comparison = "Compare Paper A ([[DOC_0]]) and Paper B ([[DOC_1]]) on their methods, inputs/supervision, experimental evaluation and stated limitations. Investigate independently when useful; read evidence and follow up on missing dimensions. Preserve each paper's applicable conditions."
        plan = "Organize a plan draft for reproducing the experiments explicitly disclosed in this paper. Research prerequisites, procedure, data/resources, evaluation and limitations. Include only source-supported actions; missing details remain pendingItems."
        if category == "follow-up":
            comparison += " Follow up specifically on evaluation data and metrics after identifying each method from a read source."
            plan += " After identifying the method, target a follow-up search for its experiment settings and evaluation."
        elif category == "missing-source":
            comparison += " Also check whether exact 2035 production GPU-hour and carbon-emission budgets are disclosed; do not invent them."
            plan += " The requested deployment is in 2035. Check exact GPU-hours/carbon emissions and preserve their absence as gaps."
        elif category == "conflict-applicability":
            comparison += " Check whether apparent disagreements are measured under comparable conditions. If comparison cannot establish a contradiction, explain the missing setup instead."
            plan += " Distinguish method-specific requirements from general conclusions; do not combine incompatible experimental settings."
        elif category == "user-input":
            plan += " My allowed preparation duration is a hard user constraint, but I have not supplied it. Ask that one question before drafting."
        elif category == "parameters":
            plan += " Include batch_size/window_size only if explicitly disclosed; otherwise retain null values and pending items."
        cid, pid = "comparison-{:02d}".format(index + 1), "plan-{:02d}".format(index + 1)
        cases.append({**common, "id": cid, "sourceDocumentIds": [a, b], "goal": comparison, "outputType": "REPORT"})
        cases.append({**common, "id": pid, "sourceDocumentIds": [a], "goal": plan, "outputType": "PLAN",
                      "reply": "Preparation duration must be at most 2 hours." if category == "user-input" else None,
                      "constraints": ["Preparation duration must be at most 2 hours."] if category == "conditions" else []})
        for identifier, docs in ((cid, [a, b]), (pid, [a])):
            reference[identifier] = {"category": category, "source_documents": [
                {"document_id": doc, "title": sources[doc][0]["title"], "paper_id": sources[doc][0]["metadata"]["paper_id"],
                 "reference_paragraph_ids": [r["id"] for r in sources[doc] if r["metadata"].get("source_field") == "abstract"]}
                for doc in docs], "expected_dimensions": ["method/procedure", "inputs/resources", "evaluation", "limitations/gaps"],
                "semantic_review": "Requires independent comparison with actual cited paragraph text; these anchors are not gold answers."}
    artifact = {"schema_version": "research-applications-v1", "cases": cases, "reference": reference,
                "source": "https://huggingface.co/datasets/allenai/qasper", "license": "CC-BY-4.0", "attribution": "Dasigi et al. (2021)",
                "queries_sha256": sha256_file(scope / "queries.regression.jsonl"), "corpus_sha256": sha256_file(scope / "corpus.jsonl"),
                "gold_used": False, "selection": "First 24 distinct fixed-regression papers, excluding the historical development paper; 12 fixed pairs."}
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("x") as target:
        json.dump(artifact, target, ensure_ascii=False, indent=2)
        target.write("\n")
    print("Frozen 12 comparisons / 12 plans. No model calls.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--prepared", type=Path, default=Path("local-data/agentic-research/prepared/research-data-v1"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.prepared, args.output)
