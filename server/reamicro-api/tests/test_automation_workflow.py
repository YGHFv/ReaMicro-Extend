import unittest
from datetime import datetime, timedelta, timezone

from app import executors, scheduler
from tests.test_automation_blessing_pawn import _Base


class AutomationWorkflowTest(_Base):
    def setUp(self):
        super().setUp()
        self.now = int(datetime.now(timezone.utc).timestamp() * 1000)
        self.today = datetime.now(timezone(timedelta(hours=8))).date().isoformat()
        self.replies = {"get-taoist-blessing": [{"blessing": None}]}

        def fake(url, token, payload, endpoint="", timeout=45):
            name = endpoint.rsplit("/", 1)[-1]
            self.calls.append((name, payload))
            queue = self.replies.get(name)
            if not queue:
                return 200, {"code": 500, "message": f"未配置测试响应：{name}"}, "{}"
            data = queue.pop(0) if len(queue) > 1 else queue[0]
            return 200, {"code": 0, "data": data}, "{}"

        executors.json_http_request = fake

    def reply(self, endpoint, *data):
        self.replies[endpoint] = list(data)

    def lore(self, finished=False, end=None):
        return {"id": 99, "title": "测试轶闻", "isFinish": finished, "claimed": False,
                "endTime": self.now + 3_600_000 if end is None else end, "exp": 5}

    def trip(self, status="SETTLED", trip_id=42, end=None):
        return {"id": trip_id, "status": status, "endTime": self.now - 1000 if end is None else end,
                "cityCode": "LANGYA", "transportId": 5, "principal": 120,
                "settlementAmount": 379, "eventTitle": "购朝鲜马"}

    def merchant_task(self):
        return self.task("traveling_merchant", {"merchantAutoComplete": True})

    def prepare_merchant(self):
        self.reply("get-traveling-merchant", {"activeTrip": self.trip()})
        self.reply("settle-traveling-merchant", {"success": True, "trip": self.trip()})
        self.reply("start-traveling-merchant", {"success": True, "trip": self.trip("TRAVELING", 43, self.now + 3_600_000)})

    def endpoints(self):
        return [endpoint for endpoint, _ in self.calls]

    def test_checkin_none_does_not_pray_or_claim_early(self):
        self.reply("get-daily-lore", self.lore())
        task = self.task("yeshe_checkin", {"blessingType": ""})
        result, message = executors.execute_task(task)
        self.assertEqual("success", result)
        self.assertNotIn("pray-taoist-blessing", self.endpoints())
        self.assertNotIn("complete-daily-lore", self.endpoints())
        self.assertNotIn("已祈禳", message)
        self.assertEqual(self.now + 3_600_000, task["nextRunAtOverride"])

    def test_luck_replaces_wealth_before_generating_lore(self):
        self.reply("get-taoist-blessing", {"blessing": {"blessingType": "WEALTH"}})
        self.reply("pray-taoist-blessing", {"success": True, "blessing": {"blessingType": "LUCK"}})
        self.reply("get-daily-lore", self.lore())
        result, message = executors.execute_task(self.task("yeshe_checkin", {"blessingType": "LUCK"}))
        self.assertEqual("success", result)
        self.assertLess(self.endpoints().index("pray-taoist-blessing"), self.endpoints().index("get-daily-lore"))
        self.assertEqual("LUCK", self.calls[1][1]["blessingType"])
        self.assertIn("已祈禳求运签", message)

    def test_claim_stage_does_not_pray_and_ignores_stale_due_time(self):
        self.reply("get-daily-lore", self.lore(True, self.now - 1000))
        self.reply("complete-daily-lore", None)
        task = self.task("yeshe_checkin", {"blessingType": "LUCK"})
        task.update(lastCheckinDate=self.today, claimDueAt=self.now + 8 * 3_600_000)
        result, _ = executors.execute_task(task)
        self.assertEqual("success", result)
        self.assertEqual(self.today, task["claimCompletedDate"])
        self.assertNotIn("pray-taoist-blessing", self.endpoints())
        self.assertEqual(1, self.endpoints().count("complete-daily-lore"))

    def test_unknown_end_time_keeps_querying(self):
        self.reply("get-daily-lore", self.lore(end=0))
        task = self.task("yeshe_checkin")
        result, _ = executors.execute_task(task)
        self.assertEqual("success", result)
        self.assertLessEqual(task["nextRunAtOverride"], self.now + 3_660_000)
        self.assertNotIn("complete-daily-lore", self.endpoints())

    def test_inner_pray_failure_does_not_generate_lore(self):
        self.reply("pray-taoist-blessing", {"success": False, "message": "道具不足"})
        self.reply("get-daily-lore", self.lore())
        result, message = executors.execute_task(self.task("yeshe_checkin", {"blessingType": "LUCK"}))
        self.assertEqual("failed", result)
        self.assertNotIn("get-daily-lore", self.endpoints())
        self.assertNotIn("已祈禳", message)

    def test_notified_settled_trip_still_claims_then_restarts(self):
        self.prepare_merchant()
        task = self.merchant_task()
        task["merchantLastNotifiedTripId"] = 42
        result, message = executors.execute_task(task)
        self.assertEqual("success", result)
        self.assertEqual(["get-traveling-merchant", "settle-traveling-merchant", "start-traveling-merchant"], self.endpoints())
        self.assertEqual(42, task["merchantSettledTripId"])
        self.assertEqual(42, task["merchantRestartedAfterTripId"])
        self.assertEqual(self.now + 3_660_000, task["nextRunAtOverride"])
        self.assertIn("收益 +259", message)

    def test_inner_settlement_failure_does_not_start(self):
        self.prepare_merchant()
        self.reply("settle-traveling-merchant", {"success": False, "message": "尚未结算"})
        task = self.merchant_task()
        result, _ = executors.execute_task(task)
        self.assertEqual("failed", result)
        self.assertNotIn("start-traveling-merchant", self.endpoints())
        self.assertNotIn("merchantSettledTripId", task)

    def test_start_failure_retries_without_reclaiming(self):
        self.prepare_merchant()
        self.reply("start-traveling-merchant", {"success": False, "message": "铜钱不足"},
                   {"success": True, "trip": self.trip("TRAVELING", 43, self.now + 3_600_000)})
        task = self.merchant_task()
        result, message = executors.execute_task(task)
        self.assertEqual("failed", result)
        self.assertIn("铜钱不足", message)
        self.assertEqual(42, task["merchantSettledTripId"])
        self.assertNotIn("merchantRestartedAfterTripId", task)
        result, _ = executors.execute_task(task)
        self.assertEqual("success", result)
        self.assertEqual(1, self.endpoints().count("settle-traveling-merchant"))
        self.assertEqual(2, self.endpoints().count("start-traveling-merchant"))

    def test_traveling_trip_does_not_pray(self):
        self.reply("get-traveling-merchant", {"activeTrip": self.trip("TRAVELING", end=self.now + 3_600_000)})
        task = self.task("traveling_merchant", {"merchantAutoComplete": True, "blessingType": "WEALTH"})
        result, _ = executors.execute_task(task)
        self.assertEqual("success", result)
        self.assertEqual(["get-traveling-merchant"], self.endpoints())
        self.assertEqual(self.now + 3_660_000, task["nextRunAtOverride"])

    def test_overdue_override_does_not_skip_to_next_day(self):
        task = self.task("yeshe_checkin")
        task.update(nextRunAtOverride=self.now - 1000, schedule={"timeOfDay": "00:00"})
        self.assertEqual(self.now + 60_000, scheduler.next_task_run(task, self.now))

    def test_no_transport_trip_keeps_zero_instead_of_old_transport(self):
        self.prepare_merchant()
        trip = self.trip()
        trip["transportId"] = 0
        self.reply("get-traveling-merchant", {"activeTrip": trip})
        task = self.merchant_task()
        task["merchantLastTransportId"] = 9
        result, _ = executors.execute_task(task)
        self.assertEqual("success", result)
        starts = [payload for endpoint, payload in self.calls if endpoint == "start-traveling-merchant"]
        self.assertEqual(0, starts[0]["transportId"])

    def test_upgrade_recovers_legacy_merchant_once(self):
        task = self.merchant_task()
        task.update(nextRunAt=self.now + 3_600_000, merchantSettledTripId=42, merchantRestartedAfterTripId=42)
        self.assertTrue(scheduler.recover_automation_task(task, self.now))
        self.assertEqual(self.now, task["nextRunAt"])
        self.assertEqual(0, task["merchantSettledTripId"])
        self.assertEqual(0, task["merchantRestartedAfterTripId"])
        self.assertFalse(scheduler.recover_automation_task(task, self.now + 1000))

    def test_upgrade_does_not_resume_disabled_or_paused_tasks(self):
        for setting in ({"enabled": False}, {"status": "paused"}):
            task = self.merchant_task()
            task.update(setting)
            self.assertFalse(scheduler.recover_automation_task(task, self.now))


if __name__ == "__main__":
    unittest.main()
