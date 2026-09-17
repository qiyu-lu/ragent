#!/usr/bin/env python3
"""Compare offline metric formulas with separately downloaded author code.

Only standard-library imports, pure functions and the SupportMetric class are
loaded from the reference files. No third-party metric runtime is required.
The output pins the actual author source bytes used by this check.
"""
import argparse
import ast
import hashlib
import json
from pathlib import Path

from scoring import answer_f1, normalize, support_scores


def reference(path, class_name=None):
    tree = ast.parse(path.read_text())
    nodes = []
    for node in tree.body:
        if isinstance(node, ast.Import):
            if all(n.name in {"collections", "re", "string"} for n in node.names):
                nodes.append(node)
        elif isinstance(node, ast.ImportFrom) and node.module in {"collections", "typing"}:
            nodes.append(node)
        elif isinstance(node, ast.FunctionDef):
            nodes.append(node)
        elif isinstance(node, ast.ClassDef) and node.name == class_name:
            nodes.append(node)
    namespace = {"Metric": object}
    exec(compile(ast.Module(body=nodes, type_ignores=[]), str(path), "exec"), namespace)
    return namespace


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--references", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    names = ["qasper-evaluator.py", "musique-answer.py", "musique-support.py", "musique-evaluate.py"]
    hashes = {n: hashlib.sha256((args.references / n).read_bytes()).hexdigest() for n in names}
    q = reference(args.references / names[0])
    m = reference(args.references / names[1])
    s = reference(args.references / names[2], "SupportMetric")
    answers = ["", "the", "A black cat", "black cats", "Yes", "No", "Unanswerable", "105 ± 5 C", "cat, cat!", "Neural\nnetworks", "a b c", "C B A", "évidence", "blue green"]
    pairs = 0
    for gold in answers:
        for predicted in answers:
            assert normalize(predicted) == q["normalize_answer"](predicted) == m["normalize_answer"](predicted)
            assert abs(answer_f1(predicted, gold) - q["token_f1_score"](predicted, gold)) < 1e-12
            assert abs(answer_f1(predicted, gold, True) - m["compute_f1"](gold, predicted)) < 1e-12
            assert float(normalize(gold) == normalize(predicted)) == m["compute_exact"](gold, predicted)
            pairs += 1
    supports = [[], [0], [1], [0, 1], [1, 2], [0, 1, 2], [2, 3, 4]]
    support_pairs = 0
    for gold in supports:
        for predicted in supports:
            ours = support_scores(predicted, gold)
            assert abs(ours["evidence_f1"] - q["paragraph_f1_score"](predicted, gold)) < 1e-12
            metric = s["SupportMetric"]()
            metric(predicted, gold)
            em, f1 = metric.get_metric()
            assert abs(ours["evidence_em"] - em) < 1e-12
            assert abs(ours["evidence_f1"] - f1) < 1e-12
            support_pairs += 1
    result = {"author_source_sha256": hashes, "answer_pairs": pairs, "support_pairs": support_pairs,
              "formula_alignment": "passed", "qasper_empty_answer_f1": 0, "musique_empty_answer_f1": 1,
              "limits": ["paragraph identity uses the prepared corpus representation", "no semantic entailment proof", "MuSiQue paired sufficiency is not implemented for unpaired samples"]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
