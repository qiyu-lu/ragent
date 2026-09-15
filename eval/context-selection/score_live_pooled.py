#!/usr/bin/env python3
"""Offline, source-scoped sentence coverage of real retrieval stage captures (not answer accuracy)."""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import unicodedata

from cs_evalkit import load_jsonl, sha256_file, write_json


def normalize(text):
    return ''.join(unicodedata.normalize('NFC', text).split())


def evidence_mapping(gold, documents):
    """Exact sentence containment inside a chunk of the matching source only.

    Unmapped/fragmented sentences are reported separately, never silently counted as retrieval failure.
    """
    sources = {d['pool_id']: d for d in documents}
    if len(sources) != len(documents):
        raise ValueError('duplicate source manifests')
    mapping = {}
    for row in gold:
        candidates = {c['id']: c for c in row['candidates']}
        for requirement in row['evidence_requirements']:
            for alternative in requirement['alternatives']:
                for evidence in alternative['all_of']:
                    key = (evidence['candidate_id'], evidence['location']['sentence_id'])
                    sentence = normalize(candidates[key[0]]['sentences'][key[1]])
                    if not sentence:
                        raise ValueError('empty supporting sentence')
                    mapping[key] = {str(c['id']) for c in sources.get(key[0], {}).get('chunks', [])
                        if sentence in normalize(c['content'])}
    return mapping


def full_support(row, selected, mapping):
    return all(any(all(bool(mapping[(e['candidate_id'], e['location']['sentence_id'])] & selected)
        for e in alt['all_of']) for alt in req['alternatives']) for req in row['evidence_requirements'])


def score(gold, captures, documents):
    by_id = {g['id']: g for g in gold}
    if len({c['id'] for c in captures}) != len(captures) or any(c['id'] not in by_id for c in captures):
        raise ValueError('duplicate or unknown captured question')
    mapping = evidence_mapping(gold, documents)
    all_chunks = {str(c['id']) for d in documents for c in d['chunks']}
    counts = Counter()
    rerank_comparison = Counter()
    details = []
    for capture in captures:
        row = by_id[capture['id']]
        available = full_support(row, all_chunks, mapping)
        stages = defaultdict(set)
        response = capture.get('response') or {}
        failed = bool(capture.get('error') or response.get('degraded'))
        for stage in response.get('stages', []):
            ids = [str(c['id']) for c in stage['chunks']]
            if set(ids) - all_chunks:
                raise ValueError('capture contains unknown chunks')
            stages[stage['stage']].update(ids)
            if stage['stage'].startswith('channel-') or stage['stage'] == 'post-Rerank':
                for k in (5, 10, 20, 40):
                    stages[stage['stage'] + '-top' + str(k)].update(ids[:k])
        support = {name: full_support(row, ids, mapping) for name, ids in stages.items()}
        if not failed and 'channel-VectorSearch-top10' in support and 'post-Rerank-top10' in support:
            before, after = support['channel-VectorSearch-top10'], support['post-Rerank-top10']
            rerank_comparison['compared'] += 1
            rerank_comparison['gain' if after and not before else 'loss' if before and not after else 'unchanged'] += 1
        for name, complete in support.items():
            if complete and not failed:
                counts[name] += 1
        recalled = any(value for name, value in support.items() if name.startswith('channel-') and '-top' not in name)
        final = support.get('request-final', False)
        category = ('service_failure' if failed else 'ingestion_or_mapping_unavailable' if not available
            else 'recall_missing' if not recalled else 'selection_missing' if not final else 'complete')
        counts[category] += 1
        details.append({'id': row['id'], 'category': category, 'source_evidence_available': available,
            'stage_full_support': support, 'subquestion_count': len(response.get('subQuestions', []))})
    return {'questions': len(captures), 'gold_questions': len(gold), 'documents': len(documents),
        'chunks': len(all_chunks), 'unique_support_sentences': len(mapping),
        'unmapped_support_sentences': sum(not ids for ids in mapping.values()),
        'counts': dict(counts), 'per_question': details,
        'rerank_vs_vector_top10_healthy_only': dict(rerank_comparison),
        'metric': 'all required source-scoped supporting sentences present in selected chunks; not answer accuracy',
        'mapping': 'NFC plus whitespace removal; full sentence must fit at least one chunk of its original source',
        'topk': 'per-subquestion stage prefix then union; final is request-level selection'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--gold', type=Path, required=True)
    parser.add_argument('--captures', type=Path, required=True)
    parser.add_argument('--documents', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--allow-incomplete', action='store_true', help='diagnose retained failures; never a complete run')
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError(args.output)
    captures = load_jsonl(args.captures)
    if not args.allow_incomplete:
        manifest = json.loads(args.captures.with_suffix('.manifest.json').read_text())
        if manifest['count'] != len(captures) or manifest['result_sha256'] != sha256_file(args.captures):
            raise ValueError('capture incomplete or modified')
    files = sorted(args.documents.glob('*.json'))
    result = score(load_jsonl(args.gold), captures, [json.loads(p.read_text()) for p in files])
    result['allow_incomplete'] = args.allow_incomplete
    result['provenance'] = {'gold_sha256': sha256_file(args.gold), 'captures_sha256': sha256_file(args.captures),
        'document_manifest_sha256': {p.name: sha256_file(p) for p in files}, 'scorer_sha256': sha256_file(Path(__file__))}
    write_json(args.output, result)
    print(json.dumps({k: v for k, v in result.items() if k not in ('per_question', 'provenance')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
