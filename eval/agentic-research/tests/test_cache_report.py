import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from cache_report import load_calls, percentile, report

RATES = {'implicit_hit': 0.2, 'explicit_hit': 0.1, 'explicit_write': 1.25}


def call(call_id, role, task='main', **usage):
    value = {'callId': call_id, 'role': role, 'taskId': task, 'status': 'COMPLETED', 'usageStatus': 'provider'}
    value.update(usage)
    return value


class CacheReportTest(unittest.TestCase):
    def run_directory(self, root):
        attempt = root / 'attempts/0000_MIXED'
        attempt.mkdir(parents=True)
        ledger = [('r1', 'B', call('c1', 'main', inputTokens=1000, outputTokens=5, cachedTokens=0, cacheCreationTokens=900,
                                   cacheType='ephemeral', durationMs=800, firstTokenMs=300)),
                  ('r1', 'B', call('c2', 'main', inputTokens=1100, outputTokens=5, cachedTokens=900, cacheCreationTokens=100,
                                   cacheType='ephemeral', durationMs=600, firstTokenMs=200)),
                  ('r1', 'B', call('f1', 'finalization', inputTokens=500, outputTokens=50, cachedTokens=0)),
                  ('r2', 'C', call('c3', 'main', inputTokens=2000, outputTokens=5, cachedTokens=1280)),
                  ('r2', 'C', dict(call('w1', 'worker', task='worker-1'), status='FAILED', usageStatus='unknown'))]
        (attempt / 'usage.jsonl').write_text(''.join(json.dumps({'runId': r, 'mode': m, 'call': c}) + '\n' for r, m, c in ledger))
        events = [('c1', 'MODEL_STARTED', 10.0), ('c1', 'MODEL_ENDED', 10.5), ('c2', 'MODEL_STARTED', 11.0), ('c2', 'MODEL_ENDED', 11.25)]
        (attempt / 'traces.jsonl').write_text(''.join(json.dumps({'runId': 'r1', 'mode': 'B', 'event': {
            'type': kind, 'createdAt': at, 'payload': {'callId': c}}}) + '\n' for c, kind, at in events))
        (attempt / 'predictions.jsonl').write_text(json.dumps({'mode': 'B', 'elapsedMillis': 42000}) + '\n'
                                                   + json.dumps({'mode': 'C', 'elapsedMillis': 9000}) + '\n')
        return root

    def test_billed_input_uses_hit_and_write_weights_by_cache_type(self):
        with tempfile.TemporaryDirectory() as temporary:
            result = report([('after', self.run_directory(Path(temporary)))], RATES)['runs']['after']['modes']
            b, c = result['B']['all'], result['C']['all']
            self.assertEqual((3, 2600, 900, 1000), (b['calls'], b['inputTokens'], b['cachedTokens'], b['cacheCreationTokens']))
            self.assertAlmostEqual(900 / 2600, b['hitRatio'])
            # c1: 100 + 900*1.25; c2: 100 + 900*0.1 + 100*1.25; f1: 500 at full price
            self.assertEqual(1225 + 315 + 500, b['billedInputTokens'])
            self.assertEqual(2040.0, b['billedInputPerTask'])
            self.assertEqual(2, b['explicitCalls'])
            self.assertEqual(720 + 1280 * 0.2, c['billedInputTokens'])
            self.assertEqual(1, c['usageUnknown'], 'Unknown usage stays in the call count, not in token sums')
            self.assertEqual({'n': 1, 'p50': 42.0, 'p95': 42.0}, result['B']['taskSeconds'])

    def test_calls_are_indexed_per_agent_and_latency_comes_from_traces_and_ledger(self):
        with tempfile.TemporaryDirectory() as temporary:
            calls = {c['runId'] + ':' + c['role'] + ':' + str(c['index']): c for c in load_calls(self.run_directory(Path(temporary)), RATES)}
            self.assertEqual(500, calls['r1:main:1']['traceMs'])
            self.assertEqual(250, calls['r1:main:2']['traceMs'])
            self.assertIn('r1:finalization:1', calls, 'Finalization has its own call index')
            self.assertNotIn('traceMs', calls['r2:main:1'])
            main = report([('after', Path(temporary))], RATES)['runs']['after']['modes']['B']['roles']['main']
            self.assertEqual(0.0, main['byCallIndex']['1']['hitRatio'])
            self.assertAlmostEqual(900 / 1100, main['byCallIndex']['2']['hitRatio'])
            self.assertEqual({'n': 2, 'p50': 200, 'p95': 300}, main['firstTokenMs'])

    def test_rates_are_parameters_and_percentiles_use_nearest_rank(self):
        with tempfile.TemporaryDirectory() as temporary:
            rates = dict(RATES, explicit_hit=0.5, explicit_write=1.0)
            b = report([('after', self.run_directory(Path(temporary)))], rates)['runs']['after']['modes']['B']['all']
            self.assertEqual(1000 + (100 + 450 + 100) + 500, b['billedInputTokens'])
        self.assertEqual(2, percentile([4, 1, 3, 2], 50))
        self.assertEqual(4, percentile([4, 1, 3, 2], 95))
        self.assertIsNone(percentile([], 50))


if __name__ == '__main__':
    unittest.main()
