#!/usr/bin/env python3
"""Ingest public development passages via the actual API and capture production stages."""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import json
import os
from pathlib import Path
import sys
import time

from cs_evalkit import load_jsonl, sha256_file, write_json

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'iron-ore'))
from evalkit import ApiClient
from prepare_kb import create_or_reuse_kb, wait_for_ingestion

BASE = os.environ.get('CS_EVAL_BASE', 'http://127.0.0.1:9093/api/ragent')
if BASE not in ('http://127.0.0.1:9093/api/ragent', 'http://127.0.0.1:9094/api/ragent'):
    raise ValueError('only isolated loopback evaluation services allowed')


def client() -> ApiClient:
    api = ApiClient(BASE, timeout=180)
    api.login('admin', 'admin')
    return api


def ingest(args):
    corpus = load_jsonl(args.corpus)
    allowed = {'id', 'document_id', 'version', 'location', 'title', 'sentences', 'text'}
    if not corpus or any(set(c) != allowed for c in corpus):
        raise ValueError('expected prepared gold-free public corpus')
    args.output_dir.mkdir(parents=True, exist_ok=True)
    marker = args.output_dir / 'setup.json'
    api = client()
    corpus_hash = sha256_file(args.corpus)
    if marker.exists():
        setup = json.loads(marker.read_text())
        if setup['corpus_sha256'] != corpus_hash or setup['base'] != BASE:
            raise ValueError('resume corpus or service mismatch')
    else:
        kb = create_or_reuse_kb(api, 'public-pool', {'name': 'CS public pool v1',
            'collection_name': 'cs_pool_v1', 'embedding_model': 'qwen-emb-8b'})
        setup = {'base': BASE, 'corpus_sha256': corpus_hash, 'kb': kb,
            'protocol': 'public-dev-real-ingestion-v1', 'created_at': time.time()}
        write_json(marker, setup)
    subset = corpus[:args.limit] if args.limit else corpus
    token = api.token

    def one(passage):
        completed_path = args.output_dir / 'documents' / (passage['id'] + '.json')
        pending_path = args.output_dir / 'pending' / (passage['id'] + '.json')
        if completed_path.exists():
            return 'cached'
        local = ApiClient(BASE, token=token, timeout=180)
        if pending_path.exists():
            pending = json.loads(pending_path.read_text())
        else:
            source = args.output_dir / 'sources' / (passage['id'] + '.md')
            source.parent.mkdir(parents=True, exist_ok=True)
            text = '# ' + passage['title'] + '\n\n' + passage['text'] + '\n'
            if source.exists() and source.read_text(encoding='utf-8') != text:
                raise ValueError('source file changed')
            if not source.exists():
                source.write_text(text, encoding='utf-8')
            uploaded = local.upload_file(f"/knowledge-base/{setup['kb']['id']}/docs/upload", {
                'sourceType': 'file', 'processMode': 'chunk',
                'ingestionSpec': json.dumps({'parseProfile': 'fast', 'maxChars': 1024,
                    'overlapChars': 128, 'rowsPerChunk': 50, 'toleranceFactor': 3}),
            }, source)
            pending = {'pool_id': passage['id'], 'doc_id': str(uploaded['id']),
                'source_sha256': sha256_file(source)}
            write_json(pending_path, pending)
        current = local.request_json(f"/knowledge-base/docs/{pending['doc_id']}")
        if current['status'] in ('pending', 'failed'):
            result = local.request_json(f"/rag/eval/pooled/ingest/{pending['doc_id']}", method='POST')
            if result != 'success':
                raise RuntimeError('synchronous production ingestion failed: ' + str(result))
        document = wait_for_ingestion(local, pending['doc_id'], timeout=180, poll_seconds=1)
        chunks = local.request_json(f"/knowledge-base/docs/{pending['doc_id']}/chunks",
            query={'current': 1, 'size': 1000})
        if int(chunks.get('total', 0)) != len(chunks['records']):
            raise ValueError('chunk page incomplete')
        write_json(completed_path, {**pending, 'document': document, 'chunks': chunks['records']})
        return 'ingested'

    successes = 0
    failures = []
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(one, p): p['id'] for p in subset}
        for future in as_completed(futures):
            try:
                future.result()
                successes += 1
                if successes % 25 == 0 or len(subset) <= 5:
                    print(f'documents completed {successes}/{len(subset)}', flush=True)
            except Exception as exc:
                failures.append({'pool_id': futures[future], 'error_type': type(exc).__name__, 'message': str(exc)[:400]})
                print(f'failed {futures[future]}: {type(exc).__name__}', flush=True)
                for other in futures:
                    other.cancel()
                break
    report = {'attempted_limit': len(subset), 'completed_this_run': successes, 'failures': failures,
        'corpus_sha256': corpus_hash, 'completed_documents': len(list((args.output_dir / 'documents').glob('*.json')))}
    write_json(args.output_dir / f'ingest-status-{time.time_ns()}.json', report)
    print(report, flush=True)
    if failures:
        raise SystemExit(1)
    if len(subset) == len(corpus) and report['completed_documents'] == len(corpus):
        write_json(args.output_dir / 'ingest-ready.json', {
            'corpus_sha256': corpus_hash, 'documents': len(corpus), 'transport': 'synchronous-production-kernel',
            'manifest_sha256': {p.name: sha256_file(p) for p in sorted((args.output_dir / 'documents').glob('*.json'))}})


