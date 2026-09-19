"""Source-file and local-run helpers moved from the retired context-selection harness."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any, Iterator
import xml.etree.ElementTree as ET


def idea_environment(path: Path, configuration: str) -> dict[str, str]:
    matches = [node for node in ET.parse(path).iter('configuration') if node.get('name') == configuration]
    if len(matches) != 1:
        raise ValueError('expected exactly one IDEA run configuration')
    values = {e.get('name'): e.get('value', '') for e in matches[0].iter('env')}
    names = ('BAILIAN_API_KEY', 'SILICONFLOW_API_KEY')
    if any(not values.get(name) for name in names):
        raise ValueError('IDEA configuration is missing required API key values')
    return {name: values[name] for name in names}



def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


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
