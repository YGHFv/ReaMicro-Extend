import os
import tempfile
import unittest
from concurrent.futures import ProcessPoolExecutor
from copy import deepcopy
from pathlib import Path
from unittest.mock import patch


_test_root = tempfile.TemporaryDirectory(prefix="reamicro-api-test-")
for _name, _directory in (
    ("CONFIG", "config"), ("TASK", "tasks"), ("AUDIT", "audit"), ("ACCOUNT", "accounts"),
    ("BACKUP", "backups"), ("SECRET_BACKUP", "secrets"), ("SERVER_BACKUP", "snapshots"),
    ("PACKAGE", "packages"), ("RELEASE", "releases"),
):
    os.environ[f"REAMICRO_{_name}_ROOT"] = str(Path(_test_root.name) / _directory)
os.environ["REAMICRO_STATE_DB"] = str(Path(_test_root.name) / "state.sqlite3")
os.environ["REAMICRO_SECRET_KEY"] = "test-only-key-never-used-by-a-server"
os.environ["REAMICRO_RUN_SCHEDULER"] = "false"
os.environ["REAMICRO_RUN_RELEASE_SYNC"] = "false"

from fastapi import FastAPI
from fastapi.testclient import TestClient
from app import runtime
from app.api import tasks as routes
from app.backups import rotate_secret_key
from app.crypto import decrypt_secret, encrypt_secret
from app.scheduler import complete_task_execution, upsert_task_configuration
from app.security import task_owner
from app.storage import StateStore


def tearDownModule():
    _test_root.cleanup()


def _idempotent_write(path):
    store = StateStore(Path(path))

    def write(values):
        values["new"] = {"id": "new"}
        return {"count": len(values)}

    return store.mutate_namespace("tasks", write, idempotency=("owner", "create", "same-key"))


class StateAndTaskTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(dir=_test_root.name)
        self.addCleanup(self.directory.cleanup)
        self.store = StateStore(Path(self.directory.name) / "test.sqlite3")
        self.store.initialize()
        self.store_patch = patch.object(runtime, "get_state_store", return_value=self.store)
        self.store_patch.start()
        self.addCleanup(self.store_patch.stop)
        application = FastAPI()
        application.include_router(routes.router)
        application.dependency_overrides[task_owner] = lambda: "host:123"
        self.client = TestClient(application)
        self.addCleanup(self.client.close)

    def credential(self, identity="rea_test"):
        self.store.mutate_namespace("credentials", lambda values: values.update({
            identity: {"id": identity, "owner": "host:123", "type": "reamicro", "accountId": "123"},
        }))

    def test_disabled_task_has_no_schedule_and_reports_paused(self):
        result = self.client.post("/v1/tasks", json={"taskType": "http", "enabled": False})
        self.assertEqual(200, result.status_code)
        task = result.json()["data"]
        self.assertFalse(task["enabled"])
        self.assertEqual("paused", task["status"])
        self.assertEqual(0, task["nextRunAt"])

    def test_credential_deleted_before_creation_cannot_leave_an_orphan_task(self):
        self.credential()
        mutate = self.store.mutate_namespaces
        deleted = False

        def concurrent_delete(namespaces, callback, **kwargs):
            nonlocal deleted
            if not deleted:
                deleted = True
                self.store.mutate_namespace("credentials", lambda values: values.pop("rea_test", None))
            return mutate(namespaces, callback, **kwargs)

        with patch.object(self.store, "mutate_namespaces", side_effect=concurrent_delete):
            result = self.client.post("/v1/tasks", json={
                "taskType": "yeshe_checkin", "request": {"credentialId": "rea_test"},
            })
        self.assertEqual(400, result.status_code)
        self.assertEqual({}, self.store.load_namespace("tasks"))

    def test_reconfiguration_cannot_replace_a_running_duplicate(self):
        self.credential("rea_one")
        self.credential("rea_two")
        self.store.save_namespace("tasks", {
            "task_one": {"id": "task_one", "owner": "host:123", "taskType": "yeshe_checkin",
                         "credentialId": "rea_one", "status": "scheduled"},
            "task_two": {"id": "task_two", "owner": "host:123", "taskType": "yeshe_checkin",
                         "credentialId": "rea_two", "status": "running"},
        })
        before = deepcopy(dict(self.store.load_namespace("tasks")))
        result = self.client.post("/v1/tasks/task_one/configure", json={"request": {"credentialId": "rea_two"}})
        self.assertEqual(409, result.status_code)
        self.assertEqual(before, self.store.load_namespace("tasks"))

    def test_missing_credential_during_configuration_rolls_back_all_changes(self):
        self.credential()
        self.store.save_namespace("tasks", {
            "task_one": {"id": "task_one", "owner": "host:123", "taskType": "yeshe_checkin",
                         "credentialId": "rea_test", "status": "scheduled"},
        })
        before = deepcopy(dict(self.store.load_namespace("tasks")))
        result = self.client.post("/v1/tasks/task_one/configure", json={
            "request": {"credentialId": "deleted"}, "schedule": {"timeOfDay": "09:30"},
        })
        self.assertEqual(400, result.status_code)
        self.assertEqual(before, self.store.load_namespace("tasks"))

    def test_stale_writers_preserve_other_changes_and_do_not_resurrect_deleted_tasks(self):
        self.store.save_namespace("tasks", {"a": {"count": 1, "label": "原配置"}, "b": {"count": 1}})
        stale = self.store.load_namespace("tasks")
        self.store.mutate_namespace("tasks", lambda values: values["a"].update(count=2))
        self.store.mutate_namespace("tasks", lambda values: values.pop("b"))
        stale["a"]["label"] = "新配置"
        stale["b"]["count"] = 2
        self.store.save_namespace("tasks", stale)
        self.assertEqual({"a": {"count": 2, "label": "新配置"}}, self.store.load_namespace("tasks"))

    def test_idempotency_is_atomic_across_worker_processes(self):
        with ProcessPoolExecutor(max_workers=2) as workers:
            results = list(workers.map(_idempotent_write, [str(self.store.path)] * 2))
        self.assertEqual([{"count": 1}, {"count": 1}], results)
        self.assertEqual({"new": {"id": "new"}}, self.store.load_namespace("tasks"))

    def test_failed_restore_finalization_keeps_live_database(self):
        self.store.save_namespace("tasks", {"saved": {"value": "旧快照"}})
        snapshot = Path(self.directory.name) / "snapshot.sqlite3"
        self.store.backup(snapshot)
        self.store.save_namespace("tasks", {"current": {"value": "当前数据"}})
        with self.assertRaisesRegex(RuntimeError, "配置保存失败"):
            self.store.restore(snapshot, lambda: (_ for _ in ()).throw(RuntimeError("配置保存失败")), 1000)
        self.assertEqual({"current": {"value": "当前数据"}}, self.store.load_namespace("tasks"))
        self.assertEqual("ok", self.store.integrity_check())

    def test_pause_during_execution_survives_the_final_worker_write(self):
        original = {"id": "task", "taskType": "http", "status": "running", "enabled": True,
                    "nextRunAt": 10, "runCount": 0, "schedule": {"intervalSeconds": 60}}
        current = {**original, "status": "paused", "enabled": False, "nextRunAt": 0}
        completed = complete_task_execution(current, original, deepcopy(original), "success", "完成", 10, 20)
        self.assertEqual("paused", completed["status"])
        self.assertFalse(completed["enabled"])
        self.assertEqual(0, completed["nextRunAt"])
        self.assertEqual(1, completed["runCount"])
        self.assertEqual("success", completed["executionHistory"][-1]["result"])

    def test_upsert_of_running_task_preserves_execution_and_history(self):
        original = {"id": "original", "owner": "host:123", "taskType": "yeshe_checkin",
                    "credentialId": "rea_test", "enabled": True, "status": "running", "nextRunAt": 10,
                    "executionId": "lease", "runCount": 5, "createdAt": 1}
        tasks = {"original": original}
        proposed = {**original, "id": "new", "schedule": {"timeOfDay": "09:00"}, "runCount": 0}
        updated, duplicate = upsert_task_configuration(tasks, proposed, 20)
        self.assertTrue(duplicate)
        self.assertEqual("running", updated["status"])
        self.assertEqual(5, updated["runCount"])
        self.assertEqual("lease", updated["executionId"])
        self.assertEqual("original", updated["id"])

    def test_key_rotation_keeps_old_ciphertext_readable(self):
        encrypted = encrypt_secret({"token": "test-token"})
        self.store.save_namespace("credentials", {"rea": {"secretEncrypted": encrypted}})
        self.assertEqual(1, rotate_secret_key("test-rotated-key-with-sufficient-length"))
        self.assertEqual({"token": "test-token"}, decrypt_secret(encrypted))
        updated = self.store.load_namespace("credentials")["rea"]["secretEncrypted"]
        self.assertNotEqual(encrypted, updated)
        self.assertEqual({"token": "test-token"}, decrypt_secret(updated))


if __name__ == "__main__":
    unittest.main()
