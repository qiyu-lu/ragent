"""评测公共工具：锚点归一化、数据集加载、语料索引。"""

import json
import re
from pathlib import Path
from typing import Dict, List

REPO_ROOT = Path(__file__).resolve().parent.parent
CORPUS_DIR = REPO_ROOT / "resources" / "docs" / "knowledge"

# 归一化时剥掉的字符：空白（含全角空格）与 Markdown 标记
_STRIP_RE = re.compile(r"[\s　*#`_~]+")


def normalize(text: str) -> str:
    """归一化文本，使锚点匹配不受 Markdown 标记和空白差异影响。

    语料里写作 `**次日最晚可于 10:00 上班**`，标注时写作 `次日最晚可于 10:00 上班`，
    归一化后两者都变成 `次日最晚可于10:00上班`，可直接做子串匹配。
    """
    return _STRIP_RE.sub("", text or "")


def load_dataset(path: Path) -> List[dict]:
    """加载 JSONL 评测集，跳过空行。"""
    rows = []
    with open(path, encoding="utf-8") as f:
        for lineno, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as e:
                raise SystemExit(f"{path}:{lineno} JSON 解析失败: {e}") from e
    return rows


def load_corpus() -> Dict[str, str]:
    """按「文件名去后缀」索引语料全文，与接口返回的 docId 口径一致。

    接口的 retrievedDocIds 来自 t_knowledge_document.doc_name 剥文件后缀，
    因此这里用 Path.stem 作为键，保证标注、校验、跑分三方对齐。
    """
    corpus = {}
    for md in CORPUS_DIR.rglob("*.md"):
        corpus[md.stem] = md.read_text(encoding="utf-8")
    return corpus


def anchor_hit(anchor: str, texts: List[str]) -> int:
    """返回首个包含该锚点的文本下标（0-based），未命中返回 -1。"""
    target = normalize(anchor)
    if not target:
        return -1
    for i, t in enumerate(texts):
        if target in normalize(t):
            return i
    return -1
