from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/java/com/reamicro/fix/ui"

class MiuixIntegrationTest(unittest.TestCase):
    def test_cards_use_library_layout_and_text_buttons_use_button_role(self):
        text = (UI / "ModuleComponents.kt").read_text()
        self.assertIn("BasicComponent(", text)
        self.assertIn("Button(onClick", text)
        self.assertNotIn("IconButton(", text)
        self.assertNotIn("isSystemInDarkTheme", text)

    def test_window_dropdown_is_not_copied_popup_implementation(self):
        main = (UI / "ModuleMainActivity.kt").read_text()
        block = main.split("private fun ChoiceDropdownField(", 1)[1].split("private fun cityAccepts", 1)[0]
        self.assertIn("WindowDropdownPreference(", block)
        self.assertNotIn("WindowListPopup(", block)
        self.assertNotIn("DropdownImpl(", block)
        self.assertIn("enabled = false, selected = true", block)

    def test_authorization_mode_and_module_are_different_native_rows(self):
        text = (UI / "RootEnhancementComponents.kt").read_text()
        for name in ("RootEnhancementCard", "RootModuleDialog"):
            self.assertIn("fun " + name, text)
        self.assertNotIn("RootDiagnosticsDialog", text)
        self.assertIn("SwitchPreference(", text)
        self.assertIn("AppWindowDialog(", text)
        self.assertNotIn("RootTaskBridge", text)

    def test_plain_and_liquid_navigation_share_the_ksu_component(self):
        main = (UI / "ModuleMainActivity.kt").read_text()
        self.assertIn("if (floating)", main)
        self.assertNotIn("else if (floating)", main)
        self.assertNotIn("FloatingNavigationBar(", main)
        self.assertIn("FloatingBottomBar(", main)
        self.assertIn("isBlurEnabled = liquid", main)

    def test_instrumentation_and_diagnostic_job_are_not_in_release_main_manifest(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
        self.assertNotIn("WakeDeviceJobService", manifest)
        self.assertNotIn("<instrumentation", manifest)
        self.assertFalse((ROOT / "app/src/debug/java/com/reamicro/fix/diagnostics/WakeDeviceJobService.kt").exists())

if __name__ == "__main__":
    unittest.main()
