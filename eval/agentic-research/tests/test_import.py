"""Import grouping/resume contracts before invoking a paid provider."""
import json
import unittest
import test_datasets as fixtures
from import_corpus import prepare_job


class ImportPreparationTest(unittest.TestCase):
    setUp = fixtures.DatasetTest.setUp
    tearDown = fixtures.DatasetTest.tearDown
    qasper_input = fixtures.DatasetTest.qasper_input
    musique_input = fixtures.DatasetTest.musique_input
    prepare = fixtures.DatasetTest.prepare
    def test_paper_grouping_restores_original_order_and_excludes_gold(self):
        self.qasper_input([fixtures.paper()])
        prepared, _ = self.prepare('qasper')
        output = self.root / 'import'
        result = prepare_job(prepared, output, 'full', 1536, 32, 3)
        docs = [json.loads(line) for line in (output / 'documents.jsonl').open()]
        self.assertEqual(1, result['counts']['documents'])
        self.assertEqual(['abstract', 'full_text', 'full_text', 'full_text'],
                         [unit['metadata']['source_field'] for unit in docs[0]['units']])
        self.assertEqual([0, 1, 0], [unit['metadata']['paragraph_index'] for unit in docs[0]['units'][1:]])
        self.assertEqual(['Real abstract.', 'First paragraph.\nNew line.', 'Second paragraph.', 'Last paragraph.'],
                         [unit['text'] for unit in docs[0]['units']])
        self.assertNotIn('gold', (output / 'documents.jsonl').read_text())

    def test_musique_smoke_keeps_each_candidate_as_an_independent_excerpt(self):
        self.musique_input(fixtures.musique_rows())
        prepared, _ = self.prepare('musique', retrieval_mode='distractor')
        output = self.root / 'import'
        prepare_job(prepared, output, 'smoke', 1536, 32, 3)
        docs = [json.loads(line) for line in (output / 'documents.jsonl').open()]
        expected = set(json.loads((prepared / 'queries.smoke.jsonl').read_text())['document_ids'])
        self.assertEqual(expected, {doc['sourceDocumentId'] for doc in docs})
        self.assertTrue(all(len(doc['units']) == 1 and doc['units'][0]['source_extent'] == 'AVAILABLE_EXCERPT' for doc in docs))
        self.assertNotIn('gold-only secret', (output / 'documents.jsonl').read_text())

    def test_resume_preserves_input_and_rejects_configuration_drift(self):
        self.qasper_input([fixtures.paper()])
        prepared, _ = self.prepare('qasper')
        output = self.root / 'import'
        first = prepare_job(prepared, output, 'full', 1536, 32, 3)
        original = (output / 'documents.jsonl').read_bytes()
        self.assertEqual(first, prepare_job(prepared, output, 'full', 1536, 32, 3))
        with self.assertRaises(ValueError):
            prepare_job(prepared, output, 'full', 1024, 32, 3)
        self.assertEqual(original, (output / 'documents.jsonl').read_bytes())


if __name__ == '__main__':
    unittest.main()
