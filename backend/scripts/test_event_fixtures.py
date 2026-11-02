from pathlib import Path
import unittest

HERE = Path(__file__).resolve().parent


class EventFixtureTests(unittest.TestCase):
    def test_five_states_and_named_endpoints(self):
        source = (HERE / 'SeedEventFixtures.java').read_text()
        for value in ('PENDING', 'SUCCESS', 'FINAL_FAILED', 'WAIT_RETRY', 'PAUSED',
                      '积分服务', '库存服务', '内置故障模拟器', '订单服务', '采购服务'):
            self.assertIn('"' + value + '"', source)
        self.assertIn('i < 30', source)
        self.assertIn('i % 5', source)
        self.assertIn('rows.getInt(2) != 6', source)

    def test_only_append_transaction_and_deduplication_guard(self):
        source = (HERE / 'SeedEventFixtures.java').read_text()
        for statement in ('setAutoCommit(false)', 'connection.commit()', 'connection.rollback()',
                          'GET_LOCK', 'ALREADY_EXISTS', 'INSERT INTO event ',
                          'INSERT INTO delivery_task ', 'INSERT INTO delivery_attempt '):
            self.assertIn(statement, source)
        for statement in ('UPDATE ', 'DELETE ', 'TRUNCATE ', 'DROP '):
            self.assertNotIn(statement, source)

    def test_secrets_are_encrypted_and_not_printed(self):
        source = (HERE / 'SeedEventFixtures.java').read_text()
        self.assertIn('new EndpointSigningSecretCipher(masterKey)', source)
        self.assertIn('cipher.encrypt(cipher.generateSecret())', source)
        self.assertNotIn('printStackTrace', source)
        self.assertNotIn('println(masterKey)', source)
        self.assertNotIn('println(password)', source)

    def test_development_ownership_guard_and_no_other_database(self):
        source = (HERE / 'seed_event_fixtures.py').read_text()
        self.assertIn("local.owned(container, config, 'container')", source)
        self.assertIn("local.owned(volume, config, 'volume')", source)
        self.assertIn("ports[0]['HostIp'] == '127.0.0.1'", source)
        self.assertNotIn('hookflow-p103-mysql', source)
        self.assertNotIn('shell=True', source)


if __name__ == '__main__':
    unittest.main()
