"""Offline research data contracts; no API client or model calls."""

from __future__ import annotations

import hashlib
import json
import sqlite3
import sys
from pathlib import Path
from typing import Any, Iterator

# Reuse the existing streaming Parquet reader and file fingerprint helper.
sys.path.append(str(Path(__file__).resolve().parents[1] / "context-selection"))
from cs_evalkit import iter_source_rows, sha256_file  # noqa: E402

CORPUS_SCHEMA = "research-corpus-v1"
QUESTION_SCHEMA = "research-questions-v1"
QUERY_SCHEMA = "research-queries-v1"
MANIFEST_SCHEMA = "research-prepared-v1"
CONVERTER_VERSION = "1.0.0"
CORPUS_KEYS = {"schema_version", "id", "dataset", "split", "document_id", "title",
               "text", "content_hash", "source_extent", "metadata"}
METADATA_KEYS = {"dataset", "split", "document_version", "source_extent", "paper_id",
                 "section_path", "section_index", "paragraph_index", "source_paragraph_id",
                 "block_type", "source_field"}
QUESTION_KEYS = {"schema_version", "id", "dataset", "split", "source_question_id",
                 "question", "retrieval_mode", "candidate_ids", "metadata", "gold"}
QUERY_KEYS = {"schema_version", "id", "dataset", "split", "question", "retrieval_mode", "document_ids"}
OUTPUT_FILES = ("corpus.jsonl", "questions.jsonl", "queries.jsonl",
                "questions.smoke.jsonl", "queries.smoke.jsonl",
                "questions.regression.jsonl", "queries.regression.jsonl")
SOURCES = {
    "qasper": {"version": "0.3", "license": "CC-BY-4.0",
               "url": "https://huggingface.co/datasets/allenai/qasper",
               "format_reference": "https://huggingface.co/datasets/allenai/qasper/blob/main/qasper.py",
               "attribution": "Dasigi, Lo, Beltagy, Cohan, Smith and Gardner (2021), QASPER"},
    "musique": {"version": "1.0", "license": "CC-BY-4.0",
                "url": "https://github.com/StonyBrookNLP/musique",
                "format_reference": "https://github.com/StonyBrookNLP/musique/blob/main/raw_data_to_official_format.py",
                "attribution": "Trivedi, Balasubramanian, Khot and Sabharwal (2022), MuSiQue"},
}


def canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def digest_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def stable_id(prefix: str, *parts: Any) -> str:
    return prefix + "-" + digest_text(canonical(parts))


