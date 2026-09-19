#!/usr/bin/env python3
"""Per-question answer changes between two frozen research runs; offline, no provider calls.

Pairs the scored answerable questions of a "before" and an "after" run (each may be several run
directories, e.g. interleaved blocks), reports the paired answer-F1 difference with a percentile
bootstrap, classifies every question whose F1 dropped, and compares both arms on every question
that either arm abstained on. The classes are for attribution only; EM / F1 keep the dataset's
official definitions (scoring.py).

  failed     after run did not finish as COMPLETED / PARTIAL
  abstained  after answered "the sources do not ..." (or no answer section) where before did not
  still_abstained  both arms abstained; the F1 change is wording inside two refusals
  format     after names the same thing in another form: one normalized answer's tokens contain the
             other's, against the before answer or a gold reference ("100" / "100th",
             "1642" / "December 13, 1642"); ordinal suffixes are ignored
  wrong      anything else

Per abstention the report shows, for each arm: how many gold support paragraphs appeared in any
search result (search results carry database IDs, so they are matched by text against the prepared
corpus) and how many were read, main / tool calls, CONTEXT_COMPACTED events and the
model-call / tool-call budget left at the end (runtime_budget of the run's recorded config).
"""
import argparse
from collections import Counter, defaultdict
import json
from pathlib import Path
import random
import re

from scoring import GOOD, normalize

ABSTAIN = re.compile(r"\b(do|does|did) not\b|\bcannot\b|\bcan't\b|\bnot found\b|\bunanswerable\b|\bno information\b", re.I)
ORDINAL = re.compile(r"\b(\d+)(st|nd|rd|th)\b")
CLASSES = ('failed', 'abstained', 'still_abstained', 'format', 'wrong')
SNIPPET = 100


def rows(path):
    if path.exists():
        with path.open() as handle:
            for line in handle:
                if line.strip():
                    yield json.loads(line)


def abstained(prediction):
    if prediction['status'] not in GOOD:
        return False
    answer = prediction.get('answer') or ''
    return prediction.get('predicted_answerable') is False or not answer.strip() or bool(ABSTAIN.search(answer))


def tokens(text):
    return set(ORDINAL.sub(r'\1', normalize(text)).split())


def same_thing(a, b):
    x, y = tokens(a), tokens(b)
    return bool(x) and bool(y) and (x <= y or y <= x)


def classify(before, after, references):
    if after['status'] not in GOOD:
        return 'failed'
    if abstained(after):
        return 'still_abstained' if abstained(before) else 'abstained'
    if any(same_thing(after['answer'], other) for other in [before['answer'], *references]):
        return 'format'
    return 'wrong'


def snippet(text):
    return ' '.join(text.split())[:SNIPPET]


def trace_facts(directory, run_ids):
    """Per runId: text snippets of search results and CONTEXT_COMPACTED count."""
    retrieved, compacted = defaultdict(set), Counter()
    for path in sorted(directory.glob('attempts/*/traces.jsonl')):
        for row in rows(path):
            run = row.get('runId')
            if run not in run_ids:
                continue
            event = row.get('event') or {}
            payload = event.get('payload') or {}
            if event.get('type') == 'CONTEXT_COMPACTED':
                compacted[run] += 1
            elif event.get('type') == 'TOOL_ENDED' and payload.get('tool') == 'search_knowledge':
                try:
                    retrieved[run] |= {snippet(r.get('text', '')) for r in json.loads(payload.get('output') or '[]')}
                except (ValueError, AttributeError):
                    pass
    return retrieved, compacted


def load_arm(directories):
    """questionId -> prediction with its scores, trace facts and budget; later directories win."""
    arm = {}
    for directory in directories:
        directory = Path(directory)
        budget = json.loads((directory / 'run.json').read_text())['identity']['config']['runtime_budget']
        scores = {row['questionId']: row for row in rows(directory / 'scores.jsonl')}
        predictions = {row['questionId']: row for row in rows(directory / 'predictions.jsonl')}
        retrieved, compacted = trace_facts(directory, {p['runId'] for p in predictions.values()})
        for identifier, prediction in predictions.items():
            if identifier not in scores:
                continue
            calls = prediction['usage'].get('calls') or []
            arm[identifier] = {**prediction, 'score': scores[identifier], 'directory': str(directory),
                               'retrieved': retrieved[prediction['runId']], 'compacted': compacted[prediction['runId']],
                               'mainCalls': sum(c.get('role') == 'main' for c in calls),
                               'modelCallsLeft': budget['model_calls'] - prediction['usage'].get('modelCalls', 0),
                               'toolCallsLeft': budget['tool_calls'] - prediction['usage'].get('toolCalls', 0)}
    return arm


def bootstrap(diffs, seed, resamples):
    if not diffs:
        return None
    rng, n = random.Random(seed), len(diffs)
    means = sorted(sum(diffs[rng.randrange(n)] for _ in range(n)) / n for _ in range(resamples))
    return [means[int(0.025 * resamples)], means[int(0.975 * resamples) - 1]]


def arm_view(item, support, texts):
    seen = [s for s in item['retrieved'] if s]
    retrieved = {i for i in support if any(s in texts.get(i, '') for s in seen)}
    return {'answer': item['answer'], 'status': item['status'], 'f1': item['score']['answer_f1'],
            'abstained': abstained(item), 'goldRetrieved': len(retrieved),
            'goldRead': len(support & set(item['read_source_ids'])), 'goldTotal': len(support),
            'mainCalls': item['mainCalls'], 'toolCalls': item['usage'].get('toolCalls', 0),
            'compacted': item['compacted'], 'modelCallsLeft': item['modelCallsLeft'],
            'toolCallsLeft': item['toolCallsLeft']}


