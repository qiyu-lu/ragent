import math
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from beir_hybrid import metrics


class BeirMetricTest(unittest.TestCase):
    def test_perfect_and_empty(self):
        self.assertEqual({'recall': 1.0, 'ndcg': 1.0}, metrics(['a', 'b'], {'a': 1, 'b': 1}, 10))
        self.assertEqual({'recall': 0.0, 'ndcg': 0.0}, metrics([], {'a': 1}, 10))

    def test_rank_discount_and_cutoff(self):
        self.assertAlmostEqual(1 / math.log2(3), metrics(['x', 'a'], {'a': 1}, 10)['ndcg'])
        self.assertEqual(0, metrics(['x', 'a'], {'a': 1}, 1)['recall'])

    def test_recall_is_not_hit_rate(self):
        result = metrics(['a'], {'a': 1, 'b': 1}, 10)
        self.assertEqual(0.5, result['recall'])
        self.assertAlmostEqual(1 / (1 + 1 / math.log2(3)), result['ndcg'])
