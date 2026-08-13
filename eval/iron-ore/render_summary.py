#!/usr/bin/env python3
"""Render a sanitized Markdown evidence summary without questions or source text."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

from evalkit import sha256_file


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--b0-retrieval", type=Path, required=True)
    parser.add_argument("--c0-retrieval", type=Path, required=True)
    parser.add_argument("--c-final-retrieval", type=Path, required=True)
    parser.add_argument("--human-score", type=Path, required=True)
    parser.add_argument("--decision", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def load(path: Path, kind: str) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("kind") != kind:
        raise ValueError(f"{path}: expected kind={kind}, got {value.get('kind')}")
    return value


def pct(value) -> str:
    return "—" if value is None else f"{100 * value:.1f}%"


def metric(report: dict, key: str):
    return (((report.get("summary") or {}).get("overall_answerable") or {}).get(key))


def family_metric(report: dict, family: str, key: str):
    return (((report.get("summary") or {}).get("by_family") or {}).get(family) or {}).get(key)


def main() -> int:
    args = parse_args()
    if args.output.exists():
        print(f"refusing to overwrite {args.output}")
        return 1
    try:
        b0 = load(args.b0_retrieval, "retrieval")
        c0 = load(args.c0_retrieval, "retrieval")
        c_final = load(args.c_final_retrieval, "retrieval")
        human = load(args.human_score, "human-review-score")
        decision = load(args.decision, "final-config-decision")
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"input error: {exc}")
        return 1
    if len({b0.get("dataset_sha256"), c0.get("dataset_sha256"), c_final.get("dataset_sha256")}) != 1:
        print("retrieval reports use different dataset hashes")
        return 1

    arms = human.get("summary") or {}
    if "B0" not in arms or "C-final" not in arms:
        print("human score must contain B0 and C-final arms")
        return 1

    lines = [
        "# 铁矿 RAG 小型评测证据摘要",
        "",
        f"> 生成时间：{datetime.now(timezone.utc).isoformat()}。本页不包含问题、企业原文或标准长摘录。",
        "",
        "## 评测范围",
        "",
        "- 3 类文档：完整 XLSX、原生文本 PDF、扫描/异常文本层 PDF。",
        "- 24 道固定题：18 道单文档题、3 道近领域混淆题、3 道不可回答题。",
        "- B0 与 C-final 共 48 个完整回答，采用隐藏实验臂的人工严格判定。",
        f"- C-final 配置：意图 `{decision['final']['intent_mode']}`，OCR `{decision['final']['ocr']}`。",
        "",
        "## 同配置检索：B0 与 C0",
        "",
        "| 指标 | B0 基线 | C0 当前版 | 变化 |",
        "| --- | ---: | ---: | ---: |",
    ]
    for title, key in (
        ("Hit@5（至少一个锚点）", "anchor_hit@5_any"),
        ("Hit@5（全部锚点）", "anchor_hit@5_all"),
        ("Anchor Recall", "anchor_recall"),
        ("Context Precision", "context_precision"),
    ):
        before = metric(b0, key)
        after = metric(c0, key)
        delta = None if before is None or after is None else after - before
        lines.append(f"| {title} | {pct(before)} | {pct(after)} | {pct(delta) if delta is not None else '—'} |")

    lines.extend(
        [
            "",
            "## 分文档族 Hit@5",
            "",
            "| 文档族 | B0 | C0 | C-final |",
            "| --- | ---: | ---: | ---: |",
        ]
    )
    names = {"xlsx": "完整 XLSX", "native_pdf": "原生 PDF", "scan_pdf": "扫描 PDF", "cross_domain": "近领域混淆"}
    for family in names:
        lines.append(
            f"| {names[family]} | {pct(family_metric(b0, family, 'anchor_hit@5_any'))} | "
            f"{pct(family_metric(c0, family, 'anchor_hit@5_any'))} | "
            f"{pct(family_metric(c_final, family, 'anchor_hit@5_any'))} |"
        )

    b0_human = arms["B0"]
    cf_human = arms["C-final"]
    lines.extend(
        [
            "",
            "## 端到端人工严格判定",
            "",
            "| 指标 | B0 基线 | C-final |",
            "| --- | ---: | ---: |",
            f"| 全部问题严格通过率 | {pct(b0_human['overall'].get('strict_pass_rate'))} | {pct(cf_human['overall'].get('strict_pass_rate'))} |",
            f"| 可回答题严格通过率 | {pct(b0_human['answerable'].get('strict_pass_rate'))} | {pct(cf_human['answerable'].get('strict_pass_rate'))} |",
            f"| 不可回答题正确拒答率 | {pct(b0_human['unanswerable'].get('refusal_correct'))} | {pct(cf_human['unanswerable'].get('refusal_correct'))} |",
            f"| 回答延迟 P95 | {b0_human['overall'].get('latency_ms', {}).get('p95', '—')} ms | {cf_human['overall'].get('latency_ms', {}).get('p95', '—')} ms |",
            "",
            "## 使用边界",
            "",
            "- 这是小型代表性评测，不代表所有国标格式或企业流程。",
            "- B0/C0 是同配置代码比较；B0/C-final 是端到端比较，不能把全部差异单独归因于某一模块。",
            "- 原始逐题输出、人工标签、模型配置和数据库快照保存在本地审计目录。",
            "",
            "## 输入哈希",
            "",
        ]
    )
    for name, path in (
        ("B0 retrieval", args.b0_retrieval),
        ("C0 retrieval", args.c0_retrieval),
        ("C-final retrieval", args.c_final_retrieval),
        ("human score", args.human_score),
        ("config decision", args.decision),
    ):
        lines.append(f"- `{name}`: `{sha256_file(path)}`")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"summary: {args.output}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