def required_text(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{field} must be a non-empty string")
    return value


def text_list(value: Any, field: str) -> list[str]:
    if not isinstance(value, list) or any(not isinstance(item, str) for item in value):
        raise ValueError(f"{field} must be a list of strings")
    return value


def records(value: Any, field: str) -> list[dict[str, Any]]:
    """HF Sequence structs become parallel lists; reject zip truncation."""
    if isinstance(value, list):
        if any(not isinstance(row, dict) for row in value):
            raise ValueError(f"{field} must contain objects")
        return value
    if not isinstance(value, dict) or not value:
        raise ValueError(f"{field} must be a sequence of objects or parallel lists")
    if any(not isinstance(items, list) for items in value.values()):
        raise ValueError(f"{field} must contain parallel lists")
    lengths = {len(items) for items in value.values()}
    if len(lengths) != 1:
        raise ValueError(f"{field} parallel lists have different lengths")
    return [{key: items[index] for key, items in value.items()} for index in range(next(iter(lengths)))]


def iter_jsonl(path: Path) -> Iterator[dict[str, Any]]:
    with path.open(encoding="utf-8") as handle:
        for number, line in enumerate(handle, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path.name}:{number}: invalid JSON") from exc
            if not isinstance(row, dict):
                raise ValueError(f"{path.name}:{number}: expected an object")
            yield row


def source_unit(dataset: str, split: str, identifier: str, document_id: str,
                title: str, text: str, metadata: dict[str, Any]) -> dict[str, Any]:
    extent = "AVAILABLE_EXCERPT" if dataset == "musique" else "CHUNK"
    return {"schema_version": CORPUS_SCHEMA, "id": identifier, "dataset": dataset, "split": split,
            "document_id": document_id, "title": title, "text": text, "content_hash": digest_text(text),
            "source_extent": extent, "metadata": {**metadata, "dataset": dataset, "split": split,
            "document_version": f"{dataset}-v{SOURCES[dataset]['version']}",
            "source_extent": extent.lower(), "source_paragraph_id": identifier}}


class PreparedWriter:
    """Disk-backed dedup and sorting keep the pooled corpus out of RAM."""

    def __init__(self, path: Path, seed: int):
        self.path = path
        self.seed = seed
        self.db = sqlite3.connect(str(path))
        self.db.executescript("""
            CREATE TABLE corpus (id TEXT PRIMARY KEY, document_id TEXT NOT NULL, payload TEXT NOT NULL);
            CREATE TABLE questions (id TEXT PRIMARY KEY, priority TEXT NOT NULL, payload TEXT NOT NULL, query TEXT NOT NULL, source_question_id TEXT NOT NULL);
        """)
        self.source_occurrences = 0

    def add_source(self, row: dict[str, Any]) -> None:
        payload = canonical(row)
        self.source_occurrences += 1
        found = self.db.execute("SELECT payload FROM corpus WHERE id = ?", (row["id"],)).fetchone()
        if found:
            if found[0] != payload:
                raise ValueError("same corpus ID has conflicting content or location")
            return
        self.db.execute("INSERT INTO corpus VALUES (?, ?, ?)", (row["id"], row["document_id"], payload))

    def add_question(self, row: dict[str, Any], document_ids: list[str]) -> None:
        query = {key: row[key] for key in ("id", "dataset", "split", "question", "retrieval_mode")}
        query.update(schema_version=QUERY_SCHEMA, document_ids=document_ids)
        priority = stable_id("sample", self.seed, row["id"])
        try:
            self.db.execute("INSERT INTO questions VALUES (?, ?, ?, ?, ?)",
                            (row["id"], priority, canonical(row), canonical(query), row["source_question_id"]))
        except sqlite3.IntegrityError as exc:
            raise ValueError("duplicate source question ID") from exc

    def checkpoint(self) -> None:
        self.db.commit()

    def export(self, output: Path, smoke: int, regression: int) -> dict[str, Any]:
        self.checkpoint()
        counts = {table: self.db.execute(f"SELECT count(*) FROM {table}").fetchone()[0]
                  for table in ("corpus", "questions")}
        documents = self.db.execute("SELECT count(DISTINCT document_id) FROM corpus").fetchone()[0]
        source_questions = self.db.execute("SELECT count(DISTINCT source_question_id) FROM questions").fetchone()[0]
        self._write(output / "corpus.jsonl", "SELECT payload FROM corpus ORDER BY id")
        self._write(output / "questions.jsonl", "SELECT payload FROM questions ORDER BY id")
        self._write(output / "queries.jsonl", "SELECT query FROM questions ORDER BY id")
        profiles = {}
        for name, requested in (("smoke", smoke), ("regression", regression)):
            actual = min(requested, counts["questions"])
            for column, filename in (("payload", "questions"), ("query", "queries")):
                self._write(output / f"{filename}.{name}.jsonl",
                            f"SELECT {column} FROM questions ORDER BY priority, id LIMIT ?", (actual,))
            ids = [row[0] for row in self.db.execute("SELECT id FROM questions ORDER BY priority, id LIMIT ?", (actual,))]
            profiles[name] = {"requested": requested, "questions": actual,
                              "question_ids_hash": digest_text(canonical(ids))}
        return {"corpus_units": counts["corpus"], "documents": documents, "questions": counts["questions"],
                "distinct_source_question_ids": source_questions,
                "source_occurrences": self.source_occurrences, "deduplicated_occurrences": self.source_occurrences - counts["corpus"],
                "profiles": profiles}

    def _write(self, path: Path, sql: str, params: tuple = ()) -> None:
        with path.open("w", encoding="utf-8") as handle:
            for (payload,) in self.db.execute(sql, params):
                handle.write(payload + "\n")

    def close(self) -> None:
        self.db.close()
