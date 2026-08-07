"""调用 /rag/eval 跑评测集并计算检索指标。

用法：
    python3 run_eval.py --label baseline
    python3 run_eval.py --label topk5 --tier hard
    python3 run_eval.py --label baseline --compare reports/20260807-1200-topk5.json

指标口径见 README.md。每次运行会把明细写入 reports/ 便于跨配置对比。
"""

import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional

from evalkit import anchor_hit, load_dataset, normalize

HERE = Path(__file__).resolve().parent
DATASET = HERE / "dataset.jsonl"
REPORTS = HERE / "reports"
CUTOFFS = (1, 3, 5, 10)


def http_json(url, method="GET", body=None, token=None, timeout=120):
    """发一个 JSON 请求，返回框架统一响应体里的 data。"""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", token)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        payload = json.loads(resp.read().decode("utf-8"))
    # 框架把业务失败也包成 HTTP 200，必须看 success 字段
    if not payload.get("success"):
        raise RuntimeError("接口返回失败: {} {}".format(payload.get("code"), payload.get("message")))
    return payload.get("data")


def login(base, username, password):
    data = http_json(base + "/auth/login", method="POST",
                     body={"username": username, "password": password})
    return data["token"]


def query(base, token, question, timeout):
    url = base + "/rag/eval?" + urllib.parse.urlencode({"question": question})
    started = time.time()
    data = http_json(url, token=token, timeout=timeout)
    data["_wallMs"] = int((time.time() - started) * 1000)
    return data


def score_one(row, resp):
    """把一条问题的召回结果打成指标。"""
    docs = resp.get("retrievedDocIds") or []
    contexts = resp.get("retrievedContexts") or []
    ref_docs = row.get("reference_docs") or []
    anchors = row.get("reference_anchors") or []

    out = {
        "id": row["id"],
        "tier": row["tier"],
        "question": row["question"],
        "retrieved_docs": docs,
        "n_chunks": len(contexts),
        "latency_ms": resp.get("latencyMs"),
        "wall_ms": resp.get("_wallMs"),
        "sub_intents": resp.get("subIntents") or [],
        "intent_leaf_ids": resp.get("intentLeafIds") or [],
    }

    if row["tier"] == "negative":
        # 接口未透出相似度分数，无法做阈值判定；先记录召回面用于人工观察
        out["negative_doc_spread"] = len(set(docs))
        return out

    hit_docs = set(docs) & set(ref_docs)
    out["doc_recall"] = len(hit_docs) / len(ref_docs) if ref_docs else None
    out["doc_precision"] = len(hit_docs) / len(docs) if docs else 0.0

    # 锚点级：每个锚点找首个包含它的 chunk 下标
    ranks = []
    for a in anchors:
        idx = anchor_hit(a, contexts)
        ranks.append(idx)
    hit_ranks = [r for r in ranks if r >= 0]

    out["anchor_recall"] = len(hit_ranks) / len(anchors) if anchors else None
    out["missed_anchors"] = [a for a, r in zip(anchors, ranks) if r < 0]
    out["anchor_mrr"] = 1.0 / (min(hit_ranks) + 1) if hit_ranks else 0.0
    for k in CUTOFFS:
        out["anchor_hit@{}".format(k)] = 1.0 if any(r < k for r in hit_ranks) else 0.0

    # 召回噪声：有多少条 chunk 真的带上了标注的证据
    norm_anchors = [normalize(a) for a in anchors if normalize(a)]
    useful = sum(1 for c in contexts if any(na in normalize(c) for na in norm_anchors))
    out["context_precision"] = useful / len(contexts) if contexts else 0.0

    return out


def mean(values):
    vals = [v for v in values if v is not None]
    return sum(vals) / len(vals) if vals else None


def aggregate(rows):
    """按档位和整体汇总。negative 档单独统计，不混入检索指标均值。"""
    scored = [r for r in rows if r["tier"] != "negative"]
    negative = [r for r in rows if r["tier"] == "negative"]

    def block(subset):
        if not subset:
            return None
        agg = {
            "n": len(subset),
            "doc_recall": mean([r.get("doc_recall") for r in subset]),
            "doc_precision": mean([r.get("doc_precision") for r in subset]),
            "anchor_recall": mean([r.get("anchor_recall") for r in subset]),
            "anchor_mrr": mean([r.get("anchor_mrr") for r in subset]),
            "context_precision": mean([r.get("context_precision") for r in subset]),
        }
        for k in CUTOFFS:
            key = "anchor_hit@{}".format(k)
            agg[key] = mean([r.get(key) for r in subset])
        return agg

    summary = {"overall": block(scored)}
    for tier in ("easy", "hard"):
        summary[tier] = block([r for r in scored if r["tier"] == tier])
    if negative:
        summary["negative"] = {
            "n": len(negative),
            "avg_doc_spread": mean([r.get("negative_doc_spread") for r in negative]),
            "note": "接口未透出分数，无法判定「正确地无答案」，此项仅供人工观察",
        }

    lat = [r["latency_ms"] for r in rows if r.get("latency_ms") is not None]
    if lat:
        summary["latency"] = {
            "mean_ms": round(mean(lat)),
            "p50_ms": round(statistics.median(lat)),
            "max_ms": max(lat),
        }
    return summary


