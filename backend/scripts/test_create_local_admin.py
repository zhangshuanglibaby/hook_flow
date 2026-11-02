import importlib.util
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('create_local_admin', Path(__file__).with_name('create_local_admin.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class CreateLocalAdminTests(unittest.TestCase):
    def test_binary_fields_preserve_unicode_without_shell_or_files(self):
        fields = ['jdbc:mysql://127.0.0.1:1/test', 'fixture', 'fixture-secret', '演示用户', 'fixture-password']
        encoded = module.encode_fields(fields)
        decoded = []
        while encoded:
            size = struct.unpack('>I', encoded[:4])[0]
            decoded.append(encoded[4:4+size].decode())
            encoded = encoded[4+size:]
        self.assertEqual(fields, decoded)

    def test_empty_and_oversized_fields_fail(self):
        for field in ('', 'x'*8193):
            with self.assertRaises(module.local.LocalError): module.encode_fields([field])

    def test_requires_interactive_terminal_before_database_calls(self):
        with patch.object(module.sys.stdin, 'isatty', return_value=False), patch.object(module.local, 'load') as load, patch.object(module.sys, 'argv', ['create_local_admin.py']):
            with self.assertRaisesRegex(module.local.LocalError, '交互终端'):
                module.main()
            load.assert_not_called()

    def test_java_insert_only_uses_existing_bcrypt_and_bound_parameters(self):
        source = Path(module.__file__).with_name('CreateLocalAdmin.java').read_text()
        self.assertIn('new BCryptPasswordEncoder().encode(password)', source)
        self.assertIn('SELECT id FROM app_user WHERE username = ?', source)
        self.assertIn('INSERT INTO app_user', source)
        self.assertNotIn('UPDATE app_user', source)
        self.assertNotIn('DELETE FROM', source)
        self.assertIn('connection.rollback()', source)
        self.assertIn('connection.commit()', source)
        self.assertNotIn('printStackTrace', source)


if __name__ == '__main__':
    unittest.main()
