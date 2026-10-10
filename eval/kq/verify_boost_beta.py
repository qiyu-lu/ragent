#!/usr/bin/env python3
"""Check that each retrieval run used the boost setting its arm name claims, and that it is valid.

The metadata boost (stage 2, plan §5.3) reorders the rerank head by ``rerank + beta * match``. The
evaluation response does not report beta, but it carries the rerank score of every head candidate and
the boosted order, so the beta a run actually used can be recovered: recompute ``match`` from the
term table and the confirmed document metadata, and find the beta whose ordering reproduces the
observed one. Arms named ``...boost-<beta>`` must show the boost stage and that beta; any other arm
must show no boost stage. A run is also invalid when more than ``--max-empty-channel`` sub-questions
hit the channel timeout.

    python3 eval/kq/verify_boost_beta.py S2-boost-0.2-tune-r1 S2-boost-0.2-tune-r2 S2-boost-0.2-tune-r3
"""

from __future__ import annotations

import argparse
import csv
import re
import sys
from pathlib import Path
from typing import Dict, List, Mapping, Optional, Sequence, Tuple

sys.path.insert(0, str(Path(__file__).resolve().parent))

from evalkit import normalize, read_json  # noqa: E402

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
BOOST_STAGE = "post-MetadataBoost"
ARM_BETA = re.compile(r"boost-([0-9]+(?:\.[0-9]+)?)")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("labels", nargs="+", help="run labels under --runs-dir")
    parser.add_argument("--runs-dir", type=Path, default=REPO_ROOT / "local-data/kq-eval/runs")
    parser.add_argument("--terms", type=Path, default=REPO_ROOT / "bootstrap/src/main/resources/kq/terms.csv")
    parser.add_argument("--metadata", type=Path,
                        default=REPO_ROOT / "local-data/kq-eval/metadata/doc-metadata-s2-confirmed.json")
    parser.add_argument("--betas", type=float, nargs="+", default=[0.1, 0.2, 0.3])
    parser.add_argument("--max-empty-channel", type=int, default=8)
    return parser.parse_args()


class Terms:
    """Longest-match scan over the term table, same normalisation idea as the server's TermDictionary."""

    def __init__(self, rows: Sequence[Mapping[str, str]]):
        self.forms: Dict[str, Tuple[str, str]] = {}
        for row in rows:
            for variant in [row["term"]] + [value for value in (row.get("synonyms") or "").split("|") if value]:
                form = normalize(variant).lower()
                if form:
                    self.forms.setdefault(form, (row["term"].strip(), row["category"].strip()))
        self.max_length = max((len(form) for form in self.forms), default=0)

    def canonicals(self, text: str, category: str) -> List[str]:
        value = normalize(text).lower()
        found: List[str] = []
        i = 0
        while i < len(value):
            for length in range(min(self.max_length, len(value) - i), 0, -1):
                hit = self.forms.get(value[i:i + length])
                if hit:
                    if hit[1] == category and hit[0] not in found:
                        found.append(hit[0])
                    i += length
                    break
            else:
                i += 1
        return found


def match(objects: Sequence[str], components: Sequence[str], metadata: Optional[Mapping]) -> float:
    mentioned = (1 if objects else 0) + (1 if components else 0)
    if not mentioned or not metadata:
        return 0.0
    matched = (1 if objects and set(metadata.get("objects") or []) & set(objects) else 0) \
        + (1 if components and set(metadata.get("components") or []) & set(components) else 0)
    return matched / mentioned


