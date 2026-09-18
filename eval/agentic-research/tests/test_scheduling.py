import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evaluate_research import interleaved_cases

class SchedulingTest(unittest.TestCase):
    def test_counterbalanced_order_and_resumption_keep_each_pair_once(self):
        cases = [{'id': 'q1'}, {'id': 'q2'}, {'id': 'q3'}]
        scheduled = interleaved_cases(cases, list('ABC'), set())
        self.assertEqual('ABCB CACAB'.replace(' ', ''), ''.join(c['mode'] for c in scheduled))
        pairs = [(c['id'], c['mode']) for c in scheduled]
        self.assertEqual(9, len(set(pairs)))
        completed = set(pairs[:4])
        resumed = interleaved_cases(cases, list('ABC'), completed)
        self.assertEqual(pairs[4:], [(c['id'], c['mode']) for c in resumed])
