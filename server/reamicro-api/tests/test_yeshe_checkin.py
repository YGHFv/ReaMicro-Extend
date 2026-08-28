"""野社签到与周奖励领取回归测试。

历史问题：文社周奖励是"每周"结算的，但任务每天都去领。阅微对重复领取回
HTTP 200 + `data.success=false` + "上一周奖励已领取"，服务器把它当成可重试的失败，
于是每天都是「5 分钟后重试 ×3」+「23:59 最后重试」，界面上一直显示领取失败。
"""
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import main


CHINA = timezone(timedelta(hours=8))


class YesheCheckinClaimTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        main.SECRET_KEY = "test-secret-key-for-yeshe"
        main.ACCOUNT_ROOT = root / "accounts"
        main.ACCOUNT_PATH = main.ACCOUNT_ROOT / "credentials.json"
        main.TASK_ROOT = root / "tasks"
        main.TASKS_PATH = main.TASK_ROOT / "tasks.json"
        main.TASK_LOG_ROOT = main.TASK_ROOT / "logs"
        main.STATE_DB_PATH = root / "state" / "reamicro.sqlite3"
        main.state_store = None
        main.save_credentials({
            "rea_1": {
                "id": "rea_1",
                "owner": "host:3",
                "type": "reamicro",
                "accountId": "3",
                "secretEncrypted": main.encrypt_secret({"token": "token-value", "baseUrl": "https://example.invalid/"}),
                "enabled": True,
            },
        })
        self._original_request = main.json_http_request
        self.calls: list[str] = []

    def tearDown(self):
        main.json_http_request = self._original_request
        self.temp_dir.cleanup()

    def _task(self, **overrides):
        task = {
            "id": "task_1",
            "owner": "host:3",
            "taskType": "yeshe_checkin",
            "credentialId": "rea_1",
            "requestEncrypted": main.encrypt_secret({"credentialId": "rea_1"}),
            "schedule": {"intervalSeconds": 86400, "timeOfDay": "00:05"},
            "status": "scheduled",
            "enabled": True,
            "createdAt": 1,
            "nextRunAt": 0,
        }
        task.update(overrides)
        return task

    def _stub(self, claim_response):
        """打桩阅微接口：轶闻已签到，领取周奖励返回给定响应。"""
        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append(endpoint)
            if "get-daily-lore" in endpoint:
                return 200, {"data": {"id": 99, "isFinish": True}}, "{}"
            if "claim-literary-society-weekly-reward" in endpoint:
                return claim_response
            return 200, {"data": {}}, "{}"
        main.json_http_request = fake

    def _due_task(self, **overrides):
        """构造一个"签到已完成、8 小时等待期已过"的任务，直接进入领取分支。"""
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        defaults = {
            "lastCheckinDate": datetime.now(CHINA).date().isoformat(),
            "lastCheckinAt": now_ms - 9 * 3_600_000,
            "claimDueAt": now_ms - 3_600_000,
            "claimRetryCount": 0,
        }
        defaults.update(overrides)
        return self._task(**defaults)

    def _claim_called(self) -> bool:
        return any("claim-literary-society-weekly-reward" in call for call in self.calls)

    def test_already_claimed_is_treated_as_success(self):
        # 核心回归：阅微回"上一周奖励已领取"时不能再判失败重试。
        self._stub((200, {"data": {"success": False, "message": "上一周奖励已领取"}}, "{}"))
        task = self._due_task()
        result, message = main.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("此前已领取", message)
        self.assertNotIn("自动重试", message)
        self.assertNotIn("最后重试", message)
        self.assertEqual(0, task["claimRetryCount"], "已领取不应累计重试次数")
        self.assertNotIn("nextRunAtOverride", task, "已领取不应安排 5 分钟后重试")
        self.assertEqual(main.iso_week_key(int(datetime.now(timezone.utc).timestamp() * 1000)), task["claimCompletedWeek"])

    def test_already_claimed_still_triggers_linked_draw(self):
        # 奖励此前已到账时，联动抽卡也要放行，否则抽卡永远等在"等待签到奖励领取完成"。
        self._stub((200, {"data": {"success": False, "message": "上一周奖励已领取"}}, "{}"))
        task = self._due_task()
        main.execute_reamicro_task(task)
        self.assertTrue(task.get("claimJustCompleted"), "已领取状态必须放行联动抽卡")

    def test_weekly_marker_stops_daily_reclaim_attempts(self):
        """本周已领取后，后续几天不再调用领取接口。"""
        self._stub((200, {"data": {"success": True, "message": "领取成功"}}, "{}"))
        task = self._due_task()
        first_result, _ = main.execute_reamicro_task(task)
        self.assertEqual("success", first_result)
        self.assertTrue(self._claim_called())

        # 模拟第二天：日期推进会重置当日状态，但周标记必须保留。
        tomorrow = (datetime.now(CHINA) + timedelta(days=1)).date().isoformat()
        task["lastCheckinDate"] = tomorrow
        self.calls.clear()
        result, message = main.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("本周签到奖励已领取", message)
        self.assertFalse(self._claim_called(), "本周已领取不应再调领取接口")

    def test_genuine_failure_still_retries(self):
        """真正的领取失败必须保留原有重试逻辑，不能被"已领取"判定吞掉。"""
        self._stub((200, {"data": {"success": False, "message": "服务器繁忙，请稍后再试"}}, "{}"))
        task = self._due_task()
        result, message = main.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("第 1/3 次", message)
        self.assertIn("5 分钟后自动重试", message)
        self.assertEqual(1, task["claimRetryCount"])
        self.assertIn("nextRunAtOverride", task)
        self.assertNotIn("claimCompletedWeek", task)

    def test_genuine_failure_escalates_to_final_attempt(self):
        self._stub((200, {"data": {"success": False, "message": "服务器繁忙"}}, "{}"))
        task = self._due_task(claimRetryCount=3)
        result, message = main.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("23:59 最后重试", message)

    def test_auth_failure_still_pauses(self):
        self._stub((401, {}, "unauthorized"))
        task = self._due_task()
        result, message = main.execute_reamicro_task(task)
        self.assertEqual("paused", result)
        self.assertIn("401", message)

    def test_claim_already_granted_patterns(self):
        detect = main.claim_reward_already_granted
        for text in ("上一周奖励已领取", "本周奖励已经领取", "奖励已领过", "重复领取", "奖励已发放", "already claimed", "Already Received"):
            self.assertTrue(detect(text), f"{text} 应判定为已领取")
        for text in ("服务器繁忙", "网络异常", "参数错误", "未达到领取条件", ""):
            self.assertFalse(detect(text), f"{text} 不应判定为已领取")
        # 结构化字段同样要认。
        self.assertTrue(detect("", {"data": {"claimed": True}}))
        self.assertFalse(detect("", {"data": {"claimed": False}}))

    def test_iso_week_key_uses_beijing_time(self):
        # 2026-08-28 是 ISO 第 35 周的周五。
        moment = int(datetime(2026, 8, 28, 12, 0, tzinfo=CHINA).timestamp() * 1000)
        self.assertEqual("2026-W35", main.iso_week_key(moment))
        # 跨周边界：北京时间周一 00:30 属于新的一周。
        monday = int(datetime(2026, 8, 31, 0, 30, tzinfo=CHINA).timestamp() * 1000)
        self.assertEqual("2026-W36", main.iso_week_key(monday))

    def test_waiting_period_unchanged(self):
        """签到后 8 小时等待期的行为不变。"""
        self._stub((200, {"data": {"success": True}}, "{}"))
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        task = self._task(
            lastCheckinDate=datetime.now(CHINA).date().isoformat(),
            claimDueAt=now_ms + 4 * 3_600_000,
        )
        result, message = main.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("8 小时后自动领取", message)
        self.assertFalse(self._claim_called())


if __name__ == "__main__":
    unittest.main()
