"""Static checks for ROOT enhancement UI wording; no Root or device mutations."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
JAVA = ROOT / "app/src/main/java/com/reamicro/fix"
PREFIXES = ("root_", "execution_", "error_ksu_", "toast_ksu_", "ksu_", "error_root_")
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[dsf]")

def strings(locale):
    nodes = ET.parse(RES / locale / "strings.xml").getroot().findall("string")
    return nodes, {node.get("name"): "".join(node.itertext()) for node in nodes}

class RootWordingTest(unittest.TestCase):
    def test_xml_has_unique_names(self):
        for locale in ("values", "values-en"):
            nodes, values = strings(locale)
            self.assertEqual(len(nodes), len(values), locale)

    def test_root_keys_and_placeholders_match(self):
        _, zh = strings("values")
        _, en = strings("values-en")
        zh_keys = {key for key in zh if key.startswith(PREFIXES)}
        en_keys = {key for key in en if key.startswith(PREFIXES)}
        self.assertEqual(zh_keys, en_keys)
        for key in zh_keys:
            self.assertEqual(PLACEHOLDER.findall(zh[key]), PLACEHOLDER.findall(en[key]), key)
            self.assertTrue(zh[key].strip(), key)
            self.assertTrue(en[key].strip(), key)
        self.assertEqual(["%1$d", "%2$d", "%3$d"], PLACEHOLDER.findall(zh["root_version_details"]))

    def test_no_internal_jargon_or_obsolete_wake_selection(self):
        for locale, banned in (
            ("values", ("心跳", "锁不泄露", "锁不泄漏", "活动标记", "暂存", "请选择本地任务唤醒方式", "唤醒方式没有改变")),
            ("values-en", ("heartbeat", "active marker", "Choose a local task wake mode", "wake mode is unchanged")),
        ):
            _, values = strings(locale)
            text = "\n".join(value for key, value in values.items() if key.startswith(PREFIXES))
            for word in banned:
                self.assertNotIn(word, text)

    def test_activity_copy_matches_detection_window(self):
        _, zh = strings("values")
        self.assertEqual("后台运行中", zh["execution_status_running"])
        self.assertEqual("运行状态待确认", zh["execution_status_no_heartbeat"])
        self.assertIn("120_000L", (JAVA / "cloud/root/RootModuleStatus.kt").read_text())
        self.assertIn("status.stateResource(enabled, status.checkedAt)",
            (JAVA / "ui/RootEnhancementComponents.kt").read_text())
        help_text = zh["root_scheduler_help"]
        for term in ("本机", "不代表联网成功", "任务已经完成", "深度休眠"):
            self.assertIn(term, help_text)

    def test_manager_help_and_su_boundaries(self):
        _, zh = strings("values")
        for name in ("KernelSU", "Magisk", "APatch", "超级用户"):
            self.assertIn(name, zh["root_permission_help"])
        for term in ("重设 SU 路径", "完整路径", "不会修改 APatch 设置", "SuperKey", "密码", "命令参数"):
            self.assertIn(term, zh["execution_su_path_description"])

    def test_help_is_wired_into_scrollable_dialogs(self):
        ui = (JAVA / "ui/RootEnhancementComponents.kt").read_text()
        for key in ("root_permission_help", "root_module_help", "root_scheduler_help"):
            self.assertIn(f"R.string.{key})", ui)
            self.assertIn(f"R.string.{key}_title)", ui)
        self.assertIn(".verticalScroll(rememberScrollState())", (JAVA / "ui/AppWindowDialog.kt").read_text())
        self.assertNotIn("HorizontalDivider", ui)

    def test_no_new_false_success_or_reboot_promise(self):
        _, zh = strings("values")
        self.assertIn("不代表更新已生效", zh["root_version_details"])
        self.assertIn("不会自动重启", zh["ksu_reboot_required"])
        self.assertIn("不保证准点", zh["root_confirm_enable"])
        self.assertIn("仅支持手动执行", zh["root_confirm_disable"])

if __name__ == "__main__":
    unittest.main()
