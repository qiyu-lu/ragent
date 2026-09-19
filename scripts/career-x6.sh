#!/usr/bin/env bash
# 秋招 X6：多实例排空积压的容量基准（计划 §5 W7），全部基于模拟上游，零接口费，约 3 小时。
# 每格起独立的模拟上游与随机隔离运行库；执行器是共享该库的独立 JVM（ResearchExecutorCommand serve，生产 ResearchRunService）。
# 实例先空转（量空闲轮询），再由实例 a 经 create() 一次性提交 300 个脚本化任务；池满的任务留在库里由任一实例轮询领取。
# 生产默认：租约 30 s、心跳 10 s、轮询 5 s、每实例 2 个槽位、2 个并发模型调用；1 / 2 / 3 实例 × 3 次，另跑轮询 1 s 对照 × 1 次。
# 结果：runs/<X6_STAMP>/x6-report.md、x6-summary.json，逐格目录含各实例日志与 result.json。中断后用同一 X6_STAMP 重跑，已完成的格跳过。
#   X6_TASKS=40 X6_REPEAT=1 X6_STAMP=career_X6_smoke bash scripts/career-x6.sh   # 小规模试跑
set -euo pipefail
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
stamp=${X6_STAMP:-career_X6_v1}
runs=$repo/local-data/agentic-research/runs/$stamp
cd "$repo"
./mvnw -q -o -pl bootstrap -am -DskipTests compile
# 固定小语料只需导入一次；导入用无故障的模拟上游，完成后停掉它。
scripts/stub-upstream.sh > /dev/null
scripts/stub-upstream.sh stop
python3 eval/agentic-research/x6_capacity.py --run-dir "$runs" --tasks "${X6_TASKS:-300}" --instances "${X6_INSTANCES:-1,2,3}" \
    --repeat "${X6_REPEAT:-3}" --poll 5 --control-poll 1 --control-repeat "${X6_CONTROL_REPEAT:-1}"
echo "X6 finished: $runs/x6-report.md and $runs/x6-summary.json"
