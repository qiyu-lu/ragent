#!/usr/bin/env python3
"""Download the pinned public HotpotQA mirror and verify every byte."""

from __future__ import annotations

import argparse
import json
import urllib.request
from pathlib import Path

from cs_evalkit import sha256_file


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sources", type=Path, default=Path(__file__).with_name("sources.json"))
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    manifest = json.loads(args.sources.read_text(encoding="utf-8"))
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for item in manifest["files"]:
        target = args.output_dir / item["name"]
        if target.exists():
            actual = sha256_file(target)
            if actual != item["sha256"]:
                raise ValueError(f"existing {target} has SHA-256 {actual}, expected {item['sha256']}")
            print(f"verified existing {target}")
            continue
        temporary = target.with_suffix(target.suffix + ".partial")
        if temporary.exists():
            raise FileExistsError(f"remove or inspect incomplete download first: {temporary}")
        try:
            urllib.request.urlretrieve(item["url"], temporary)
            actual = sha256_file(temporary)
            if actual != item["sha256"]:
                raise ValueError(f"downloaded {item['name']} has SHA-256 {actual}, expected {item['sha256']}")
            if temporary.stat().st_size != item["bytes"]:
                raise ValueError(f"downloaded {item['name']} has an unexpected size")
            temporary.replace(target)
            print(f"downloaded and verified {target}")
        except Exception:
            if temporary.exists():
                print(f"incomplete file retained for inspection: {temporary}")
            raise


if __name__ == "__main__":
    main()
