"""Root panel presentation and explicit action routing; no device/Root mutations."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/reamicro/fix"
PREFIXES = ("root_", "execution_", "error_ksu_", "toast_ksu_", "ksu_", "error_root_", "error_execution_")

class RootPanelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = (JAVA / "ui/ModuleMainActivity.kt").read_text()
        cls.ui = (JAVA / "ui/RootEnhancementComponents.kt").read_text()

    def test_diagnostics_route_and_gesture_branch_are_removed(self):
        for token in ("RootDiagnosticsDialog", "rootDiagnosticsDialog", "onDiagnostics"):
            self.assertNotIn(token, self.main + self.ui)
        for locale in ("values", "values-en"):
            text = (ROOT / f"app/src/main/res/{locale}/strings.xml").read_text()
            self.assertNotIn('name="root_diagnostics', text)

    def test_inspection_and_sync_have_independent_handlers(self):
        inspect = self.main.split("private fun inspectRoot()", 1)[1].split("private fun syncRootTasks()", 1)[0]
        sync = self.main.split("private fun syncRootTasks()", 1)[1].split("private fun LauncherIconPicker()", 1)[0]
        self.assertIn("RootTaskBridge.inspect(applicationContext)", inspect)
        self.assertNotIn("RootTaskBridge.synchronize(", inspect)
        self.assertIn("RootTaskBridge.synchronize(applicationContext)", sync)
        for token in ("RootTaskBridge.inspect(", "rootAction", "rootUi.value =", "rootChecking.value = true"):
            self.assertNotIn(token, sync)
        self.assertIn("onSync = ::syncRootTasks", self.main)
        self.assertIn("rootSyncedAt.value = it", sync)
        self.assertIn("rootSyncError.value = it.message", sync)
        self.assertIn("check(RootTaskBridge.isEnabled(applicationContext))", sync)

    def test_sync_does_not_look_like_permission_check(self):
        access_row = self.ui.split("title = stringResource(R.string.root_access_title)", 1)[1].split(
            "title = stringResource(R.string.root_daemon_title)", 1)[0]
        self.assertIn("if (checking)", access_row)
        self.assertNotIn("if (busy)", access_row)
        self.assertIn("R.string.root_sync_running", self.ui)
        self.assertIn("R.string.root_sync_failed", self.ui)
        self.assertIn("R.string.root_synced_at", self.ui)

    def test_root_operations_remain_exclusive_and_reset_busy_on_completion(self):
        for name, end in (("inspectRoot", "private fun syncRootTasks()"),
                          ("syncRootTasks", "private fun LauncherIconPicker()")):
            text = self.main.split(f"private fun {name}()", 1)[1].split(end, 1)[0]
            self.assertIn("if (rootBusy.value) return", text)
            self.assertIn("rootBusy.value = true", text)
            self.assertIn("rootBusy.value = false", text)
            self.assertIn("runCatching", text)

    def test_module_and_task_states_are_not_conflated(self):
        module = self.ui.split("private fun moduleSummary(", 1)[1].split("private fun taskSummary(", 1)[0]
        task = self.ui.split("private fun taskSummary(", 1)[1]
        self.assertIn("status.moduleResource()", module)
        self.assertNotIn("stateResource(", module)
        self.assertIn("status.stateResource(enabled, status.checkedAt)", task)
        status = (JAVA / "cloud/root/RootModuleStatus.kt").read_text()
        module = status.split("fun moduleResource()", 1)[1].split("fun stateResource(", 1)[0]
        for token in ("daemonRunning", "heartbeatAt", "taskRunning", "rootSelected"):
            self.assertNotIn(token, module)

    def test_unique_details_are_retained_in_module_management(self):
        dialog = self.ui.split("internal fun RootModuleDialog(", 1)[1].split("private fun formatRootTime(", 1)[0]
        for token in ("root_version_details", "root_inspection_detail", "root_last_error",
                      "root_legacy_title", "execution_su_path", "root_scheduler_help"):
            self.assertIn(token, dialog)
        for token in ("root_access_title", "root_enable_switch", "root_daemon_title"):
            self.assertNotIn(token, dialog)

    def test_display_casing_and_plain_language(self):
        for locale in ("values", "values-en"):
            values = {e.get("name"): e.text or "" for e in ET.parse(
                ROOT / f"app/src/main/res/{locale}/strings.xml").getroot().findall("string")}
            for key, value in values.items():
                if key.startswith(PREFIXES):
                    self.assertNotRegex(value, r"(?<![A-Za-z0-9_])(?:ROOT|root)(?![A-Za-z0-9_])", key)
                    self.assertNotRegex(value, r"(?<![A-Za-z0-9_/])su(?![A-Za-z0-9_])", key)
                    self.assertNotIn("调度", value, key)
                    self.assertNotIn("scheduler", value.lower(), key)
                    self.assertNotIn("不自动刷新", value, key)
                    self.assertNotIn("not refreshed automatically", value, key)
            self.assertIn("/system/bin/su", values["execution_su_path_description"])
            self.assertNotIn("/system/bin/SU", values["execution_su_path_description"])
        self.assertEqual("Root增强", values_from("values")["root_enhancement_title"])
        self.assertEqual("同步任务", values_from("values")["root_sync"])

    def test_sync_still_uses_real_permission_checked_transport(self):
        bridge = (JAVA / "cloud/root/RootTaskBridge.kt").read_text()
        command = bridge.split("private fun command(", 1)[1]
        self.assertIn("RootCommandRunner.run(", command)
        runner = (JAVA / "cloud/root/RootCommandRunner.kt").read_text()
        self.assertIn("RootAccessException", runner)
        self.assertIn("UID 0", runner)

def values_from(locale):
    return {e.get("name"): e.text for e in ET.parse(
        ROOT / f"app/src/main/res/{locale}/strings.xml").getroot().findall("string")}

if __name__ == "__main__":
    unittest.main()
