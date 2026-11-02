import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('seed_demo_events', Path(__file__).with_name('seed_demo_events.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class DemoEventTests(unittest.TestCase):
    def test_thirty_fictional_payloads_with_six_types(self):
        bodies = [module.event_body(i, 7, 'fixture-batch') for i in range(30)]
        self.assertEqual(6, len({b['eventType'] for b in bodies}))
        self.assertEqual(list(range(1, 31)), [b['payload']['sequence'] for b in bodies])
        self.assertTrue(all(b['payload']['fictional'] and b['endpointId'] == 7 for b in bodies))
        self.assertTrue(all('eventId' not in b and 'url' not in b for b in bodies))

    def test_journal_does_not_store_token_or_password(self):
        with tempfile.TemporaryDirectory() as directory:
            seed = module.Seeder({'port': 8080, 'admin_password': 'fixture-password'}, Path(directory)/'record.json')
            seed.token = 'fixture-token'
            seed.save()
            value = seed.path.read_text()
            self.assertNotIn('fixture-token', value)
            self.assertNotIn('fixture-password', value)
            self.assertEqual(0o600, seed.path.stat().st_mode & 0o777)

    def test_does_not_change_shared_simulator_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            seed = module.Seeder({'port':8080}, Path(directory)/'record.json')
            responses = [({'token':'fixture-token'}, 'fixture'), ({'mode':'ALWAYS_500'}, 'fixture')]
            with patch.object(seed, 'api', side_effect=responses) as api:
                with self.assertRaisesRegex(module.local.LocalError, '不自动切换'):
                    seed.create('fixture', 'fixture-password')
                self.assertEqual(2, api.call_count)
                self.assertFalse(seed.path.exists())

    def test_only_admin_api_and_no_cleanup_or_business_key_rotation(self):
        source = Path(module.__file__).read_text()
        for forbidden in ('DELETE FROM', 'UPDATE event', '/api-key/rotate', 'signingSecret='):
            self.assertNotIn(forbidden, source)
        self.assertIn('/api/v1/admin/events/test', source)
        self.assertIn("for index in range(30)", source)
        self.assertIn("local.owned(container", source)


if __name__ == '__main__':
    unittest.main()
