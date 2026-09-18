#!/usr/bin/env bash
# 秋招 X5：内容寻址的 embedding 复用（计划 §7）。每次运行新建临时库 ragent_x5_*（缓存从空表开始），
# 用离线命令 IngestionReuseCommand 按下列步骤把调研表走一遍真实摄取内核：
#   1 v13_uncached   V1.3 关闭缓存入库（对照：全量重嵌入的调用、token 与耗时）
#   2 v12_cold       V1.2 首次入库（冷缓存）
#   3 v12_repeat     V1.2 原样重新入库（期望上游调用 0）
#   4 v13_upgrade    同一文档升级到 V1.3（只重嵌入变化的块）
#   5 v12_rollback   回退到 V1.2（两版向量都在缓存里）
# X5_UPSTREAM=stub（默认）起模拟上游，零接口费，数字注明“基于模拟上游”；
# X5_UPSTREAM=real 调 SiliconFlow Qwen3-Embedding-8B（需 SILICONFLOW_API_KEY，可选 HTTPS_PROXY）。
# 产出在 local-data/agentic-research/runs/<X5_STAMP>/：x5-summary.json、x5-steps.jsonl、x5-report.md、x5.log。
set -euo pipefail
repo=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
mode=${X5_UPSTREAM:-stub}
case "$mode" in stub|real) ;; *) echo "X5_UPSTREAM must be stub or real" >&2; exit 2 ;; esac
ts=$(date -u +%Y%m%d%H%M%S)
stamp=${X5_STAMP:-career_X5_${mode}_$ts}
run=$repo/local-data/agentic-research/runs/$stamp
container=${X5_POSTGRES_CONTAINER:-ragent-iron-ore-dev-postgres-1}
db=ragent_x5_${mode}_${ts}
v12=${X5_V12:-$repo/local-data/source/铁矿石人工检测流程调研V1.2.xlsx}
v13=${X5_V13:-$repo/local-data/source/铁矿石人工检测流程调研V1.3-demo.xlsx}
stub_port=${X5_STUB_PORT:-18085}
mkdir -p "$run"
exec > >(tee -a "$run/x5.log") 2>&1

say() { printf '[%s] %s\n' "$(date '+%F %T')" "$*"; }
[ -f "$v12" ] && [ -f "$v13" ] || { say "missing $v12 or $v13" >&2; exit 1; }

db_created=false
stub_pid=
cleanup() {
  [ -n "$stub_pid" ] && kill "$stub_pid" 2>/dev/null || true
  if [ "$db_created" = true ] && [ "${X5_KEEP_DB:-false}" != true ]; then
    docker exec "$container" sh -c 'exec dropdb -U "$POSTGRES_USER" "$1"' sh "$db" && say "dropped $db"
  fi
}
trap cleanup EXIT
trap 'exit 1' HUP INT TERM

say "X5 mode=$mode run=$run commit=$(git -C "$repo" rev-parse --short HEAD)"
(cd "$repo" && ./mvnw -q -o -pl bootstrap -am -DskipTests compile)
jars=$(cd "$repo" && ./mvnw -o -q -pl bootstrap dependency:build-classpath -Dmdep.outputFile=/dev/stdout \
    -Dmdep.outputAbsoluteArtifactFilename=true | grep '^/')
cp="$repo/bootstrap/target/classes:$repo/framework/target/classes:$repo/infra-ai/target/classes:$jars"

docker exec "$container" sh -c 'exec createdb -U "$POSTGRES_USER" "$1"' sh "$db"
db_created=true
docker exec -i "$container" sh -c 'exec psql -q -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1' sh "$db" \
    < "$repo/resources/database/schema_pg.sql" > /dev/null
pg_port=$(docker inspect --format '{{(index (index .NetworkSettings.Ports "5432/tcp") 0).HostPort}}' "$container")
export RAGENT_POSTGRES_URL="jdbc:postgresql://127.0.0.1:${pg_port}/${db}"
export RAGENT_POSTGRES_USER=$(docker exec "$container" sh -c 'printf "%s" "$POSTGRES_USER"')
export RAGENT_POSTGRES_PASSWORD=$(docker exec "$container" sh -c 'printf "%s" "$POSTGRES_PASSWORD"')
say "database $db ready"

