#!/usr/bin/env bash
# 秋招 X1：W1 提示缓存前后对照（计划 §7）。在两个临时工作树里分别构建 career-v0-baseline 与 W1 代码提交，
# 先在 after 上跑 6 个任务的 smoke（固定题之外的 3 题 × B、C）：embedding 失败率超过 10%、任务缺失或失败过多即停止；
# 通过后按 ABBA 顺序（before B、after B、after C、before C）各跑固定 40 题，最后用 cache_report.py 出报告。
# 真实调用百炼与 SiliconFlow。中断后用同一 X1_STAMP 重跑本脚本，已完成的任务不会重跑。
# 复核用：X1_CASES 换题集，X1_MODES 只跑部分模式（如 "C"），X1_SMOKE=0 跳过 smoke。
set -euo pipefail
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
before_ref=${X1_BEFORE_REF:-career-v0-baseline}
after_ref=${X1_AFTER_REF:-73deab5}
stamp=${X1_STAMP:-career_X1_v1}
trees=${X1_TREES:-$(dirname -- "$repo")/ragent-x1}
runs=$repo/local-data/agentic-research/runs
prepared=$repo/local-data/agentic-research/prepared/research-data-v1
idea=$repo/.idea/workspace.xml
config=$repo/eval/agentic-research/configs/career-x1.json
cases=${X1_CASES:-$repo/eval/agentic-research/manifests/career-cache-case-ids.json}
modes=" ${X1_MODES:-B C} "
smoke=$repo/eval/agentic-research/manifests/career-cache-smoke-ids.json

say() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }

tree() {
  local name=$1 commit
  commit=$(git -C "$repo" rev-parse "$2^{commit}")
  mkdir -p "$trees"
  [ -d "$trees/$name" ] || git -C "$repo" worktree add --detach "$trees/$name" "$commit"
  if [ "$(git -C "$trees/$name" rev-parse HEAD)" != "$commit" ]; then
    say "$trees/$name is not at $commit; remove it or set X1_TREES" >&2; exit 1
  fi
  say "building $name @ ${commit:0:7}"
  (cd "$trees/$name" && ./mvnw -q -o -pl bootstrap -am -DskipTests compile)
}

evaluate() {
  local name=$1 mode=$2 ids=$3 dir=$runs/${stamp}_$4
  local resume=()
  [ -f "$dir/run.json" ] && resume=(--resume)
  say "running $4 ($name, mode $mode) -> $dir"
  (cd "$trees/$name" && python3 eval/agentic-research/evaluate_research.py --config "$config" --profile regression \
      --case-ids "$ids" --mode "$mode" --prepared "$prepared" --idea "$idea" --run-dir "$dir" --execute "${resume[@]}") >> "$dir.log" 2>&1
  python3 - "$dir" <<'EOF'
import json, sys
record = json.load(open(sys.argv[1] + "/run.json"))
if record.get("stopped_reason") or record.get("unexecuted_tasks"):
    sys.exit("STOP: {} stopped ({}) with {} unexecuted tasks; rerun with the same X1_STAMP to resume".format(
        sys.argv[1], record.get("stopped_reason"), record.get("unexecuted_tasks")))
EOF
}

tree before "$before_ref"
tree after "$after_ref"

if [ "${X1_SMOKE:-1}" = 1 ]; then
evaluate after B "$smoke" smoke_B
evaluate after C "$smoke" smoke_C
python3 -"$runs/${stamp}_smoke_B" "$runs/${stamp}_smoke_C" <<'EOF'
import glob, json, sys
calls, statuses = {}, {}
for directory in sys.argv[1:]:
    for path in glob.glob(directory + "/attempts/*/embedding-usage.jsonl"):
        for line in open(path):
            row = json.loads(line)
            calls[row["call_id"]] = row
    for path in glob.glob(directory + "/attempts/*/predictions.jsonl"):
        for line in open(path):
            status = json.loads(line)["run"]["status"]
            statuses[status] = statuses.get(status, 0) + 1
finished = [row for row in calls.values() if row.get("request_state") == "COMPLETED"]
rate = sum(not row.get("success") for row in finished) / len(finished) if finished else 1.0
print("smoke: embedding calls {}, failure rate {:.1%}, task statuses {}".format(len(finished), rate, statuses))
if rate > 0.10:
    sys.exit("STOP: embedding failure rate above 10%; switch to local embedding (plan appendix) before X1")
if sum(statuses.values()) != 6 or statuses.get("FAILED", 0) > 2:
    sys.exit("STOP: smoke tasks missing or mostly failing; review the smoke run directories before X1")
EOF
fi

reports=()
if [[ $modes == *" B "* ]]; then
  evaluate before B "$cases" before_B
  evaluate after B "$cases" after_B
  reports+=(--run before-B="$runs/${stamp}_before_B" --run after-B="$runs/${stamp}_after_B")
fi
if [[ $modes == *" C "* ]]; then
  evaluate after C "$cases" after_C
  evaluate before C "$cases" before_C
  reports+=(--run before-C="$runs/${stamp}_before_C" --run after-C="$runs/${stamp}_after_C")
fi

python3 "$trees/after/eval/agentic-research/cache_report.py" "${reports[@]}" \
    --out "$runs/${stamp}_cache_report.json" | tee "$runs/${stamp}_cache_report.md"
say "X1 finished; guardrails are in each run directory's summary.json"
