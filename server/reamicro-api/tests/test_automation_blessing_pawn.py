"""行商结算播报、道观运签、期物典当的新行为回归测试。"""
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


class _Base(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        isolate(Path(self.temp_dir.name), secret_key="test-secret-automation")
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

    def task(self, task_type: str, request_extra=None):
        request = {"credentialId": "rea_1"}
        if request_extra:
            request.update(request_extra)
        return {
            "id": "task_x",
            "owner": "host:3",
            "taskType": task_type,
            "credentialId": "rea_1",
            "requestEncrypted": crypto.encrypt_secret(request),
            "status": "scheduled",
            "enabled": True,
        }


class MerchantSettledNotificationTest(_Base):
    """游戏在 endTime 到达时就把状态置成 SETTLED，所以结算分支才是常见路径。"""

    def _settled(self, extra=None):
        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload))
            if endpoint == "rest/community/get-traveling-merchant":
                trip = {
                    "id": 42, "status": "SETTLED", "endTime": (_now_ms() - 60_000) // 1000,
                    "settlementAmount": 379, "principal": 120, "eventTitle": "购朝鲜马",
                    "cityCode": "CHANGAN", "transportId": 9,
                }
                if extra:
                    trip.update(extra)
                return 200, {"code": 0, "data": {"activeTrip": trip}}, "{}"
            return 200, {"code": 0, "data": {}}, "{}"

        executors.json_http_request = fake

    def test_settled_trip_reports_event_and_profit(self):
        self._settled()
        task = self.task("traveling_merchant")
        result, message = executors.execute_traveling_merchant_task(task)
        self.assertEqual("success", result)
        # 以前这里只说一句"行商已结算"，通知里没有事件也没有收益/亏损。
        self.assertIn("购朝鲜马", message)
        self.assertIn("收益 +259", message)
        self.assertEqual(42, task["merchantLastNotifiedTripId"])

    def test_same_settled_trip_only_reported_once(self):
        self._settled()
        task = self.task("traveling_merchant")
        executors.execute_traveling_merchant_task(task)
        _, second = executors.execute_traveling_merchant_task(task)
        self.assertNotIn("购朝鲜马", second)

    def test_settled_with_auto_complete_starts_next_trip_from_remembered_config(self):
        # 用户没填城池/车马/本金：应沿用这趟行商实际用的参数，而不是直接放弃开新行商。
        self._settled()
        task = self.task("traveling_merchant", {"merchantAutoComplete": True})
        _, message = executors.execute_traveling_merchant_task(task)
        self.assertIn("已开启新行商", message)
        start_calls = [payload for endpoint, payload in self.calls if endpoint == "rest/community/start-traveling-merchant"]
        self.assertEqual([{"cityCode": "CHANGAN", "principal": 120, "transportId": 9}], start_calls)
        self.assertEqual("CHANGAN", task["merchantLastCityCode"])

    def test_missing_config_reports_what_is_missing(self):
        self._settled(extra={"cityCode": "", "transportId": 0})
        task = self.task("traveling_merchant", {"merchantAutoComplete": True})
        _, message = executors.execute_traveling_merchant_task(task)
        self.assertIn("未能开启新行商", message)
        self.assertIn("缺少城池", message)

    def test_no_trip_with_auto_complete_starts_one(self):
        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload))
            if endpoint == "rest/community/get-traveling-merchant":
                return 200, {"code": 0, "data": {"activeTrip": None}}, "{}"
            return 200, {"code": 0, "data": {}}, "{}"

        executors.json_http_request = fake
        task = self.task("traveling_merchant", {
            "merchantAutoComplete": True,
            "merchantCityCode": "PENGLAI",
            "merchantTransportId": 7,
            "merchantPrincipal": 500,
        })
        _, message = executors.execute_traveling_merchant_task(task)
        # 没有在途行商时原来只会静默等待，链路一旦断掉就再也不开新行商。
        self.assertIn("已按配置开启新行商", message)
        self.assertEqual(1, len([1 for e, _ in self.calls if e == "rest/community/start-traveling-merchant"]))


