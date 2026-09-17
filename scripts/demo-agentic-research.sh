#!/usr/bin/env bash
set -euo pipefail

demo_repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd -- "$demo_repo"
demo_execute=()
if [ "${1:-}" = --execute ]; then
  demo_execute=(--execute)
  shift
fi
if [ "$#" -gt 1 ]; then
  echo 'Usage: bash scripts/demo-agentic-research.sh [--execute] [new-run-directory]' >&2
  exit 2
fi
demo_dir=${1:-local-data/agentic-research/runs/$(date -u +%Y%m%dT%H%M%S)_handoff_demo}
if [ -e "$demo_dir" ]; then
  echo 'Demo output must be a new directory.' >&2
  exit 2
fi
mkdir -p -- "$demo_dir"

# Two fixed public questions use the one-shot comparator. The application cases
# compare Agatha with multilingual Bayesian semantic-role induction, then
# draft an AMR summarization reproduction plan with missing batch/window
# parameters. These IDs are from the complete 24-case review; this demonstration
# is not a replacement for that batch. Requests precede any API call.
python3 eval/agentic-research/evaluate_research.py \
  --run-dir "$demo_dir/one-shot" --profile smoke --mode A --limit 1 "${demo_execute[@]}"
python3 eval/agentic-research/evaluate_applications.py \
  --run-dir "$demo_dir/research" --case comparison-05 --case plan-06 "${demo_execute[@]}"
echo "Demo requests and outputs: $demo_dir"
if [ "${#demo_execute[@]}" -eq 0 ]; then
  echo 'Prepared four requests. Add --execute to call providers using the imported corpus.'
else
  echo 'Inspect per-task status, one-shot/report.md and research/source-review.md; exit 0 does not imply semantic correctness.'
fi
