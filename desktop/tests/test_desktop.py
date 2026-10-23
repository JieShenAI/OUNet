"""Offline tests: synthetic credentials, isolated files, never a real portal call."""
from dataclasses import replace
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

HERE = Path(__file__).resolve().parent
sys.path[:0] = [str(HERE.parent), str(HERE.parents[1])]
import auto_login as core
from ouc_desktop.engine import MonitorEngine
from ouc_desktop.storage import CredentialStore, Settings, StorageError, EventStore, write_json
from ouc_desktop.windows import action_matches, WindowsError
from ouc_desktop.migration import merge_budget, import_config, complete_migration


class TempTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=HERE)
        self.directory = Path(self.temp.name).resolve()
        self.directory.relative_to(HERE)
    def tearDown(self): self.temp.cleanup()


class StorageTests(TempTest):
    def test_current_user_dpapi_roundtrip_and_no_plaintext(self):
        store = CredentialStore(self.directory/"credentials.dat")
        store.save("fake-student","001234-secret-for-test")
        self.assertEqual(store.read(),("fake-student","001234-secret-for-test"))
        self.assertNotIn(b"001234-secret-for-test",store.path.read_bytes())
        self.assertNotIn(b"fake-student",store.path.read_bytes())
    def test_damaged_encrypted_credentials_are_rejected(self):
        store = CredentialStore(self.directory/"credentials.dat")
        store.path.write_bytes(b"invalid-ciphertext")
        with self.assertRaises(StorageError): store.read()
    def test_pause_survives_reload(self):
        path = self.directory/"settings.json"
        Settings(configured=True,ssid="test",profile="test",monitor=False).save(path)
        self.assertFalse(Settings.read(path).monitor)
    def test_invalid_settings_do_not_fall_back_to_enabled(self):
        path = self.directory/"settings.json"
        for data in ({"version":9},{"version":1,"interval":0},{"version":1,"monitor":"true"}):
            write_json(path,data)
            with self.assertRaises(StorageError): Settings.read(path)
    def test_log_export_removes_sensitive_fields(self):
        log = EventStore(self.directory)
        log.add("info","测试 fake-user secret-pass 10.20.30.40 aa:bb:cc:dd:ee:ff https://example.test/?password=secret-pass")
        target = self.directory/"export.txt"
        log.export(target,("fake-user","secret-pass"))
        text = target.read_text(encoding="utf-8-sig")
        for value in ("fake-user","secret-pass","10.20.30.40","aa:bb:cc:dd:ee:ff","https://"):
            self.assertNotIn(value,text)
    def test_event_history_is_bounded_and_reloads(self):
        log = EventStore(self.directory)
        for number in range(305): log.add("info",f"event {number}")
        self.assertEqual(len(EventStore(self.directory).recent()),300)


class MigrationTests(TempTest):
    def test_matching_exact_source_script(self):
        action={"Execute":"pythonw.exe","Arguments":"auto_login.py --once","WorkingDirectory":str(self.directory)}
        self.assertTrue(action_matches(action,self.directory))
        self.assertFalse(action_matches(dict(action,WorkingDirectory=str(self.directory/"other")),self.directory))
        self.assertFalse(action_matches(dict(action,Arguments="other_auto_login.py"),self.directory))
        self.assertFalse(action_matches(dict(action,Arguments="-c print('auto_login.py')"),self.directory))
        self.assertFalse(action_matches(dict(action,Arguments='-c "auto_login.py"'),self.directory))
        self.assertFalse(action_matches({"Execute":"cmd.exe","Arguments":"/c echo auto_login.py","WorkingDirectory":str(self.directory)},self.directory))
        self.assertTrue(action_matches({"Execute":"cmd.exe","Arguments":"/c start.cmd","WorkingDirectory":str(self.directory)},self.directory))
    def test_absolute_quoted_script_with_spaces(self):
        directory = self.directory/"folder with spaces"
        self.assertTrue(action_matches({"Execute":"pythonw.exe","Arguments":f'"{directory / "auto_login.py"}" --once'},directory))
    def test_merge_preserves_block_and_is_idempotent(self):
        a,b = self.directory/"new.json",self.directory/"old.json"
        write_json(a,{"version":1,"attempts":[1000],"blocked":False})
        write_json(b,{"version":1,"attempts":[1000,1100,1200],"blocked":True})
        merge_budget(a,b); merge_budget(a,b)
        attempts,blocked = core.LoginRetryGuard(a)._read()
        self.assertTrue(blocked);self.assertEqual(attempts,[1000,1100,1200])
    def test_corrupt_import_stops_authentication(self):
        a,b=self.directory/"new.json",self.directory/"old.json"
        b.write_text("broken",encoding="utf-8")
        with self.assertRaises(core.RetryStateError):merge_budget(a,b)
        self.assertFalse(a.exists())
    @patch("ouc_desktop.migration.legacy_tasks",return_value=[])
    def test_import_preserves_leading_zero_and_pauses_until_migrated(self,tasks):
        source=self.directory/"source";source.mkdir()
        (source/"auto_login.py").write_text("# fixture",encoding="utf-8")
        env=source/".env";env.write_text("OUC_USERNAME=fake\nOUC_PASSWORD=001234\nOUC_SSID=Test-WiFi",encoding="utf-8")
        target=self.directory/"data";target.mkdir()
        store=CredentialStore(target/"credentials.dat")
        with patch.dict(os.environ,{"OUC_PASSWORD":"wrong-environment-value"}):
            result=import_config(env,target,Settings(),store)
        self.assertTrue(result["settings"].migration_pending)
        self.assertFalse(result["settings"].monitor)
        self.assertEqual(store.read()[1],"001234")
        self.assertTrue(env.exists())
    @patch("ouc_desktop.migration.legacy_tasks",return_value=[{"name":"fake","path":"\\","enabled":True}])
    def test_enabled_old_task_prevents_completion(self,tasks):
        (self.directory/"auto_login.py").write_text("# fixture",encoding="utf-8")
        with self.assertRaises(StorageError):
            complete_migration(self.directory,Settings(legacy_directory=str(self.directory),migration_pending=True),[])
    def test_cli_runtime_marker_rejects_arbitrary_destination(self):
        with patch.object(core,"DIRECTORY",self.directory):
            write_json(self.directory/"desktop-managed.json",{"version":1,"data_directory":str(self.directory/"wrong")})
            with self.assertRaises(core.RetryStateError): core.runtime_paths()


if __name__ == "__main__": unittest.main()
