#!/usr/bin/env python3
"""Call the MinerU v4 API directly with explicit parameters and keep the raw output.

Flow (same as MinerUClient.java): ``POST file-urls/batch`` → ``PUT`` the file to the pre-signed URL →
poll ``GET extract-results/batch/{batch_id}`` → download the zip. Unlike the application this script also
sends ``model_version`` (``pipeline`` / ``vlm`` / ``MinerU-HTML``) and saves everything under
``local-data/kq-eval/mineru-raw/<file-stem>/<params>/<timestamp>/`` with a ``probe.json`` that records
the parameters, timings, zip / markdown hashes and the digit, empty-slot and noise counts of ``full.md``.

Requires ``MINERU_API_KEY`` in the environment. Each call costs MinerU quota; run one step at a time
(plan §4.2) and look at the result before the next.
"""

from __future__ import annotations

import argparse
import io
import json
import os
import sys
import time
import urllib.request
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

sys.path.insert(0, str(Path(__file__).resolve().parent))

from evalkit import (  # noqa: E402
    count_digits,
    count_empty_slots,
    count_noise_lines,
    count_non_space,
    sha256_file,
    sha256_text,
    slots_per_1000,
    utc_now_iso,
    write_json,
)

HERE = Path(__file__).resolve().parent
REPO_ROOT = HERE.parents[1]
DEFAULT_OUT = REPO_ROOT / "local-data/kq-eval/mineru-raw"
DEFAULT_API = "https://mineru.net/api/v4"


