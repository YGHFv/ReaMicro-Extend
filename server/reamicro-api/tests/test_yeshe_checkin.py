"""野社每日签到与签到奖励领取回归测试。

历史问题：要领的是每日签到 8 小时后解锁的任务奖励，但代码调的是文社**周**奖励接口
`claim-literary-society-weekly-reward`。那个奖励早就领过，接口一直回
"上一周奖励已领取"，被当成可重试失败，于是每天「5 分钟 ×3」+「23:59 最后重试」。
正确链路（阅微 2.3.1 反编译确认）是 rest/task/get-my-task-list + rest/task/receive-reward。
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

    def _stub(self, task_list, claim_response=(200, {"data": {"success": True}}, "{}")):
        """打桩阅微接口：轶闻已签到，任务列表与领取结果按参数给出。"""
        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload if isinstance(payload, dict) else {}))
            if "get-daily-lore" in endpoint:
                return 200, {"data": {"id": 99, "isFinish": True}}, "{}"
            if "get-my-task-list" in endpoint:
                return task_list
            if "receive-reward" in endpoint:
                return claim_response
            return 200, {"data": {}}, "{}"
        executors.json_http_request = fake

    def _endpoints(self) -> list[str]:
        return [endpoint for endpoint, _ in self.calls]

    def _claimed_ids(self) -> list[str]:
        return [str(payload.get("id")) for endpoint, payload in self.calls if "receive-reward" in endpoint]

    # ---------- 核心：正确的接口 ----------

    def test_uses_daily_task_endpoints_not_weekly_reward(self):
        self._stub((200, {"data": {"tasks": [{"id": "t1", "status": 2, "content": "每日签到"}]}}, "{}"))
        task = self._due_task()
        result, message = executors.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("rest/task/get-my-task-list", self._endpoints())
        self.assertIn("rest/task/receive-reward", self._endpoints())
        self.assertNotIn(
            "rest/community/claim-literary-society-weekly-reward",
            self._endpoints(),
            "绝不能再调文社周奖励接口",
        )
        self.assertIn("领取成功", message)
        self.assertEqual(["t1"], self._claimed_ids())

    def test_claims_every_claimable_task(self):
        self._stub((200, {"data": {"tasks": [
            {"id": "t1", "status": 2, "content": "每日签到"},
            {"id": "t2", "status": 2, "content": "阅读 30 分钟"},
            {"id": "t3", "status": 1, "content": "已领过的"},
            {"id": "t4", "status": 0, "content": "还没完成"},
        ]}}, "{}"))
        task = self._due_task()
        result, message = executors.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertEqual(["t1", "t2"], self._claimed_ids(), "只领 status=2 的项")
        self.assertIn("2 项", message)

    def test_query_type_is_daily(self):
        self._stub((200, {"data": {"tasks": []}}, "{}"))
        executors.execute_reamicro_task(self._due_task())
        payload = next(p for e, p in self.calls if "get-my-task-list" in e)
        self.assertEqual(executors.DAILY_TASK_QUERY_TYPE, payload["queryType"])

    # ---------- 三类非失败情形 ----------

    def test_already_claimed_is_success_without_retry(self):
        self._stub((200, {"data": {"tasks": [{"id": "t1", "status": 1, "content": "每日签到"}]}}, "{}"))
        task = self._due_task()
        result, message = executors.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("此前已领取", message)
        self.assertNotIn("自动重试", message)
        self.assertEqual(0, task["claimRetryCount"])
        self.assertNotIn("nextRunAtOverride", task)
        self.assertEqual([], self._claimed_ids(), "已领取不应再调领取接口")

    def test_locked_reward_waits_without_retry(self):
        """奖励还没解锁：不算失败，也不进重试循环。"""
        self._stub((200, {"data": {"tasks": [{"id": "t1", "status": 0, "content": "还没完成"}]}}, "{}"))
        task = self._due_task()
        result, message = executors.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("暂无可领取", message)
        self.assertEqual(0, task["claimRetryCount"])
        self.assertNotIn("nextRunAtOverride", task)
        self.assertNotIn("claimCompletedDate", task, "未领取不应标记为今日已完成")

    def test_single_item_already_claimed_does_not_abort_batch(self):
        """某一项回"已领取"时继续领其余项。"""
        responses = iter([
            (200, {"data": {"success": False, "message": "该奖励已领取"}}, "{}"),
            (200, {"data": {"success": True, "message": "领取成功"}}, "{}"),
        ])
        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload if isinstance(payload, dict) else {}))
            if "get-daily-lore" in endpoint:
                return 200, {"data": {"id": 99, "isFinish": True}}, "{}"
            if "get-my-task-list" in endpoint:
                return 200, {"data": {"tasks": [
                    {"id": "t1", "status": 2, "content": "甲"},
                    {"id": "t2", "status": 2, "content": "乙"},
                ]}}, "{}"
            if "receive-reward" in endpoint:
                return next(responses)
            return 200, {"data": {}}, "{}"
        executors.json_http_request = fake
        result, message = executors.execute_reamicro_task(self._due_task())
        self.assertEqual("success", result)
        self.assertEqual(["t1", "t2"], self._claimed_ids())
        self.assertIn("2 项", message)

    def test_already_claimed_triggers_linked_draw(self):
        self._stub((200, {"data": {"tasks": [{"id": "t1", "status": 1, "content": "每日签到"}]}}, "{}"))
        task = self._due_task()
        executors.execute_reamicro_task(task)
        self.assertTrue(task.get("claimJustCompleted"), "已领取也要放行联动抽卡")

    def test_granted_triggers_linked_draw(self):
        self._stub((200, {"data": {"tasks": [{"id": "t1", "status": 2, "content": "每日签到"}]}}, "{}"))
        task = self._due_task()
        executors.execute_reamicro_task(task)
        self.assertTrue(task.get("claimJustCompleted"))

    def test_locked_does_not_trigger_linked_draw(self):
        self._stub((200, {"data": {"tasks": []}}, "{}"))
        task = self._due_task()
        executors.execute_reamicro_task(task)
        self.assertFalse(task.get("claimJustCompleted"), "没领到奖励不应触发抽卡")

    # ---------- 每天都要重新领 ----------

    def test_claims_again_next_day(self):
        """每日奖励必须每天都领，不能被任何"已完成"标记长期压掉。"""
        self._stub((200, {"data": {"tasks": [{"id": "t1", "status": 2, "content": "每日签到"}]}}, "{}"))
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
        self._stub((200, {"data": {"tasks": [{"id": "t1", "status": 2, "content": "每日签到"}]}}, "{}"))
        task = self._due_task()
        executors.execute_reamicro_task(task)
        self.calls.clear()
        result, message = executors.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("今日已领取", message)
        self.assertEqual([], self._claimed_ids(), "同一天不重复领取")

    # ---------- 真实失败仍走重试 ----------

    def test_genuine_claim_failure_retries(self):
        self._stub(
            (200, {"data": {"tasks": [{"id": "t1", "status": 2, "content": "每日签到"}]}}, "{}"),
            claim_response=(200, {"data": {"success": False, "message": "服务器繁忙，请稍后再试"}}, "{}"),
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
            (200, {"data": {"tasks": [{"id": "t1", "status": 2, "content": "每日签到"}]}}, "{}"),
            claim_response=(200, {"data": {"success": False, "message": "服务器繁忙"}}, "{}"),
        )
        result, message = executors.execute_reamicro_task(self._due_task(claimRetryCount=3))
        self.assertEqual("success", result)
        self.assertIn("23:59 最后重试", message)

    def test_task_list_http_error_retries(self):
        self._stub((500, {}, "internal error"))
        task = self._due_task()
        result, message = executors.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("第 1/3 次", message)
        self.assertEqual([], self._claimed_ids())

    def test_auth_failure_pauses_on_list(self):
        self._stub((401, {}, "unauthorized"))
        result, message = executors.execute_reamicro_task(self._due_task())
        self.assertEqual("paused", result)
        self.assertIn("401", message)

    def test_auth_failure_pauses_on_claim(self):
        self._stub(
            (200, {"data": {"tasks": [{"id": "t1", "status": 2, "content": "每日签到"}]}}, "{}"),
            claim_response=(429, {}, "rate limited"),
        )
        result, message = executors.execute_reamicro_task(self._due_task())
        self.assertEqual("paused", result)
        self.assertIn("429", message)

    # ---------- 其余不变 ----------

    def test_waiting_period_unchanged(self):
        self._stub((200, {"data": {"tasks": []}}, "{}"))
        now_ms = int(datetime.now(timezone.utc).timestamp() * 1000)
        task = self._task(
            lastCheckinDate=datetime.now(CHINA).date().isoformat(),
            claimDueAt=now_ms + 4 * 3_600_000,
        )
        result, message = executors.execute_reamicro_task(task)
        self.assertEqual("success", result)
        self.assertIn("8 小时后自动领取", message)
        self.assertNotIn("rest/task/get-my-task-list", self._endpoints())

    def test_claim_already_granted_patterns(self):
        detect = executors.claim_reward_already_granted
        for text in ("该奖励已领取", "已经领取", "奖励已领过", "重复领取", "已发放", "already claimed", "Already Received"):
            self.assertTrue(detect(text), f"{text} 应判定为已领取")
        for text in ("服务器繁忙", "网络异常", "参数错误", "未达到领取条件", ""):
            self.assertFalse(detect(text), f"{text} 不应判定为已领取")
        self.assertTrue(detect("", {"data": {"claimed": True}}))
        self.assertFalse(detect("", {"data": {"claimed": False}}))

    def test_tolerates_alternate_list_shapes(self):
        """响应结构是从反编译推的，兼容 data.list 与 data 直接是数组两种形态。"""
        for body in (
            {"data": {"list": [{"id": "t1", "status": 2, "content": "每日签到"}]}},
            {"data": [{"id": "t1", "status": 2, "content": "每日签到"}]},
        ):
            self.calls.clear()
            self._stub((200, body, "{}"))
            result, _ = executors.execute_reamicro_task(self._due_task())
            self.assertEqual("success", result)
            self.assertEqual(["t1"], self._claimed_ids(), f"未能解析 {list(body['data'])}")


if __name__ == "__main__":
    unittest.main()