class TaoistBlessingTest(_Base):
    def _http(self, blessing):
        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload))
            if endpoint == "rest/community/get-taoist-blessing":
                return 200, {"code": 0, "data": {"blessing": blessing}}, "{}"
            return 200, {"code": 0, "data": {"success": True}}, "{}"

        executors.json_http_request = fake

    def test_prays_when_no_blessing(self):
        self._http(None)
        failure = executors.ensure_taoist_blessing("https://example.invalid/", "t", {}, "LUCK")
        self.assertIsNone(failure)
        self.assertEqual(
            ["rest/community/get-taoist-blessing", "rest/community/pray-taoist-blessing"],
            [e for e, _ in self.calls],
        )
        self.assertEqual("LUCK", self.calls[1][1]["blessingType"])

    def test_keeps_existing_blessing(self):
        # 已有签就不替换：替换会白白消耗祈禳道具。
        self._http({"blessingType": "SAFETY", "name": "平安签"})
        self.assertIsNone(executors.ensure_taoist_blessing("https://example.invalid/", "t", {}, "WEALTH"))
        self.assertEqual(["rest/community/get-taoist-blessing"], [e for e, _ in self.calls])

    def test_blank_type_does_nothing(self):
        self._http(None)
        self.assertIsNone(executors.ensure_taoist_blessing("https://example.invalid/", "t", {}, ""))
        self.assertEqual([], self.calls)

    def test_blessing_label_covers_all_three(self):
        self.assertEqual("求运签", executors.blessing_label("LUCK"))
        self.assertEqual("求安签", executors.blessing_label("safety"))
        self.assertEqual("求财签", executors.blessing_label("WEALTH"))
        self.assertEqual("", executors.blessing_label(""))


class PawnTaskTest(_Base):
    def _http(self, remaining=2, prop_id="21", materials=None, coin=88):
        def fake(url, token, payload, endpoint="", timeout=45):
            self.calls.append((endpoint, payload))
            if endpoint == "rest/community/get-pawn-count":
                return 200, {"code": 0, "data": {
                    "remaining": remaining, "maxPerDay": 3, "usedToday": 3 - remaining,
                    "specialPropId": prop_id, "specialPropName": "残卷",
                }}, "{}"
            if endpoint == "rest/community/get-user-materials":
                return 200, {"code": 0, "data": {"materials": materials or []}}, "{}"
            return 200, {"code": 0, "data": {"coin": coin}}, "{}"

        executors.json_http_request = fake

    def test_pawns_every_available_copy(self):
        self._http(remaining=2, materials=[{"propId": "21", "quantity": 5, "userPropId": 777}])
        task = self.task("pawn")
        result, message = executors.execute_pawn_task(task)
        self.assertEqual("success", result)
        self.assertIn("典当「残卷」2 件", message)
        self.assertIn("176", message)
        self.assertEqual(2, len([1 for e, _ in self.calls if e == "rest/community/pawn"]))
        # usedToday 原本是 1（maxPerDay 3 - remaining 2），这次又典当 2 件。
        self.assertEqual(3, task["pawnUsedToday"])

    def test_prohibited_prop_is_skipped(self):
        # 祈禳/传承/夺宝要用的消耗品不能典当掉。
        self._http(remaining=2, prop_id="12", materials=[{"propId": "12", "quantity": 5, "userPropId": 1}])
        task = self.task("pawn")
        result, message = executors.execute_pawn_task(task)
        self.assertEqual("success", result)
        self.assertIn("跳过典当", message)
        self.assertEqual([], [e for e, _ in self.calls if e == "rest/community/pawn"])

    def test_daily_limit_exhausted_skips(self):
        self._http(remaining=0)
        task = self.task("pawn")
        result, message = executors.execute_pawn_task(task)
        self.assertEqual("success", result)
        self.assertIn("已用完", message)
        self.assertEqual(1, len(self.calls))

    def test_quantity_caps_the_loop(self):
        # 背包只有 1 件，即使还剩 2 次也只典当 1 件。
        self._http(remaining=2, materials=[{"propId": "21", "quantity": 1, "userPropId": 777}])
        task = self.task("pawn")
        _, message = executors.execute_pawn_task(task)
        self.assertIn("1 件", message)
        self.assertEqual(1, len([1 for e, _ in self.calls if e == "rest/community/pawn"]))

    def test_missing_material_skips(self):
        self._http(remaining=2, materials=[])
        task = self.task("pawn")
        result, message = executors.execute_pawn_task(task)
        self.assertEqual("success", result)
        self.assertIn("没有期物", message)
