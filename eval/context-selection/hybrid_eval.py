#!/usr/bin/env python3
"""Real ES BM25 + PGVector + production RRF/rerank paired diagnostic."""
from __future__ import annotations
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
from pathlib import Path
import time
import urllib.request

from cs_evalkit import load_jsonl, sha256_file, write_json, write_jsonl
from live_pooled import client, ApiClient, BASE
from score_live_pooled import evidence_mapping, full_support

ES = 'http://127.0.0.1:9201'
INDEX = os.environ.get('CS_EVAL_INDEX', 'cs_pool_hybrid_v1')
if INDEX not in ('cs_pool_hybrid_v1', 'cs_pool_scifact_hybrid_v1'):
    raise ValueError('isolated evaluation indices only')


def load_run(directory, expected_ids):
    manifest = json.loads((directory / 'manifest.json').read_text())
    if manifest['count'] != len(expected_ids) or set(manifest['results']) != set(expected_ids):
        raise ValueError('incomplete or mismatched comparison run')
    rows = {}
    for identifier in expected_ids:
        path = directory / (identifier + '.json')
        if sha256_file(path) != manifest['results'][identifier]:
            raise ValueError('comparison output changed')
        rows[identifier] = json.loads(path.read_text())
    return rows


def es(path, body=None, method='GET', ndjson=False):
    data = body.encode() if ndjson else json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(ES + path, data=data, method=method,
        headers={'Content-Type': 'application/x-ndjson' if ndjson else 'application/json'})
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def index(args):
    documents = [json.loads(p.read_text()) for p in sorted(args.documents.glob('*.json'))]
    if not documents:
        raise ValueError('no completed documents')
    # Explicit new index only. Reject an existing nonempty index rather than replace data.
    try:
        count = es('/' + INDEX + '/_count')['count']
        if count:
            raise ValueError('index already contains documents')
    except urllib.error.HTTPError as exc:
        if exc.code != 404:
            raise
        es('/' + INDEX, {'settings': {'number_of_shards': 1, 'number_of_replicas': 0},
            'mappings': {'properties': {'content': {'type': 'text', 'analyzer': 'english', 'search_analyzer': 'english'},
                'collection_name': {'type': 'keyword'}, 'doc_id': {'type': 'keyword'}, 'chunk_index': {'type': 'integer'}}}}, 'PUT')
    records = []
    for document in documents:
        for chunk in document['chunks']:
            records.append((str(chunk['id']), {'content': chunk['content'], 'collection_name': 'cs_pool_v1',
                'doc_id': document['doc_id'], 'chunk_index': chunk['chunkIndex']}))
    for start in range(0, len(records), 200):
        lines = []
        for identifier, document in records[start:start + 200]:
            lines.extend([json.dumps({'create': {'_index': INDEX, '_id': identifier}}), json.dumps(document)])
        result = es('/_bulk', '\n'.join(lines) + '\n', 'POST', True)
        if result['errors']:
            raise RuntimeError('ES bulk failed; preserve partial index for inspection')
    es('/' + INDEX + '/_refresh', method='POST')
    actual = es('/' + INDEX + '/_count')['count']
    if actual != len(records):
        raise ValueError('ES count mismatch')
    print({'documents': len(documents), 'indexed_chunks': actual, 'analyzer': 'english'})