class ProbeError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--file", type=Path, required=True)
    parser.add_argument("--is-ocr", choices=["true", "false"], default="false")
    parser.add_argument("--enable-formula", choices=["true", "false"], default="true")
    parser.add_argument("--enable-table", choices=["true", "false"], default="true")
    parser.add_argument("--model-version", choices=["pipeline", "vlm", "MinerU-HTML"], default=None,
                        help="omit to let the service default (pipeline)")
    parser.add_argument("--language", default="ch")
    parser.add_argument("--api-url", default=os.environ.get("MINERU_API_URL", DEFAULT_API))
    parser.add_argument("--out-root", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--poll-seconds", type=int, default=10)
    parser.add_argument("--timeout-seconds", type=int, default=1800)
    parser.add_argument("--note", default="", help="free text stored in probe.json, e.g. 'step a'")
    parser.add_argument("--dry-run", action="store_true", help="print the request body and exit")
    return parser.parse_args()


def _bool(value: str) -> bool:
    return value == "true"


def param_key(args: argparse.Namespace) -> str:
    version = args.model_version or "pipeline"
    return f"{version}_ocr-{args.is_ocr}_formula-{args.enable_formula}_table-{args.enable_table}"


def _request(url: str, *, method: str = "GET", body: Optional[bytes] = None, headers: Optional[Dict[str, str]] = None,
             timeout: int = 120) -> bytes:
    request = urllib.request.Request(url, data=body, method=method)
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read()
    except urllib.error.HTTPError as exc:  # type: ignore[attr-defined]
        detail = exc.read().decode("utf-8", errors="replace")[:500]
        raise ProbeError(f"{method} {url} -> HTTP {exc.code}: {detail}") from exc
    except Exception as exc:
        raise ProbeError(f"{method} {url} failed: {exc}") from exc


def _json(url: str, api_key: str, *, method: str = "GET", body: Optional[dict] = None) -> dict:
    headers = {"Authorization": f"Bearer {api_key}", "Accept": "application/json"}
    raw = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        raw = json.dumps(body, ensure_ascii=False).encode("utf-8")
    payload = json.loads(_request(url, method=method, body=raw, headers=headers).decode("utf-8"))
    if payload.get("code") != 0:
        raise ProbeError(f"MinerU {method} {url} business error code={payload.get('code')} msg={payload.get('msg')}")
    return payload.get("data") or {}


def build_body(args: argparse.Namespace, data_id: str) -> dict:
    body: Dict[str, Any] = {
        "enable_formula": _bool(args.enable_formula),
        "enable_table": _bool(args.enable_table),
        "language": args.language,
        "files": [{"name": args.file.name, "is_ocr": _bool(args.is_ocr), "data_id": data_id}],
    }
    if args.model_version:
        body["model_version"] = args.model_version
    return body


def markdown_from_zip(zip_bytes: bytes) -> tuple:
    """First ``.md`` entry (what MinerUResultUnpacker uses) plus the names of all entries."""

    names: List[str] = []
    markdown = None
    md_names: List[str] = []
    images = 0
    with zipfile.ZipFile(io.BytesIO(zip_bytes)) as archive:
        for info in archive.infolist():
            if info.is_dir():
                continue
            names.append(info.filename)
            lower = info.filename.lower()
            if lower.endswith(".md"):
                md_names.append(info.filename)
                if markdown is None:
                    markdown = archive.read(info).decode("utf-8", errors="replace")
            elif lower.endswith((".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp")):
                images += 1
    return markdown, md_names, images, names


def markdown_metrics(markdown: str) -> dict:
    lines = markdown.splitlines()
    return {
        "chars": len(markdown),
        "non_space_chars": count_non_space(markdown),
        "digits_nd": count_digits(markdown),
        "empty_slots": count_empty_slots(markdown),
        "slots_per_1000": slots_per_1000(markdown),
        "noise_lines": count_noise_lines(markdown),
        "dollar_signs": markdown.count("$"),
        "lines_with_math": sum(1 for line in lines if "$" in line),
        "image_refs": markdown.count("]("),
    }


def main() -> int:
    args = parse_args()
    if not args.file.is_file():
        print(f"missing file: {args.file}")
        return 1
    api_key = os.environ.get("MINERU_API_KEY", "")
    data_id = f"kq-{int(time.time())}"
    body = build_body(args, data_id)
    if args.dry_run:
        print(json.dumps({"url": args.api_url + "/file-urls/batch", "body": body}, ensure_ascii=False, indent=2))
        return 0
    if not api_key:
        print("MINERU_API_KEY is not set")
        return 1

    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out_dir = args.out_root / args.file.stem / param_key(args) / stamp
    out_dir.mkdir(parents=True, exist_ok=False)
    probe: Dict[str, Any] = {
        "schema_version": 1,
        "kind": "kq-mineru-probe",
        "note": args.note,
        "file": str(args.file),
        "file_sha256": sha256_file(args.file),
        "api_url": args.api_url,
        "params": {
            "model_version": args.model_version or "(service default: pipeline)",
            "is_ocr": _bool(args.is_ocr),
            "enable_formula": _bool(args.enable_formula),
            "enable_table": _bool(args.enable_table),
            "language": args.language,
        },
        "request_body": body,
        "started_at": utc_now_iso(),
    }
    started = time.monotonic()
    try:
        data = _json(args.api_url + "/file-urls/batch", api_key, method="POST", body=body)
        batch_id = data.get("batch_id")
        urls = data.get("file_urls") or []
        if not batch_id or not urls:
            raise ProbeError(f"requestUpload returned no batch_id/file_urls: {data}")
        probe["batch_id"] = batch_id
        _request(urls[0], method="PUT", body=args.file.read_bytes(), timeout=600)
        probe["uploaded_at"] = utc_now_iso()

        deadline = time.monotonic() + args.timeout_seconds
        polls = 0
        state = None
        item: Dict[str, Any] = {}
        while True:
            polls += 1
            result = _json(f"{args.api_url}/extract-results/batch/{batch_id}", api_key)
            items = result.get("extract_result") or []
            item = items[0] if items else {}
            state = str(item.get("state") or "").lower()
            if state in {"done", "success", "succeeded", "completed"}:
                break
            if state in {"failed", "fail", "error"}:
                raise ProbeError(f"MinerU task failed: {item.get('err_msg')}")
            if time.monotonic() > deadline:
                raise ProbeError(f"MinerU task timed out after {args.timeout_seconds}s, last state={state!r}")
            print(f"  state={state or 'queued'} ({polls} polls, {round(time.monotonic() - started)}s)")
            time.sleep(args.poll_seconds)
        probe["polls"] = polls
        probe["final_state"] = state
        probe["service_item"] = {key: item.get(key) for key in ("state", "err_msg", "data_id", "file_name")}
        zip_url = item.get("full_zip_url")
        if not zip_url:
            raise ProbeError("done without full_zip_url")
        zip_bytes = _request(zip_url, timeout=600)
    except ProbeError as exc:
        probe["error"] = str(exc)
        probe["elapsed_seconds"] = round(time.monotonic() - started, 1)
        write_json(out_dir / "probe.json", probe)
        print(f"probe failed: {exc}\nprobe: {out_dir / 'probe.json'}")
        return 1

    probe["elapsed_seconds"] = round(time.monotonic() - started, 1)
    (out_dir / "result.zip").write_bytes(zip_bytes)
    markdown, md_names, images, names = markdown_from_zip(zip_bytes)
    probe["zip_sha256"] = sha256_file(out_dir / "result.zip")
    probe["zip_bytes"] = len(zip_bytes)
    probe["zip_entries"] = len(names)
    probe["markdown_entries"] = md_names
    probe["image_entries"] = images
    if markdown is None:
        probe["error"] = "zip contains no .md"
    else:
        (out_dir / "full.md").write_text(markdown, encoding="utf-8")
        probe["markdown_sha256"] = sha256_text(markdown)
        probe["markdown"] = markdown_metrics(markdown)
    write_json(out_dir / "probe.json", probe)
    metrics = probe.get("markdown") or {}
    print(f"done in {probe['elapsed_seconds']}s  digits={metrics.get('digits_nd')}  slots={metrics.get('empty_slots')} "
          f"(per1000={None if metrics.get('slots_per_1000') is None else round(metrics['slots_per_1000'], 2)})  "
          f"noise={metrics.get('noise_lines')}  $={metrics.get('dollar_signs')}  zip={probe['zip_sha256'][:12]}")
    print(f"probe: {out_dir / 'probe.json'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
