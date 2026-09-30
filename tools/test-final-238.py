"""Release guards for icon proportions, Root panel ordering and module 1.0."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/reamicro/fix/ui/ModuleMainActivity.kt"
UI = ROOT / "app/src/main/java/com/reamicro/fix/ui/RootEnhancementComponents.kt"

class Final238Test(unittest.TestCase):
    def test_about_uses_selected_launcher_layers_and_full_size_foreground(self):
        main = MAIN.read_text()
        about = main.split("private fun AboutPage()",1)[1].split("private fun rootAction(",1)[0]
        picker = main.split("private fun LauncherIconPicker()",1)[1].split("private fun ",1)[0]
        self.assertIn("val iconStyle = launcherIconStyle.value", about)
        self.assertIn("painterResource(iconStyle.foreground)", about)
        self.assertIn("colorResource(iconStyle.background)", about)
        self.assertIn("Modifier.size(88.dp)", about)
        self.assertIn("Modifier.fillMaxSize().graphicsLayer", about)
        self.assertNotIn("74.dp", about)
        for text in (about, picker):
            self.assertIn("scaleX = 1.5f", text)
            self.assertIn("scaleY = 1.5f", text)
        self.assertIn("Modifier.padding(top = 16.dp, bottom = 5.dp)", about)

    def test_root_panel_matches_requested_order(self):
        card = UI.read_text().split("internal fun RootEnhancementCard(",1)[1].split("internal fun RootModuleDialog(",1)[0]
        keys = re.findall(r"title = stringResource\(R.string.(\w+)\)", card)
        self.assertEqual(["root_enable_switch","root_access_title","root_inspect",
                          "root_module_title","root_daemon_title","root_sync"], keys)

    def test_task_status_copy_is_compact(self):
        values = {e.get("name"):e.text for e in ET.parse(ROOT/"app/src/main/res/values/strings.xml").getroot().findall("string")}
        self.assertEqual("任务状态", values["root_daemon_title"])
        for key in ("execution_status_running","execution_status_no_heartbeat",
                    "execution_task_running","execution_daemon_stopped","root_tasks_disabled","root_task_unknown"):
            self.assertLessEqual(len(values[key]), 12, key)

    def test_root_module_version_and_runtime_marker_match(self):
        module = ROOT / "module/reamicro-automation"
        props = dict(line.split("=",1) for line in (module/"module.prop").read_text().splitlines() if "=" in line)
        self.assertEqual("1.0", props["version"])
        self.assertEqual("9", props["versionCode"])
        self.assertEqual("reamicro_automation", props["id"])
        self.assertIn("REAMICRO_MODULE_VERSION=9", (module/"common.sh").read_text())
        self.assertIn("1.0 / versionCode=9", (ROOT/"module/README.md").read_text())

if __name__ == "__main__":
    unittest.main()