def run(args):
    queries = load_jsonl(args.queries)
    if any(set(q) != {'id', 'split', 'language', 'task_type', 'question'} or q['split'] not in ('public_dev', 'public_benchmark') for q in queries):
        raise ValueError('gold-free development questions required')
    args.output.mkdir(parents=True, exist_ok=True)
    api = client()
    def one(query):
        path = args.output / (query['id'] + '.json')
        if path.exists():
            return
        start = time.monotonic()
        local = ApiClient(BASE, token=api.token, timeout=180)
        try:
            result = local.request_json('/rag/eval/pooled/hybrid-compare', method='POST',
                body={'question': query['question'], 'rewrite': False})
            row = {'id': query['id'], 'result': result, 'error': None}
        except Exception as exc:
            row = {'id': query['id'], 'result': None, 'error': type(exc).__name__ + ': ' + str(exc)[:300]}
        row['wall_ms'] = (time.monotonic() - start) * 1000
        write_json(path, row)
    subset = queries[:args.limit] if args.limit else queries
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = [pool.submit(one, q) for q in subset]
        for i, future in enumerate(as_completed(futures), 1):
            future.result()
            if i % 10 == 0 or i == len(subset):
                print('completed', i, '/', len(subset), flush=True)
    write_json(args.output / 'manifest.json', {'queries_sha256': sha256_file(args.queries), 'count': len(subset),
        'results': {q['id']: sha256_file(args.output / (q['id'] + '.json')) for q in subset},
        'protocol': 'same per-query candidates; vector40 + BM2540; production RRF k20 equal weights, pool40, final10',
        'rerank': 'hybrid production pipeline then independent vector/BM25 qwen3-rerank calls; no rewriting or generation'})


def score(args):
    gold = load_jsonl(args.gold)
    run = load_run(args.run, [q['id'] for q in gold])
    documents = [json.loads(p.read_text()) for p in args.documents.glob('*.json')]
    mapping = evidence_mapping(gold, documents)
    labels = {'vector': 'channel-VectorSearch', 'bm25': 'channel-KeywordSearch', 'rrf': 'post-Fusion',
        'vector-rerank': 'VectorSearch-rerank', 'bm25-rerank': 'KeywordSearch-rerank', 'rrf-rerank': 'request-final'}
    counts = {arm: {str(k): 0 for k in (5, 10, 20, 40) if k <= (10 if 'rerank' in arm else 40)} for arm in labels}
    details = []
    for question in gold:
        row = run[question['id']]
        result = row.get('result') or {}
        hybrid = result.get('hybrid') or {}
        stages = {s['stage']: s for s in hybrid.get('stages', []) + result.get('baselines', [])}
        healthy = not row.get('error') and not hybrid.get('degraded') and all(not s['failure'] for s in result.get('baselines', []))
        cover = {}
        for arm, stage_name in labels.items():
            stage = stages.get(stage_name) or {'chunks': [], 'failure': 'missing'}
            cover[arm] = {}
            for k in counts[arm]:
                hit = not stage['failure'] and full_support(question, {str(c['id']) for c in stage['chunks'][:int(k)]}, mapping)
                cover[arm][k] = bool(hit)
                counts[arm][k] += bool(hit)
        details.append({'id': question['id'], 'healthy_all_arms': bool(healthy), 'coverage': cover})
    paired = {}
    for arm in ('bm25', 'rrf', 'bm25-rerank', 'rrf-rerank'):
        deltas = [int(d['coverage'][arm]['10']) - int(d['coverage']['vector-rerank']['10']) for d in details if d['healthy_all_arms']]
        paired[arm] = {'compared': len(deltas), 'gain': deltas.count(1), 'loss': deltas.count(-1)}
    output = {'questions': len(gold), 'counts': counts, 'healthy_all_arms': sum(d['healthy_all_arms'] for d in details),
        'paired_vs_vector_rerank_top10': paired, 'per_question': details,
        'metric': 'source-scoped full supporting sentence coverage, not answer accuracy',
        'gold_sha256': sha256_file(args.gold), 'run_manifest_sha256': sha256_file(args.run / 'manifest.json')}
    if args.output.exists():
        raise FileExistsError(args.output)
    write_json(args.output, output)
    print(json.dumps({k: v for k, v in output.items() if k != 'per_question'}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest='command', required=True)
    p = subs.add_parser('index'); p.add_argument('--documents', type=Path, required=True)
    p = subs.add_parser('run'); p.add_argument('--queries', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True); p.add_argument('--workers', type=int, default=4, choices=[1, 2, 4])
    p.add_argument('--limit', type=int)
    p = subs.add_parser('score'); p.add_argument('--gold', type=Path, required=True)
    p.add_argument('--documents', type=Path, required=True); p.add_argument('--run', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); {'index': index, 'run': run, 'score': score}[args.command](args)


if __name__ == '__main__':
    main()