def capture(args):
    rows = load_jsonl(args.queries)
    if any(set(q) != {'id', 'split', 'language', 'task_type', 'question'} or q['split'] != 'public_dev' for q in rows):
        raise ValueError('only gold-free public development questions accepted')
    if args.output.exists():
        raise FileExistsError(args.output)
    ready_file = args.output.parent / 'ingest-ready.json'
    if not args.allow_partial_corpus_smoke:
        ready = json.loads(ready_file.read_text())
        for filename, expected_hash in ready['manifest_sha256'].items():
            if sha256_file(args.output.parent / 'documents' / filename) != expected_hash:
                raise ValueError('ingestion manifest changed')
        if len(ready['manifest_sha256']) != ready['documents']:
            raise ValueError('incomplete ingestion manifest')
    elif args.limit is None or args.limit > 3:
        raise ValueError('partial corpus smoke permits at most 3 questions')
    api = client()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    remaining = rows[args.offset:]
    subset = remaining[:args.limit] if args.limit else remaining
    consecutive_failures = 0
    with args.output.open('x', encoding='utf-8') as output:
        for index, row in enumerate(subset):
            start = time.monotonic()
            try:
                response = api.request_json('/rag/eval/pooled', method='POST',
                    body={'question': row['question'], 'rewrite': args.rewrite})
                value = {'id': row['id'], 'response': response, 'error': None}
            except Exception as exc:
                value = {'id': row['id'], 'response': None, 'error': type(exc).__name__ + ': ' + str(exc)[:400]}
            value['wall_ms'] = (time.monotonic() - start) * 1000
            output.write(json.dumps(value, ensure_ascii=False) + '\n')
            output.flush()
            print(f'query {index + 1}/{len(subset)} error={value["error"] is not None} '
                f'degraded={bool((value["response"] or {}).get("degraded"))}', flush=True)
            failed = value['error'] or value['response'].get('degraded')
            consecutive_failures = consecutive_failures + 1 if failed else 0
            if consecutive_failures >= args.max_consecutive_failures:
                raise RuntimeError('failed/degraded query retained; inspect before continuing')
    write_json(args.output.with_suffix('.manifest.json'), {'base': BASE, 'query_sha256': sha256_file(args.queries),
        'result_sha256': sha256_file(args.output), 'count': len(subset), 'rewrite': args.rewrite, 'offset': args.offset,
        'scope': 'real RetrievalEngine, intent disabled, recallBudget=40, requestTopK=10; no answer generation'})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    imp = sub.add_parser('ingest')
    imp.add_argument('--corpus', type=Path, required=True)
    imp.add_argument('--output-dir', type=Path, required=True)
    imp.add_argument('--limit', type=int)
    imp.add_argument('--workers', type=int, default=4, choices=[1, 2, 4, 8, 16])
    cap = sub.add_parser('capture')
    cap.add_argument('--queries', type=Path, required=True)
    cap.add_argument('--output', type=Path, required=True)
    cap.add_argument('--limit', type=int)
    cap.add_argument('--rewrite', action='store_true')
    cap.add_argument('--allow-partial-corpus-smoke', action='store_true')
    cap.add_argument('--offset', type=int, default=0, help='resume at next unseen query; never replace failed rows')
    cap.add_argument('--max-consecutive-failures', type=int, default=1, choices=[1, 2, 3])
    args = parser.parse_args()
    if args.limit is not None and args.limit < 1:
        parser.error('limit must be positive')
    if getattr(args, 'offset', 0) < 0:
        parser.error('offset must be nonnegative')
    {'ingest': ingest, 'capture': capture}[args.command](args)


if __name__ == '__main__':
    main()
