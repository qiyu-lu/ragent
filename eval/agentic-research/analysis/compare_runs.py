#!/usr/bin/env python3
"""Offline paired diagnostics for frozen runs; no provider calls or monetary estimates."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import statistics


def rows(path):
    if path.exists():
        with path.open() as handle:
            for line in handle:
                if line.strip():
                    yield json.loads(line)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def keyed(path):
    result = {}
    for row in rows(path):
        key = (row['questionId'], row['mode'])
        if key in result:
            raise ValueError('Duplicate prediction/score: ' + str(key))
        result[key] = row
    return result


def trace_summary(directory):
    events, failures, retry_runs, workers = Counter(), Counter(), set(), set()
    validation, phase_failures = Counter(), Counter()
    seen = set()
    for path in sorted(directory.glob('attempts/*/traces.jsonl')):
        for row in rows(path):
            event = row['event']
            key = (row['runId'], event['sequence'])
            if key in seen:
                continue
            seen.add(key)
            kind, payload = event['type'], event.get('payload', {})
            events[kind] += 1
            if kind in ('TOOL_FAILED', 'TOOL_ERROR'):
                failures[str(payload.get('error', payload.get('reason', payload.get('message', payload))))] += 1
            if kind == 'MODEL_RETRY':
                retry_runs.add(row['runId'])
            if kind == 'FINALIZATION_VALIDATION_FAILED':
                validation[str(payload.get('reason'))] += 1
            if kind == 'RETRIEVAL_PHASE' and payload.get('status') == 'FAILED':
                phase_failures[str(payload.get('phase')) + '/' + str(payload.get('code'))] += 1
            if kind == 'WORKER_CREATED':
                workers.add(row['runId'])
    return {'event_counts':dict(events), 'tool_errors':dict(failures), 'artifact_validation_errors':dict(validation),
            'retrieval_phase_failures':dict(phase_failures), 'model_retry_runs':len(retry_runs),
            'note':'Error events can repeat within one task; counts are not additional failed tasks.'}


def embeddings(directory):
    calls = {}
    for path in sorted(directory.glob('attempts/*/embedding-usage.jsonl')):
        for row in rows(path):
            calls[row['call_id']] = row
    failed = [r for r in calls.values() if not r.get('success')]
    phases = Counter(str(r.get('transport', {}).get('lastPhase', 'not_recorded')) for r in failed)
    return {'requests':len(calls),'successful':sum(bool(r.get('success')) for r in calls.values()),
            'failed_or_unfinished':len(failed), 'failure_last_transport_phase':dict(phases),
            'attempt_numbers':dict(Counter(str(r.get('attempt', 'not_recorded')) for r in calls.values())),
            'inference_boundary':'No response headers after request body does not distinguish provider queueing from return-network failure.'}


def run_summary(directory):
    summary = json.loads((directory/'summary.json').read_text())
    predictions = keyed(directory/'predictions.jsonl')
    scores = keyed(directory/'scores.jsonl')
    if predictions.keys() != scores.keys() or len(predictions) != summary['expected_tasks']:
        raise ValueError('Require a fully recorded run with one score per prediction')
    errors = Counter(p.get('error') for p in predictions.values() if p['status'] == 'FAILED')
    usage = defaultdict(lambda: {'requests':0,'known_input_tokens':0,'known_output_tokens':0,'unknown':0})
    for p in predictions.values():
        for call in p['usage'].get('calls', []):
            item=usage[p['mode']]
            item['requests']+=1
            if call.get('usageStatus') == 'provider':
                item['known_input_tokens']+=call.get('inputTokens') or 0
                item['known_output_tokens']+=call.get('outputTokens') or 0
            else:
                item['unknown']+=1
    return {'run':str(directory),'recorded':len(predictions),'statuses':dict(Counter(p['status'] for p in predictions.values())),
            'failures':dict(errors),'quality':summary['quality'],'execution':summary['execution'],
            'resources':{k:v for k,v in summary['resources'].items() if not any(x in k for x in ('cost','cny','prices','currency'))},
            'model_usage_by_mode':dict(usage),'trace':trace_summary(directory),'embedding':embeddings(directory),
            'artifacts_sha256':{n:digest(directory/n) for n in ('run.json','summary.json','scores.jsonl','predictions.jsonl')}},predictions,scores


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before',required=True,type=Path)
    parser.add_argument('--after',required=True,type=Path)
    parser.add_argument('--repeat',action='append',default=[],type=Path)
    parser.add_argument('--output',required=True,type=Path)
    args=parser.parse_args()
    old,op,os=run_summary(args.before)
    new,np,ns=run_summary(args.after)
    if op.keys()!=np.keys():
        raise ValueError('Before/after must contain identical question-mode pairs')
    paired=defaultdict(list)
    transitions=Counter()
    changes=[]
    for key in sorted(op):
        before,after=os[key],ns[key]
        transitions[op[key]['status']+' -> '+np[key]['status']]+=1
        if before['answer_scored'] != after['answer_scored']:
            raise ValueError('Changed answer-scoring denominator')
        if before['answer_scored']:
            paired[before['dataset']+'/'+key[1]].append((before,after))
        changes.append({'question_id':key[0],'mode':key[1], 'before_status':op[key]['status'],
                        'after_status':np[key]['status'],'answer_scored':before['answer_scored'],
                        'answer_f1_before':before['answer_f1'],'answer_f1_after':after['answer_f1'],
                        'evidence_f1_before':before['evidence_f1'],'evidence_f1_after':after['evidence_f1']})
    deltas={}
    for group,items in paired.items():
        metrics={}
        for metric in ('answer_f1','answer_em','evidence_f1','evidence_recall','read_evidence_recall'):
            values=[a[metric]-b[metric] for b,a in items]
            metrics[metric]={'delta':statistics.mean(values),'improved':sum(v>1e-12 for v in values),
                             'worsened':sum(v< -1e-12 for v in values),'equal':sum(abs(v)<=1e-12 for v in values)}
        deltas[group]={'denominator':len(items),**metrics}
    repeats=[]
    for path in args.repeat:
        summary,pred,score=run_summary(path)
        if not pred.keys()<=np.keys():
            raise ValueError('Repeat questions must belong to main run')
        summary['paired_with_main']={mode:{'tasks':sum(k[1]==mode for k in pred),
             'exact_answer_agreement':sum(pred[k]['answer']==np[k]['answer'] for k in pred if k[1]==mode),
             'same_status':sum(pred[k]['status']==np[k]['status'] for k in pred if k[1]==mode)} for mode in 'ABC'}
        repeats.append(summary)
    result={'schema_version':'research-r5-comparison-v1','script_sha256':digest(Path(__file__)),
            'before':old,'after':new,'status_transitions':dict(transitions),'paired_deltas':deltas,'repeats':repeats,
            'boundaries':['Fixed development regression, not an independent test or industrial corpus.',
                          'Before/after bundles contract, recovery and prompting changes and different provider time periods; not component-level causality.',
                          'A scoped one-shot, B single researcher, C optional delegation; C minus B is not an isolated worker effect.',
                          'Paragraph identity metrics do not certify semantic support. Failures retained in scoring denominators.',
                          'Repeat agreement compares exact answer strings, not semantic equivalence.']}
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,ensure_ascii=False,indent=2,sort_keys=True)+'\n')
    args.output.with_name(args.output.stem+'-paired.jsonl').write_text(''.join(json.dumps(r,ensure_ascii=False)+'\n' for r in changes))
    print(json.dumps({'before':old['statuses'],'after':new['statuses'],'transitions':dict(transitions),'pairs':len(changes)}))

if __name__=='__main__':
    main()