def fmt(v):
    return "  —  " if v is None else "{:.3f}".format(v)


def print_summary(summary, title):
    print("\n=== {} ===".format(title))
    cols = ["doc_recall", "doc_precision", "anchor_recall", "anchor_mrr",
            "anchor_hit@1", "anchor_hit@3", "anchor_hit@10", "context_precision"]
    header = "{:<9}{:>4}".format("档位", "n") + "".join("{:>16}".format(c) for c in cols)
    print(header)
    print("-" * len(header))
    for tier in ("overall", "easy", "hard"):
        b = summary.get(tier)
        if not b:
            continue
        line = "{:<9}{:>4}".format(tier, b["n"]) + "".join("{:>16}".format(fmt(b.get(c))) for c in cols)
        print(line)
    neg = summary.get("negative")
    if neg:
        print("\nnegative({} 条)：平均召回 {:.1f} 篇不同文档。{}".format(
            neg["n"], neg["avg_doc_spread"] or 0, neg["note"]))
    lat = summary.get("latency")
    if lat:
        print("耗时：均值 {}ms / P50 {}ms / 最大 {}ms".format(
            lat["mean_ms"], lat["p50_ms"], lat["max_ms"]))


def print_compare(base, other, base_label, other_label):
    print("\n=== 对比：{} → {} ===".format(other_label, base_label))
    cols = ["doc_recall", "anchor_recall", "anchor_mrr", "anchor_hit@1", "anchor_hit@3", "context_precision"]
    for tier in ("overall", "easy", "hard"):
        b, o = base.get(tier), other.get(tier)
        if not b or not o:
            continue
        print("\n[{}]".format(tier))
        for c in cols:
            bv, ov = b.get(c), o.get(c)
            if bv is None or ov is None:
                continue
            delta = bv - ov
            arrow = "↑" if delta > 1e-9 else ("↓" if delta < -1e-9 else "=")
            print("  {:<20}{} → {}  {} {:+.3f}".format(c, fmt(ov), fmt(bv), arrow, delta))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://127.0.0.1:9090/api/ragent")
    ap.add_argument("--username", default="admin")
    ap.add_argument("--password", default="admin")
    ap.add_argument("--token", help="直接指定 token，跳过登录")
    ap.add_argument("--label", required=True, help="本次运行的配置标签，如 baseline / topk5 / no-rerank")
    ap.add_argument("--tier", choices=["easy", "hard", "negative"], help="只跑某一档")
    ap.add_argument("--concurrency", type=int, default=4,
                    help="并发数，注意别超过 rag.rate-limit.global.max-concurrent")
    ap.add_argument("--timeout", type=int, default=120)
    ap.add_argument("--compare", help="对比的历史报告路径")
    args = ap.parse_args()

    rows = load_dataset(DATASET)
    if args.tier:
        rows = [r for r in rows if r["tier"] == args.tier]
    if not rows:
        print("没有可跑的问题")
        return 1

    token = args.token or login(args.base, args.username, args.password)
    print("已登录，共 {} 条问题，并发 {}".format(len(rows), args.concurrency))

    results = [None] * len(rows)
    failures = []

    def work(item):
        i, row = item
        try:
            resp = query(args.base, token, row["question"], args.timeout)
            return i, score_one(row, resp), None
        except (urllib.error.URLError, RuntimeError, OSError) as e:
            return i, None, "{}: {}".format(row["id"], e)

    started = time.time()
    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        done = 0
        for i, scored, err in pool.map(work, enumerate(rows)):
            done += 1
            if err:
                failures.append(err)
                print("  [{}/{}] ✗ {}".format(done, len(rows), err))
            else:
                results[i] = scored
                print("  [{}/{}] {}".format(done, len(rows), scored["id"]), end="\r")
    elapsed = time.time() - started

    results = [r for r in results if r]
    if not results:
        print("\n全部失败，无法出分")
        for f in failures:
            print("  ", f)
        return 1

    summary = aggregate(results)
    print("\n\n跑完 {} 条，耗时 {:.1f}s，失败 {} 条".format(len(results), elapsed, len(failures)))
    print_summary(summary, "配置: {}".format(args.label))

    misses = [(r["id"], r["missed_anchors"]) for r in results if r.get("missed_anchors")]
    if misses:
        print("\n未命中锚点的问题（优先看这些）：")
        for rid, ms in misses[:15]:
            print("  {} → {}".format(rid, "、".join(ms)))
        if len(misses) > 15:
            print("  ...共 {} 条".format(len(misses)))

    REPORTS.mkdir(exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    out_path = REPORTS / "{}-{}.json".format(stamp, args.label)
    out_path.write_text(json.dumps({
        "label": args.label,
        "timestamp": stamp,
        "dataset_size": len(rows),
        "failures": failures,
        "summary": summary,
        "details": results,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n明细已写入 {}".format(out_path.relative_to(HERE.parent)))

    if args.compare:
        prev = json.loads(Path(args.compare).read_text(encoding="utf-8"))
        print_compare(summary, prev["summary"], args.label, prev.get("label", args.compare))

    return 0


if __name__ == "__main__":
    sys.exit(main())
