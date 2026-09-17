"""Offline P7 scoring. Official normalization/formulas; no judge-model requests.

References checked 2026-09-17:
https://github.com/allenai/qasper-led-baseline/blob/main/scripts/evaluator.py
https://github.com/StonyBrookNLP/musique/blob/main/metrics/answer.py
https://github.com/StonyBrookNLP/musique/blob/main/metrics/support.py
MuSiQue answer/support metrics use answerable rows only; paired sufficiency
metrics are intentionally absent for the existing unpaired fixed sample.
"""
from __future__ import annotations

from collections import Counter, defaultdict
import json
from pathlib import Path
import re
import string

SCORER_VERSION = "research-score-v1"
GOOD = {"COMPLETED", "PARTIAL"}


def normalize(text):
    text = "".join(c for c in text.lower() if c not in string.punctuation)
    return " ".join(re.sub(r"\b(a|an|the)\b", " ", text).split())


def answer_f1(predicted, gold, empty_agreement=False):
    p, g = normalize(predicted).split(), normalize(gold).split()
    if empty_agreement and (not p or not g):
        return float(p == g)
    same = sum((Counter(p) & Counter(g)).values())
    if not same:
        return 0.0
    precision, recall = same / len(p), same / len(g)
    return 2 * precision * recall / (precision + recall)


def support_scores(predicted, gold):
    p, g = set(predicted), set(gold)
    if not p and not g:
        return {"evidence_em": 1.0, "evidence_f1": 1.0, "evidence_recall": 1.0}
    same = len(p & g)
    precision, recall = same / len(p) if p else 0, same / len(g) if g else 0
    return {"evidence_em": float(p == g),
            "evidence_f1": 2 * precision * recall / (precision + recall) if same else 0.0,
            "evidence_recall": recall}


def source_ids(location):
    result = set()
    if isinstance(location, dict):
        for key, value in location.items():
            if key in ("source_paragraph_id", "sourceParagraphId") and isinstance(value, str):
                result.add(value)
            elif isinstance(value, (list, dict)):
                result.update(source_ids(value))
    elif isinstance(location, list):
        for value in location:
            result.update(source_ids(value))
    return result


def prediction(envelope, query):
    run = envelope["run"]
    artifact = run.get("artifact") or {}
    sections = artifact.get("sections", [])
    answers = [s["text"] for s in sections if s.get("heading", "").lower() == "answer"]
    valid = run["status"] in GOOD and bool(artifact)
    format_error = bool(sections) and (len(answers) != 1 or len(sections) != 1)
    return {"questionId": query["id"], "dataset": query["dataset"], "split": query["split"],
            "mode": envelope["mode"], "runId": run["id"], "status": run["status"],
            "answer": answers[0] if len(answers) == 1 else "Unanswerable" if valid and not sections else "",
            "predicted_answerable": bool(sections) if valid and not format_error else None,
            "answer_format_error": format_error, "artifact": artifact,
            "citation_source_ids": sorted(set().union(*(source_ids(c.get("sourceLocation", {})) for c in artifact.get("citations", [])))),
            "read_source_ids": sorted(set().union(*(source_ids(s.get("sourceLocation", {})) for s in envelope.get("sources", [])))),
            "elapsedMillis": envelope["elapsedMillis"], "error": run.get("errorSummary"), "usage": run["usage"]}


