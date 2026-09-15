import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from score_live_pooled import evidence_mapping, full_support, score
from merge_live_pooled import merge


class LiveCoverageTest(unittest.TestCase):
    def test_merge_retains_failed_first_attempt_and_rejects_replacement(self):
        queries = [{'id': 'a', 'split': 'public_dev', 'question': 'a'},
            {'id': 'b', 'split': 'public_dev', 'question': 'b'}]
        failed = {'id': 'a', 'error': 'timeout', 'response': None}
        success = {'id': 'b', 'error': None, 'response': {'question': 'b', 'rewriteEnabled': False}}
        self.assertEqual([failed, success], merge(queries, [[failed], [success]]))
        with self.assertRaises(ValueError):
            merge(queries, [[failed], [failed, success]])
        with self.assertRaises(ValueError):
            merge(queries, [[success]])

    def setUp(self):
        self.gold = [{'id': 'q', 'candidates': [{'id': 'source', 'sentences': ['A fact.']}],
            'evidence_requirements': [{'alternatives': [{'all_of': [
                {'candidate_id': 'source', 'location': {'sentence_id': 0}}]}]}]}]
        self.docs = [{'pool_id': 'source', 'chunks': [{'id': 'chunk', 'content': 'A\n fact.'}]}]

    def test_whitespace_normalized_but_wrong_source_rejected(self):
        mapping = evidence_mapping(self.gold, self.docs)
        self.assertTrue(full_support(self.gold[0], {'chunk'}, mapping))
        wrong = [{'pool_id': 'other', 'chunks': [{'id': 'chunk', 'content': 'A fact.'}]}]
        self.assertFalse(full_support(self.gold[0], {'chunk'}, evidence_mapping(self.gold, wrong)))

    def test_selection_loss_distinct_from_missing_ingestion(self):
        capture = {'id': 'q', 'response': {'stages': [
            {'stage': 'channel-vector', 'chunks': [{'id': 'chunk'}]},
            {'stage': 'request-final', 'chunks': []}]}}
        self.assertEqual(1, score(self.gold, [capture], self.docs)['counts']['selection_missing'])
        empty = {'id': 'q', 'response': {'stages': []}}
        self.assertEqual(1, score(self.gold, [empty], [])['counts']['ingestion_or_mapping_unavailable'])

    def test_failed_calls_and_unknown_chunks_not_success(self):
        capture = {'id': 'q', 'error': 'timeout', 'response': None}
        self.assertEqual(1, score(self.gold, [capture], self.docs)['counts']['service_failure'])
        capture = {'id': 'q', 'response': {'stages': [{'stage': 'request-final', 'chunks': [{'id': 'foreign'}]}]}}
        with self.assertRaises(ValueError):
            score(self.gold, [capture], self.docs)
        with self.assertRaises(ValueError):
            score(self.gold, [capture, capture], self.docs)


if __name__ == '__main__':
    unittest.main()