def compare(before, after, gold, seed=20260918, resamples=10000):
    """gold: questionId -> {'references': [...], 'support_ids': [...], 'texts': {id: text}} for answerable questions."""
    paired = sorted(i for i in before.keys() & after.keys() & gold.keys()
                    if before[i]['score'].get('answer_scored') and after[i]['score'].get('answer_scored'))
    diffs = [after[i]['score']['answer_f1'] - before[i]['score']['answer_f1'] for i in paired]
    drops, abstentions = [], []
    for i, diff in zip(paired, diffs):
        support = set(gold[i]['support_ids'])
        if diff < 0:
            drops.append({'questionId': i, 'class': classify(before[i], after[i], gold[i]['references']),
                          'diff': round(diff, 4), 'gold': gold[i]['references'][0],
                          'before': before[i]['answer'], 'after': after[i]['answer']})
        if abstained(before[i]) or abstained(after[i]):
            abstentions.append({'questionId': i, 'gold': gold[i]['references'][0],
                                'before': arm_view(before[i], support, gold[i]['texts']),
                                'after': arm_view(after[i], support, gold[i]['texts'])})
    n = len(paired)

    def arm_totals(arm):
        items = [arm[i] for i in paired]
        return {'answerF1': round(sum(x['score']['answer_f1'] for x in items) / n, 4) if n else None,
                'answerEM': round(sum(x['score']['answer_em'] for x in items) / n, 4) if n else None,
                'abstained': sum(abstained(x) for x in items),
                'compactedTasks': sum(x['compacted'] > 0 for x in items)}

    interval = bootstrap(diffs, seed, resamples)
    return {'paired': n, 'meanDiff': round(sum(diffs) / n, 4) if n else None,
            'bootstrap95': [round(v, 4) for v in interval] if interval else None,
            'bootstrap': {'seed': seed, 'resamples': resamples, 'method': 'percentile over question resamples'},
            'up': sum(d > 0 for d in diffs), 'down': len(drops),
            'dropClasses': {c: sum(d['class'] == c for d in drops) for c in CLASSES},
            'before': arm_totals(before), 'after': arm_totals(after),
            'drops': drops, 'abstentions': abstentions}


def load_gold(paths, corpus=None):
    gold = {}
    for path in paths:
        for row in rows(Path(path)):
            g = row.get('gold') or {}
            if row.get('dataset') == 'musique' and g.get('answerable'):
                gold[row['id']] = {'references': [g['answer'], *g['answer_aliases']], 'support_ids': g['support_ids'], 'texts': {}}
    if corpus:
        wanted = {i: q for q in gold.values() for i in q['support_ids']}
        for row in rows(Path(corpus)):
            if row['id'] in wanted:
                for q in gold.values():
                    if row['id'] in q['support_ids']:
                        q['texts'][row['id']] = ' '.join(row['text'].split())
    return gold


def markdown(result):
    b, a = result['before'], result['after']
    lines = [f"Paired answerable questions: {result['paired']}; answer F1 {b['answerF1']} -> {a['answerF1']}, "
             f"mean diff {result['meanDiff']}, 95% bootstrap {result['bootstrap95']}; up {result['up']}, down {result['down']}",
             f"Abstained on answerable: {b['abstained']} -> {a['abstained']}; tasks with CONTEXT_COMPACTED: "
             f"{b['compactedTasks']} -> {a['compactedTasks']}",
             'Drop classes: ' + ', '.join(f'{c} {n}' for c, n in result['dropClasses'].items()), '',
             '| class | diff | gold | before | after |', '| --- | --- | --- | --- | --- |']
    clip = lambda text: (text or '').replace('|', '/').replace('\n', ' ')[:70]
    for d in sorted(result['drops'], key=lambda d: (CLASSES.index(d['class']), d['diff'])):
        lines.append(f"| {d['class']} | {d['diff']} | {clip(d['gold'])} | {clip(d['before'])} | {clip(d['after'])} |")
    lines += ['', 'Abstentions (either arm): gold retrieved / read of total, main calls, tool calls, compactions, '
              'model / tool calls left', '', '| question | gold | arm | abstained | F1 | retrieved | read | main | tools | compacted | left |',
              '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |']
    for x in result['abstentions']:
        for name in ('before', 'after'):
            v = x[name]
            lines.append(f"| {x['questionId'][:11]} | {clip(x['gold'])[:30]} | {name} | {'yes' if v['abstained'] else 'no'} | "
                         f"{v['f1']:.2f} | {v['goldRetrieved']}/{v['goldTotal']} | {v['goldRead']}/{v['goldTotal']} | "
                         f"{v['mainCalls']} | {v['toolCalls']} | {v['compacted']} | {v['modelCallsLeft']}/{v['toolCallsLeft']} |")
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--before', nargs='+', required=True, metavar='DIR', help='run directories of the before arm')
    parser.add_argument('--after', nargs='+', required=True, metavar='DIR', help='run directories of the after arm')
    parser.add_argument('--questions', nargs='+', required=True, type=Path, help='prepared questions*.jsonl with gold')
    parser.add_argument('--corpus', type=Path, help='prepared corpus.jsonl, to match search results to gold paragraphs')
    parser.add_argument('--seed', type=int, default=20260918)
    parser.add_argument('--resamples', type=int, default=10000)
    parser.add_argument('--out', type=Path, help='write the full JSON result here')
    args = parser.parse_args()
    result = compare(load_arm(args.before), load_arm(args.after), load_gold(args.questions, args.corpus), args.seed, args.resamples)
    result['runs'] = {'before': args.before, 'after': args.after}
    if args.out:
        args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(markdown(result))


if __name__ == '__main__':
    main()
