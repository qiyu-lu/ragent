from __future__ import annotations

import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from datasetkit import OUTPUT_FILES, canonical, iter_jsonl, sha256_file
from prepare_dataset import prepare_dataset
from verify_prepared import validate_dataset


def answer(unanswerable=False, evidence=None, yes_no=None):
    return {"unanswerable": unanswerable, "evidence": evidence or [], "extractive_spans": [],
            "free_form_answer": "gold-only secret", "yes_no": yes_no,
            "highlighted_evidence": ["annotation highlight must not enter corpus"]}


def paper():
    return {"id": "p1", "title": "Source paper", "abstract": "Real abstract.",
            "full_text": {"section_name": ["Methods", "Results"],
                          "paragraphs": [["First paragraph.\nNew line.", "Second paragraph."], ["Last paragraph."]]},
            "figures_and_tables": {"caption": ["Caption without a table body"], "file": ["table.png"]},
            "qas": {"question_id": ["q1", "q2"], "question": ["Which method?", "Is it valid?"],
                    "answers": [{"answer": [answer(evidence=["First paragraph. New line.", "Caption without a table body"]),
                                            answer(unanswerable=True)],
                                 "annotation_id": ["a1", "a2"], "worker_id": ["w1", "w2"]},
                                {"answer": [answer(yes_no=False)], "annotation_id": ["a3"], "worker_id": ["w3"]}]}}


def paragraph(index, title, text, supporting=True):
    return {"idx": index, "title": title, "paragraph_text": text, "is_supporting": supporting}


def musique_rows():
    first = {"id": "shared", "question": "Which place?", "answer": "gold-only secret",
             "answer_aliases": ["gold alias"], "answerable": True,
             "paragraphs": [paragraph(0, "Alpha", "Alpha source."), paragraph(1, "Beta", "Beta source.")],
             "question_decomposition": [{"id": 1, "question": "gold subquestion", "answer": "gold intermediate",
                                         "paragraph_support_idx": 1}]}
    negative = copy.deepcopy(first)
    negative["answerable"] = False
    negative["paragraphs"][1] = paragraph(1, "Gamma", "Gamma source.", False)
    third = copy.deepcopy(first)
    third["id"] = "other"
    third["paragraphs"] = [paragraph(0, "Beta", "Beta source."), paragraph(1, "Beta", "Different Beta source.")]
    return [first, negative, third]


