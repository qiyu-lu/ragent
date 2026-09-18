#!/usr/bin/env bash
# 一条命令起模拟上游（计划 W3）：必要时编译，确保固定小语料库 research_corpus_stub 已导入（用无故障的模拟上游嵌入），
# 再按传入的故障旋钮在后台启动 stub_upstream.py，最后打印要导出的环境变量。零接口费。
#   scripts/stub-upstream.sh                          # 无故障
#   scripts/stub-upstream.sh --embedding-hang 0.3     # 30% 的 embedding 请求无响应
#   scripts/stub-upstream.sh stop
# 端口用 STUB_PORT（默认 18080）；日志与请求台账在 local-data/stub-upstream/。
set -euo pipefail
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
port=${STUB_PORT:-18080}
state=$repo/local-data/stub-upstream
pidfile=$state/stub-$port.pid
mkdir -p "$state"

stop() {
  if [ -f "$pidfile" ] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then kill "$(cat "$pidfile")"; fi
  rm -f "$pidfile"
}

start() {
  nohup python3 "$repo/eval/agentic-research/stub_upstream.py" --port "$port" --log "$state/requests-$port.jsonl" "$@" \
      >> "$state/stub-$port.log" 2>&1 &
  echo $! > "$pidfile"
  for _ in $(seq 50); do
    curl -fsS "http://127.0.0.1:$port/healthz" > /dev/null 2>&1 && return 0
    sleep 0.1
  done
  echo "stub upstream did not start; see $state/stub-$port.log" >&2
  exit 1
}

if [ "${1:-}" = stop ]; then stop; exit 0; fi
stop
if [ ! -f "$repo/bootstrap/target/classes/com/nageoffer/ai/ragent/research/eval/ResearchCorpusCommand.class" ]; then
  (cd "$repo" && ./mvnw -q -o -pl bootstrap -am -DskipTests compile)
fi
start
python3 "$repo/eval/agentic-research/stub_corpus.py" --stub-url "http://127.0.0.1:$port"
if [ "$#" -gt 0 ]; then stop; start "$@"; fi
cat <<EOF
stub upstream pid $(cat "$pidfile") on http://127.0.0.1:$port ($*)
export SPRING_PROFILES_ACTIVE=stub STUB_UPSTREAM_URL=http://127.0.0.1:$port
export AI_PROVIDERS_BAILIAN_URL=http://127.0.0.1:$port AI_PROVIDERS_SILICONFLOW_URL=http://127.0.0.1:$port
export BAILIAN_API_KEY=stub-only SILICONFLOW_API_KEY=stub-only
corpus database: research_corpus_stub, collection rs_stub_v1 (SRC-001 … SRC-060)
EOF
