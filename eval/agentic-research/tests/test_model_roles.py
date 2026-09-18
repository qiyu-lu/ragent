import json
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evaluate_research import expected_models, resource_summary


class ModelRolesTest(unittest.TestCase):
    def test_legacy_config_freezes_all_roles_and_partial_overrides_use_fallback(self):
        self.assertEqual({'main': 'flash', 'worker': 'flash', 'finalization': 'flash'}, expected_models({'model_id': 'flash'}))
        self.assertEqual({'main': 'max', 'worker': 'flash', 'finalization': 'flash'},
                         expected_models({'model_id': 'flash', 'models_by_role': {'main': 'max'}}))
        for roles in ({'unknown': 'max'}, {'main': ''}, {'worker': None}):
            with self.assertRaises(ValueError):
                expected_models({'model_id': 'flash', 'models_by_role': roles})

    def test_attempt_merge_groups_actual_models_and_keeps_failed_unknown_usage(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = root / 'attempts/0000_MIXED'
            second = root / 'attempts/0001_MIXED'
            first.mkdir(parents=True)
            second.mkdir(parents=True)
            main = {'callId': 'm', 'role': 'main', 'model': 'max', 'status': 'COMPLETED',
                    'usageStatus': 'provider', 'inputTokens': 10, 'outputTokens': 2}
            worker = {'callId': 'w', 'role': 'worker', 'model': 'flash', 'status': 'FAILED', 'usageStatus': 'unknown'}
            final = dict(main, callId='f', role='finalization')
            (first / 'usage.jsonl').write_text('\n'.join(json.dumps({'call': c}) for c in (main, worker)) + '\n')
            (second / 'usage.jsonl').write_text('\n'.join(json.dumps({'call': c}) for c in (main, final)) + '\n')
            summary = resource_summary(root, {})
            self.assertEqual(3, summary['model_requests'])
            self.assertEqual(20, summary['known_input_tokens'])
            self.assertEqual(1, summary['model_usage_unknown'])
            grouped = {(c['role'], c['model']): c for c in summary['model_usage_by_role']}
            self.assertEqual(1, grouped['main', 'max']['requests'])
            self.assertEqual({'FAILED': 1}, grouped['worker', 'flash']['statuses'])
            self.assertEqual(1, grouped['worker', 'flash']['usage_unknown'])
            self.assertIsNone(summary['known_generation_cost_estimate_cny'])

    def test_single_model_price_table_cannot_be_applied_to_mixed_model_profile(self):
        with self.assertRaises(ValueError):
            expected_models({'model_id': 'flash', 'models_by_role': {'main': 'max'}, 'estimate_generation_cost': True})
