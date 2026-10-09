#!/usr/bin/env python3
"""Extract PDF text layers with ``pdftotext -layout`` and count characters by Unicode class.

Writes ``<out-dir>/<stem>.txt`` per document plus ``textlayer-summary.json``. Digits are counted by
Unicode category Nd so full-width ０-９ count (plan §0.8); ASCII and full-width counts are reported
separately so the "needs NFKC" documents are visible. XLSX sources are read with openpyxl.
"""

from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Optional

sys.path.insert(0, str(Path(__file__).resolve().parent))

from evalkit import (  # noqa: E402
    count_ascii_digits,
    count_digits,
    count_fullwidth_digits,
    count_noise_lines,
    count_non_space,
    count_pua,
    extract_source_text,
    load_corpus,
    sha256_file,
    sha256_text,
    utc_now_iso,
    write_json,
)

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DEFAULT_CORPUS = REPO_ROOT / "local-data/kq-eval/corpus-kq-s1.json"
DEFAULT_OUT = REPO_ROOT / "local-data/kq-eval/textlayer"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("files", nargs="*", type=Path, help="explicit files; default: every document in --corpus")
    parser.add_argument("--corpus", type=Path, default=DEFAULT_CORPUS)
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT)
    return parser.parse_args()


def pdf_pages(path: Path) -> Optional[int]:
    completed = subprocess.run(["pdfinfo", str(path)], check=False, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    for line in completed.stdout.decode("utf-8", errors="replace").splitlines():
        if line.startswith("Pages:"):
            try:
                return int(line.split(":", 1)[1].strip())
            except ValueError:
                return None
    return None


def pdf_fonts(path: Path) -> Dict[str, int]:
    """Font count and how many fonts lack a ToUnicode map (``uni`` column = no)."""

    completed = subprocess.run(["pdffonts", str(path)], check=False, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    lines = completed.stdout.decode("utf-8", errors="replace").splitlines()
    fonts = 0
    without_unicode = 0
    for line in lines[2:]:
        columns = line.split()
        if len(columns) < 6:
            continue
        fonts += 1
        # pdffonts columns: name type encoding emb sub uni object ID
        if columns[-3] == "no":
            without_unicode += 1
    return {"fonts": fonts, "fonts_without_unicode": without_unicode}


def guess_layer(stats: Dict[str, object]) -> str:
    pages = stats.get("pages") or 1
    per_page = (stats.get("non_space_chars") or 0) / max(1, int(pages))
    if per_page < 300:
        return "scan"
    if (stats.get("fullwidth_digits") or 0) > (stats.get("ascii_digits") or 0):
        return "text-fullwidth"
    return "text"


def analyse(path: Path, out_dir: Path, doc_id: Optional[str] = None) -> Dict[str, object]:
    text = extract_source_text(path)
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / (path.stem + ".txt")
    target.write_text(text, encoding="utf-8")
    stats: Dict[str, object] = {
        "id": doc_id or path.stem,
        "path": str(path),
        "sha256": sha256_file(path),
        "text_path": str(target),
        "text_sha256": sha256_text(text),
        "chars": len(text),
        "non_space_chars": count_non_space(text),
        "digits_nd": count_digits(text),
        "ascii_digits": count_ascii_digits(text),
        "fullwidth_digits": count_fullwidth_digits(text),
        "pua_chars": count_pua(text),
        "noise_lines": count_noise_lines(text),
    }
    if path.suffix.lower() == ".pdf":
        stats["pages"] = pdf_pages(path)
        stats.update(pdf_fonts(path))
        stats["text_layer_guess"] = guess_layer(stats)
    else:
        stats["pages"] = None
        stats["text_layer_guess"] = "xlsx" if path.suffix.lower().startswith(".xls") else "text"
    return stats


def main() -> int:
    args = parse_args()
    targets: List[tuple] = []
    if args.files:
        targets = [(None, path) for path in args.files]
    else:
        manifest = load_corpus(args.corpus)
        targets = [(doc["id"], Path(doc["path"])) for doc in manifest["documents"]]
    results = []
    for doc_id, path in targets:
        if not path.is_file():
            print(f"missing: {path}")
            return 1
        stats = analyse(path, args.out_dir, doc_id)
        results.append(stats)
        print(f"{stats['id'][:28]:30s} pages={str(stats['pages']):>4} nonspace={stats['non_space_chars']:>7} "
              f"Nd={stats['digits_nd']:>6} ascii={stats['ascii_digits']:>6} full={stats['fullwidth_digits']:>6} "
              f"pua={stats['pua_chars']:>4} noise={stats['noise_lines']:>3} guess={stats['text_layer_guess']}")
    write_json(args.out_dir / "textlayer-summary.json", {
        "schema_version": 1,
        "kind": "kq-textlayer",
        "created_at": utc_now_iso(),
        "tool": "pdftotext -layout; digits counted by Unicode category Nd",
        "documents": results,
    })
    print(f"summary: {args.out_dir / 'textlayer-summary.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
