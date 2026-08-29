"""野社每日签到与签到奖励领取回归测试。

阅微 2.3.1 的签到与奖励领取都调用 complete-daily-lore，并传同一个 userLoreId。
领取按钮只在 isFinish=true 且 claimed=false 时启用；成功后客户端把 claimed 更新为 true。
通用任务中心的 get-my-task-list / receive-reward 与野社每日轶闻奖励无关。
"""
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import executors, main, releases, runtime
from app import crypto
from app import state
from tests.conftest_support import isolate


CHINA = timezone(timedelta(hours=8))


class YesheCheckinClaimTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        isolate(root, secret_key="test-secret-key-for-yeshe")
        state.save_credentials({
            "rea_1": {
                "id": "rea_1", "owner": "host:3", "type": "reamicro", "accountId": "3",
                "secretEncrypted": crypto.encrypt_secret({"token": "token-value", "baseUrl": "https://example.invalid/"}),
                "enabled": True,
            },
        })
        self._original = executors.json_http_request
        self.calls: list[tuple[str, dict]] = []

    def tearDown(self):
        executors.json_http_request = self._original
        self.temp_dir.cleanup()

    def _task(self, **overrides):
        task = {
            "id": "task_1", "owner": "host:3", "taskType": "yeshe_checkin",
            "credentialId": "rea_1",
            "requestEncrypted": crypto.encrypt_secret({"credentialId": "rea_1"}),
            "schedule": {"intervalSeconds": 86400, "timeOfDay": "00:05"},
            "status": "scheduled", "enabled": True, "createdAt": 1, "nextRunAt": 0,
        }
        task.update(overrides)
        return task

    def _due_task(self, **overrides):
        """签到已完成、8 小时等待期已过，直接进入领取分支。"""
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        defaults = {
            "lastCheckinDate": datetime.now(CHINA).date().isoformat(),
            "lastCheckinAt": now_ms - 9 * 3_600_000,
            "claimDueAt": now_ms - 3_600_000,
            "claimRetryCount": 0,
        }
        defaults.update(overrides)
        return self._task(**defaults)

    def _stub(self, lore=None, claim_response=(200, {"code": 0, "data": None}, "{}")):
        """打桩阅微接口：默认轶闻已签到、奖励待领取。"""
        lore = lore or {
            "code": 0,
            "data": {
                "id": 99,
                "isFinish": True,
                "claimed": False,
                "exp": 5,
                "gem": 3,
                "propName": "端砚",
            },
        }

        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload if isinstance(payload, dict) else {}))
            if "get-daily-lore" in endpoint:
                return 200, lore, "{}"
            if "complete-daily-lore" in endpoint:
                return claim_response
            return 200, {"data": {}}, "{}"
        executors.json_http_request = fake

    def _endpoints(self) -> list[str]:
        return [endpoint for endpoint, _ in self.calls]

    def _completed_lore_ids(self) -> list[str]:
        return [str(payload.get("userLoreId")) for endpoint, payload in self.calls if "complete-daily-lore" in endpoint]

    # ---------- 核心：与阅微领取按钮使用同一接口 ----------

    def test_claims_by_completing_same_daily_lore(self):
        self._stub()
        task = self._due_task()
        result, message = executors.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertEqual(["99"], self._completed_lore_ids())
        self.assertNotIn("rest/task/get-my-task-list", self._endpoints())
        self.assertNotIn("rest/task/receive-reward", self._endpoints())
        self.assertNotIn("rest/community/claim-literary-society-weekly-reward", self._endpoints())
        self.assertIn("领取成功", message)

    def test_reward_notification_contains_specific_rewards(self):
        self._stub()
        _, message = executors.execute_reamicro_task(self._due_task())
        self.assertIn("阅历 5 点", message)
        self.assertIn("彩筹 3 枚", message)
        self.assertIn("端砚", message)

    def test_reward_notification_contains_prop_quality(self):
        self._stub(lore={"code": 0, "data": {
            "id": 99, "isFinish": True, "claimed": False,
            "propName": "端砚", "propQuality": "珍品",
        }})
        _, message = executors.execute_reamicro_task(self._due_task())
        self.assertIn("端砚（珍品）", message)

    # ---------- 三类非失败情形 ----------

    def test_already_claimed_in_lore_is_success_without_request(self):
        self._stub(lore={"code": 0, "data": {
            "id": 99, "isFinish": True, "claimed": True, "exp": 5, "gem": 3,
        }})
        task = self._due_task()
        result, message = executors.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("此前已领取", message)
        self.assertIn("阅历 5 点", message)
        self.assertEqual([], self._completed_lore_ids(), "claimed=true 不应重复调用领取接口")
        self.assertTrue(task.get("claimJustCompleted"), "已领取也要放行联动抽卡")

    def test_already_claimed_business_error_is_terminal(self):
        self._stub(claim_response=(200, {"code": 400, "message": "该奖励已领取"}, "{}"))
        task = self._due_task()
        result, message = executors.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("此前已领取", message)
        self.assertEqual(0, task["claimRetryCount"])
        self.assertTrue(task.get("claimJustCompleted"))

    def test_locked_reward_schedules_another_check(self):
        self._stub(claim_response=(200, {"code": 400, "message": "未到领取时间"}, "{}"))
        task = self._due_task()
        before = int(datetime.now(timezone.utc).timestamp() * 1000)
        result, message = executors.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("5 分钟后再次检查", message)
        self.assertEqual(0, task["claimRetryCount"])
        self.assertGreaterEqual(task["nextRunAtOverride"], before + 5 * 60_000)
        self.assertNotIn("claimCompletedDate", task)
        self.assertFalse(task.get("claimJustCompleted"), "没领到奖励不应触发抽卡")

    def test_granted_triggers_linked_draw(self):
        self._stub()
        task = self._due_task()
        executors.execute_reamicro_task(task)
        self.assertTrue(task.get("claimJustCompleted"))

    # ---------- 每天都要重新领 ----------

    def test_claims_again_next_day(self):
        """每日奖励必须每天都领，不能被任何"已完成"标记长期压掉。"""
        self._stub()
        task = self._due_task()
        executors.execute_reamicro_task(task)
        self.assertEqual(datetime.now(CHINA).date().isoformat(), task["claimCompletedDate"])

        # 次日：日期推进后重置，重新进入 8 小时等待。
        self.calls.clear()
        task["lastCheckinDate"] = (datetime.now(CHINA) - timedelta(days=1)).date().isoformat()
        result, message = executors.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertEqual("", task["claimCompletedDate"], "跨天必须清掉当日领取标记")
        self.assertIn("8 小时后自动领取", message)

    def test_same_day_second_run_skips_claim(self):
        self._stub()
        task = self._due_task()
        executors.execute_reamicro_task(task)
        self.calls.clear()
        result, message = executors.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("今日已领取", message)
        self.assertEqual([], self._completed_lore_ids(), "同一天不重复领取")

    # ---------- 真实失败仍走重试 ----------

    def test_genuine_claim_failure_retries(self):
        self._stub(
            claim_response=(200, {"code": 500, "message": "服务器繁忙，请稍后再试"}, "{}"),
        )
        task = self._due_task()
        result, message = executors.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("第 1/3 次", message)
        self.assertIn("5 分钟后自动重试", message)
        self.assertEqual(1, task["claimRetryCount"])
        self.assertIn("nextRunAtOverride", task)

    def test_genuine_failure_escalates_to_final_attempt(self):
        self._stub(
            claim_response=(200, {"code": 500, "message": "服务器繁忙"}, "{}"),
        )
        result, message = executors.execute_reamicro_task(self._due_task(claimRetryCount=3))
        self.assertEqual("success", result)
        self.assertIn("23:59 最后重试", message)

    def test_claim_http_error_retries(self):
        self._stub(claim_response=(500, {}, "internal error"))
        task = self._due_task()
        result, message = executors.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("第 1/3 次", message)
        self.assertEqual(["99"], self._completed_lore_ids())

    def test_auth_failure_pauses_on_claim(self):
        self._stub(claim_response=(429, {}, "rate limited"))
        result, message = executors.execute_reamicro_task(self._due_task())
        self.assertEqual("paused", result)
        self.assertIn("429", message)

    # ---------- 其余不变 ----------

    def test_waiting_period_unchanged(self):
        self._stub()
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        task = self._task(
            lastCheckinDate=datetime.now(CHINA).date().isoformat(),
            claimDueAt=now_ms + 4 * 3_600_000,
        )
        result, message = executors.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("8 小时后自动领取", message)
        self.assertEqual([], self._completed_lore_ids())

    def test_response_end_time_controls_claim_schedule(self):
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        end_seconds = (now_ms + 30 * 60_000) // 1000
        self._stub(lore={"code": 0, "data": {
            "id": 99, "isFinish": True, "claimed": False, "endTime": end_seconds,
        }})
        task = self._task(lastCheckinDate="")
        result, message = executors.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("8 小时后自动领取", message)
        self.assertEqual(end_seconds * 1000, task["claimDueAt"])
        self.assertEqual(end_seconds * 1000, task["nextRunAtOverride"])

    def test_millisecond_end_time_is_preserved(self):
        timestamp = 1_787_968_800_000
        self.assertEqual(timestamp, executors.timestamp_millis(timestamp))

    def test_initial_checkin_business_error_is_not_success(self):
        self._stub(
            lore={"code": 0, "data": {"id": 99, "isFinish": False, "claimed": False}},
            claim_response=(200, {"code": 500, "message": "签到失败"}, "{}"),
        )
        result, message = executors.execute_reamicro_task(self._task())
        self.assertEqual("failed", result)
        self.assertIn("阅微业务码 500", message)

    def test_claim_already_granted_patterns(self):
        detect = executors.claim_reward_already_granted
        for text in ("该奖励已领取", "已经领取", "奖励已领过", "重复领取", "已发放", "already claimed", "Already Received"):
            self.assertTrue(detect(text), f"{text} 应判定为已领取")
        for text in ("服务器繁忙", "网络异常", "参数错误", "未达到领取条件", ""):
            self.assertFalse(detect(text), f"{text} 不应判定为已领取")
        self.assertTrue(detect("", {"data": {"claimed": True}}))
        self.assertFalse(detect("", {"data": {"claimed": False}}))

    def test_reward_not_ready_patterns(self):
        for text in ("未达到领取条件", "尚未解锁", "未到领取时间", "暂不可领取"):
            self.assertTrue(executors.reward_not_ready(text), f"{text} 应判定为尚未解锁")
        self.assertFalse(executors.reward_not_ready("服务器繁忙"))


if __name__ == "__main__":
    unittest.main()
