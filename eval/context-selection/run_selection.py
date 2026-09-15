#!/usr/bin/env python3
"""Invoke the production Java selector over gold-free JSONL snapshots."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path

from cs_evalkit import sha256_file, write_json


MAIN_CLASS = "com.nageoffer.ai.ragent.rag.core.retrieval.selection.ContextSelectionReplayCli"


def run(command: list[str], repo: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=repo, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--strategy", required=True)
    parser.add_argument("--token-budget", type=int, required=True)
    parser.add_argument("--max-chunks", type=int, default=10)
    parser.add_argument("--lambda", dest="coverage_weight", type=float, default=1.0)
    parser.add_argument("--mu", dest="diversity_weight", type=float, default=0.3)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--skip-compile", action="store_true")
    args = parser.parse_args()
    repo = args.repo.resolve()
    input_path = args.input.resolve()
    output_path = args.output.resolve()
    manifest_path = output_path.with_suffix(output_path.suffix + ".manifest.json")
    if output_path.exists() or manifest_path.exists():
        raise FileExistsError(f"refusing to overwrite {output_path} or {manifest_path}")
    if not args.skip_compile:
        completed = run([
            "./mvnw", "-o", "-pl", "bootstrap", "-am", "-DskipTests",
            "-Dspotless.apply.skip=true", "compile",
        ], repo)
        print(completed.stdout, end="")
    classpath_result = run([
        "./mvnw", "-o", "-pl", "bootstrap", "-Dspotless.apply.skip=true",
        "-DincludeGroupIds=com.fasterxml.jackson.core", "dependency:build-classpath",
        "-Dmdep.outputAbsoluteArtifactFilename=true",
    ], repo)
    dependency_lines = [
        line.strip() for line in classpath_result.stdout.splitlines()
        if line.startswith("/") and ".jar" in line
    ]
    if len(dependency_lines) != 1:
        raise RuntimeError("could not resolve a single Jackson classpath from Maven output")
    classpath = os.pathsep.join([str(repo / "bootstrap/target/classes"), dependency_lines[0]])
    command = [
        "java", "-cp", classpath, MAIN_CLASS,
        "--input", str(input_path),
        "--output", str(output_path),
        "--strategy", args.strategy,
        "--token-budget", str(args.token_budget),
        "--max-chunks", str(args.max_chunks),
        "--lambda", str(args.coverage_weight),
        "--mu", str(args.diversity_weight),
    ]
    completed = run(command, repo)
    if completed.stdout:
        print(completed.stdout, end="")
    git_head = run(["git", "rev-parse", "HEAD"], repo).stdout.strip()
    write_json(manifest_path, {
        "schema_version": "context-selection-run-manifest-v1",
        "created_at": datetime.now(timezone.utc).isoformat(),
        "input": str(args.input),
        "input_sha256": sha256_file(input_path),
        "output": str(args.output),
        "output_sha256": sha256_file(output_path),
        "code_commit": git_head,
        "main_class": MAIN_CLASS,
        "strategy": args.strategy,
        "token_budget": args.token_budget,
        "max_chunks": args.max_chunks,
        "coverage_weight": args.coverage_weight,
        "diversity_weight": args.diversity_weight,
        "gold_fields_available_to_selector": False,
    })
    print(f"wrote {output_path} and {manifest_path}")


if __name__ == "__main__":
    main()
