import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pooled_retrieval import BM25Index, prepare
from prepare_hotpot import convert
from cs_evalkit import bm25_scores


class PooledRetrievalTest(unittest.TestCase):
    def row(self, identifier, title, text):
        return convert({'_id': identifier, 'type': 'bridge', 'question': 'alpha?', 'answer': 'a',
            'context': [[title, [text]]], 'supporting_facts': [[title, 0]]}, 'public_dev')

    def test_dev_only_dedup_and_gold_remapping(self):
        first = self.row('1', 'A', 'alpha')
        duplicate = self.row('2', 'A', 'alpha')
        variant = self.row('3', 'A', 'beta')
        frozen = self.row('4', 'SECRET', 'test-only')
        frozen['split'] = 'public_test'
        before = copy.deepcopy(first)
        corpus, queries, gold, stats = prepare([first, duplicate, variant, frozen])
        self.assertEqual(2, len(corpus))
        self.assertEqual(3, len(queries))
        self.assertEqual(1, stats['same_title_multiple_variants'])
        self.assertEqual(first, before)
        self.assertEqual(gold[0]['candidates'][0]['id'], gold[1]['candidates'][0]['id'])
        self.assertEqual(gold[0]['candidates'][0]['id'], gold[0]['evidence_requirements'][0]['alternatives'][0]['all_of'][0]['candidate_id'])
        self.assertFalse(any('answer' in q or 'evidence_requirements' in q for q in queries))
        self.assertNotIn('SECRET', str(corpus))

    def test_pooled_search_can_find_document_not_in_question_context(self):
        corpus, _, _, _ = prepare([self.row('1', 'A', 'alpha'), self.row('2', 'B', 'unusual beta')])
        result = BM25Index(corpus).search('unusual', 40)
        self.assertEqual(['B'], [c['title'] for c, _ in result])
        self.assertEqual([], BM25Index(corpus).search('unmatchedword', 40))

    def test_index_matches_reference_bm25(self):
        corpus, _, _, _ = prepare([self.row('1', 'A', 'alpha alpha'), self.row('2', 'B', 'alpha beta')])
        expected = bm25_scores('alpha beta', [c['title'] + '\n' + c['text'] for c in corpus])
        actual = {c['id']: score for c, score in BM25Index(corpus).search('alpha beta', 40)}
        for candidate, score in zip(corpus, expected):
            self.assertAlmostEqual(score, actual[candidate['id']], places=10)


if __name__ == '__main__':
    unittest.main()
