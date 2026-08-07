"""校验评测集自身的正确性，跑分前必须先通过。

检查四件事：
1. id 唯一、tier 合法、必填字段齐全
2. reference_docs 里的文档名在语料中真实存在
3. 每个 reference_anchor 确实出现在它标注的某篇 reference_doc 全文里
4. negative 档不带任何 reference_docs / reference_anchors

第 3 条最关键：锚点写错字会让指标恒为 0，且看起来像是检索效果差。
"""

import sys
from pathlib import Path
from typing import Dict, List, Set

from evalkit import anchor_hit, load_corpus, load_dataset

VALID_TIERS = {"easy", "hard", "negative"}
DATASET = Path(__file__).resolve().parent / "dataset.jsonl"


def main() -> int:
    rows = load_dataset(DATASET)
    corpus = load_corpus()
    errors = []  # type: List[str]
    seen_ids = set()  # type: Set[str]

    for row in rows:
        rid = row.get("id") or "<缺少 id>"

        if rid in seen_ids:
            errors.append(f"[{rid}] id 重复")
        seen_ids.add(rid)

        if row.get("tier") not in VALID_TIERS:
            errors.append(f"[{rid}] tier 非法: {row.get('tier')!r}")

        if not (row.get("question") or "").strip():
            errors.append(f"[{rid}] question 为空")

        docs = row.get("reference_docs") or []
        anchors = row.get("reference_anchors") or []

        if row.get("tier") == "negative":
            if docs or anchors:
                errors.append(f"[{rid}] negative 档不应带 reference_docs / reference_anchors")
            continue

        if not docs:
            errors.append(f"[{rid}] 非 negative 档必须至少标一个 reference_doc")

        for doc in docs:
            if doc not in corpus:
                near = [k for k in corpus if doc in k or k in doc]
                hint = f"，是否想写 {near}" if near else ""
                errors.append(f"[{rid}] reference_doc 不存在: {doc!r}{hint}")

        # 锚点必须能在它标注的任一 reference_doc 全文里找到
        doc_texts = [corpus[d] for d in docs if d in corpus]
        for anchor in anchors:
            if doc_texts and anchor_hit(anchor, doc_texts) < 0:
                errors.append(f"[{rid}] 锚点在 {docs} 全文中找不到: {anchor!r}")

    tiers = {}  # type: Dict[str, int]
    for row in rows:
        tiers[row.get("tier", "?")] = tiers.get(row.get("tier", "?"), 0) + 1

    print(f"共 {len(rows)} 条：" + "、".join(f"{k} {v}" for k, v in sorted(tiers.items())))

    if errors:
        print(f"\n发现 {len(errors)} 个问题：")
        for e in errors:
            print("  ✗", e)
        return 1

    print("校验通过：文档名、锚点全部可在语料中定位。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
