#!/usr/bin/env python3
"""Combine disjoint first-attempt capture segments, retaining failures and checking exact query order."""
import argparse
from pathlib import Path

from cs_evalkit import load_jsonl, sha256_file, write_json, write_jsonl


def merge(queries, segments):
    if any(q.get('split') != 'public_dev' for q in queries):
        raise ValueError('development only')
    rows = [row for segment in segments for row in segment]
    if [r['id'] for r in rows] != [q['id'] for q in queries]:
        raise ValueError('segments must cover each development query exactly once, in original order')
    if any(r.get('response', {}).get('rewriteEnabled') for r in rows if r.get('response')):
        raise ValueError('this first-attempt merger accepts raw-question captures only')
    for row, query in zip(rows, queries):
        if row.get('response') and row['response']['question'] != query['question']:
            raise ValueError('question changed')
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--queries', type=Path, required=True)
    parser.add_argument('--parts', nargs='+', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists() or args.output.with_suffix('.manifest.json').exists():
        raise FileExistsError(args.output)
    rows = merge(load_jsonl(args.queries), [load_jsonl(p) for p in args.parts])
    write_jsonl(args.output, rows)
    write_json(args.output.with_suffix('.manifest.json'), {'count': len(rows), 'rewrite': False,
        'result_sha256': sha256_file(args.output), 'query_sha256': sha256_file(args.queries),
        'parts': {str(p): sha256_file(p) for p in args.parts},
        'failed_or_degraded': sum(bool(r.get('error') or (r.get('response') or {}).get('degraded')) for r in rows),
        'protocol': 'first-attempt-only, no replacement of failed rows'})
    print('Merged first attempts:', len(rows))


if __name__ == '__main__':
    main()
