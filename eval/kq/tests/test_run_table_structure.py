import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import run_gate_fix_check as gf  # noqa: E402
import run_table_structure as ts  # noqa: E402


def comparison(arm, hit_verdict="unproven", mrr_verdict="unproven", hit_delta=0.0, mrr_delta=0.0):
    return {"comparisons": {arm: {"overall_answerable": {
        "hit@5": {"baseline_mean": 0.8, "candidate_mean": 0.8 + hit_delta, "delta": hit_delta, "threshold": 0.019,
                  "verdict": hit_verdict},
        "mrr": {"baseline_mean": 0.7, "candidate_mean": 0.7 + mrr_delta, "delta": mrr_delta, "threshold": 0.022,
                "verdict": mrr_verdict}}}}}


def merged(*parts):
    out = {"comparisons": {}}
    for part in parts:
        out["comparisons"].update(part["comparisons"])
    return out


class ConfigTest(unittest.TestCase):
    def test_each_arm_gets_its_own_database_port_redis_and_names_but_reads_the_s2b_bucket(self):
        s2b = gf.s2b_config(gf.CONFIG_EXAMPLE.read_text(encoding="utf-8"))
        for arm, database, redis, unique in ((ts.T1, "ragent_eval_kq_t1", 11, "_kq_t1"),
                                             (ts.T2, "ragent_eval_kq_t2", 12, "_kq_t2")):
            config = ts.arm_config(s2b, arm)
            for expected in ("port: 9096", f"unique-name: {unique}\n", f"/{database}?", f"database: {redis}",
                             "ragent-sources-kq-s2b", "parse-quality:\n      enabled: true"):
                self.assertIn(expected, config)
            for gone in ("port: 9095", "unique-name: _kq_s2b\n", "/ragent_eval_kq_s2b?", "database: 15",
                         "rewrites-s2b.jsonl"):
                self.assertNotIn(gone, config)
        self.assertIn(ts.CDC_FLAG, ts.ARMS[ts.T2]["flags"])
        self.assertNotIn(ts.CDC_FLAG, ts.ARMS[ts.T1]["flags"])
        self.assertTrue(all(flag.endswith("=false") for flag in ts.ARMS[ts.BASELINE]["flags"]))

    def test_a_changed_s2b_config_is_refused_instead_of_half_rewritten(self):
        with self.assertRaises(RuntimeError):
            ts.arm_config("server:\n  port: 9095\n", ts.T1)


class DecisionTest(unittest.TestCase):
    def test_t2_is_final_when_it_regresses_against_neither(self):
        base = merged(comparison(ts.T1), comparison(ts.T2))
        self.assertEqual(ts.T2, ts.decide(base, base, comparison(ts.T2))["final"])

    def test_t1_is_final_when_t2_regresses_against_t1(self):
        base = merged(comparison(ts.T1), comparison(ts.T2))
        decision = ts.decide(base, base, comparison(ts.T2, mrr_verdict="regressed", mrr_delta=-0.03))
        self.assertEqual(ts.T1, decision["final"])
        self.assertTrue(decision["T2_regressed_vs_T1"])

    def test_none_is_final_when_both_regress_against_s2b(self):
        base = merged(comparison(ts.T1, hit_verdict="regressed"), comparison(ts.T2, mrr_verdict="regressed"))
        self.assertIsNone(ts.decide(base, base, comparison(ts.T2))["final"])

    def test_an_improvement_is_not_a_regression(self):
        base = merged(comparison(ts.T1, hit_verdict="improved", hit_delta=0.05), comparison(ts.T2))
        self.assertFalse(ts.regressed(base, ts.T1))


class TestSplitTest(unittest.TestCase):
    def test_a_fall_beyond_the_test_range_is_reported(self):
        result = ts.test_check(comparison(ts.T2, hit_delta=-0.06, mrr_delta=-0.01), ts.T2, {"hit@5": 0.058, "mrr": 0.048})
        self.assertTrue(result["hit@5"]["test_regressed"])
        self.assertFalse(result["mrr"]["test_regressed"])
        self.assertTrue(result["test_regressed"])

    def test_a_fall_within_the_range_is_not(self):
        result = ts.test_check(comparison(ts.T2, hit_delta=-0.058, mrr_delta=0.02), ts.T2, {"hit@5": 0.058, "mrr": 0.048})
        self.assertFalse(result["test_regressed"])


class LayoutCheckTest(unittest.TestCase):
    def test_old_title_headers_must_be_gone_and_lab_titles_present(self):
        self.assertTrue(ts.layout_check(["0", "3", "83"], ts.T1)["passed"])
        self.assertFalse(ts.layout_check(["2", "3", "83"], ts.T1)["passed"])
        self.assertFalse(ts.layout_check(["0", "0", "77"], ts.T1)["passed"])


class PerQuestionTest(unittest.TestCase):
    def test_means_over_repeats(self):
        with tempfile.TemporaryDirectory() as tmp:
            paths = []
            for repeat, mrr in enumerate((1.0, 0.5, 0.0), start=1):
                path = Path(tmp) / f"r{repeat}.json"
                path.write_text(json.dumps({"details": [
                    {"id": "tbl-01", "score": {"hit@5": 1.0 if mrr else 0.0, "mrr": mrr}},
                    {"id": "tbl-02", "score": {"hit@5": 1.0, "mrr": 1.0}}]}), encoding="utf-8")
                paths.append(str(path))
            result = ts.per_question(paths)
        self.assertEqual({"hit@5": 0.667, "mrr": 0.5}, result["tbl-01"])
        self.assertEqual({"hit@5": 1.0, "mrr": 1.0}, result["tbl-02"])


if __name__ == "__main__":
    unittest.main()
