"""数据保留策略。

三处数据此前无限增长，长期运行会把数据卷占满：审计日志只追加、任务日志只追加且任务删了
文件还留着、内容包历史每次编辑都归档一份完整副本。只有快照和消息原先有上限。
"""
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import retention, runtime
from app.config_store import load_config, save_config
from tests.conftest_support import (
    HOST_ACCOUNT_ID,
    admin_auth,
    admin_csrf,
    base_config,
    client,
    isolate,
    module_headers,
    seed_credential,
    seed_package,
    seed_task,
)


class RetentionSettingsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()

    def tearDown(self):
        self.temp.cleanup()

    def test_defaults_present(self):
        settings = retention.retention_settings()
        self.assertEqual(retention.DEFAULT_AUDIT_MAX_MB, settings["auditMaxMb"])
        self.assertEqual(retention.DEFAULT_AUDIT_KEEP_FILES, settings["auditKeepFiles"])
        self.assertEqual(retention.DEFAULT_TASK_LOG_MAX_KB, settings["taskLogMaxKb"])
        self.assertEqual(retention.DEFAULT_PACKAGE_HISTORY_KEEP, settings["packageHistoryKeep"])

    def test_values_are_floored(self):
        """阈值不能配成 0 或负数，否则会把数据清光或反复触发。"""
        config = load_config()
        config.update({"auditMaxMb": 0, "taskLogMaxKb": -1, "packageHistoryKeep": 0})
        save_config(config)
        settings = retention.retention_settings()
        self.assertGreaterEqual(settings["auditMaxMb"], 1)
        self.assertGreaterEqual(settings["taskLogMaxKb"], 16)
        self.assertGreaterEqual(settings["packageHistoryKeep"], 1)

    def test_config_survives_round_trip(self):
        config = load_config()
        config.update({"auditMaxMb": 64, "auditKeepFiles": 5, "taskLogMaxKb": 256, "packageHistoryKeep": 3})
        save_config(config)
        reloaded = load_config()
        self.assertEqual(64, reloaded["auditMaxMb"])
        self.assertEqual(5, reloaded["auditKeepFiles"])
        self.assertEqual(256, reloaded["taskLogMaxKb"])
        self.assertEqual(3, reloaded["packageHistoryKeep"])


class AuditRotationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        runtime.AUDIT_ROOT.mkdir(parents=True, exist_ok=True)

    def tearDown(self):
        self.temp.cleanup()

    def _history(self) -> list[str]:
        return sorted(path.name for path in runtime.AUDIT_ROOT.glob("events.jsonl.*"))

    def test_below_threshold_does_nothing(self):
        runtime.AUDIT_PATH.write_text("small", encoding="utf-8")
        self.assertFalse(retention.rotate_audit_log(1024, 3))
        self.assertTrue(runtime.AUDIT_PATH.is_file())
        self.assertEqual([], self._history())

    def test_rotation_preserves_history_not_truncates(self):
        """审计是安全事件，超限要切历史文件而不是直接丢掉最早部分。"""
        runtime.AUDIT_PATH.write_text("x" * 2048, encoding="utf-8")
        self.assertTrue(retention.rotate_audit_log(1024, 3))
        self.assertFalse(runtime.AUDIT_PATH.exists(), "当前文件已被移走，下次写入会新建")
        self.assertEqual(["events.jsonl.1"], self._history())

    def test_history_shifts_and_caps(self):
        """编号越大越旧，超出保留份数的删掉。"""
        for round_index in range(5):
            runtime.AUDIT_PATH.write_text(f"round{round_index}" + "x" * 2048, encoding="utf-8")
            retention.rotate_audit_log(1024, 2)
        self.assertEqual(["events.jsonl.1", "events.jsonl.2"], self._history())
        newest = (runtime.AUDIT_ROOT / "events.jsonl.1").read_text(encoding="utf-8")
        self.assertTrue(newest.startswith("round4"), "编号 1 应该是最近切下来的")

    def test_zero_keep_files_truncates(self):
        """明确配置为不保留历史时才直接删。"""
        runtime.AUDIT_PATH.write_text("x" * 2048, encoding="utf-8")
        self.assertTrue(retention.rotate_audit_log(1024, 0))
        self.assertFalse(runtime.AUDIT_PATH.exists())
        self.assertEqual([], self._history())

    def test_missing_file_is_safe(self):
        self.assertFalse(retention.rotate_audit_log(1024, 3))


class TaskLogRetentionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        seed_credential()
        runtime.TASK_LOG_ROOT.mkdir(parents=True, exist_ok=True)
        self.client = client()

    def tearDown(self):
        self.temp.cleanup()

    def _log(self, task_id: str) -> Path:
        return runtime.TASK_LOG_ROOT / f"{task_id}.log"

    def test_below_threshold_untouched(self):
        self._log("t1").write_text("short\n", encoding="utf-8")
        self.assertFalse(retention.trim_task_log("t1", 1024))
        self.assertEqual("short\n", self._log("t1").read_text(encoding="utf-8"))

    def test_oversized_log_is_trimmed_with_notice(self):
        lines = "\n".join(json.dumps({"at": index, "message": f"line {index}"}) for index in range(3000))
        self._log("t1").write_text(lines, encoding="utf-8")
        before = self._log("t1").stat().st_size
        self.assertTrue(retention.trim_task_log("t1", 8 * 1024))
        after = self._log("t1").read_text(encoding="utf-8")
        self.assertLess(len(after.encode("utf-8")), before)
        self.assertIn("已因超出保留上限被裁剪", after, "要留一行说明，否则看起来像日志丢了")

    def test_trim_does_not_leave_partial_line(self):
        lines = "\n".join(json.dumps({"at": index, "message": "x" * 200}) for index in range(2000))
        self._log("t1").write_text(lines, encoding="utf-8")
        retention.trim_task_log("t1", 8 * 1024)
        body = self._log("t1").read_text(encoding="utf-8").splitlines()[1:]
        for line in body:
            if line.strip():
                json.loads(line)  # 截断留下半行就会在这里抛错

    def test_orphan_logs_removed(self):
        self._log("alive").write_text("x", encoding="utf-8")
        self._log("ghost").write_text("x", encoding="utf-8")
        self.assertEqual(1, retention.purge_orphan_task_logs({"alive"}))
        self.assertTrue(self._log("alive").is_file())
        self.assertFalse(self._log("ghost").is_file())

    def test_deleting_task_removes_its_log(self):
        """原先删任务后还会写一条日志，反而给不存在的任务新建文件。"""
        seed_task("task_1")
        self._log("task_1").write_text("x", encoding="utf-8")
        response = self.client.delete("/v1/tasks/task_1", headers=module_headers())
        self.assertEqual(200, response.status_code)
        self.assertFalse(self._log("task_1").exists(), "任务没了，日志也不该留着")


class PackageHistoryRetentionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        seed_package(package_id="p1")
        self.history = runtime.PACKAGE_ROOT / "online_source" / "p1" / "history"

    def tearDown(self):
        self.temp.cleanup()

    def _add_versions(self, count: int) -> None:
        for index in range(count):
            directory = self.history / f"1.{index}.0-{1740000000000 + index}"
            directory.mkdir(parents=True, exist_ok=True)
            (directory / "manifest.json").write_text(json.dumps({"version": f"1.{index}.0"}), encoding="utf-8")

    def test_keeps_newest_versions(self):
        self._add_versions(6)
        retention.prune_package_history("online_source", "p1", 2)
        kept = sorted(path.name for path in self.history.iterdir())
        self.assertEqual(2, len(kept))
        self.assertTrue(all("1740000000004" in name or "1740000000005" in name for name in kept),
                        f"应保留最新两份，实际 {kept}")

    def test_below_keep_count_untouched(self):
        self._add_versions(2)
        before = sorted(path.name for path in self.history.iterdir())
        self.assertEqual(0, retention.prune_package_history("online_source", "p1", 10))
        self.assertEqual(before, sorted(path.name for path in self.history.iterdir()))

    def test_missing_history_is_safe(self):
        self.assertEqual(0, retention.prune_package_history("online_source", "nope", 3))

    def test_malformed_directory_names_do_not_crash(self):
        (self.history / "no-timestamp").mkdir(parents=True, exist_ok=True)
        self._add_versions(3)
        retention.prune_package_history("online_source", "p1", 1)
        self.assertLessEqual(len(list(self.history.iterdir())), 1)

    def test_editing_prunes_immediately(self):
        """归档时就裁剪，不等定时清理——编辑频繁的源会在两次清理之间堆很多副本。"""
        config = load_config()
        config["packageHistoryKeep"] = 2
        save_config(config)
        self._add_versions(5)
        admin = client()
        response = admin.post(
            "/admin/packages/online_source/p1/edit",
            auth=admin_auth(),
            data={
                "csrf_token": admin_csrf(),
                "version": "9.0.0",
                "filename": "source.json",
                "payload_text": json.dumps({"bookSourceName": "示例书源"}, ensure_ascii=False),
                "status_value": "published",
                "channel": "stable",
                "dependencies": "[]",
            },
        )
        self.assertEqual(200, response.status_code, response.text[:300])
        # 裁剪到 2 份后又归档了 1 份当前版本。
        self.assertLessEqual(len(list(self.history.iterdir())), 3)

    def test_prune_all_covers_every_kind(self):
        seed_package(kind="theme", package_id="t1")
        theme_history = runtime.PACKAGE_ROOT / "theme" / "t1" / "history"
        for index in range(4):
            directory = theme_history / f"1.{index}.0-{1740000000000 + index}"
            directory.mkdir(parents=True, exist_ok=True)
        self._add_versions(4)
        removed = retention.prune_all_package_history(1)
        self.assertGreater(removed, 0)
        self.assertLessEqual(len(list(theme_history.iterdir())), 1)


class RunRetentionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        isolate(Path(self.temp.name))
        base_config()
        seed_credential()
        self.client = client()

    def tearDown(self):
        self.temp.cleanup()

    def test_run_reports_each_action(self):
        config = load_config()
        config.update({"auditMaxMb": 1, "auditKeepFiles": 2, "taskLogMaxKb": 16, "packageHistoryKeep": 1})
        save_config(config)
        runtime.AUDIT_ROOT.mkdir(parents=True, exist_ok=True)
        runtime.AUDIT_PATH.write_text("x" * (2 * 1024 * 1024), encoding="utf-8")
        seed_task("t1")
        runtime.TASK_LOG_ROOT.mkdir(parents=True, exist_ok=True)
        (runtime.TASK_LOG_ROOT / "t1.log").write_text("y" * (64 * 1024), encoding="utf-8")
        (runtime.TASK_LOG_ROOT / "ghost.log").write_text("z", encoding="utf-8")
        seed_package(package_id="p1")
        history = runtime.PACKAGE_ROOT / "online_source" / "p1" / "history"
        for index in range(4):
            (history / f"1.{index}.0-{1740000000000 + index}").mkdir(parents=True, exist_ok=True)

        report = retention.run_retention()
        self.assertTrue(report["auditRotated"])
        self.assertEqual(1, report["taskLogsTrimmed"])
        self.assertEqual(1, report["orphanLogsRemoved"])
        self.assertGreater(report["historyRemoved"], 0)

    def test_run_is_idempotent_when_nothing_exceeds(self):
        report = retention.run_retention()
        self.assertFalse(report["auditRotated"])
        self.assertEqual(0, report["taskLogsTrimmed"])
        self.assertEqual(0, report["orphanLogsRemoved"])

    def test_audit_records_only_when_something_happened(self):
        retention.run_retention()
        first = runtime.AUDIT_PATH.read_text(encoding="utf-8") if runtime.AUDIT_PATH.is_file() else ""
        self.assertNotIn("retention_applied", first, "没清理任何东西时不该留审计噪音")

    def test_data_usage_reports_all_categories(self):
        seed_package(package_id="p1")
        usage = retention.data_usage()
        for key in ("audit", "auditHistory", "taskLogs", "packages", "packageHistory", "backups", "snapshots"):
            self.assertIn(key, usage)
            self.assertGreaterEqual(usage[key], 0)
        self.assertGreater(usage["packages"], 0, "已有内容包时占用应大于 0")

    def test_settings_page_shows_thresholds_and_usage(self):
        page = self.client.get("/admin/settings", auth=admin_auth()).text
        self.assertIn("数据保留", page)
        self.assertIn("name='audit_max_mb'", page)
        self.assertIn("name='package_history_keep'", page)
        self.assertIn("数据占用", page)
        self.assertIn("立即执行清理", page)

    def test_manual_run_route(self):
        response = self.client.post(
            "/admin/settings/retention/run",
            auth=admin_auth(),
            data={"csrf_token": admin_csrf()},
        )
        self.assertEqual(200, response.status_code, response.text[:300])
        self.assertIn("数据保留清理完成", response.text)

    def test_manual_run_requires_csrf(self):
        response = self.client.post("/admin/settings/retention/run", auth=admin_auth(), data={})
        self.assertEqual(403, response.status_code)

    def test_settings_form_persists_thresholds(self):
        response = self.client.post("/admin/settings", auth=admin_auth(), data={
            "csrf_token": admin_csrf(),
            "server_id": "reamicro-api",
            "auth_mode": "host_account_allowlist",
            "host_account_allowlist": HOST_ACCOUNT_ID,
            "features": "backup",
            "min_module_version": "2.0.0",
            "github_repository": "YGHFv/ReaMicro-Extend",
            "release_sync_seconds": "1800",
            "release_version_code": "0",
            "server_snapshot_seconds": "86400",
            "server_snapshot_retention": "30",
            "signing_public_key": "",
            "audit_max_mb": "64",
            "audit_keep_files": "5",
            "task_log_max_kb": "256",
            "package_history_keep": "4",
        })
        self.assertEqual(200, response.status_code, response.text[:400])
        config = load_config()
        self.assertEqual(64, config["auditMaxMb"])
        self.assertEqual(5, config["auditKeepFiles"])
        self.assertEqual(256, config["taskLogMaxKb"])
        self.assertEqual(4, config["packageHistoryKeep"])


if __name__ == "__main__":
    unittest.main()
