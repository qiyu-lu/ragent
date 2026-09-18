#!/usr/bin/env python3
"""Offline prompt-cache and call-latency report for frozen research runs; no provider calls.

Billing weights default to the Bailian context-cache document
(https://help.aliyun.com/zh/model-studio/context-cache, read 2026-09-18): implicit hits cost 20% of the
input price, explicit hits 10%, explicit cache writes 125%. They are parameters because they differ by
provider and model; the report records the weights it used. Billed input is expressed in input-token
equivalents, not money.
"""
import argparse
from collections import defaultdict
import json
import math
from pathlib import Path

INDEX_BUCKETS = ('1', '2', '3', '4', '5', '6-8', '9+')


def rows(path):
    if path.exists():
        with path.open() as handle:
            for line in handle:
                if line.strip():
                    yield json.loads(line)


def percentile(values, q):
    """Nearest-rank percentile; None when there is nothing to rank."""
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(1, math.ceil(q / 100 * len(ordered))) - 1]


def bucket(index):
    if index <= 5:
        return str(index)
    return '6-8' if index <= 8 else '9+'


def attempt_mode(path):
    suffix = path.parent.name.split('_')[-1]
    return suffix if suffix in ('A', 'B', 'C') else None


def load_calls(directory, rates):
    """Ledger calls in file order with per-agent call index, billed input and trace-measured duration."""
    started, ended = {}, {}
    for path in sorted(directory.glob('attempts/*/traces.jsonl')):
        for row in rows(path):
            event = row.get('event') or {}
            call = (event.get('payload') or {}).get('callId')
            if call and event.get('createdAt') is not None:
                if event.get('type') == 'MODEL_STARTED':
                    started.setdefault(call, float(event['createdAt']))
                elif event.get('type') == 'MODEL_ENDED':
                    ended.setdefault(call, float(event['createdAt']))
    calls, seen, counters = [], set(), defaultdict(int)
    for path in sorted(directory.glob('attempts/*/usage.jsonl')):
        for row in rows(path):
            call = row.get('call') or {}
            if call.get('callId') in seen:
                continue
            seen.add(call.get('callId'))
            role = call.get('role') or 'unknown'
            counters[row.get('runId'), call.get('taskId'), role] += 1
            known = call.get('usageStatus') == 'provider' and call.get('inputTokens') is not None
            item = {'runId': row.get('runId'), 'mode': row.get('mode') or attempt_mode(path) or 'unknown', 'role': role,
                    'index': counters[row.get('runId'), call.get('taskId'), role], 'known': known,
                    'explicit': call.get('cacheType') == 'ephemeral', 'durationMs': call.get('durationMs'),
                    'firstTokenMs': call.get('firstTokenMs')}
            if call.get('callId') in started and call.get('callId') in ended:
                item['traceMs'] = round((ended[call['callId']] - started[call['callId']]) * 1000)
            if known:
                total, cached = call['inputTokens'], call.get('cachedTokens') or 0
                written = call.get('cacheCreationTokens') or 0
                hit_rate = rates['explicit_hit'] if item['explicit'] else rates['implicit_hit']
                item.update(input=total, cached=cached, written=written,
                            billed=(total - cached - written) + cached * hit_rate + written * rates['explicit_write'])
            calls.append(item)
    return calls


def task_seconds(directory):
    """End-to-end task time per mode from prediction rows, including failed tasks."""
    result = defaultdict(list)
    for path in sorted(directory.glob('attempts/*/predictions.jsonl')):
        for row in rows(path):
            if row.get('elapsedMillis') is not None:
                result[row.get('mode') or attempt_mode(path) or 'unknown'].append(row['elapsedMillis'] / 1000)
    return result


def latency(calls, key):
    values = [c[key] for c in calls if c.get(key) is not None]
    return {'n': len(values), 'p50': percentile(values, 50), 'p95': percentile(values, 95)}


