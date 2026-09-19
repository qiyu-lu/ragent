import sys
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from x2_takeover import distributions, spread
from x3_upstream import pool, wilson

HOLD = {"one_terminal_event": True, "no_interrupted_status": True}


def run(recovery, calls=11, status="COMPLETED", invariants=HOLD):
    return {"status": status, "model_calls": calls, "recovery_seconds": recovery, "invariants": invariants}


class WilsonTest(unittest.TestCase):
    def test_known_values(self):
        low, high = wilson(0, 50)
        self.assertEqual(0.0, low)
        self.assertAlmostEqual(0.07135, high, places=5)
        low, high = wilson(41, 50)
        self.assertAlmostEqual(0.6920, low, places=4)
        self.assertAlmostEqual(0.9023, high, places=4)
        self.assertEqual(1.0, wilson(200, 200)[1])

    def test_no_trials(self):
        self.assertEqual((None, None), wilson(0, 0))


class PoolTest(unittest.TestCase):
    def test_adds_seeds_per_tree_and_rate(self):
        cell = {"tree": "after", "rate": 0.5, "recorded": 50, "wrong_completion": 0}
        cells = [dict(cell, seed=7, success=41, honest_failure=9, success_rate=.82),
                 dict(cell, seed=11, success=45, honest_failure=5, success_rate=.9),
                 dict(cell, tree="before", seed=7, success=20, honest_failure=30, success_rate=.4)]
        after = next(p for p in pool(cells) if p["tree"] == "after")
        self.assertEqual((100, 86, [7, 11], [.82, .9]), (after["recorded"], after["success"], after["seeds"], after["per_seed_success_rate"]))
        self.assertEqual(wilson(86, 100), after["success_ci95"])
        self.assertEqual((0.0, wilson(0, 100)[1]), after["wrong_completion_ci95"])


class DistributionTest(unittest.TestCase):
    def test_spread_is_nearest_rank(self):
        self.assertEqual({"n": 20, "p50": 10, "p95": 19, "max": 20}, spread(list(range(20, 0, -1)) + [None]))
        self.assertEqual({"n": 0, "p50": None, "p95": None, "max": None}, spread([]))

    def test_counts_errors_failures_and_takes_slowest_run(self):
        results = [{"scenario": "T1", "repetition": 1, "runs": [run(4.9), run(5.1)]},
                   {"scenario": "T1", "repetition": 2, "runs": [run(4.8, calls=12), run(4.7, invariants={"one_terminal_event": False})]},
                   {"scenario": "T1", "repetition": 3, "error": "RuntimeError: runs did not finish after kill -9"},
                   {"scenario": "T3", "repetition": 1, "sigterm_exit_seconds": 0.9, "runs": [run(0.95, status="PARTIAL")]}]
        summary = distributions(results, clean=10)
        t1 = summary["T1"]
        self.assertEqual((3, 1, 4, 0, 1), (t1["repetitions"], t1["errored"], t1["runs"], t1["runs_not_completed"], t1["runs_invariant_failed"]))
        self.assertEqual({"n": 2, "p50": 4.8, "p95": 5.1, "max": 5.1}, t1["recovery_seconds"])
        self.assertEqual({"1": 3, "2": 1}, t1["duplicate_model_calls"])
        self.assertEqual(1, summary["T3"]["runs_not_completed"])
        self.assertEqual({}, summary["T3"]["duplicate_model_calls"])
        self.assertEqual(0.9, summary["T3"]["sigterm_exit_seconds"]["max"])


if __name__ == "__main__":
    unittest.main()
