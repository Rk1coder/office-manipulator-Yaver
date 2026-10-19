#!/usr/bin/env python3
"""Offline launcher safety regressions; never starts Docker or provider calls."""
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

loader = importlib.machinery.SourceFileLoader('office_native', str(Path(__file__).with_name('office-native')))
spec = importlib.util.spec_from_loader(loader.name, loader)
native = importlib.util.module_from_spec(spec)
loader.exec_module(native)


class LauncherTests(unittest.TestCase):
    def test_env_is_literal_and_never_executes_shell(self):
        with tempfile.TemporaryDirectory() as temporary:
            env = Path(temporary) / '.env'
            sentinel = Path(temporary) / 'executed'
            env.write_text(f'A=$(touch {sentinel})\nB=`touch {sentinel}`\nC=${{HOME}}\nD="has # space" # comment\nexport E=literal # comment\nF=abc#def\n')
            values = native.parse_env(env)
            self.assertFalse(sentinel.exists())
            self.assertEqual('${HOME}', values['C'])
            self.assertEqual('has # space', values['D'])
            self.assertEqual('literal', values['E'])
            self.assertEqual('abc#def', values['F'])

    def test_invalid_env_never_includes_secret_in_error(self):
        with tempfile.TemporaryDirectory() as temporary:
            env = Path(temporary) / '.env'
            for content in ('SECRET="sensitive-example', 'SECRET="sensitive-example" more', 'sensitive-example no assignment'):
                env.write_text(content)
                with self.assertRaises(native.LauncherError) as error:
                    native.parse_env(env)
                self.assertNotIn('sensitive-example', str(error.exception))

    def test_path_mapping_only_replaces_prefix(self):
        target = Path('/private/files')
        self.assertEqual('/private/files/id/file.docx', native.mapped_path('/data/files/id/file.docx', target))
        self.assertEqual('/other/data/files/a', native.mapped_path('/other/data/files/a', target))
        self.assertIsNone(native.mapped_path(None, target))
        self.assertEqual('/data/files-other/a', native.mapped_path('/data/files-other/a', target))

    def test_path_mapping_rejects_traversal_and_ambiguous_paths(self):
        for tail in ('', '../secret', 'a/../../secret', '/absolute', './a', 'a//b', 'a\\b', 'a\x00b'):
            with self.subTest(tail=tail), self.assertRaises(native.LauncherError):
                native.mapped_path('/data/files/' + tail, Path('/private/files'))

    def test_image_json_maps_nested_values_and_preserves_other_content(self):
        data = {'one': '/data/files/id/first.png', 'nested': ['/data/files/id/second.png', '/elsewhere/x'], 'number': 4}
        result = native.mapped_images(data, Path('/private/files'))
        self.assertEqual('/private/files/id/first.png', result['one'])
        self.assertEqual(['/private/files/id/second.png', '/elsewhere/x'], result['nested'])
        self.assertEqual(4, result['number'])
        self.assertEqual('/data/files/id/first.png', data['one'])

    def test_copy_validation_rejects_symlinks(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'unsafe').symlink_to('/etc/passwd')
            with self.assertRaises(native.LauncherError):
                native.validate_tree(root)

    def test_identity_mismatch_never_signals_process(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(native, 'STATE', Path(temporary)):
            identity = native.process_identity(os.getpid())
            identity['start'] = 'wrong-start-time'
            (Path(temporary) / 'process.json').write_text(json.dumps(identity))
            with patch.object(native.signal, 'pidfd_send_signal') as send:
                native.stop_native()
                send.assert_not_called()

    def test_identity_matches_current_process_and_checks_cmdline(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(native, 'STATE', Path(temporary)):
            identity = native.process_identity(os.getpid())
            state = Path(temporary) / 'process.json'
            state.write_text(json.dumps(identity))
            self.assertEqual(identity, native.managed_process())
            identity['cmdline'] = 'changed'
            state.write_text(json.dumps(identity))
            self.assertIsNone(native.managed_process())

    def test_private_state_permissions(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder = Path(temporary) / 'state'
            native.private_dir(folder)
            native.write_json(folder / 'state.json', {'phase': 'copied'})
            self.assertEqual(0o700, folder.stat().st_mode & 0o777)
            self.assertEqual(0o600, (folder / 'state.json').stat().st_mode & 0o777)

    def test_sql_literals_escape_quotes_and_preserve_null(self):
        self.assertEqual("'a''b'", native.literal("a'b"))
        self.assertEqual('NULL', native.literal(None))

    def test_migration_retry_keeps_backup_and_completed_copy(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(native, 'STATE', Path(temporary)):
            state = Path(temporary)
            backup = state / 'backup-original'
            backup.mkdir()
            (backup / 'office-trial.dump').write_bytes(b'original backup')
            row = {'id': '11111111-1111-1111-1111-111111111111',
                   'file_path': '/data/files/job/document.docx', 'image_paths_json': '{"image":"/data/files/job/a.png"}',
                   'file_required': True}
            (backup / 'original-paths.json').write_text(json.dumps([row]))
            copy = state / 'files-copy/job'
            copy.mkdir(parents=True)
            (copy / 'document.docx').write_bytes(b'document')
            native.write_json(state / 'migration.json', {'phase': 'copied', 'destination': str(state / 'files'), 'backup': str(backup)})
            with patch.object(native, 'sql', side_effect=native.LauncherError('transaction failed')):
                with self.assertRaises(native.LauncherError):
                    native.migrate({})
            self.assertEqual('copied', json.loads((state / 'migration.json').read_text())['phase'])
            self.assertEqual(b'document', (state / 'files/job/document.docx').read_bytes())
            with patch.object(native, 'compose') as compose, patch.object(native, 'sql', return_value='') as sql:
                native.migrate({})
                compose.assert_not_called()
                query = sql.call_args.args[0]
                self.assertTrue(query.startswith('BEGIN;'))
                self.assertTrue(query.endswith('COMMIT;'))
                self.assertIn('LOCK TABLE jobs IN ACCESS EXCLUSIVE MODE', query)
                self.assertIn('SELECT 1 / CASE WHEN EXISTS', query)
                self.assertIn(str(state / 'files/job/a.png'), query)
            self.assertEqual('complete', json.loads((state / 'migration.json').read_text())['phase'])
            self.assertEqual(b'original backup', (backup / 'office-trial.dump').read_bytes())

    def test_missing_unexpired_document_prevents_database_update(self):
        with tempfile.TemporaryDirectory() as temporary, patch.object(native, 'STATE', Path(temporary)):
            state = Path(temporary)
            backup = state / 'backup-original'
            backup.mkdir()
            row = {'id': '11111111-1111-1111-1111-111111111111', 'file_path': '/data/files/job/missing.docx',
                   'image_paths_json': None, 'file_required': True}
            (backup / 'original-paths.json').write_text(json.dumps([row]))
            (state / 'files-copy').mkdir()
            native.write_json(state / 'migration.json', {'phase': 'copied', 'destination': str(state / 'files'), 'backup': str(backup)})
            with patch.object(native, 'sql') as sql, self.assertRaises(native.LauncherError):
                native.migrate({})
            sql.assert_not_called()
            self.assertEqual('copied', json.loads((state / 'migration.json').read_text())['phase'])

    def test_active_jobs_abort_before_compose_recreates_database(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'target').mkdir()
            (root / 'target/office-manipulator-1.0.0-SNAPSHOT.jar').touch()
            environment = {'ANTHROPIC_API_KEY': 'test-only', 'OFFICE_DB_PASSWORD': 'test-only', 'OFFICE_JWT_SECRET': 'test-only'}
            with patch.object(native, 'ROOT', root), patch.object(native, 'parse_env', return_value=environment), \
                    patch.object(native, 'dependencies', return_value=[]), patch.object(native, 'compose', return_value='database-id') as compose, \
                    patch.object(native, 'active_jobs', return_value=1), patch.object(native, 'stop_native') as stop:
                with self.assertRaises(native.LauncherError):
                    native.up()
                self.assertEqual(1, compose.call_count)
                self.assertEqual('ps', compose.call_args.args[0])
                stop.assert_not_called()


if __name__ == '__main__':
    unittest.main()
