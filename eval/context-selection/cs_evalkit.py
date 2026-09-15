"""Shared helpers for the context-selection evaluation.

The module deliberately keeps gold-labelled datasets separate from candidate
snapshots.  Selection code consumes snapshots only; scoring joins the selected
IDs back to the labelled dataset afterwards.
"""

from __future__ import annotations

import hashlib
import json
import math
import re
from pathlib import Path
from typing import Any, Iterable, Iterator, Mapping, Sequence


SCHEMA_VERSION = "context-selection-dataset-v1"
SNAPSHOT_SCHEMA_VERSION = "context-selection-candidate-snapshot-v1"
_TOKEN_RE = re.compile(r"[A-Za-z0-9]+|[\u3400-\u9fff]")


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open(encoding="utf-8") as handle:
        for line_number, raw in enumerate(handle, 1):
            line = raw.strip()
            if not line:
                continue
            try:
                value = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_number}: invalid JSON: {exc}") from exc
            if not isinstance(value, dict):
                raise ValueError(f"{path}:{line_number}: each row must be an object")
            rows.append(value)
    return rows


def write_json(path: Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: Iterable[Mapping[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(dict(row), ensure_ascii=False, separators=(",", ":")) + "\n")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def stable_hash(*parts: object) -> str:
    joined = "\x1f".join(str(part) for part in parts)
    return hashlib.sha256(joined.encode("utf-8")).hexdigest()


def tokenize(text: str) -> list[str]:
    return [token.lower() for token in _TOKEN_RE.findall(text or "")]


def heuristic_token_count(text: str) -> int:
    """Conservative local estimate; never presented as provider token usage."""

    tokens = tokenize(text)
    punctuation = sum(1 for char in text if not char.isspace() and not char.isalnum())
    return max(1, len(tokens) + math.ceil(punctuation / 3))


def bm25_scores(query: str, documents: Sequence[str]) -> list[float]:
    """Small deterministic BM25 implementation for offline fixture features."""

    query_terms = list(dict.fromkeys(tokenize(query)))
    doc_terms = [tokenize(document) for document in documents]
    if not documents:
        return []
    avg_len = sum(len(terms) for terms in doc_terms) / len(doc_terms) or 1.0
    document_frequency = {
        term: sum(1 for terms in doc_terms if term in set(terms)) for term in query_terms
    }
    scores: list[float] = []
    for terms in doc_terms:
        counts: dict[str, int] = {}
        for term in terms:
            counts[term] = counts.get(term, 0) + 1
        score = 0.0
        for term in query_terms:
            tf = counts.get(term, 0)
            if not tf:
                continue
            df = document_frequency[term]
            idf = math.log(1.0 + (len(documents) - df + 0.5) / (df + 0.5))
            denominator = tf + 1.2 * (1.0 - 0.75 + 0.75 * len(terms) / avg_len)
            score += idf * tf * 2.2 / denominator
        scores.append(round(score, 12))
    return scores


def hashing_vector(text: str, dimensions: int = 256) -> list[float]:
    """Signed hashing vector used only as an explicit offline redundancy proxy."""

    vector = [0.0] * dimensions
    for token in tokenize(text):
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        index = int.from_bytes(digest[:4], "big") % dimensions
        sign = 1.0 if digest[4] & 1 else -1.0
        vector[index] += sign
    norm = math.sqrt(sum(value * value for value in vector))
    if norm:
        vector = [round(value / norm, 12) for value in vector]
    return vector


def explicit_aspects(question: str, task_type: str | None = None) -> list[str]:
    """Extract only explicit comparison alternatives; otherwise use the question."""

    normalized = " ".join((question or "").split())
    if task_type == "comparison":
        match = re.search(r"\b(.{2,80}?)\s+or\s+(.{2,80}?)(?:\?|$)", normalized, re.IGNORECASE)
        if match:
            prefix = normalized[: match.start()].strip()
            left = f"{prefix} {match.group(1)}".strip()
            right = f"{prefix} {match.group(2)}".strip()
            aspects = list(dict.fromkeys([left, right]))
            if len(aspects) == 2:
                return aspects
    return [normalized] if normalized else []


def iter_json_array(path: Path, chunk_size: int = 1024 * 1024) -> Iterator[dict[str, Any]]:
    """Stream a top-level JSON array without loading the 535 MB train file."""

    decoder = json.JSONDecoder()
    with path.open(encoding="utf-8") as handle:
        buffer = ""
        position = 0
        started = False
        ended = False
        while not ended:
            block = handle.read(chunk_size)
            if block:
                buffer = buffer[position:] + block
                position = 0
            elif position >= len(buffer):
                break
            while True:
                while position < len(buffer) and buffer[position].isspace():
                    position += 1
                if not started:
                    if position >= len(buffer):
                        break
                    if buffer[position] != "[":
                        raise ValueError(f"{path}: expected a top-level JSON array")
                    started = True
                    position += 1
                    continue
                while position < len(buffer) and (buffer[position].isspace() or buffer[position] == ","):
                    position += 1
                if position >= len(buffer):
                    break
                if buffer[position] == "]":
                    ended = True
                    position += 1
                    break
                try:
                    value, new_position = decoder.raw_decode(buffer, position)
                except json.JSONDecodeError:
                    if not block:
                        raise ValueError(f"{path}: truncated JSON array")
                    break
                if not isinstance(value, dict):
                    raise ValueError(f"{path}: array entries must be objects")
                yield value
                position = new_position
        if not ended:
            raise ValueError(f"{path}: JSON array did not terminate")


def iter_source_rows(path: Path) -> Iterator[dict[str, Any]]:
    if path.suffix.lower() == ".parquet":
        try:
            import pyarrow.parquet as parquet
        except ImportError as exc:
            raise RuntimeError("pyarrow is required to read parquet HotpotQA mirrors") from exc
        parquet_file = parquet.ParquetFile(path)
        for batch in parquet_file.iter_batches(batch_size=256):
            yield from batch.to_pylist()
        return
    yield from iter_json_array(path)
