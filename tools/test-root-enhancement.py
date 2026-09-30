"""Regression guards for removal of Android wake and the native ROOT enhancement UI."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/reamicro/fix"


class RootEnhancementTest(unittest.TestCase):
    def test_no_android_scheduler_or_job_service_implementation(self):
        forbidden = ("CloudTaskWakeScheduler.kt", "LocalTaskJobService.kt", "LocalWakePlan.kt",
                     "WakeAlarmScheduling.kt", "NextWakeHint.kt", "CloudTaskWakeDiagnostics.kt")
        names = {p.name for p in JAVA.rglob("*.kt")}
        for name in forbidden:
            self.assertNotIn(name, names)

    def test_only_upgrade_cleanup_can_touch_legacy_platform_schedulers(self):
        for p in JAVA.rglob("*.kt"):
            text = p.read_text()
            if p.name == "LegacyAndroidWakeCleanup.kt":
                self.assertIn("FLAG_NO_CREATE", text)
                self.assertNotIn(".schedule(", text)
                self.assertNotIn("setExact", text)
                self.assertNotIn("setAndAllowWhileIdle", text)
            else:
                self.assertNotIn("import android.app.AlarmManager", text, str(p))
                self.assertNotIn("import android.app.job.JobScheduler", text, str(p))

    def test_removed_manifest_permissions_and_components(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        for term in ("SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM", "RECEIVE_BOOT_COMPLETED",
                     "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "BIND_JOB_SERVICE",
                     "CloudTaskHeartbeatReceiver", "CloudTaskRescheduleReceiver"):
            self.assertNotIn(term, manifest)
        self.assertIn("RootTaskSyncReceiver", manifest)
        self.assertIn("PackageUpgradeReceiver", manifest)

    def test_root_panels_do_not_draw_between_row_dividers(self):
        text = (JAVA / "ui/RootEnhancementComponents.kt").read_text()
        self.assertNotIn("HorizontalDivider", text)
        self.assertNotIn("CardDivider", text)
        self.assertIn("SwitchPreference(", text)
        self.assertNotIn("RootDiagnosticsDialog", text)
        self.assertIn("RootModuleDialog", text)
        self.assertIn("BasicComponent(", text)

    def test_root_and_task_dialogs_share_native_window_and_safe_footer(self):
        root = (JAVA / "ui/RootEnhancementComponents.kt").read_text()
        window = (JAVA / "ui/AppWindowDialog.kt").read_text()
        main = (JAVA / "ui/ModuleMainActivity.kt").read_text()
        self.assertIn("AppWindowDialog(", root)
        task = main.split("private fun TaskEditorDialog(", 1)[1].split("private fun EditorField(", 1)[0]
        self.assertIn("AppWindowDialog(", task)
        for token in ("WindowDialog(", "defaultWindowInsetsPadding = true",
                      "RoundedCorner.POSITION_BOTTOM_LEFT", "minHeight = 48.dp",
                      "Modifier.weight(1f, fill = false)"):
            self.assertIn(token, window)
        self.assertNotIn("WindowBottomSheet", root)

    def test_only_task_cards_opt_into_separator(self):
        main = (JAVA / "ui/ModuleMainActivity.kt").read_text()
        helper = (JAVA / "ui/ModuleComponents.kt").read_text()
        self.assertNotIn("CardDivider()", main)
        self.assertEqual(1, main.count("showActionDivider = true"))
        self.assertIn("showActionDivider = true",
            main.split("private fun TaskCard(", 1)[1].split("private fun RootEnhancementSection", 1)[0])
        self.assertIn("showActionDivider: Boolean = false", helper)
        self.assertIn("if (showActionDivider) CardDivider()", helper)

    def test_current_wake_mode_selection_is_gone(self):
        main = (JAVA / "ui/ModuleMainActivity.kt").read_text()
        for token in ("WakeModeSheet", "showWakeModeConfirmation", "NoRootWakeCard",
                      "wakePermissionsSheet", "createWakeProbe", "rescheduleAlarm"):
            self.assertNotIn(token, main)
        self.assertIn("RootEnhancementAction.ENABLE", main)
        self.assertIn("RootEnhancementAction.DISABLE", main)

    def test_root_toggle_still_requires_confirmation(self):
        main = (JAVA / "ui/ModuleMainActivity.kt").read_text()
        section = main.split("private fun RootEnhancementSection()", 1)[1].split("private fun inspectRoot", 1)[0]
        self.assertIn("rootConfirmation.value", section)
        self.assertNotIn("RootTaskBridge.enable(", section)
        self.assertNotIn("RootTaskBridge.disable(", section)

    def test_chinese_title_and_obsolete_strings(self):
        for folder in ("values", "values-en"):
            tree = ET.parse(ROOT / f"app/src/main/res/{folder}/strings.xml")
            values = {s.get("name"): s.text or "" for s in tree.findall("string")}
            self.assertIn("root_enhancement_title", values)
            if folder == "values":
                self.assertEqual("Root增强", values["root_enhancement_title"])
            self.assertFalse(any(k.startswith(("no_root_", "wake_")) for k in values))
            self.assertNotIn("execution_mode_android", values)

    def test_cleanup_never_deletes_root_choice_or_account_store(self):
        text = (JAVA / "migration/LegacyAndroidWakeCleanup.kt").read_text()
        self.assertNotIn("reamicro_ksu_tasks", text)
        self.assertNotIn("local_tasks", text)
        self.assertNotIn("RootTaskBridge.disable", text)
        self.assertIn("android_wake_removed_v1", text)


if __name__ == "__main__":
    unittest.main()
