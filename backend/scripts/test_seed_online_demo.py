"""离线控制流测试，不连接线上服务，也不证明真实 HTTP 投递通过。"""
import copy
import importlib.util
import json
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('seed_online_demo', Path(__file__).with_name('seed_online_demo.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class FakeSeeder(module.Seeder):
    def __init__(self, path, count=6):
        super().__init__(8080, path, count)
        self.current = {'mode': 'ALWAYS_200', 'failFirstN': 0, 'delayMs': 0, 'updatedAt': 'initial'}
        self.stored = {}
        self.endpoints = {}
        self.calls = []
        self.serial = 0

    def api(self, method, path, body=None, expected=200):
        self.calls.append((method, path, copy.deepcopy(body)))
        if path.endswith('/simulator/config'):
            if method == 'PUT':
                self.serial += 1
                self.current = dict(body, updatedAt=str(self.serial))
            return copy.deepcopy(self.current)
        if method == 'GET' and '?' in path:
            query = module.urllib.parse.parse_qs(module.urllib.parse.urlsplit(path).query)
            if 'status' in query:
                return {'total': 0, 'items': []}
            endpoint_id = int(query['endpointId'][0])
            items = [{'eventId': eid} for eid, value in self.stored.items() if value['endpointId'] == endpoint_id]
            return {'total': len(items), 'items': items}
        if path.endswith('/endpoints'):
            endpoint_id = len(self.endpoints) + 1
            self.endpoints[endpoint_id] = dict(body)
            return dict(body, id=endpoint_id, url=None)
        if path.endswith('/disable'):
            endpoint_id = int(path.split('/')[-2])
            self.endpoints[endpoint_id]['enabled'] = False
            return {'enabled': False}
        if path.endswith('/events/test'):
            eid = str(module.uuid.uuid4())
            self.stored[eid] = dict(body, reads=0, redelivered=False)
            return {'eventId': eid, 'endpointId': body['endpointId'], 'duplicate': False}
        if path.endswith('/redeliver'):
            self.stored[path.split('/')[-2]]['redelivered'] = True
            return {}
        eid = path.rsplit('/', 1)[-1]
        value = self.stored[eid]
        group = next(item for item, endpoint_id in self.data['endpoints'].items() if endpoint_id == value['endpointId'])
        value['reads'] += 1
        if group == 'paused':
            state, rounds, codes = ('WAIT_RETRY' if self.endpoints[value['endpointId']]['enabled'] else 'PAUSED'), [1], [[500]]
        elif group == 'direct':
            state, rounds, codes = 'SUCCESS', [1], [[200]]
        elif group == 'retry':
            state, rounds, codes = 'SUCCESS', [2], [[500, 200]]
        elif group == 'redelivered' and value['redelivered']:
            state, rounds, codes = 'SUCCESS', [3, 1], [[500, 500, 500], [200]]
        else:
            state, rounds, codes = 'FINAL_FAILED', [3], [[500, 500, 500]]
        return {'status': state, 'latestRoundNo': len(rounds), 'rounds': [
            {'roundNo': number, 'status': state if number == len(rounds) else 'FINAL_FAILED',
             'attemptCount': count, 'maxAttempts': 3, 'attempts': [
                 {'httpStatus': code, 'result': 'SUCCESS' if code == 200 else 'HTTP_FAILURE'} for code in statuses]}
            for number, (count, statuses) in enumerate(zip(rounds, codes), 1)]}


class Tests(unittest.TestCase):
    def test_payloads_are_fictional_unique_and_six_types(self):
        values = [module.event_body(group, i, 7, 'batch') for group in module.GROUPS for i in range(6)]
        self.assertEqual(30, len({value['payload']['orderNo'] for value in values}))
        self.assertEqual(6, len({value['eventType'] for value in values}))
        self.assertTrue(all(value['payload']['fictional'] for value in values))
        self.assertTrue(all('eventId' not in value and 'url' not in value for value in values))

    def test_full_control_flow_thirty_records_and_restore(self):
        with tempfile.TemporaryDirectory() as directory:
            seed = FakeSeeder(Path(directory) / 'record.json')
            seed.run()
            seed.restore()
            self.assertEqual(30, len(seed.data['events']))
            counts = module.Counter(event['observedStatus'] for event in seed.data['events'])
            self.assertEqual({'SUCCESS': 18, 'FINAL_FAILED': 6, 'PAUSED': 6}, counts)
            self.assertEqual('RESTORED', seed.data['restoration'])
            self.assertEqual('ALWAYS_200', seed.current['mode'])
            self.assertFalse(seed.endpoints[seed.data['endpoints']['paused']]['enabled'])
            self.assertEqual(6, sum(path.endswith('/redeliver') for _, path, _ in seed.calls))
            self.assertFalse(any('api-key' in path for _, path, _ in seed.calls))

    def test_does_not_overwrite_concurrent_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            seed = FakeSeeder(Path(directory) / 'record.json')
            seed.preflight()
            seed.current['updatedAt'] = 'someone-else'
            with self.assertRaises(module.DemoError):
                seed.set_mode('ALWAYS_500')
            with self.assertRaises(module.DemoError):
                seed.restore()
            self.assertFalse(any(method == 'PUT' for method, _, _ in seed.calls))

    def test_existing_active_events_stop_before_writes(self):
        with tempfile.TemporaryDirectory() as directory:
            seed = module.Seeder(8080, Path(directory) / 'record.json', 6)
            with patch.object(seed, 'api', return_value={'total': 1, 'items': []}) as api:
                with self.assertRaises(module.DemoError):
                    seed.preflight()
                self.assertEqual(1, api.call_count)
                self.assertEqual('GET', api.call_args.args[0])
                self.assertIsNone(seed.original_config)

    def test_journal_permissions_and_no_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            seed = module.Seeder(8080, Path(directory) / 'record.json', 6)
            seed.token = 'DO-NOT-SAVE-TOKEN'
            seed.save()
            self.assertNotIn(seed.token, seed.path.read_text())
            self.assertEqual(0o600, stat.S_IMODE(seed.path.stat().st_mode))
            self.assertNotIn('password', seed.path.read_text())

    def test_timed_out_write_is_recorded_and_not_retried(self):
        with tempfile.TemporaryDirectory() as directory:
            seed = module.Seeder(8080, Path(directory) / 'record.json', 6)
            with patch.object(seed.opener, 'open', side_effect=TimeoutError) as opening:
                with self.assertRaises(module.DemoError):
                    seed.api('POST', module.API + '/admin/events/test', {'payload': {'fictional': True}}, 202)
                self.assertEqual(1, opening.call_count)
            data = json.loads(seed.path.read_text())
            self.assertEqual('UNCONFIRMED', data['writes'][-1]['state'])

    def test_rejects_http_redirect(self):
        self.assertIsNone(module.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://example.com/'))

    def test_wrong_attempt_history_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            seed = module.Seeder(8080, Path(directory) / 'record.json', 6)
            detail = {'status': 'SUCCESS', 'latestRoundNo': 1, 'rounds': [
                {'roundNo': 1, 'status': 'SUCCESS', 'maxAttempts': 3, 'attemptCount': 1,
                 'attempts': [{'httpStatus': 500, 'result': 'HTTP_FAILURE'}]}]}
            with self.assertRaises(module.DemoError):
                seed.verify(detail, 'SUCCESS', [1], [[200]])

    def test_failure_cleanup_only_disables_own_endpoints(self):
        with tempfile.TemporaryDirectory() as directory:
            seed = FakeSeeder(Path(directory) / 'record.json', 1)
            seed.endpoint('direct')
            seed.endpoint('failed')
            seed.pause_incomplete()
            self.assertTrue(all(not endpoint['enabled'] for endpoint in seed.endpoints.values()))
            disabled = [path for method, path, _ in seed.calls if path.endswith('/disable')]
            self.assertEqual(['/api/v1/admin/endpoints/1/disable', '/api/v1/admin/endpoints/2/disable'], disabled)

    def test_single_writer_lock(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / 'state'
            fd = module.open_state(state)
            try:
                with self.assertRaises(module.DemoError):
                    module.open_state(state)
            finally:
                module.os.close(fd)


if __name__ == '__main__':
    unittest.main()