class DatasetTest(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory(prefix="research-data-test-")
        self.root = Path(self.scratch.name)

    def tearDown(self):
        self.scratch.cleanup()

    def qasper_input(self, rows):
        import pyarrow as pa
        import pyarrow.parquet as pq
        path = self.root / "raw/qasper/train/0000.parquet"
        path.parent.mkdir(parents=True, exist_ok=True)
        pq.write_table(pa.Table.from_pylist(rows), path)
        return path

    def musique_input(self, rows, split="dev"):
        path = self.root / f"raw/musique/data/musique_full_v1.0_{split}.jsonl"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("".join(canonical(row) + "\n" for row in rows), encoding="utf-8")
        return path

    def prepare(self, dataset, name="prepared", **kwargs):
        output = self.root / name
        manifest = prepare_dataset(dataset, "train" if dataset == "qasper" else "dev", self.root,
                                   output, smoke=1, regression=2, **kwargs)
        return output, manifest

    def refresh_fingerprint(self, output, filename):
        p = output / "manifest.json"
        manifest = json.loads(p.read_text())
        f = output / filename
        manifest["outputs"][filename] = {"size_bytes": f.stat().st_size, "sha256": sha256_file(f)}
        p.write_text(canonical(manifest), encoding="utf-8")

    def mutate_first(self, output, filename, change):
        rows = list(iter_jsonl(output / filename))
        change(rows[0])
        (output / filename).write_text("".join(canonical(row) + "\n" for row in rows), encoding="utf-8")
        self.refresh_fingerprint(output, filename)

    def test_hf_parallel_lists_preserve_locations_votes_yes_no_and_unresolved_evidence(self):
        self.qasper_input([paper()])
        output, manifest = self.prepare("qasper")
        report = validate_dataset(output, self.root)
        self.assertEqual(4, report["counts"]["corpus_units"])
        self.assertEqual(2, report["counts"]["questions"])
        rows = list(iter_jsonl(output / "corpus.jsonl"))
        methods = sorted([row for row in rows if row["metadata"].get("section_path") == ["Methods"]],
                         key=lambda row: row["metadata"]["paragraph_index"])
        self.assertEqual(["First paragraph.\nNew line.", "Second paragraph."], [row["text"] for row in methods])
        self.assertEqual([0, 0], [row["metadata"]["section_index"] for row in methods])
        labels = {row["source_question_id"]: row for row in iter_jsonl(output / "questions.jsonl")}
        annotations = labels["q1"]["gold"]["annotations"]
        self.assertEqual([False, True], [item["unanswerable"] for item in annotations])
        self.assertEqual(["resolved", "unresolved"], [item["status"] for item in annotations[0]["evidence"]])
        self.assertIs(False, labels["q2"]["gold"]["annotations"][0]["yes_no"])
        self.assertEqual(1, manifest["statistics"]["questions_mixed"])
        self.assertNotIn("Caption without a table body", (output / "corpus.jsonl").read_text())

    def test_native_sequence_objects_are_also_supported(self):
        row = paper()
        row["full_text"] = [{"section_name": "Methods", "paragraphs": ["Native paragraph."]}]
        row["qas"] = [{"question_id": "q", "question": "Native question?",
                       "answers": [{"answer": answer(evidence=["Native paragraph."]), "annotation_id": "a"}]}]
        self.qasper_input([row])
        output, _ = self.prepare("qasper")
        self.assertEqual(1, validate_dataset(output)["counts"]["questions"])

    def test_musique_keeps_full_pairs_and_deduplicates_by_title_and_exact_content(self):
        self.musique_input(musique_rows())
        output, manifest = self.prepare("musique")
        report = validate_dataset(output, self.root)
        self.assertEqual(3, report["counts"]["questions"])
        self.assertEqual(2, report["counts"]["distinct_source_question_ids"])
        self.assertEqual(4, report["counts"]["corpus_units"])
        self.assertEqual(2, manifest["counts"]["deduplicated_occurrences"])
        labels = list(iter_jsonl(output / "questions.jsonl"))
        pair = [row for row in labels if row["source_question_id"] == "shared"]
        self.assertEqual(2, len({row["id"] for row in pair}))
        self.assertEqual({True, False}, {row["gold"]["answerable"] for row in pair})
        self.assertTrue(all(row["source_extent"] == "AVAILABLE_EXCERPT" for row in iter_jsonl(output / "corpus.jsonl")))
        self.assertTrue(all(row["document_ids"] == [] for row in iter_jsonl(output / "queries.jsonl")))

    def test_distractor_queries_limit_to_original_candidates_without_labels(self):
        self.musique_input(musique_rows())
        output, _ = self.prepare("musique", retrieval_mode="distractor")
        labels = {row["id"]: row for row in iter_jsonl(output / "questions.jsonl")}
        for query in iter_jsonl(output / "queries.jsonl"):
            self.assertEqual(labels[query["id"]]["candidate_ids"], query["document_ids"])
            self.assertNotIn("gold", query)
        self.assertEqual("distractor", validate_dataset(output)["retrieval_mode"])

    def test_gold_mutation_never_changes_corpus_queries_or_sample_identity(self):
        for dataset in ("qasper", "musique"):
            if dataset == "qasper":
                original = [paper()]
                self.qasper_input(original)
            else:
                original = musique_rows()
                self.musique_input(original)
            first, _ = self.prepare(dataset, dataset + "-first")
            changed = copy.deepcopy(original)
            if dataset == "qasper":
                for group in changed[0]["qas"]["answers"]:
                    for item in group["answer"]:
                        item.update(free_form_answer="different gold", unanswerable=not item["unanswerable"], evidence=[])
                self.qasper_input(changed)
            else:
                for row in changed:
                    row.update(answer="different gold", answer_aliases=[], answerable=not row["answerable"], question_decomposition=[])
                    for item in row["paragraphs"]:
                        item["is_supporting"] = not item["is_supporting"]
                self.musique_input(changed)
            second, _ = self.prepare(dataset, dataset + "-second")
            for filename in ("corpus.jsonl", "queries.jsonl", "queries.smoke.jsonl", "queries.regression.jsonl"):
                self.assertEqual((first / filename).read_bytes(), (second / filename).read_bytes(), (dataset, filename))
            self.assertNotEqual((first / "questions.jsonl").read_bytes(), (second / "questions.jsonl").read_bytes())

    def test_reordering_source_rows_keeps_all_prepared_files_and_samples_identical(self):
        rows = musique_rows()
        self.musique_input(rows)
        first, _ = self.prepare("musique", "first")
        self.musique_input(list(reversed(rows)))
        second, _ = self.prepare("musique", "second")
        for filename in OUTPUT_FILES:
            self.assertEqual((first / filename).read_bytes(), (second / filename).read_bytes(), filename)

    def test_mismatched_parallel_lists_fail_and_remove_only_staging(self):
        row = paper()
        row["full_text"]["paragraphs"].pop()
        self.qasper_input([row])
        with self.assertRaisesRegex(ValueError, "different lengths"):
            self.prepare("qasper")
        self.assertFalse((self.root / "prepared").exists())
        self.assertEqual([], list(self.root.glob(".research-prepare-*")))

    def test_duplicate_candidate_indexes_are_rejected(self):
        rows = musique_rows()[:1]
        rows[0]["paragraphs"][1]["idx"] = 0
        self.musique_input(rows)
        with self.assertRaisesRegex(ValueError, "unique"):
            self.prepare("musique")

    def test_duplicate_identical_question_context_is_rejected(self):
        row = musique_rows()[0]
        self.musique_input([row, row])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.prepare("musique")

    def test_existing_outputs_are_preserved(self):
        self.musique_input(musique_rows())
        output, _ = self.prepare("musique")
        before = (output / "manifest.json").read_bytes()
        with self.assertRaises(FileExistsError):
            self.prepare("musique")
        self.assertEqual(before, (output / "manifest.json").read_bytes())

    def test_test_split_requires_opt_in_and_hidden_labels_stay_unknown(self):
        with self.assertRaisesRegex(ValueError, "allow-test"):
            prepare_dataset("musique", "test", self.root, self.root / "test")
        row = musique_rows()[0]
        for key in ("answer", "answer_aliases", "answerable", "question_decomposition"):
            del row[key]
        for item in row["paragraphs"]:
            del item["is_supporting"]
        self.musique_input([row], "test")
        output = self.root / "synthetic-test"
        manifest = prepare_dataset("musique", "test", self.root, output, allow_test=True)
        self.assertIsNone(next(iter_jsonl(output / "questions.jsonl"))["gold"])
        self.assertEqual(1, manifest["statistics"]["questions_unlabelled"])
        validate_dataset(output, self.root)

    def test_validator_rejects_corpus_labels_even_with_refreshed_fingerprint(self):
        self.musique_input(musique_rows())
        output, _ = self.prepare("musique")
        self.mutate_first(output, "corpus.jsonl", lambda row: row["metadata"].update(is_supporting=True))
        with self.assertRaisesRegex(ValueError, "label leakage"):
            validate_dataset(output)

    def test_validator_rejects_query_labels_even_with_refreshed_fingerprint(self):
        self.musique_input(musique_rows())
        output, _ = self.prepare("musique")
        self.mutate_first(output, "queries.jsonl", lambda row: row.update(answer="oracle answer"))
        with self.assertRaisesRegex(ValueError, "planner input"):
            validate_dataset(output)

    def test_validator_rejects_dangling_candidates(self):
        self.musique_input(musique_rows())
        output, _ = self.prepare("musique")
        self.mutate_first(output, "questions.jsonl", lambda row: row["candidate_ids"].append("missing"))
        with self.assertRaisesRegex(ValueError, "missing corpus"):
            validate_dataset(output)

    def test_validator_rejects_sample_order_changes(self):
        self.musique_input(musique_rows())
        output, _ = self.prepare("musique")
        p = output / "questions.regression.jsonl"
        p.write_text("\n".join(reversed(p.read_text().splitlines())) + "\n", encoding="utf-8")
        self.refresh_fingerprint(output, p.name)
        with self.assertRaisesRegex(ValueError, "sample changed"):
            validate_dataset(output)

    def test_validator_detects_changed_original_file(self):
        path = self.musique_input(musique_rows())
        output, _ = self.prepare("musique")
        path.write_text(path.read_text() + "\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "raw input fingerprint"):
            validate_dataset(output, self.root)


if __name__ == "__main__":
    unittest.main()