if [ "$mode" = stub ]; then
  python3 "$repo/eval/agentic-research/stub_upstream.py" --port "$stub_port" --log "$run/stub-requests.jsonl" \
      >> "$run/stub.log" 2>&1 &
  stub_pid=$!
  for _ in $(seq 50); do curl -fsS "http://127.0.0.1:$stub_port/healthz" > /dev/null 2>&1 && break; sleep 0.1; done
  export AI_PROVIDERS_SILICONFLOW_URL=http://127.0.0.1:$stub_port SILICONFLOW_API_KEY=stub-only
else
  : "${SILICONFLOW_API_KEY:?SILICONFLOW_API_KEY required for X5_UPSTREAM=real}"
  unset AI_PROVIDERS_SILICONFLOW_URL
fi

python3 - "$run" "$v12" "$v13" <<'PY'
import json, sys
run, v12, v13 = sys.argv[1:]
steps = [("v13_uncached", v13, "x5-base", False), ("v12_cold", v12, "x5-doc", True), ("v12_repeat", v12, "x5-doc", True),
         ("v13_upgrade", v13, "x5-doc", True), ("v12_rollback", v12, "x5-doc", True)]
job = {"runDir": run, "dimension": 1536,
       "steps": [{"name": n, "file": f, "docId": d, "cache": c} for n, f, d, c in steps]}
open(run + "/job.json", "w").write(json.dumps(job, ensure_ascii=False, indent=2) + "\n")
PY
java -Xmx1g -cp "$cp" com.nageoffer.ai.ragent.knowledge.eval.IngestionReuseCommand "$run/job.json"

python3 - "$run" "$mode" <<'PY'
import json, sys
from pathlib import Path
run, mode = Path(sys.argv[1]), sys.argv[2]
summary = json.loads((run / "x5-summary.json").read_text())
steps = {s["step"]: s for s in summary["steps"]}
if mode == "stub":
    lines = (run / "stub-requests.jsonl").read_text().splitlines() if (run / "stub-requests.jsonl").exists() else []
    summary["stubEmbeddingRequests"] = sum(1 for l in lines if json.loads(l).get("endpoint") == "embedding")
base, up = steps["v13_uncached"], steps["v13_upgrade"]
def ratio(a, b):
    return None if a is None or not b else round(a / b, 4)
summary["derived"] = {
    "repeatUpstreamCalls": steps["v12_repeat"]["upstreamCalls"],
    "rollbackUpstreamCalls": steps["v12_rollback"]["upstreamCalls"],
    "upgradeReembedChunkRatio": ratio(up["cacheMisses"], up["chunks"]),
    "upgradeUpstreamTextRatioVsUncached": ratio(up["upstreamTexts"], base["upstreamTexts"]),
    "upgradeTokenRatioVsUncached": ratio(up["upstreamTokens"], base["upstreamTokens"]),
    "upgradeTokensSaved": None if base["upstreamTokens"] is None or up["upstreamTokens"] is None
                          else base["upstreamTokens"] - up["upstreamTokens"],
    "upgradeEmbedMillisVsUncached": [up["embedMillis"], base["embedMillis"]],
    "rowsConsistent": all(s["chunkRows"] == s["vectorRows"] == s["chunks"] for s in summary["steps"]),
}
(run / "x5-summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
note = "基于模拟上游（token 为模拟值，耗时不代表真实供应商）" if mode == "stub" else "真实上游 SiliconFlow Qwen3-Embedding-8B，单次运行"
out = [f"# X5 内容寻址 embedding 复用（{note}）", "",
       "| 步骤 | 文件 | 缓存 | 块数 | 命中 | 未命中 | 上送文本 | 上游调用 | 上游 token | 向量化 ms | 全程 ms | 块表/向量表行 |",
       "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |"]
for s in summary["steps"]:
    out.append(f"| {s['step']} | {s['file']} | {'开' if s['cache'] else '关'} | {s['chunks']} | {s['cacheHits']} | {s['cacheMisses']} | "
               f"{s['upstreamTexts']} | {s['upstreamCalls']} | {s['upstreamTokens']} | {s['embedMillis']} | {s['totalMillis']} | "
               f"{s['chunkRows']}/{s['vectorRows']} |")
out += ["", "```json", json.dumps(summary["derived"], ensure_ascii=False, indent=2), "```"]
if mode == "stub":
    out.append(f"\n模拟上游收到的 embedding 请求总数：{summary['stubEmbeddingRequests']}（与各步上游调用之和对照）")
(run / "x5-report.md").write_text("\n".join(out) + "\n")
print("\n".join(out))
PY
say "X5 finished: $run/x5-report.md"
