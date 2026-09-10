"""行商通知任务执行回归测试。"""
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app import crypto, executors, state
from tests.conftest_support import isolate


def _now_ms() -> int:
    return int(datetime.now(timezone.utc).timestamp() * 1000)


class TravelingMerchantTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        isolate(Path(self.temp_dir.name), secret_key="test-secret-merchant")
        state.save_credentials({
            "rea_1": {
                "id": "rea_1",
                "owner": "host:3",
                "type": "reamicro",
                "accountId": "3",
                "secretEncrypted": crypto.encrypt_secret({
                    "token": "token-value",
                    "baseUrl": "https://example.invalid/",
                }),
                "enabled": True,
            },
        })
        self.original_request = executors.json_http_request
        self.calls: list[tuple[str, dict]] = []

    def tearDown(self):
        executors.json_http_request = self.original_request
        self.temp_dir.cleanup()

    def task(self, request_extra=None):
        request = {"credentialId": "rea_1"}
        if request_extra:
            request.update(request_extra)
        return {
            "id": "task_m",
            "owner": "host:3",
            "taskType": "traveling_merchant",
            "credentialId": "rea_1",
            "requestEncrypted": crypto.encrypt_secret(request),
            "status": "scheduled",
            "enabled": True,
        }

    def test_profit_text_gain_and_loss(self):
        self.assertEqual("购朝鲜马 · 收益 +259", executors.merchant_profit_text("购朝鲜马", 379, 120))
        self.assertEqual("行商 · 亏损 -50", executors.merchant_profit_text("", 70, 120))

    def test_no_active_trip_reschedules_without_notification(self):
        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload))
            return 200, {"code": 0, "data": {"cities": []}}, "{}"

        executors.json_http_request = fake
        task = self.task()
        result, message = executors.execute_traveling_merchant_task(task)
        self.assertEqual("success", result)
        self.assertIn("没有进行中的行商", message)
        self.assertGreater(task.get("nextRunAtOverride", 0), _now_ms())

    def test_in_transit_trip_pauses_until_end_time(self):
        end_time_ms = _now_ms() + 3_600_000
        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload))
            return 200, {"code": 0, "data": {"activeTrip": {
                "id": 42, "status": "RUNNING", "endTime": end_time_ms // 1000,
                "settlementAmount": 0, "principal": 120, "eventTitle": "购朝鲜马",
            }}}, "{}"

        executors.json_http_request = fake
        task = self.task()
        result, message = executors.execute_traveling_merchant_task(task)
        self.assertEqual("success", result)
        self.assertIn("进行中", message)
        # 下次检查排到 endTime 之后（暂停常规轮询）。
        self.assertGreaterEqual(task["nextRunAtOverride"], end_time_ms)

    def test_arrived_trip_notifies_profit_without_auto_complete(self):
        end_time_ms = _now_ms() - 60_000
        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload))
            return 200, {"code": 0, "data": {"activeTrip": {
                "id": 42, "status": "ARRIVED", "endTime": end_time_ms // 1000,
                "settlementAmount": 379, "principal": 120, "eventTitle": "购朝鲜马",
            }}}, "{}"

        executors.json_http_request = fake
        task = self.task()
        result, message = executors.execute_traveling_merchant_task(task)
        self.assertEqual("success", result)
        self.assertIn("购朝鲜马", message)
        self.assertIn("收益 +259", message)
        self.assertEqual(42, task["merchantLastNotifiedTripId"])
        # 未开启自动完成：只调用了 get，不调用 settle。
        self.assertEqual(["rest/community/get-traveling-merchant"], [e for e, _ in self.calls])

    def test_already_notified_trip_is_silent(self):
        end_time_ms = _now_ms() - 60_000
        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload))
            return 200, {"code": 0, "data": {"activeTrip": {
                "id": 42, "status": "ARRIVED", "endTime": end_time_ms // 1000,
                "settlementAmount": 379, "principal": 120, "eventTitle": "购朝鲜马",
            }}}, "{}"

        executors.json_http_request = fake
        task = self.task()
        task["merchantLastNotifiedTripId"] = 42
        result, message = executors.execute_traveling_merchant_task(task)
        self.assertEqual("success", result)
        self.assertIn("已通知", message)

    def test_auto_complete_settles_and_starts_new_trip(self):
        end_time_ms = _now_ms() - 60_000
        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload))
            if endpoint == "rest/community/get-traveling-merchant":
                return 200, {"code": 0, "data": {"activeTrip": {
                    "id": 42, "status": "ARRIVED", "endTime": end_time_ms // 1000,
                    "settlementAmount": 379, "principal": 120, "eventTitle": "购朝鲜马",
                }}}, "{}"
            return 200, {"code": 0, "data": {}}, "{}"

        executors.json_http_request = fake
        task = self.task({
            "merchantAutoComplete": True,
            "merchantCityCode": "PENGLAI",
            "merchantPrincipal": 120,
            "merchantTransportId": 5,
        })
        result, message = executors.execute_traveling_merchant_task(task)
        self.assertEqual("success", result)
        self.assertIn("已自动完成行商", message)
        self.assertIn("开启新行商", message)
        endpoints = [e for e, _ in self.calls]
        self.assertEqual([
            "rest/community/get-traveling-merchant",
            "rest/community/settle-traveling-merchant",
            "rest/community/start-traveling-merchant",
        ], endpoints)


if __name__ == "__main__":
    unittest.main()
