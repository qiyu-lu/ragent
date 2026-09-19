import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from quality_diff import abstained, bootstrap, classify, compare, load_arm, load_gold

BUDGET = {'model_calls': 16, 'tool_calls': 24}


def prediction(answer, status='COMPLETED', answerable=True):
    return {'answer': answer, 'status': status, 'predicted_answerable': answerable}


def write_rows(path, values):
    path.write_text(''.join(json.dumps(v) + '\n' for v in values))


def run_directory(root, answers, searched=(), compacted=()):
    """answers: questionId -> (answer, answer_f1, main calls); one run per question."""
    attempt = root / 'attempts/0000_C'
    attempt.mkdir(parents=True)
    (root / 'run.json').write_text(json.dumps({'identity': {'config': {'runtime_budget': BUDGET}}}))
    write_rows(root / 'scores.jsonl', [{'questionId': q, 'answer_scored': True, 'answer_f1': f1, 'answer_em': float(f1 == 1)}
                                       for q, (_, f1, _) in answers.items()])
    write_rows(root / 'predictions.jsonl', [
        {'questionId': q, 'runId': 'run-' + q, 'status': 'COMPLETED', 'answer': text, 'predicted_answerable': True,
         'read_source_ids': ['p1'], 'usage': {'modelCalls': calls + 1, 'toolCalls': calls,
                                              'calls': [{'role': 'main'}] * calls + [{'role': 'finalization'}]}}
        for q, (text, _, calls) in answers.items()])
    events = [{'runId': 'run-' + q, 'event': {'type': 'TOOL_ENDED', 'payload': {
        'tool': 'search_knowledge', 'output': json.dumps([{'text': text}])}}} for q, text in searched]
    events += [{'runId': 'run-' + q, 'event': {'type': 'CONTEXT_COMPACTED', 'payload': {}}} for q in compacted]
    events.append({'runId': 'run-q1', 'event': {'type': 'TOOL_ENDED', 'payload': {'tool': 'read_source', 'output': '{'}}})
    write_rows(attempt / 'traces.jsonl', events)
    return root


class ClassifyTest(unittest.TestCase):
    def test_abstention_wording_and_missing_sections(self):
        self.assertTrue(abstained(prediction('The provided sources do not contain the name.')))
        self.assertTrue(abstained(prediction('Unanswerable', answerable=False)))
        self.assertTrue(abstained(prediction('')))
        self.assertFalse(abstained(prediction('Francisco Guterres')))
        self.assertFalse(abstained(prediction('', status='FAILED')))

    def test_classes(self):
        gold = ['100']
        self.assertEqual('failed', classify(prediction('100'), prediction('', status='FAILED'), gold))
        self.assertEqual('abstained', classify(prediction('Mido'), prediction('The sources do not say.'), ['Mido']))
        self.assertEqual('still_abstained', classify(prediction('Sources do not say where.'),
                                                     prediction('Sources cannot answer.'), ['Mido']))
        self.assertEqual('format', classify(prediction('100 (2017)'), prediction('100th (2017)'), gold))
        self.assertEqual('format', classify(prediction('December 13, 1642'), prediction('1642'), ['13 December 1642']))
        self.assertEqual('wrong', classify(prediction('Mario Andretti'), prediction('A. J. Foyt'), ['Mario Andretti']))


class CompareTest(unittest.TestCase):
    def test_pairs_classifies_and_compares_abstentions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            before = load_arm([run_directory(root / 'before', {
                'q1': ('Mido', 1.0, 7), 'q2': ('100', 1.0, 3), 'q3': ('Paris', 0.0, 4)},
                searched=[('q1', 'Mido  played for\nAjax.'), ('q1', 'unrelated')])])
            after = load_arm([run_directory(root / 'after', {
                'q1': ('The sources do not say.', 0.0, 5), 'q2': ('100th', 0.0, 3), 'q3': ('Lyon', 1.0, 4)},
                compacted=['q1', 'q1'])])
            (root / 'questions.jsonl').write_text(''.join(json.dumps({'id': q, 'dataset': 'musique', 'gold': {
                'answer': a, 'answer_aliases': [], 'answerable': True, 'support_ids': ['p1', 'p2']}}) + '\n'
                for q, a in (('q1', 'Mido'), ('q2', '100'), ('q3', 'Lyon'))))
            write_rows(root / 'corpus.jsonl', [{'id': 'p1', 'text': 'Mido played for Ajax.'}, {'id': 'p2', 'text': 'Other.'}])
            result = compare(before, after, load_gold([root / 'questions.jsonl'], root / 'corpus.jsonl'), resamples=200)
        self.assertEqual(3, result['paired'])
        self.assertEqual((1, 2), (result['up'], result['down']))
        self.assertEqual({'failed': 0, 'abstained': 1, 'still_abstained': 0, 'format': 1, 'wrong': 0}, result['dropClasses'])
        self.assertEqual((0, 1), (result['before']['abstained'], result['after']['abstained']))
        self.assertEqual((0, 1), (result['before']['compactedTasks'], result['after']['compactedTasks']))
        [row] = result['abstentions']
        self.assertEqual({'goldRetrieved': 1, 'goldRead': 1, 'goldTotal': 2, 'mainCalls': 7, 'compacted': 0,
                          'modelCallsLeft': 8, 'toolCallsLeft': 17},
                         {k: row['before'][k] for k in ('goldRetrieved', 'goldRead', 'goldTotal', 'mainCalls', 'compacted',
                                                        'modelCallsLeft', 'toolCallsLeft')})
        self.assertEqual((0, 2, 5, True), (row['after']['goldRetrieved'], row['after']['compacted'],
                                           row['after']['mainCalls'], row['after']['abstained']))
        self.assertAlmostEqual(-1 / 3, result['meanDiff'], places=4)

    def test_later_directories_replace_earlier_answers(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            arm = load_arm([run_directory(root / 'one', {'q1': ('A', 0.0, 2)}),
                            run_directory(root / 'two', {'q1': ('B', 1.0, 2), 'q2': ('C', 1.0, 2)})])
        self.assertEqual(('B', 2), (arm['q1']['answer'], len(arm)))


class BootstrapTest(unittest.TestCase):
    def test_deterministic_and_degenerate(self):
        self.assertEqual([0.0, 0.0], bootstrap([0.0] * 10, 1, 100))
        values = [-1.0, 0.0, 0.5, 0.2, -0.3]
        self.assertEqual(bootstrap(values, 7, 500), bootstrap(values, 7, 500))
        low, high = bootstrap(values, 7, 500)
        self.assertTrue(-1.0 <= low <= sum(values) / 5 <= high <= 0.5)
        self.assertIsNone(bootstrap([], 1, 10))


if __name__ == '__main__':
    unittest.main()
