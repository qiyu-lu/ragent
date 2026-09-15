#!/usr/bin/env python3
"""Development-only pooled corpus diagnostic; not the production vector retriever."""

from __future__ import annotations

import argparse
import copy
import json
import math
import time
from collections import Counter, defaultdict
from pathlib import Path

from capture_candidates import git_head, snapshot
from cs_evalkit import load_jsonl, sha256_file, stable_hash, tokenize, write_json, write_jsonl
from score_selection import complete, requirement_satisfied


def prepare(rows: list[dict]) -> tuple[list[dict], list[dict], list[dict], dict]:
    """Only development contexts enter the index. Gold remapping is preparation-only."""
    dev = [row for row in rows if row['split'] == 'public_dev']
    if not dev or len({r['id'] for r in dev}) != len(dev):
        raise ValueError('expected nonempty, unique public_dev rows')
    corpus: dict[str, dict] = {}
    labelled = []
    queries = []
    occurrences = 0
    title_variants: dict[str, set[str]] = defaultdict(set)
    for row in dev:
        converted = copy.deepcopy(row)
        remap = {}
        for candidate in converted['candidates']:
            occurrences += 1
            # Preserve sentence boundaries and title identity; same-title variants stay separate.
            identifier = 'pool-' + stable_hash(candidate['title'], candidate['sentences'])
            remap[candidate['id']] = identifier
            candidate['id'] = identifier
            candidate['location'] = {'title': candidate['title']}
            corpus.setdefault(identifier, copy.deepcopy(candidate))
            title_variants[candidate['title']].add(identifier)
        for requirement in converted['evidence_requirements']:
            for alternative in requirement['alternatives']:
                for evidence in alternative['all_of']:
                    evidence['candidate_id'] = remap[evidence['candidate_id']]
        labelled.append(converted)
        queries.append({key: row[key] for key in ('id', 'split', 'language', 'task_type', 'question')})
    return sorted(corpus.values(), key=lambda c: c['id']), queries, labelled, {
        'dev_questions': len(dev), 'input_occurrences': occurrences,
        'unique_passages': len(corpus), 'deduplicated_occurrences': occurrences - len(corpus),
        'same_title_multiple_variants': sum(len(v) > 1 for v in title_variants.values()),
        'test_queries_evaluated': 0, 'test_contexts_added': 0,
    }


class BM25Index:
    def __init__(self, corpus: list[dict]):
        if not corpus or len({c['id'] for c in corpus}) != len(corpus):
            raise ValueError('corpus must be nonempty with unique IDs')
        self.corpus = corpus
        self.postings: dict[str, list[tuple[int, int]]] = defaultdict(list)
        self.lengths = []
        for index, candidate in enumerate(corpus):
            terms = Counter(tokenize(candidate['title'] + '\n' + candidate['text']))
            self.lengths.append(sum(terms.values()))
            for term, count in terms.items():
                self.postings[term].append((index, count))
        self.average = sum(self.lengths) / len(corpus) or 1.0

    def search(self, question: str, limit: int) -> list[tuple[dict, float]]:
        if limit <= 0:
            raise ValueError('limit must be positive')
        scores: dict[int, float] = defaultdict(float)
        for term in dict.fromkeys(tokenize(question)):
            posting = self.postings.get(term, [])
            idf = math.log(1 + (len(self.corpus) - len(posting) + .5) / (len(posting) + .5))
            for index, tf in posting:
                scores[index] += idf * tf * 2.2 / (tf + 1.2 * (.25 + .75 * self.lengths[index] / self.average))
        # Unmatched zero-score passages are not fake retrieval hits.
        ranked = sorted(scores, key=lambda i: (-round(scores[i], 12), self.corpus[i]['id']))[:limit]
        return [(self.corpus[i], round(scores[i], 12)) for i in ranked]


def prepare_command(args: argparse.Namespace) -> None:
    if args.output_dir.exists():
        raise FileExistsError(f'refusing to overwrite {args.output_dir}')
    corpus, queries, labelled, stats = prepare(load_jsonl(args.dataset))
    outputs = {'corpus.jsonl': corpus, 'queries.jsonl': queries, 'gold.jsonl': labelled}
    for filename, rows in outputs.items():
        write_jsonl(args.output_dir / filename, rows)
    write_json(args.output_dir / 'manifest.json', {
        'protocol': 'hotpot-public-dev-pooled-v1', 'source_sha256': sha256_file(args.dataset),
        'statistics': stats, 'license': 'CC-BY-SA-4.0',
        'source': 'https://github.com/hotpotqa/hotpot',
        'artifacts': {name: sha256_file(args.output_dir / name) for name in outputs},
        'code_sha256': sha256_file(Path(__file__)),
        'scope': 'Union of development provided contexts, not official fullwiki or independent test.',
    })
    print(stats)


