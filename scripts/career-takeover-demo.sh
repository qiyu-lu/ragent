#!/usr/bin/env bash
# 秋招 X2：研究执行器的进程级故障（计划 §7），全部基于模拟上游，零接口费，约 4 分钟。
# 每个场景起独立的模拟上游与随机隔离运行库，执行器是共享该库的独立 JVM（ResearchExecutorCommand serve）：
#   T0 无故障基线 · T1 kill -9 · T2 运行中启动新实例 · T3 SIGTERM 优雅停机 · T4 SIGSTOP 超过租约后 SIGCONT · T5 连续崩溃的毒任务
# 结果：runs/<X2_STAMP>/x2-report.md、x2-summary.json，逐场景目录含各实例日志与 result.json。已完成的场景重跑时跳过。
#   X2_SCENARIOS=T1,T3 bash scripts/career-takeover-demo.sh      # 只跑部分场景
#   X2_STAMP=career_X2_v2 X2_SCENARIOS=T0,T1,T3,T4 X2_REPEAT=20 bash scripts/career-takeover-demo.sh   # 每场景重复 20 次（约 50 分钟），报告 P50/P95/最大值
set -euo pipefail
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
stamp=${X2_STAMP:-career_X2_v1}
runs=$repo/local-data/agentic-research/runs/$stamp
cd "$repo"
./mvnw -q -o -pl bootstrap -am -DskipTests compile
# 固定小语料只需导入一次；导入用无故障的模拟上游，完成后停掉它。
scripts/stub-upstream.sh > /dev/null
scripts/stub-upstream.sh stop
python3 eval/agentic-research/x2_takeover.py --run-dir "$runs" --scenarios "${X2_SCENARIOS:-T0,T1,T2,T3,T4,T5}" --repeat "${X2_REPEAT:-1}"
echo "X2 finished: $runs/x2-report.md and $runs/x2-summary.json"
