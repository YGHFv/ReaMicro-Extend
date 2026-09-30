"""Regression guards: navigating the UI must not request su or queue a Root sync."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/reamicro/fix"

class RootEntryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = (JAVA / "ui/ModuleMainActivity.kt").read_text()
        cls.ui = (JAVA / "ui/RootEnhancementComponents.kt").read_text()
        cls.bridge = (JAVA / "cloud/root/RootTaskBridge.kt").read_text()

    def section(self, start, end):
        return self.main.split(start, 1)[1].split(end, 1)[0]

    def assert_no_root_request(self, text):
        for call in ("RootTaskBridge.inspect(", "RootTaskBridge.requestSync(",
                     "RootTaskBridge.synchronize(", "RootCommandRunner.",
                     "inspectRoot()", "rootAction {"):
            self.assertNotIn(call, text)

    def test_resume_only_reads_last_snapshot(self):
        text = self.section("override fun onResume()", "override fun onPause()")
        self.assert_no_root_request(text)
        self.assertIn("rootUi.value = RootTaskBridge.lastInspection", text)
        self.assertIn("refresh()", text)

    def test_notification_or_launcher_reentry_does_not_request_root(self):
        self.assert_no_root_request(self.section("override fun onNewIntent(", "override fun onResume()"))

    def test_cold_start_does_not_request_root(self):
        self.assert_no_root_request(self.section("override fun onCreate(", "private fun ImmersiveSystemBars("))
        self.assertIn("private val rootUi = mutableStateOf<RootModuleStatus?>(null)", self.main)
        self.assertIn("@Volatile var lastInspection: RootModuleStatus? = null", self.bridge)

    def test_settings_is_render_only_even_when_enhancement_is_on(self):
        text = self.section("private fun ConfigPage()", "private fun TaskCard(")
        self.assert_no_root_request(text)
        self.assertNotIn("LaunchedEffect", text)
        self.assertNotIn("rootRefreshGeneration", self.main)
        self.assertIn("TAB_CONFIG -> ConfigPage()", self.main)

    def test_plain_refresh_reads_local_state_only(self):
        self.assert_no_root_request(self.section("private fun refresh()", "private fun buildOverviewText("))

    def test_manual_check_and_confirmed_operations_remain(self):
        text = self.section("private fun inspectRoot()", "private fun LauncherIconPicker()")
        self.assertIn("RootTaskBridge.inspect(applicationContext)", text)
        self.assertIn("if (rootBusy.value) return", text)
        self.assertIn("onInspect = ::inspectRoot", self.main)
        for call in ("RootTaskBridge.enable(uiContext)", "RootTaskBridge.disable(uiContext, remove = false)",
                     "RootTaskBridge.disable(uiContext, remove = true)",
                     "RootTaskBridge.installOrUpdate(uiContext)", "RootTaskBridge.synchronize(applicationContext)"):
            self.assertIn(call, self.main)
        self.assertIn("val access = inspect(context)", self.bridge)
        self.assertIn("if (!access.rootAvailable) throw RootAccessException", self.bridge)

    def test_background_config_and_completion_sync_are_retained(self):
        store = (JAVA / "cloud/local/LocalTaskStore.kt").read_text()
        receiver = (JAVA / "cloud/root/RootTaskSyncReceiver.kt").read_text()
        self.assertIn("RootTaskBridge.requestSync(context)", store)
        self.assertIn("RootTaskBridge.requestSync(context.applicationContext)", receiver)
        self.assertIn("if (!isEnabled(context)) return", self.bridge)

    def test_cached_diagnostics_use_observation_time_not_current_time(self):
        self.assertIn("status.stateResource(enabled, status.checkedAt)", self.ui)
        self.assertNotIn("System.currentTimeMillis()", self.ui)
        status = (JAVA / "cloud/root/RootModuleStatus.kt").read_text()
        self.assertIn("!rootAvailable -> R.string.execution_root_unavailable", status)
        self.assertIn("!inspectionComplete -> R.string.execution_module_probe_error", status)
        self.assertIn("now - heartbeatAt in 0L..120_000L", status)
        self.assertIn("getDateTimeInstance(", self.ui)

    def test_custom_su_path_invalidates_display_without_auto_probe(self):
        text = self.section("suPathDialog.value?.let", "textDialog.value?.let")
        self.assertIn("RootTaskBridge.setCustomSuPath", text)
        self.assertIn("rootUi.value = null", text)
        self.assert_no_root_request(text)
        path_setter = self.bridge.split("fun setCustomSuPath(", 1)[1].split("private fun prepareTransport", 1)[0]
        self.assertIn("lastInspection = null", path_setter)

    def test_cached_status_is_clearly_labelled(self):
        for locale, cached, manual in (("values", "上次检测", "主动检查"),
                                       ("values-en", "Last checked", "explicitly")):
            values = {e.get("name"): e.text for e in ET.parse(
                ROOT / f"app/src/main/res/{locale}/strings.xml").getroot().findall("string")}
            self.assertIn(cached, values["root_checked_at"])
            self.assertIn("%1$s", values["root_checked_at"])
            self.assertIn(manual, values["root_not_checked"])

if __name__ == "__main__":
    unittest.main()
