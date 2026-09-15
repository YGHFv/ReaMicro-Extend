import asyncio
from unittest.mock import patch

from app import executors, scheduler, state
from tests.conftest_support import client, module_headers, seed_credential, unwrap
from tests.test_automation_blessing_pawn import _Base


class AutomationAuditTest(_Base):
    def run_scheduler_once(self, execute):
        class StopLoop(BaseException):
            pass

        async def stop(_seconds):
            raise StopLoop()

        with patch.object(scheduler, "execute_task", execute), patch.object(scheduler.asyncio, "sleep", stop):
            with self.assertRaises(StopLoop):
                asyncio.run(scheduler.task_scheduler_loop())

    def test_scheduler_does_not_overwrite_other_tasks_created_during_networking(self):
        state.save_tasks({"due": {"id": "due", "taskType": "pawn", "enabled": True, "status": "scheduled", "nextRunAt": 1}})

        def execute(task):
            current = state.load_tasks()
            scheduler.apply_task_action(current["due"], "pause")
            current["new"] = {"id": "new", "enabled": False, "status": "paused"}
            state.save_tasks(current)
            task["pawnUsedToday"] = 1
            return "success", "done"

        self.run_scheduler_once(execute)
        stored = state.load_tasks()
        self.assertIn("new", stored)
        self.assertFalse(stored["due"]["enabled"])
        self.assertEqual("paused", stored["due"]["status"])
        self.assertEqual(1, stored["due"]["pawnUsedToday"])

    def test_scheduler_does_not_resurrect_a_deleted_running_task(self):
        state.save_tasks({"due": {"id": "due", "taskType": "pawn", "enabled": True, "status": "scheduled", "nextRunAt": 1}})

        def execute(_task):
            state.save_tasks({})
            return "success", "done"

        self.run_scheduler_once(execute)
        self.assertEqual({}, state.load_tasks())

    def test_pawn_can_be_created_through_api_without_forcing_retries(self):
        seed_credential()
        response = client().post("/v1/tasks", headers=module_headers(), json={
            "taskType": "pawn", "request": {"credentialId": "rea_1"}, "maxRetries": 0,
        })
        self.assertEqual(200, response.status_code)
        self.assertEqual("pawn", unwrap(response)["taskType"])
        self.assertEqual(0, unwrap(response)["maxRetries"])

    def test_lower_daily_read_cap_is_respected(self):
        self.replies([{}])
        task = self.task("cloud_auto_read", {"books": [{"bookId": 1}], "durationMinutes": 30, "dailyLimitMinutes": 10})
        self.assertEqual("success", executors.execute_task(task)[0])
        self.assertEqual(10, task["dailyReadMinutes"])

    def test_inflight_execution_preserves_pause_and_progress(self):
        original = {"status": "running", "enabled": True, "nextRunAt": 1000, "taskType": "cloud_auto_read"}
        current = {**original, "status": "paused", "enabled": False, "nextRunAt": 0}
        executed = {**original, "dailyReadMinutes": 30}
        result = scheduler.complete_task_execution(current, original, executed, "success", "read", 1000, 2000)
        self.assertEqual("paused", result["status"])
        self.assertFalse(result["enabled"])
        self.assertEqual(0, result["nextRunAt"])
        self.assertEqual(30, result["dailyReadMinutes"])
        self.assertEqual(1, result["runCount"])

    def test_inflight_schedule_change_is_used_for_next_run(self):
        original = {"status": "running", "enabled": True, "taskType": "pawn", "schedule": {"intervalSeconds": 3600}}
        current = {**original, "schedule": {"intervalSeconds": 7200}, "updatedAt": 1500}
        result = scheduler.complete_task_execution(current, original, original, "success", "done", 1000, 2000)
        self.assertEqual(2000 + 7200_000, result["nextRunAt"])
        self.assertEqual("success", result["status"])

    def test_inflight_manual_draw_request_survives_current_success(self):
        original = {"status": "running", "enabled": True, "taskType": "yeshe_draw_card", "triggeredByCheckinReward": True}
        current = dict(original)
        scheduler.apply_task_action(current, "run", 1500)
        executed = {**original, "triggeredByCheckinReward": False, "dailyCounter": 1}
        result = scheduler.complete_task_execution(current, original, executed, "success", "drawn", 1000, 2000)
        self.assertEqual("scheduled", result["status"])
        self.assertEqual(1500, result["nextRunAt"])
        self.assertTrue(result["triggeredByCheckinReward"])
        self.assertEqual(1, result["dailyCounter"])

    def test_reward_draw_failure_uses_normal_retry_budget(self):
        task = {"status": "running", "enabled": True, "taskType": "yeshe_draw_card", "triggeredByCheckinReward": True, "maxRetries": 1}
        result = scheduler.complete_task_execution(task, task, task, "failed", "temporary", 1000, 2000)
        self.assertEqual("scheduled", result["status"])
        self.assertEqual(302000, result["nextRunAt"])
        self.assertTrue(result["triggeredByCheckinReward"])
        result["status"] = "running"
        exhausted = scheduler.complete_task_execution(result, result, result, "failed", "temporary", 303000, 304000)
        self.assertFalse(exhausted["enabled"])
        self.assertEqual("paused", exhausted["status"])

    def replies(self, responses):
        pending = list(responses)

        def request(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload))
            return 200, {"code": 0, "data": pending.pop(0)}, ""

        executors.json_http_request = request

    def test_main_dispatcher_reaches_pawn_executor(self):
        self.replies([{"remaining": 0}])
        result, message = executors.execute_task(self.task("pawn"))
        self.assertEqual("success", result)
        self.assertIn("次数已用完", message)
        self.assertEqual("rest/community/get-pawn-count", self.calls[0][0])

    def test_linked_draw_is_queued_for_normal_lock_and_retry_path(self):
        checkin = self.task("yeshe_checkin")
        checkin["claimJustCompleted"] = True
        draw = self.task("yeshe_draw_card")
        result = scheduler.schedule_linked_draw_task({"draw": draw}, checkin, 12345)
        self.assertEqual("scheduled", result[0])
        self.assertEqual("scheduled", draw["status"])
        self.assertEqual(12345, draw["nextRunAt"])
        self.assertTrue(draw["triggeredByCheckinReward"])
        self.assertEqual([], self.calls)

    def test_draw_failure_preserves_event_for_retry(self):
        task = self.task("yeshe_draw_card", {"dailyLimit": 1})
        task["triggeredByCheckinReward"] = True
        self.replies([{"success": False, "message": "临时失败"}, {"success": True, "props": []}])
        self.assertEqual("failed", executors.execute_task(task)[0])
        self.assertTrue(task["triggeredByCheckinReward"])
        self.assertEqual("success", executors.execute_task(task)[0])
        self.assertFalse(task["triggeredByCheckinReward"])
        self.assertEqual(2, len(self.calls))

    def test_draw_event_is_not_scheduled_for_disabled_task(self):
        checkin = self.task("yeshe_checkin")
        checkin["claimJustCompleted"] = True
        draw = self.task("yeshe_draw_card")
        draw["enabled"] = False
        self.assertIsNone(scheduler.schedule_linked_draw_task({"draw": draw}, checkin, 12345))
        self.assertFalse(draw.get("triggeredByCheckinReward", False))

    def test_multi_book_duration_is_counted_and_capped(self):
        self.replies([{}, {}])
        task = self.task("cloud_auto_read", {"books": [{"bookId": 1}, {"bookId": 2}, {"bookId": 3}],
                                            "bookLimit": 3, "durationMinutes": 30, "dailyLimitMinutes": 50})
        self.assertEqual("success", executors.execute_task(task)[0])
        self.assertEqual(50, task["dailyReadMinutes"])
        self.assertEqual([1800, 1200], [payload["list"][0]["duration"] for _, payload in self.calls])

    def test_partial_read_failure_keeps_completed_progress(self):
        self.replies([{}])
        task = self.task("cloud_auto_read", {"books": [{"bookId": 1}, {"bookId": 2}],
                                            "bookLimit": 2, "durationMinutes": 30})
        self.assertEqual("failed", executors.execute_task(task)[0])
        self.assertEqual(30, task["dailyReadMinutes"])
        self.assertEqual(1, task["bookRotation"])

    def test_pawn_false_success_is_not_counted(self):
        self.replies([
            {"remaining": 2, "specialPropId": 999, "specialPropName": "测试期物"},
            {"materials": [{"propId": 999, "userPropId": 42, "quantity": 2}]},
            {"success": True, "coin": 8},
            {"success": False, "message": "频繁"},
        ])
        task = self.task("pawn")
        self.assertEqual("failed", executors.execute_task(task)[0])
        self.assertEqual(1, task["pawnUsedToday"])
        self.assertEqual(8, task["pawnLastCoin"])
