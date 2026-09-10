"""device 执行模式退役后的回归测试。

覆盖三件事，均为本次「云端任务改回服务器执行」新增/改动的路径：
1. 创建与配置任务时 executionMode 一律落成 server，并清掉设备租约残留；
2. 存量 device 任务在服务器启动与模块心跳两条路径上都会被迁回 server；
3. 调度循环确实会执行 server 任务（device 跳过判断已移除），且旧的设备端点已不存在。

背景：device 模式指模块领服务器租约、在模块进程代跑云端任务。该模式废弃后，
对应的 claim/complete 端点与客户端调用一并删除。
"""
import asyncio
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app import scheduler, state
from tests.conftest_support import (
    HOST_ACCOUNT_ID,
    client,
    isolate,
    module_headers,
    seed_credential,
    seed_task,
    unwrap,
)

OWNER = f"host:{HOST_ACCOUNT_ID}"


class DeviceExecutionRetiredTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        isolate(Path(self.temp_dir.name), secret_key="test-secret-key")
        self.client = client()

    def tearDown(self):
        self.temp_dir.cleanup()

    # ---- 1. 创建 / 配置一律落成 server ----

    def test_create_task_ignores_device_execution_mode(self):
        seed_credential()
        response = self.client.post(
            "/v1/tasks",
            json={
                "taskType": "cloud_auto_read",
                "executionMode": "device",
                "schedule": {"intervalSeconds": 86400, "timeOfDay": "00:05", "timezoneOffsetMinutes": 480},
                "request": {"credentialId": "rea_1"},
            },
            headers=module_headers(),
        )
        self.assertEqual(200, response.status_code)
        task = unwrap(response)
        self.assertEqual("server", task["executionMode"], "device 模式已废弃，创建时必须落成 server")
        self.assertEqual("server", state.load_tasks()[task["id"]]["executionMode"])

    def test_configure_task_downgrades_device_and_clears_lease(self):
        seed_credential()
        seed_task(
            task_id="task_dev",
            task_type="cloud_auto_read",
            executionMode="device",
            deviceLeaseToken="lease-abc",
            deviceLeaseUntil=9_999_999_999_999,
        )
        response = self.client.post(
            "/v1/tasks/task_dev/configure",
            json={"executionMode": "device", "enabled": True},
            headers=module_headers(),
        )
        self.assertEqual(200, response.status_code)
        stored = state.load_tasks()["task_dev"]
        self.assertEqual("server", stored["executionMode"])
        self.assertNotIn("deviceLeaseToken", stored, "迁移/配置后不应残留设备租约")
        self.assertNotIn("deviceLeaseUntil", stored)

    # ---- 2. 迁移 ----

    def test_migration_moves_in_transit_task_and_clears_lease(self):
        seed_task(
            task_id="task_running",
            task_type="cloud_auto_read",
            executionMode="device",
            status="running",
            nextRunAt=0,
            deviceLeaseToken="lease-abc",
            deviceLeaseUntil=9_999_999_999_999,
        )
        migrated = scheduler.migrate_device_tasks_to_server()
        self.assertEqual(1, migrated)

        stored = state.load_tasks()["task_running"]
        self.assertEqual("server", stored["executionMode"])
        self.assertEqual("scheduled", stored["status"], "在途任务要复位为 scheduled 才会被调度接手")
        self.assertNotIn("deviceLeaseToken", stored)
        self.assertNotIn("deviceLeaseUntil", stored)
        # device 任务习惯把 nextRunAt 置 0 等模块唤醒；直接迁回会被当成已到期立刻补跑。
        self.assertGreater(stored["nextRunAt"], 0, "非事件任务迁回后必须重新排期，不能立刻补跑")

    def test_migration_keeps_event_task_next_run_at(self):
        seed_task(
            task_id="task_draw",
            task_type="yeshe_draw_card",
            executionMode="device",
            schedule={"event": "yeshe_checkin_reward_claimed"},
            nextRunAt=0,
        )
        scheduler.migrate_device_tasks_to_server()
        stored = state.load_tasks()["task_draw"]
        self.assertEqual("server", stored["executionMode"])
        self.assertEqual(0, stored["nextRunAt"], "抽卡是事件型任务，nextRunAt 必须保持 0")

    def test_migration_leaves_server_tasks_untouched_and_is_idempotent(self):
        seed_task(task_id="task_ok", executionMode="server", nextRunAt=1_750_000_000_000)
        self.assertEqual(0, scheduler.migrate_device_tasks_to_server())
        self.assertEqual(1_750_000_000_000, state.load_tasks()["task_ok"]["nextRunAt"])

        seed_task(task_id="task_dev", executionMode="device", status="running", nextRunAt=0)
        self.assertEqual(1, scheduler.migrate_device_tasks_to_server())
        self.assertEqual(0, scheduler.migrate_device_tasks_to_server(), "迁移必须幂等")

    def test_presence_heartbeat_migrates_without_server_restart(self):
        seed_task(
            task_id="task_dev",
            task_type="cloud_auto_read",
            executionMode="device",
            status="running",
            deviceLeaseToken="lease-abc",
        )
        response = self.client.post("/v1/presence/heartbeat", json={"source": "test"}, headers=module_headers())
        self.assertEqual(200, response.status_code)
        stored = state.load_tasks()["task_dev"]
        self.assertEqual("server", stored["executionMode"], "模块心跳应顺手迁移，无需等服务器重启")
        self.assertNotIn("deviceLeaseToken", stored)

    def test_presence_heartbeat_still_reports_pending_notifications(self):
        seed_task(task_id="task_dev", task_type="cloud_auto_read", executionMode="device")
        data = unwrap(self.client.post("/v1/presence/heartbeat", json={"source": "test"}, headers=module_headers()))
        # 迁移不能影响心跳原有字段，否则模块会拿不到消息而反复重拉。
        for key in ("onlineUntil", "nextTaskAt", "pendingCount", "notifications"):
            self.assertIn(key, data)

    # ---- 3. 调度与端点 ----

    def test_scheduler_executes_due_server_task(self):
        """device 跳过判断移除后，调度循环必须真的执行 server 任务。"""
        seed_task(task_id="task_due", executionMode="server", nextRunAt=1)

        executed = []
        original_execute = scheduler.execute_task
        original_sleep = asyncio.sleep

        async def stop_after_first_pass(_seconds):
            raise _StopLoop()

        scheduler.execute_task = lambda task: (executed.append(task["id"]), ("success", "stub ok"))[1]
        asyncio.sleep = stop_after_first_pass
        try:
            with self.assertRaises(_StopLoop):
                asyncio.run(scheduler.task_scheduler_loop())
        finally:
            scheduler.execute_task = original_execute
            asyncio.sleep = original_sleep

        self.assertEqual(["task_due"], executed, "到期的 server 任务应被调度执行")
        stored = state.load_tasks()["task_due"]
        self.assertEqual("success", stored["status"])
        self.assertEqual(1, stored["runCount"])

    def test_device_endpoints_are_removed(self):
        """旧设备端点不应再可用。

        注意 `/v1/tasks/claim` 会被 `/v1/tasks/{task_id}` 路径模板接住（该路径只有 GET/DELETE），
        因此返回 405 而非 404——两种都说明端点已不存在，关键是不能返回 200。
        """
        seed_credential()
        seed_task(task_id="task_x")
        claim = self.client.post("/v1/tasks/claim", json={}, headers=module_headers())
        self.assertNotEqual(200, claim.status_code, "claim 端点应已移除")
        self.assertIn(claim.status_code, (404, 405))
        complete = self.client.post("/v1/tasks/task_x/complete", json={"leaseToken": "x"}, headers=module_headers())
        self.assertNotEqual(200, complete.status_code, "complete 端点应已移除")
        self.assertIn(complete.status_code, (404, 405))

    def test_device_routes_absent_from_router(self):
        paths = {route.path for route in __import__("app.api.tasks", fromlist=["router"]).router.routes}
        self.assertNotIn("/v1/tasks/claim", paths)
        self.assertFalse([p for p in paths if p.endswith("/complete")], f"设备端点残留: {paths}")


class _StopLoop(BaseException):
    """用于在首轮调度后跳出 while True 循环，避免测试挂死。"""


if __name__ == "__main__":
    unittest.main()