def summarize(calls):
    known = [c for c in calls if c['known']]
    total = sum(c['input'] for c in known)
    cached = sum(c['cached'] for c in known)
    billed = sum(c['billed'] for c in known)
    tasks = len({c['runId'] for c in calls})
    per_call = [c['cached'] / c['input'] for c in known if c['input']]
    by_index = {}
    for name in INDEX_BUCKETS:
        group = [c for c in calls if bucket(c['index']) == name]
        if group:
            group_known = [c for c in group if c['known']]
            group_input = sum(c['input'] for c in group_known)
            by_index[name] = {'calls': len(group), 'hitRatio': sum(c['cached'] for c in group_known) / group_input if group_input else None,
                              'traceMs': latency(group, 'traceMs'), 'firstTokenMs': latency(group, 'firstTokenMs')}
    return {'tasks': tasks, 'calls': len(calls), 'usageUnknown': len(calls) - len(known), 'explicitCalls': sum(c['explicit'] for c in known),
            'inputTokens': total, 'cachedTokens': cached, 'cacheCreationTokens': sum(c['written'] for c in known),
            'hitRatio': cached / total if total else None, 'billedInputTokens': round(billed, 1),
            'billedRatio': billed / total if total else None, 'billedInputPerTask': round(billed / tasks, 1) if tasks else None,
            'perCallHitRatio': {q: percentile(per_call, int(q[1:])) for q in ('p10', 'p50', 'p90')},
            'traceMs': latency(calls, 'traceMs'), 'durationMs': latency(calls, 'durationMs'),
            'firstTokenMs': latency(calls, 'firstTokenMs'), 'byCallIndex': by_index}


def report(runs, rates):
    result = {'rates': rates, 'runs': {}}
    for label, directory in runs:
        calls = load_calls(directory, rates)
        seconds = task_seconds(directory)
        modes = {}
        for mode in sorted({c['mode'] for c in calls}):
            selected = [c for c in calls if c['mode'] == mode]
            modes[mode] = {'all': summarize(selected),
                           'roles': {role: summarize([c for c in selected if c['role'] == role])
                                     for role in sorted({c['role'] for c in selected})},
                           'taskSeconds': {'n': len(seconds[mode]), 'p50': percentile(seconds[mode], 50),
                                           'p95': percentile(seconds[mode], 95)}}
        result['runs'][label] = {'directory': str(directory), 'modes': modes}
    return result


def markdown(result):
    def pct(value):
        return '-' if value is None else f'{value * 100:.2f}%'

    def num(value):
        return '-' if value is None else f'{value:,.0f}'

    lines = ['| run | mode | tasks | calls | input | cached | hit | written | billed/input | billed/task | call ms p50/p95 | first token ms p50/p95 | task s p50/p95 |',
             '| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | --- |']
    for label, run in result['runs'].items():
        for mode, data in run['modes'].items():
            s, t = data['all'], data['taskSeconds']
            lines.append(f"| {label} | {mode} | {s['tasks']} | {s['calls']} | {num(s['inputTokens'])} | {num(s['cachedTokens'])} | {pct(s['hitRatio'])} "
                         f"| {num(s['cacheCreationTokens'])} | {pct(s['billedRatio'])} | {num(s['billedInputPerTask'])} "
                         f"| {num(s['traceMs']['p50'])}/{num(s['traceMs']['p95'])} | {num(s['firstTokenMs']['p50'])}/{num(s['firstTokenMs']['p95'])} "
                         f"| {num(t['p50'])}/{num(t['p95'])} |")
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--run', action='append', required=True, metavar='LABEL=DIR', help='run directory to report, repeatable')
    parser.add_argument('--implicit-hit-rate', type=float, default=0.2)
    parser.add_argument('--explicit-hit-rate', type=float, default=0.1)
    parser.add_argument('--explicit-write-rate', type=float, default=1.25)
    parser.add_argument('--out', type=Path, help='write the full JSON summary here')
    args = parser.parse_args()
    runs = []
    for value in args.run:
        label, separator, directory = value.partition('=')
        if not separator or not Path(directory).is_dir():
            raise SystemExit('--run expects LABEL=DIR with an existing directory: ' + value)
        runs.append((label, Path(directory)))
    result = report(runs, {'implicit_hit': args.implicit_hit_rate, 'explicit_hit': args.explicit_hit_rate,
                           'explicit_write': args.explicit_write_rate})
    if args.out:
        args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + '\n')
    print(markdown(result))


if __name__ == '__main__':
    main()
