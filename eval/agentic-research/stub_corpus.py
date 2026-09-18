#!/usr/bin/env python3
"""Fixed synthetic corpus for the stub upstream, imported through the real Java chunk/embed/index path.

Sixty MuSiQue-shaped single-paragraph documents SRC-001 … SRC-060, each stating one recorded value.
The embedding calls go to a running stub_upstream.py, so the stored vectors are the stub's deterministic
vectors and a query naming a key retrieves that key's document first. Nothing here calls a provider.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).resolve().parent))
from import_corpus import database_environment  # noqa: E402

DATABASE = "research_corpus_stub"
COLLECTION = "rs_stub_v1"
DOCUMENTS = 60
CONDITIONS = ("dry ore", "wet ore", "sintered pellets", "fine concentrate", "lump ore")


def key(index: int) -> str:
    return "SRC-{:03d}".format(index)


def value(index: int) -> str:
    return "{} units".format(100 + index * 7 % 83)


def document(index: int) -> dict:
    identifier = "stub-{:03d}".format(index)
    title = key(index) + " stub inspection record"
    text = ("{title}. The recorded value for {key} is {value}. It was measured on {condition} in the synthetic test "
            "line; this paragraph exists only so the scripted upstream has a real source to search and read.").format(
        title=title, key=key(index), value=value(index), condition=CONDITIONS[index % len(CONDITIONS)])
    metadata = {"block_type": "paragraph", "dataset": "musique", "document_version": "stub-v1",
                "source_extent": "available_excerpt", "source_field": "paragraph_text",
                "source_paragraph_id": identifier, "split": "stub"}
    unit = {"schema_version": "research-corpus-v1", "id": identifier, "dataset": "musique", "split": "stub",
            "document_id": identifier, "title": title, "text": text, "content_hash": hashlib.sha256(text.encode()).hexdigest(),
            "source_extent": "AVAILABLE_EXCERPT", "metadata": metadata}
    return {"sourceDocumentId": identifier, "units": [unit]}


def write_inputs(directory: Path) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / "documents.jsonl").open("w") as out:
        for index in range(1, DOCUMENTS + 1):
            out.write(json.dumps(document(index), sort_keys=True) + "\n")
    with (directory / "queries.jsonl").open("w") as out:
        for index in range(1, 4):
            out.write(json.dumps({"id": "stub-probe-{:03d}".format(index), "question": key(index) + " recorded value",
                                  "document_ids": ["stub-{:03d}".format(index)]}) + "\n")
    job = {"documentsFile": str((directory / "documents.jsonl").resolve()), "queriesFile": str((directory / "queries.jsonl").resolve()),
           "collection": COLLECTION, "runDir": str(directory.resolve()), "batchDocuments": 20, "maxRetries": 1,
           "maxChars": 1024, "overlapChars": 128, "dimension": 1536}
    (directory / "job.json").write_text(json.dumps(job, indent=2) + "\n")
    return directory / "job.json"


def classpath(tree: Path) -> str:
    """Compiled classes of a checkout plus its resolved dependency jars (offline)."""
    resolved = subprocess.run(["./mvnw", "-o", "-q", "-pl", "bootstrap", "dependency:build-classpath",
                               "-Dmdep.outputFile=/dev/stdout", "-Dmdep.outputAbsoluteArtifactFilename=true"],
                              cwd=tree, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True).stdout
    jars = [line.strip() for line in resolved.splitlines() if line.strip().startswith("/") and ".jar" in line]
    if len(jars) != 1:
        raise RuntimeError("classpath resolution failed in " + str(tree))
    return os.pathsep.join([str(tree / module / "target/classes") for module in ("bootstrap", "framework", "infra-ai")] + jars)


def corpus_exists(container: str) -> bool:
    try:
        count = subprocess.run(["docker", "exec", "-i", container, "sh", "-c", 'exec psql -U "$POSTGRES_USER" -d "$1" -Atc "$2"', "sh",
                                DATABASE, "SELECT count(*) FROM t_research_corpus_document"],
                               text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout.strip()
    except OSError:
        return False
    return count == str(DOCUMENTS)


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--stub-url", default="http://127.0.0.1:18080")
    parser.add_argument("--run-dir", type=Path, default=REPO / "local-data/stub-upstream/corpus")
    parser.add_argument("--container", default="ragent-iron-ore-dev-postgres-1")
    args = parser.parse_args()
    if corpus_exists(args.container):
        print("{} already holds {} documents in {}".format(DATABASE, DOCUMENTS, COLLECTION))
        return
    job = write_inputs(args.run_dir)
    env = dict(os.environ, SILICONFLOW_API_KEY="stub-only", AI_PROVIDERS_SILICONFLOW_URL=args.stub_url)
    env.update(database_environment(args.container, DATABASE, 1536))
    with (args.run_dir / "java.log").open("a") as log:
        code = subprocess.run(["java", "-Xmx1g", "-cp", classpath(REPO), "com.nageoffer.ai.ragent.research.eval.ResearchCorpusCommand", str(job)],
                              cwd=REPO, env=env, stdout=log, stderr=subprocess.STDOUT).returncode
    if code:
        raise SystemExit("stub corpus import failed; see " + str(args.run_dir / "java.log"))
    print((args.run_dir / "complete.json").read_text())


if __name__ == "__main__":
    main()
