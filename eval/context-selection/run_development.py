#!/usr/bin/env python3
"""Run the predeclared development grid through the Java selector."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path

from cs_evalkit import sha256_file, write_json


SAFE_LABEL = re.compile(r"^[A-Za-z0-9._-]+$")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--snapshots", type=Path, required=True)
    parser.add_argument("--grid", type=Path, default=Path(__file__).with_name("development-grid.json"))
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    repo = args.repo.resolve()
    grid = json.loads(args.grid.read_text(encoding="utf-8"))
    output_dir = args.output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    index_path = output_dir / "arms.json"
    score_path = output_dir / "selection-score.json"
    if index_path.exists() or score_path.exists():
        raise FileExistsError(f"refusing to overwrite completed development run in {output_dir}")

    subprocess.run([
        "./mvnw", "-o", "-pl", "bootstrap", "-am", "-DskipTests",
        "-Dspotless.apply.skip=true", "compile",
    ], cwd=repo, check=True)
    arm_paths: list[tuple[str, Path]] = []
    for arm in grid["arms"]:
        label = str(arm["label"])
        if not SAFE_LABEL.fullmatch(label):
            raise ValueError(f"unsafe arm label: {label}")
        output = output_dir / f"{label}.jsonl"
        subprocess.run([
            "python3", "eval/context-selection/run_selection.py",
            "--input", str(args.snapshots),
            "--output", str(output),
            "--strategy", str(arm["strategy"]),
            "--token-budget", str(grid["token_budget"]),
            "--max-chunks", str(grid["max_chunks"]),
            "--lambda", str(arm["lambda"]),
            "--mu", str(arm["mu"]),
            "--skip-compile",
        ], cwd=repo, check=True)
        arm_paths.append((label, output))
    score_command = [
        "python3", "eval/context-selection/score_selection.py",
        "--dataset", str(args.dataset),
        "--snapshots", str(args.snapshots),
        "--output", str(score_path),
    ]
    for label, path in arm_paths:
        score_command.extend(["--arm", f"{label}={path}"])
    subprocess.run(score_command, cwd=repo, check=True)
    write_json(index_path, {
        "schema_version": "context-selection-development-run-v1",
        "grid": str(args.grid),
        "grid_sha256": sha256_file(args.grid),
        "dataset": str(args.dataset),
        "dataset_sha256": sha256_file(args.dataset),
        "snapshots": str(args.snapshots),
        "snapshots_sha256": sha256_file(args.snapshots),
        "score": str(score_path),
        "score_sha256": sha256_file(score_path),
        "arms": [{"label": label, "path": str(path), "sha256": sha256_file(path)} for label, path in arm_paths],
    })
    print(f"completed {len(arm_paths)} arms in {output_dir}")


if __name__ == "__main__":
    main()
