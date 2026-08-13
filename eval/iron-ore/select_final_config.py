#!/usr/bin/env python3
"""Apply the preregistered intent/OCR gates for the current final arm."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from evalkit import sha256_file, write_json


POSITIVE_FAMILIES = ("xlsx", "native_pdf", "scan_pdf")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--current-default", type=Path, required=True, help="C0 retrieval report")
    parser.add_argument("--current-intent", type=Path, required=True, help="C1 retrieval report")
    parser.add_argument("--parse-ocr-off", type=Path, required=True, help="C0 parse audit")
    parser.add_argument("--parse-ocr-on", type=Path, required=True, help="C2 parse audit")
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def load(path: Path, kind: str) -> dict:
    report = json.loads(path.read_text(encoding="utf-8"))
    if report.get("kind") != kind:
        raise ValueError(f"{path} has kind={report.get('kind')!r}, expected {kind!r}")
    return report


def main() -> int:
    args = parse_args()
    if args.output.exists():
        print(f"refusing to overwrite {args.output}")
        return 1
    try:
        c0 = load(args.current_default, "retrieval")
        c1 = load(args.current_intent, "retrieval")
        ocr_off = load(args.parse_ocr_off, "parse-audit")
        ocr_on = load(args.parse_ocr_on, "parse-audit")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"input error: {exc}")
        return 1
    if c0.get("dataset_sha256") != c1.get("dataset_sha256"):
        print("C0 and C1 use different datasets")
        return 1

    c0_overall = (c0.get("summary") or {}).get("overall_answerable") or {}
    c1_overall = (c1.get("summary") or {}).get("overall_answerable") or {}
    intent_accuracy = c1_overall.get("intent_top1_correct")
    family_non_regression = True
    family_checks = {}
    for family in POSITIVE_FAMILIES:
        before = ((c0.get("summary") or {}).get("by_family") or {}).get(family) or {}
        after = ((c1.get("summary") or {}).get("by_family") or {}).get(family) or {}
        before_hit = before.get("anchor_hit@5_any")
        after_hit = after.get("anchor_hit@5_any")
        passed = before_hit is not None and after_hit is not None and after_hit + 1e-12 >= before_hit
        family_checks[family] = {"off": before_hit, "on": after_hit, "passed": passed}
        family_non_regression = family_non_regression and passed
    routing_off = c0_overall.get("routing_purity")
    routing_on = c1_overall.get("routing_purity")
    routing_non_regression = (
        routing_off is not None and routing_on is not None and routing_on + 1e-12 >= routing_off
    )
    intent_enabled = bool(
        intent_accuracy is not None
        and intent_accuracy >= 0.90
        and family_non_regression
        and routing_non_regression
    )

    off_by_family = {row.get("family"): row for row in ocr_off.get("details") or []}
    on_by_family = {row.get("family"): row for row in ocr_on.get("details") or []}
    scan_off = off_by_family.get("scan_pdf") or {}
    scan_on = on_by_family.get("scan_pdf") or {}
    native_off = off_by_family.get("native_pdf") or {}
    native_on = on_by_family.get("native_pdf") or {}
    scan_gain = (scan_on.get("anchor_recovered") or 0) - (scan_off.get("anchor_recovered") or 0)
    native_no_loss = (
        native_off.get("anchor_recovered") is not None
        and native_on.get("anchor_recovered") is not None
        and native_on["anchor_recovered"] >= native_off["anchor_recovered"]
    )
    ocr_enabled = bool(scan_gain >= 1 and native_no_loss)

    decision = {
        "schema_version": 1,
        "kind": "final-config-decision",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "final": {"intent_mode": "on" if intent_enabled else "off", "ocr": "on" if ocr_enabled else "off"},
        "intent_gate": {
            "enabled": intent_enabled,
            "top1_accuracy": intent_accuracy,
            "minimum_top1_accuracy": 0.90,
            "family_hit_at_5_non_regression": family_checks,
            "routing_purity": {"off": routing_off, "on": routing_on, "passed": routing_non_regression},
        },
        "ocr_gate": {
            "enabled": ocr_enabled,
            "scan_anchor_recovered": {
                "off": scan_off.get("anchor_recovered"),
                "on": scan_on.get("anchor_recovered"),
                "gain": scan_gain,
                "required_gain": 1,
            },
            "native_anchor_recovered": {
                "off": native_off.get("anchor_recovered"),
                "on": native_on.get("anchor_recovered"),
                "no_loss": native_no_loss,
            },
        },
        "inputs": {
            "current_default": sha256_file(args.current_default),
            "current_intent": sha256_file(args.current_intent),
            "parse_ocr_off": sha256_file(args.parse_ocr_off),
            "parse_ocr_on": sha256_file(args.parse_ocr_on),
        },
    }
    write_json(args.output, decision)
    print(f"intent={decision['final']['intent_mode']} ocr={decision['final']['ocr']}")
    print(f"decision: {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
