#!/usr/bin/env bash
# 秋招 X3：不稳定上游治理前后对照（计划 §7），全部基于模拟上游，零接口费。
# 治理前 = 3381fa9（P7，三层 30 s、不重试、失败当空结果），治理后 = W3 代码提交；各建临时工作树并编译。
# 同一 50 个脚本化任务（B 模式）× embedding 无响应率 0 / 10% / 30% / 50%，每格独立的模拟上游与隔离运行库；
# 每个故障率轮换两版的先后。中断后用同一 X3_STAMP 重跑本脚本，已完成的格不会重跑。
set -euo pipefail
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
before_ref=${X3_BEFORE_REF:-3381fa9}
after_ref=${X3_AFTER_REF:-97edcc1}
stamp=${X3_STAMP:-career_X3_v1}
trees=${X3_TREES:-$(dirname -- "$repo")/ragent-x3}
runs=$repo/local-data/agentic-research/runs/$stamp

say() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }

tree() {
  local name=$1 commit
  commit=$(git -C "$repo" rev-parse "$2^{commit}")
  mkdir -p "$trees"
  [ -d "$trees/$name" ] || git -C "$repo" worktree add --detach "$trees/$name" "$commit"
  if [ "$(git -C "$trees/$name" rev-parse HEAD)" != "$commit" ]; then
    say "$trees/$name is not at $commit; remove it or set X3_TREES" >&2; exit 1
  fi
  say "building $name @ ${commit:0:7}"
  (cd "$trees/$name" && ./mvnw -q -o -pl bootstrap -am -DskipTests compile)
}

tree before "$before_ref"
tree after "$after_ref"
# 固定小语料只需导入一次；导入用无故障的模拟上游，完成后停掉它。
"$repo/scripts/stub-upstream.sh" > /dev/null
"$repo/scripts/stub-upstream.sh" stop
python3 "$trees/after/eval/agentic-research/x3_upstream.py" --tree before="$trees/before" --tree after="$trees/after" \
    --run-dir "$runs" --rates 0,0.1,0.3,0.5 --tasks 50 --mode B --target embedding
say "X3 finished: $runs/x3-report.md and $runs/x3-summary.json"
