#!/usr/bin/env bash
# 秋招 X1：W1 提示缓存前后对照（计划 §7）。在两个临时工作树里分别构建 career-v0-baseline 与 W1 代码提交，
# 先在 after 上跑 6 个任务的 smoke（固定题之外的 3 题 × B、C）：embedding 失败率超过 10%、任务缺失或失败过多即停止；
# 通过后按 ABBA 顺序（before B、after B、after C、before C）各跑固定 40 题，最后用 cache_report.py 出报告。
# 真实调用百炼与 SiliconFlow。中断后用同一 X1_STAMP 重跑本脚本，已完成的任务不会重跑。
# 复核用：X1_CASES 换题集，X1_MODES 只跑部分模式（如 "C"），X1_SMOKE=0 跳过 smoke，X1_SMOKE_CASES 换 smoke 题。
# W6 用参数（也可用同名环境变量 X1_PROFILE / X1_BLOCKS / X1_ORDER / X1_ARMS）：
#   --profile full          题目取自全量 queries.jsonl（默认 regression）
#   --blocks <前缀>          分块交错：题目文件为 <前缀>-<块号>.json，按 --order 逐块执行，运行目录 <stamp>_<臂>_<模式>_<块号>
#   --order "before:1 after:1 ..."  分块顺序，默认 前1 后1 后2 前2 前3 后3 后4 前4
#   --arms after            非分块时只跑部分臂（噪声基线只跑 after）
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
smoke=${X1_SMOKE_CASES:-$repo/eval/agentic-research/manifests/career-cache-smoke-ids.json}
profile=${X1_PROFILE:-regression}
blocks=${X1_BLOCKS:-}
order=${X1_ORDER:-before:1 after:1 after:2 before:2 before:3 after:3 after:4 before:4}
arms=" ${X1_ARMS:-before after} "
while [ $# -gt 0 ]; do
  case $1 in
    --profile) profile=$2 ;;
    --blocks) blocks=$2 ;;
    --order) order=$2 ;;
    --arms) arms=" $2 " ;;
    *) echo "usage: $0 [--profile regression|full] [--blocks PREFIX [--order 'before:1 after:1 ...']] [--arms 'before after']" >&2; exit 2 ;;
  esac
  shift 2
done
if [ -n "$blocks" ]; then arms=" "; for item in $order; do [[ $arms == *" ${item%%:*} "* ]] || arms+="${item%%:*} "; done; fi
questions=$prepared/musique-dev/$([ "$profile" = full ] && echo questions.jsonl || echo "questions.$profile.jsonl")

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
  [ -f "$ids" ] || { say "missing case IDs $ids" >&2; exit 1; }
  local resume=()
  [ -f "$dir/run.json" ] && resume=(--resume)
  say "running $4 ($name, mode $mode) -> $dir"
  (cd "$trees/$name" && python3 eval/agentic-research/evaluate_research.py --config "$config" --profile "$profile" \
      --case-ids "$ids" --mode "$mode" --prepared "$prepared" --idea "$idea" --run-dir "$dir" --execute "${resume[@]}") >> "$dir.log" 2>&1
  python3 - "$dir" <<'EOF'
import json, sys
record = json.load(open(sys.argv[1] + "/run.json"))
if record.get("stopped_reason") or record.get("unexecuted_tasks"):
    sys.exit("STOP: {} stopped ({}) with {} unexecuted tasks; rerun with the same X1_STAMP to resume".format(
        sys.argv[1], record.get("stopped_reason"), record.get("unexecuted_tasks")))
EOF
}

for arm in before after; do
  if [[ $arms == *" $arm "* ]]; then ref=${arm}_ref; tree "$arm" "${!ref}"; fi
done

if [ "${X1_SMOKE:-1}" = 1 ]; then
smoke_dirs=()
for mode in $modes; do
  evaluate after "$mode" "$smoke" "smoke_$mode"
  smoke_dirs+=("$runs/${stamp}_smoke_$mode")
done
expected=$(( $(python3 -c 'import json,sys; print(len(json.load(open(sys.argv[1]))))' "$smoke") * ${#smoke_dirs[@]} ))
X1_SMOKE_EXPECTED=$expected python3 - "${smoke_dirs[@]}" <<'EOF'
import glob, json, os, sys
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
expected = int(os.environ["X1_SMOKE_EXPECTED"])
if sum(statuses.values()) != expected or statuses.get("FAILED", 0) > expected // 3:
    sys.exit("STOP: smoke tasks missing or mostly failing; review the smoke run directories before X1")
EOF
fi

reports=()
if [ -n "$blocks" ]; then
  for mode in $modes; do
    declare -a before_dirs=() after_dirs=()
    for item in $order; do
      arm=${item%%:*} block=${item#*:}
      evaluate "$arm" "$mode" "$blocks-$block.json" "${arm}_${mode}_$block"
      reports+=(--run "$arm-$mode-$block=$runs/${stamp}_${arm}_${mode}_$block")
      if [ "$arm" = before ]; then before_dirs+=("$runs/${stamp}_${arm}_${mode}_$block"); else after_dirs+=("$runs/${stamp}_${arm}_${mode}_$block"); fi
    done
    if [ ${#before_dirs[@]} -gt 0 ] && [ ${#after_dirs[@]} -gt 0 ]; then
      python3 "$repo/eval/agentic-research/quality_diff.py" --before "${before_dirs[@]}" --after "${after_dirs[@]}" \
          --questions "$questions" --corpus "$prepared/musique-dev/corpus.jsonl" \
          --out "$runs/${stamp}_quality_diff_$mode.json" | tee "$runs/${stamp}_quality_diff_$mode.md"
    fi
  done
else
  for mode in $modes; do
    # B: before then after; C: after then before (ABBA across the two modes)
    [ "$mode" = B ] && sequence="before after" || sequence="after before"
    for arm in $sequence; do
      if [[ $arms == *" $arm "* ]]; then
        evaluate "$arm" "$mode" "$cases" "${arm}_$mode"
        reports+=(--run "$arm-$mode=$runs/${stamp}_${arm}_$mode")
      fi
    done
  done
fi

python3 "$repo/eval/agentic-research/cache_report.py" "${reports[@]}" \
    --out "$runs/${stamp}_cache_report.json" | tee "$runs/${stamp}_cache_report.md"
say "X1 finished; guardrails are in each run directory's summary.json"