def retrieve_command(args: argparse.Namespace) -> None:
    if args.output.exists():
        raise FileExistsError(f'refusing to overwrite {args.output}')
    corpus = load_jsonl(args.corpus)
    queries = load_jsonl(args.queries)
    allowed = {'id', 'split', 'language', 'task_type', 'question'}
    if any(set(q) != allowed or q['split'] != 'public_dev' for q in queries):
        raise ValueError('only gold-free public_dev queries are allowed')
    corpus_allowed = {'id', 'document_id', 'version', 'location', 'title', 'sentences', 'text'}
    if any(set(c) != corpus_allowed for c in corpus):
        raise ValueError('unexpected corpus fields')
    index = BM25Index(corpus)
    captured = []
    head = git_head(Path(__file__).resolve().parents[2])
    for query in queries:
        start = time.perf_counter()
        ranked = index.search(query['question'], args.limit)
        elapsed = (time.perf_counter() - start) * 1000
        row = dict(query, candidates=[c for c, _ in ranked])
        result = snapshot(row, sha256_file(args.queries), head)
        for candidate, (_, score) in zip(result['candidates'], ranked):
            candidate['source_ranks'] = {'pooled_bm25': candidate['source_order'] + 1}
            candidate['source_scores'] = {'pooled_bm25': score}
        result['timings_ms']['retrieval'] = elapsed
        result['provenance'].update({
            'index_identity': 'hotpot-public-dev-pool-' + sha256_file(args.corpus),
            'config_identity': f'pooled-bm25-top{args.limit}-local-bm25-rerank-v1',
            'retrieval_model': 'pooled-inverted-bm25-k1=1.2-b=0.75',
            'code_sha256': sha256_file(Path(__file__)),
        })
        captured.append(result)
    write_jsonl(args.output, captured)
    print(f'captured {len(captured)} queries over {len(corpus)} pooled passages')


def diagnose_command(args: argparse.Namespace) -> None:
    if args.output.exists():
        raise FileExistsError(f'refusing to overwrite {args.output}')
    gold = {r['id']: r for r in load_jsonl(args.gold)}
    snapshots = load_jsonl(args.snapshots)
    if len(snapshots) != len(gold) or {s['example_id'] for s in snapshots} != set(gold):
        raise ValueError('diagnosis must cover every development query exactly once')
    details = []
    for snap in snapshots:
        row = gold[snap['example_id']]
        requirements = row['evidence_requirements']
        if not requirements:
            raise ValueError('expected answerable HotpotQA with nonempty support')
        ordered = [c['id'] for c in sorted(snap['candidates'], key=lambda c: c['source_order'])]
        metrics = {}
        for k in (5, 10, 20, 40):
            ids = set(ordered[:k])
            metrics[str(k)] = {'complete': complete(requirements, ids),
                'evidence_recall': sum(requirement_satisfied(r, ids) for r in requirements) / len(requirements)}
        details.append({'id': row['id'], 'task_type': row['task_type'], 'at_k': metrics})
    groups = {'all': details}
    groups.update({kind: [d for d in details if d['task_type'] == kind] for kind in sorted({d['task_type'] for d in details})})
    summary = {name: {'n': len(group), 'at_k': {str(k): {
        'complete_count': sum(d['at_k'][str(k)]['complete'] for d in group),
        'complete_rate': sum(d['at_k'][str(k)]['complete'] for d in group) / len(group),
        'evidence_recall': sum(d['at_k'][str(k)]['evidence_recall'] for d in group) / len(group),
    } for k in (5, 10, 20, 40)}} for name, group in groups.items()}
    write_json(args.output, {'protocol': 'pooled-development-retrieval-diagnostic-v1',
        'summary': summary, 'details': details,
        'gold_sha256': sha256_file(args.gold), 'snapshots_sha256': sha256_file(args.snapshots),
        'limitation': 'Offline lexical retrieval; not production vector retrieval or answer quality.'})
    print(summary)


def classify_command(args: argparse.Namespace) -> None:
    if args.output.exists():
        raise FileExistsError(f'refusing to overwrite {args.output}')
    score = json.loads(args.score.read_text(encoding='utf-8'))
    result = {}
    for name, arm in score['arms'].items():
        if arm['errors']:
            raise ValueError(f'{name}: invalid score report')
        cases: dict[str, list[str]] = defaultdict(list)
        for row in arm['details']:
            category = ('retrieval_missing' if not row['candidate_complete'] else
                'budget_infeasible' if not row['gold_budget_feasible'] else
                'selection_missing' if not row['selected_complete'] else 'evidence_complete')
            cases[category].append(row['id'])
        result[name] = {'n': len(arm['details']),
            'counts': {k: len(cases[k]) for k in ('retrieval_missing', 'budget_infeasible', 'selection_missing', 'evidence_complete')},
            'case_ids': dict(cases), 'answer_correctness': 'not_evaluated'}
    write_json(args.output, {'protocol': 'pooled-dev-stage-attribution-v1', 'arms': result,
        'source_sha256': sha256_file(args.score), 'runtime': 'offline BM25/hashing/token proxies'})
    print({k: v['counts'] for k, v in result.items()})


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    prep = sub.add_parser('prepare')
    prep.add_argument('--dataset', type=Path, required=True)
    prep.add_argument('--output-dir', type=Path, required=True)
    retrieve = sub.add_parser('retrieve')
    retrieve.add_argument('--corpus', type=Path, required=True)
    retrieve.add_argument('--queries', type=Path, required=True)
    retrieve.add_argument('--output', type=Path, required=True)
    retrieve.add_argument('--limit', type=int, default=40, choices=[40])
    diagnose = sub.add_parser('diagnose')
    diagnose.add_argument('--gold', type=Path, required=True)
    diagnose.add_argument('--snapshots', type=Path, required=True)
    diagnose.add_argument('--output', type=Path, required=True)
    classify = sub.add_parser('classify')
    classify.add_argument('--score', type=Path, required=True)
    classify.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    {'prepare': prepare_command, 'retrieve': retrieve_command, 'diagnose': diagnose_command,
        'classify': classify_command}[args.command](args)


if __name__ == '__main__':
    main()