def score_one(question, predicted, corpus_text):
    gold = question["gold"]
    if gold is None:
        raise ValueError("Unlabelled/test questions cannot receive gold scores")
    ok = predicted["status"] in GOOD and bool(predicted["artifact"]) and not predicted["answer_format_error"]
    answer = predicted["answer"] if ok else ""
    cited, read = predicted["citation_source_ids"], predicted["read_source_ids"]
    result = {"questionId": question["id"], "source_question_id": question["source_question_id"],
              "dataset": question["dataset"], "mode": predicted["mode"], "status": predicted["status"],
              "scorer_version": SCORER_VERSION, "answer_format_error": predicted["answer_format_error"]}
    if question["dataset"] == "qasper":
        references = []
        for annotation in gold["annotations"]:
            if annotation["unanswerable"]:
                reference, kind = "Unanswerable", "none"
            elif annotation["extractive_spans"]:
                reference, kind = ", ".join(annotation["extractive_spans"]), "extractive"
            elif annotation["free_form_answer"]:
                reference, kind = annotation["free_form_answer"], "abstractive"
            elif annotation["yes_no"] is not None:
                reference, kind = "Yes" if annotation["yes_no"] else "No", "boolean"
            else:
                raise ValueError("QASPER annotation lacks an answer")
            # Text identity matches the author's paragraph scorer, including unresolved gold.
            support = {" ".join(e["text"].split()) for e in annotation["evidence"] if "FLOAT SELECTED" not in e["text"]}
            if annotation["unanswerable"]:
                support = set()
            references.append((reference, kind, support, annotation["unanswerable"]))
        if not references:
            raise ValueError("QASPER question has no labelled annotations")
        best = max(references, key=lambda r: answer_f1(answer, r[0]))
        predicted_text = {" ".join(corpus_text[i].split()) for i in cited}
        read_text = {" ".join(corpus_text[i].split()) for i in read}
        result.update(answer_f1=max(answer_f1(answer, r[0]) for r in references) if ok else 0.0,
                      answer_em=float(any(normalize(answer) == normalize(r[0]) for r in references)) if ok else 0.0,
                      answer_type=best[1], answer_scored=True,
                      answerability_correct=float(ok and any(predicted["predicted_answerable"] == (not r[3]) for r in references)),
                      unresolved_gold=sum(e["status"] == "unresolved" for a in gold["annotations"] for e in a["evidence"]))
        result.update(max((support_scores(predicted_text, r[2]) for r in references), key=lambda s: s["evidence_f1"]) if ok
                      else {"evidence_em": 0.0, "evidence_f1": 0.0, "evidence_recall": 0.0})
        result["read_evidence_recall"] = max(support_scores(read_text, r[2])["evidence_recall"] for r in references)
    else:
        if question["retrieval_mode"] != "distractor":
            raise ValueError("Full answerability labels require the original distractor scope")
        references = [gold["answer"]] + gold["answer_aliases"]
        result.update(answer_scored=gold["answerable"], gold_answerable=gold["answerable"],
                      hops=len(gold["decomposition"]),
                      answerability_correct=float(ok and predicted["predicted_answerable"] == gold["answerable"]),
                      answer_em=max(float(normalize(answer) == normalize(r)) for r in references) if ok and gold["answerable"] else 0.0,
                      answer_f1=max(answer_f1(answer, r, True) for r in references) if ok and gold["answerable"] else 0.0)
        result.update(support_scores(cited, gold["support_ids"]) if ok else {"evidence_em": 0.0, "evidence_f1": 0.0, "evidence_recall": 0.0})
        result["read_evidence_recall"] = support_scores(read, gold["support_ids"])["evidence_recall"]
    return result


def aggregate(scores):
    groups = defaultdict(list)
    for score in scores:
        groups[(score["dataset"], score["mode"])].append(score)
    result = {}
    for (dataset, mode), rows in sorted(groups.items()):
        answer_rows = [r for r in rows if r["answer_scored"]]
        mean = lambda name, subset: sum(r[name] for r in subset) / len(subset) if subset else None
        result[dataset + "/" + mode] = {"records": len(rows), "source_question_groups": len({r["source_question_id"] for r in rows}),
                                      "answer_scored_records": len(answer_rows),
                                      "statuses": dict(Counter(r["status"] for r in rows)),
                                      **{name: mean(name, answer_rows) for name in ("answer_em", "answer_f1", "evidence_f1", "evidence_recall", "read_evidence_recall")},
                                      "answerability_accuracy": mean("answerability_correct", rows),
                                      "paired_sufficiency_metrics": None,
                                      "answer_format_errors": sum(r["answer_format_error"] for r in rows)}
    return result
