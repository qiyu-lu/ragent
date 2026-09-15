#!/usr/bin/env python3
"""Prepare official BEIR SciFact and evaluate source-document relevance after real chunk retrieval."""
import argparse
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
import zipfile

from cs_evalkit import load_jsonl, sha256_file, write_json, write_jsonl
from hybrid_eval import load_run


def prepare(args):
    digest = hashlib.md5(args.archive.read_bytes()).hexdigest()
    if digest != '5f7d1de60b170fc8027bb7898e2efca1':
        raise ValueError('archive differs from official BEIR SciFact MD5')
    if (args.output / 'corpus.jsonl').exists():
        raise FileExistsError(args.output)
    with zipfile.ZipFile(args.archive) as archive:
        corpus = [json.loads(line) for line in archive.read('scifact/corpus.jsonl').decode().splitlines()]
        queries = [json.loads(line) for line in archive.read('scifact/queries.jsonl').decode().splitlines()]
        qrels = defaultdict(dict)
        for line in archive.read('scifact/qrels/test.tsv').decode().splitlines()[1:]:
            query, doc, score = line.split('\t')
            if int(score) > 0:
                qrels['scifact-' + query]['scifact-' + doc] = int(score)
    passages = [{'id': 'scifact-' + d['_id'], 'document_id': d['_id'], 'version': 'BEIR-scifact-v1',
        'location': {'title': d['title']}, 'title': d['title'], 'text': d['text'], 'sentences': [d['text']]} for d in corpus]
    questions = [{'id': 'scifact-' + q['_id'], 'split': 'public_benchmark', 'language': 'en',
        'task_type': 'claim_retrieval', 'question': q['text']} for q in queries if 'scifact-' + q['_id'] in qrels]
    if len(passages) != 5183 or len(questions) != 300:
        raise ValueError('unexpected official corpus/query count')
    write_jsonl(args.output / 'corpus.jsonl', passages)
    write_jsonl(args.output / 'queries.jsonl', questions)
    write_json(args.output / 'qrels.json', dict(qrels))
    write_json(args.output / 'manifest.json', {'source': 'https://github.com/beir-cellar/beir',
        'archive_md5': digest, 'archive_sha256': sha256_file(args.archive), 'documents': len(passages),
        'queries': len(questions), 'split': 'official test', 'parameter_search': False,
        'scope': 'scientific claim to relevant paper retrieval, not answer generation or industrial-domain accuracy'})
    print('Prepared SciFact:', len(passages), 'documents,', len(questions), 'queries')


def metrics(ranked, relevant, k):
    selected = ranked[:k]
    hits = sum(doc in relevant for doc in selected)
    dcg = sum((2 ** relevant.get(doc, 0) - 1) / math.log2(i + 2) for i, doc in enumerate(selected))
    ideal = sum((2 ** value - 1) / math.log2(i + 2) for i, value in enumerate(sorted(relevant.values(), reverse=True)[:k]))
    return {'recall': hits / len(relevant), 'ndcg': dcg / ideal if ideal else 0.0}


def score(args):
    qrels = json.loads(args.qrels.read_text())
    run = load_run(args.run, list(qrels))
    chunk_source = {}
    for path in args.documents.glob('*.json'):
        doc = json.loads(path.read_text())
        chunk_source.update({str(c['id']): doc['pool_id'] for c in doc['chunks']})
    arms = {'vector': 'channel-VectorSearch', 'bm25': 'channel-KeywordSearch', 'rrf': 'post-Fusion',
        'vector-rerank': 'VectorSearch-rerank', 'bm25-rerank': 'KeywordSearch-rerank', 'rrf-rerank': 'request-final'}
    sums = {a: {'recall@10': 0, 'ndcg@10': 0, 'recall@5': 0, 'ndcg@5': 0} for a in arms}
    details = []
    for query, relevant in qrels.items():
        row = run[query]
        result = row.get('result') or {}; hybrid = result.get('hybrid') or {}
        stages = {s['stage']: s for s in hybrid.get('stages', []) + result.get('baselines', [])}
        valid = not row.get('error') and not hybrid.get('degraded') and all(not s['failure'] for s in result.get('baselines', []))
        scores = {}
        for arm, name in arms.items():
            stage = stages.get(name) or {'chunks': [], 'failure': 'missing'}
            scores[arm] = {}
            for k in (5, 10):
                # Same context chunk budget for every arm, then collapse duplicate source documents.
                ranked = list(dict.fromkeys(chunk_source[str(c['id'])] for c in stage['chunks'][:k])) if not stage['failure'] else []
                value = metrics(ranked, relevant, k)
                for metric, score_value in value.items():
                    key = metric + '@' + str(k); sums[arm][key] += score_value; scores[arm][key] = score_value
        details.append({'id': query, 'healthy_all_arms': bool(valid), 'scores': scores})
    for arm in sums:
        sums[arm] = {k: value / len(qrels) for k, value in sums[arm].items()}
    paired = {}
    for arm in arms:
        diffs = [d['scores'][arm]['ndcg@10'] - d['scores']['vector-rerank']['ndcg@10'] for d in details if d['healthy_all_arms']]
        paired[arm] = {'compared': len(diffs), 'gain': sum(x > 1e-12 for x in diffs), 'loss': sum(x < -1e-12 for x in diffs),
            'mean_ndcg10_delta': sum(diffs) / len(diffs) if diffs else None}
    result = {'questions': len(qrels), 'chunks': len(chunk_source), 'scores': sums, 'paired_vs_vector_rerank': paired,
        'healthy_all_arms': sum(d['healthy_all_arms'] for d in details), 'per_question': details,
        'qrels_sha256': sha256_file(args.qrels), 'run_manifest_sha256': sha256_file(args.run / 'manifest.json'),
        'scope': 'full SciFact corpus, official test qrels; equal topK CHUNK budgets then source-document deduplication; not an official BEIR leaderboard run'}
    if args.output.exists(): raise FileExistsError(args.output)
    write_json(args.output, result)
    print(json.dumps({k:v for k,v in result.items() if k!='per_question'}))


def main():
    parser = argparse.ArgumentParser(); subs = parser.add_subparsers(dest='command', required=True)
    p = subs.add_parser('prepare'); p.add_argument('--archive', type=Path, required=True); p.add_argument('--output', type=Path, required=True)
    p = subs.add_parser('score'); p.add_argument('--qrels', type=Path, required=True); p.add_argument('--documents', type=Path, required=True)
    p.add_argument('--run', type=Path, required=True); p.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); {'prepare': prepare, 'score': score}[args.command](args)


if __name__ == '__main__': main()
