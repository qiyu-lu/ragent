import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import run_gate_fix_check as gf  # noqa: E402


def metrics(found):
    return {"documents": [{"doc": doc, "facts_found_raw": n, "facts_total": 24} for doc, n in found.items()]}


def report(empty=0, sub_questions=10, extra_stage=None):
    stages = [{"stage": "channel-VectorSearch", "chunkCount": 0 if i < empty else 20} for i in range(sub_questions)]
    if extra_stage:
        stages.append({"stage": extra_stage})
    return {"details": [{"raw_response": {"results": [{}] * sub_questions, "stages": stages}}]}


class ConfigTest(unittest.TestCase):
    def test_the_s2_example_becomes_an_isolated_s2b_config(self):
        config = gf.s2b_config(gf.CONFIG_EXAMPLE.read_text(encoding="utf-8"))
        for expected in ("port: 9095", "unique-name: _kq_s2b\n", "/ragent_eval_kq_s2b?", "database: 15",
                         "rewrites-s2b.jsonl", "ragent-sources-kq-s2b", "ragent-assets-kq-s2b\n"):
            self.assertIn(expected, config)
        for gone in ("port: 9094", "/ragent_eval_kq_s2?", "database: 14", "ragent-assets-kq-s2\n"):
            self.assertNotIn(gone, config)
        self.assertIn("parse-quality:\n      enabled: true", config)
        self.assertIn("metadata-boost:\n      enabled: false", config)

    def test_a_changed_example_is_refused_instead_of_half_rewritten(self):
        with self.assertRaises(RuntimeError):
            gf.s2b_config("server:\n  port: 9094\n")


class ParseLayerTest(unittest.TestCase):
    def test_main_threshold_and_differences(self):
        s1 = metrics({"si": 2, "smp": 3, "tfe": 22})
        s2 = metrics({"si": 19, "smp": 18, "tfe": 23})
        ok = gf.parse_layer_check(metrics({"si": 19, "smp": 17, "tfe": 23}), s1, s2, minimum_total=40, max_loss=1)
        self.assertTrue(ok["passed"])
        self.assertEqual(ok["difference_vs_s2"], {"smp": -1})
        lost = gf.parse_layer_check(metrics({"si": 19, "smp": 18, "tfe": 20}), s1, s2, minimum_total=40, max_loss=1)
        self.assertFalse(lost["passed"])
        self.assertEqual(lost["documents_losing_vs_s1"], {"tfe": 2})
        self.assertFalse(gf.parse_layer_check(metrics({"si": 5, "smp": 3, "tfe": 22}), s1, s2, 40, 1)["passed"])


class IngestionCheckTest(unittest.TestCase):
    S2 = [["si.pdf", "RECOVERED", "-"], ["tfe.pdf", "PASSED", "-"], ["survey.xlsx", "-", "-"]]

    def test_same_verdicts_new_form_and_kept_punctuation_pass(self):
        s2b = [["si.pdf", "RECOVERED", "2"], ["tfe.pdf", "PASSED", "2"], ["survey.xlsx", "-", "2"]]
        result = gf.ingestion_check(s2b, self.S2, ["79", "79", "16"])
        self.assertTrue(result["passed"])
        self.assertEqual(result["survey"], {"chunks": 79, "with_chinese_punctuation": 79, "with_superscripts": 16})

    def test_a_changed_verdict_or_flattened_text_fails(self):
        s2b = [["si.pdf", "NEEDS_REVIEW", "2"], ["tfe.pdf", "PASSED", "2"], ["survey.xlsx", "-", "2"]]
        changed = gf.ingestion_check(s2b, self.S2, ["79", "79", "16"])
        self.assertFalse(changed["passed"])
        self.assertEqual(changed["verdict_mismatches"], {"si.pdf": {"s2": "RECOVERED", "s2b": "NEEDS_REVIEW"}})
        flattened = gf.ingestion_check([row[:2] + ["2"] for row in self.S2], self.S2, ["77", "0", "0"])
        self.assertFalse(flattened["passed"])
        old_form = gf.ingestion_check([row[:2] + ["-"] for row in self.S2], self.S2, ["79", "79", "16"])
        self.assertEqual(old_form["documents_without_normalization_v2"], ["si.pdf", "survey.xlsx", "tfe.pdf"])


class RunCheckTest(unittest.TestCase):
    def test_states(self):
        self.assertEqual(gf.check_report(report(empty=2))[0], "ok")
        self.assertEqual(gf.check_report(report(empty=9, sub_questions=20))[0], "empty")
        self.assertEqual(gf.check_report(report(empty=6, sub_questions=10))[0], "dead")
        self.assertEqual(gf.check_report(report(extra_stage="post-MetadataBoost"))[0], "config")
        self.assertEqual(gf.check_report(report(extra_stage="channel-FullTextSearch"))[0], "config")

    def test_ingestion_complete_only_when_every_document_succeeded(self):
        self.assertTrue(gf.ingestion_complete({"documents": [{"status": "success"}] * 7, "failures": []}))
        self.assertFalse(gf.ingestion_complete({"documents": [{"status": "success"}, {"status": "failed"}],
                                                "failures": ["x"]}))
        self.assertFalse(gf.ingestion_complete(None))


if __name__ == "__main__":
    unittest.main()
