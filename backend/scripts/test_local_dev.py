import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('local_dev', Path(__file__).with_name('local_dev.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def fixture():
    return dict(version=1, owner='a'*32, container='hookflow-local-'+'a'*12,
                volume='hookflow-local-data-'+'a'*32, state='new', database='hookflow_dev',
                db_user='hookflow_dev', db_password='b'*43, root_password='c'*43,
                admin_user='fixture-admin', admin_password='fixture-password',
                master_key=module.base64.b64encode(b'd'*32).decode(), port=8080)


class LocalDevTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.private = Path(self.temporary.name) / 'private'
        self.patcher = patch.object(module, 'PRIVATE', self.private)
        self.patcher.start()

    def tearDown(self):
        self.patcher.stop()
        self.temporary.cleanup()

    def test_private_roundtrip_is_stable_and_not_printed(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            module.private_directory()
            config = fixture()
            module.save(config)
            self.assertEqual(config, module.load())
            module.save(config)
            self.assertEqual(config['master_key'], module.load()['master_key'])
        self.assertEqual('', output.getvalue())
        self.assertEqual(0o700, stat.S_IMODE(self.private.stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE((self.private/'config.json').stat().st_mode))
        self.assertEqual(['config.json'], [p.name for p in self.private.iterdir()])

    def test_insecure_or_symlink_config_is_rejected(self):
        module.private_directory()
        module.save(fixture())
        path = self.private/'config.json'
        path.chmod(0o644)
        with self.assertRaises(module.LocalError): module.load()
        path.unlink()
        original = Path(self.temporary.name)/'other'
        original.write_text('{}')
        path.symlink_to(original)
        with self.assertRaises(module.LocalError): module.load()
        with self.assertRaises(module.LocalError): module.save(fixture())

    def test_resource_identity_rejects_original_container(self):
        config = fixture()
        other = {'Config': {'Labels': {module.LABEL: 'different'}, 'Image': module.IMAGE}}
        with self.assertRaisesRegex(module.LocalError, '归属不符'):
            module.owned(other, config, 'container')

    def test_missing_persistent_volume_is_not_recreated(self):
        config = fixture()
        config['state'] = 'ready'
        with patch.object(module, 'docker_ready'), patch.object(module, 'inspect', return_value=None), patch.object(module, 'call') as call:
            with self.assertRaisesRegex(module.LocalError, '原开发卷缺失'):
                module.ensure_database(config, create=True)
            call.assert_not_called()

    def test_application_env_overrides_other_database_and_profiles(self):
        original = {'DB_URL': 'other', 'DB_PASSWORD': 'other', 'SPRING_PROFILES_ACTIVE': 'skeleton',
                    'HOOKFLOW_SECRET_ENCRYPTION_KEY': 'other', 'JAVA_TOOL_OPTIONS': 'unsafe', 'PATH': '/bin'}
        with patch.dict(os.environ, original, clear=True):
            env = module.application_environment(fixture(), 14000)
        self.assertIn('127.0.0.1:14000/hookflow_dev', env['DB_URL'])
        self.assertEqual('b'*43, env['DB_PASSWORD'])
        self.assertEqual('false', env['HOOKFLOW_REDIS_ENABLED'])
        self.assertNotIn('SPRING_PROFILES_ACTIVE', env)
        self.assertNotIn('JAVA_TOOL_OPTIONS', env)
        self.assertEqual('/bin', env['PATH'])

    def test_noninteractive_init_does_not_save_or_create(self):
        with patch.object(module.sys.stdin, 'isatty', return_value=False), patch.object(module, 'call') as call:
            with self.assertRaisesRegex(module.LocalError, '交互终端'):
                module.initialize(8080)
            call.assert_not_called()
        self.assertFalse(self.private.exists())

    def test_password_mismatch_never_saves_or_creates(self):
        with patch.object(module.sys.stdin, 'isatty', return_value=True), patch.object(module, 'docker_ready'), patch.object(module, 'call'), patch('builtins.input', return_value='fixture'), patch.object(module.getpass, 'getpass', side_effect=['first', 'second']), patch.object(module, 'ensure_database') as database:
            with self.assertRaisesRegex(module.LocalError, '密码不一致'):
                module.initialize(8080)
            database.assert_not_called()
        self.assertFalse((self.private/'config.json').exists())

    def test_original_environment_has_no_delete_or_reset_entry(self):
        text = Path(module.__file__).read_text()
        self.assertNotIn('hookflow-p103-mysql', text)
        self.assertNotIn('docker rm', text)
        self.assertNotIn('DROP DATABASE', text)
        self.assertNotIn('ALTER USER', text)
        self.assertIn("BACKEND / 'mvnw'", text)

    def test_owned_volume_and_container_must_match(self):
        config = fixture()
        module.owned({'Labels': {module.LABEL: config['owner']}}, config, 'volume')
        resource = {'Config': {'Labels': {module.LABEL: config['owner']}, 'Image': module.IMAGE},
                    'Mounts': [{'Destination': '/var/lib/mysql', 'Name': config['volume']}]}
        module.owned(resource, config, 'container')
        resource['Mounts'][0]['Name'] = 'another-volume'
        with self.assertRaisesRegex(module.LocalError, '持久卷不符'):
            module.owned(resource, config, 'container')


if __name__ == '__main__':
    unittest.main()
