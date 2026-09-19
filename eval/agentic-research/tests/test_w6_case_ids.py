from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from datasetkit import stable_id
from w6_case_ids import sample


class SampleTest(unittest.TestCase):
    def test_excludes_seen_sources_keeps_first_version_and_orders_by_hash(self):
        questions = [(f'q{n}', f's{n // 2}') for n in range(40)]
        blocks = sample(questions, {'q0'}, 5, 3, 4)
        picked = [i for block in blocks for i in block]
        self.assertEqual(12, len(set(picked)))
        source = dict(questions)
        self.assertNotIn('s0', {source[i] for i in picked})
        self.assertEqual(len(picked), len({source[i] for i in picked}))
        self.assertEqual(picked, sorted(picked, key=lambda i: stable_id('sample', 5, i)))
        for identifier in picked:
            sibling = next(q for q, s in questions if s == source[identifier] and q != identifier)
            self.assertLess(stable_id('sample', 5, identifier), stable_id('sample', 5, sibling))
        self.assertEqual(blocks, sample(list(reversed(questions)), {'q0'}, 5, 3, 4))

    def test_too_few_questions(self):
        with self.assertRaises(ValueError):
            sample([('q1', 's1')], set(), 1, 1, 2)


if __name__ == '__main__':
    unittest.main()
