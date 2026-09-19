#!/usr/bin/env python3
"""Sample the W6 (X1b) MuSiQue dev questions without looking at answers or answerability labels.

Same rule as the dataset tool's fixed profiles: order question IDs by SHA256(seed, question ID)
ascending. Questions in the regression profile (seen during development) are dropped together with
every other version of their source question; of the rest, only the first version of each
source_question_id is kept. The first blocks x size IDs are written as consecutive blocks.
Only the id and source_question_id fields of questions.jsonl are used.
"""
import argparse
import json
from pathlib import Path

from datasetkit import iter_jsonl, stable_id


def sample(questions, seen, seed, blocks, size):
    """questions: (id, source_question_id) pairs; seen: question IDs to exclude with their siblings."""
    source = dict(questions)
    excluded = {source[i] for i in seen if i in source}
    ordered = sorted(source, key=lambda i: (stable_id('sample', seed, i), i))
    picked, used = [], set()
    for identifier in ordered:
        if source[identifier] in excluded or source[identifier] in used:
            continue
        used.add(source[identifier])
        picked.append(identifier)
    if len(picked) < blocks * size:
        raise ValueError(f'only {len(picked)} eligible questions for {blocks} x {size}')
    return [picked[k * size:(k + 1) * size] for k in range(blocks)]


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--prepared', type=Path, required=True, help='prepared musique-dev directory')
    parser.add_argument('--seed', type=int, default=20260919)
    parser.add_argument('--blocks', type=int, default=4)
    parser.add_argument('--size', type=int, default=100)
    parser.add_argument('--out-prefix', type=Path, required=True, help='writes <prefix>-1.json .. <prefix>-N.json')
    args = parser.parse_args()
    questions = [(row['id'], row['source_question_id']) for row in iter_jsonl(args.prepared / 'questions.jsonl')]
    seen = {row['id'] for row in iter_jsonl(args.prepared / 'queries.regression.jsonl')}
    for number, block in enumerate(sample(questions, seen, args.seed, args.blocks, args.size), 1):
        path = Path(f'{args.out_prefix}-{number}.json')
        path.write_text(json.dumps(block, indent=2) + '\n')
        print(path, len(block))


if __name__ == '__main__':
    main()