def infer_beta(report: Mapping, terms: Terms, metadata: Mapping[str, Mapping], betas: Sequence[float]) -> dict:
    """Share of decisive sub-questions whose observed head order each beta reproduces."""

    agreement = {beta: 0 for beta in betas}
    decisive = 0
    for item in report.get("details") or []:
        for result in (item.get("raw_response") or {}).get("results") or []:
            head = [c for c in result.get("candidates") or [] if c.get("rerankHead") and c.get("rerankScore") is not None]
            if len(head) < 2:
                continue
            objects = terms.canonicals(result.get("subQuestion") or "", "检测对象")
            components = terms.canonicals(result.get("subQuestion") or "", "组分")
            observed = [c["id"] for c in head]
            by_rerank = sorted(head, key=lambda c: -c["rerankScore"])
            orders = {beta: [c["id"] for c in sorted(by_rerank, key=lambda c: -(
                c["rerankScore"] + beta * match(objects, components, metadata.get(c.get("docName")))))]
                for beta in betas}
            if len({tuple(order) for order in orders.values()}) < 2:
                continue  # every beta gives the same order: not informative
            decisive += 1
            for beta, order in orders.items():
                agreement[beta] += order == observed
    best = max(agreement.values()) if decisive else 0
    winners = [beta for beta, count in agreement.items() if count == best]
    return {"decisive": decisive, "agreement": agreement,
            "beta": winners[0] if decisive and len(winners) == 1 else None}


def empty_channels(report: Mapping) -> int:
    """Empty vector-channel sub-questions, the same count as compare_retrieval_repeats."""

    return sum(1 for item in report.get("details") or []
               for stage in (item.get("raw_response") or {}).get("stages") or []
               if stage.get("stage") == "channel-VectorSearch" and not stage.get("chunkCount"))


def check_run(report: Mapping, terms: Terms, metadata: Mapping[str, Mapping], betas: Sequence[float],
              max_empty: int) -> Tuple[bool, str]:
    arm = str(report.get("arm") or "")
    expected = ARM_BETA.search(arm)
    expected_beta = float(expected.group(1)) if expected else None
    details = report.get("details") or []
    boosted = sum(1 for item in details if any(stage.get("stage") == BOOST_STAGE
                                               for stage in (item.get("raw_response") or {}).get("stages") or []))
    empty = empty_channels(report)
    problems = []
    if expected_beta is None:
        if boosted:
            problems.append(f"arm {arm} should run without boost but {boosted}/{len(details)} answers went through it")
        found = "off"
    else:
        if boosted < len(details):
            problems.append(f"boost stage missing in {len(details) - boosted}/{len(details)} answers: "
                            "the instance was not started with --rag.search.metadata-boost.enabled=true")
            found = "off"
        else:
            inferred = infer_beta(report, terms, metadata, betas)
            found = "?" if inferred["beta"] is None else f"{inferred['beta']:g}"
            if inferred["beta"] is None or abs(inferred["beta"] - expected_beta) > 1e-9:
                problems.append(f"head order fits beta {found} (agreement {inferred['agreement']} over "
                                f"{inferred['decisive']} decisive sub-questions), not {expected_beta:g}: "
                                "restart the instance with the arm's beta")
    if empty > max_empty:
        problems.append(f"{empty} empty-channel sub-questions > {max_empty}")
    line = f"boost={found} expected={'off' if expected_beta is None else format(expected_beta, 'g')} empty-channel={empty}"
    return not problems, line + ("" if not problems else " | " + "; ".join(problems))


def main() -> int:
    args = parse_args()
    with args.terms.open(encoding="utf-8") as handle:
        terms = Terms(list(csv.DictReader(handle)))
    metadata = (read_json(args.metadata).get("documents") or {}) if args.metadata.is_file() else {}
    failed = 0
    for label in args.labels:
        path = args.runs_dir / label / "retrieval.json"
        if not path.is_file():
            print(f"{label}: missing {path}")
            failed += 1
            continue
        ok, line = check_run(read_json(path), terms, metadata, args.betas, args.max_empty_channel)
        failed += not ok
        print(f"{label}: {'OK' if ok else 'INVALID'}  {line}")
    if failed:
        print(f"{failed} run(s) invalid: move each aside (invalid-<label>-<reason>) and rerun it with the same label")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
