from pathlib import Path
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/java/com/reamicro/fix/ui"

class UiTimeRefreshTest(unittest.TestCase):
    def test_root_section_follows_gestures(self):
        main = (UI / "ModuleMainActivity.kt").read_text()
        settings = main.split("private fun ConfigPage(", 1)[1].split("private fun TaskCard(", 1)[0]
        self.assertLess(settings.index("R.string.section_gesture"), settings.index("R.string.root_enhancement_title"))
        self.assertLess(settings.index("RootEnhancementSection()"), settings.index("R.string.section_notifications_logs"))

    def test_about_contains_brand_and_real_project_links_not_status_or_groups(self):
        main = (UI / "ModuleMainActivity.kt").read_text()
        about = main.split("private fun AboutPage()", 1)[1].split("private fun rootAction(", 1)[0]
        for expected in ("Image(", "versionLine()", "buildTimeText()", "ArrowPreference(", "about_source", "about_releases"):
            self.assertIn(expected, about)
        for unwanted in ("about_status", "StatusLine(", "myPid()", "hasPermission", "Telegram", "QQ", "t.me"):
            self.assertNotIn(unwanted, about)

    def test_compact_ksu_action_geometry(self):
        components = (UI / "ModuleComponents.kt").read_text()
        self.assertEqual(2, components.count("minWidth = 35.dp, minHeight = 35.dp"))
        self.assertEqual(2, components.count("fontSize = 15.sp"))
        self.assertIn("thickness = 0.5.dp", components)
        self.assertIn("horizontal = 16.dp, vertical = 8.dp", components)
        self.assertIn("bottom = if (showActionDivider && actions != null) 0.dp else 16.dp", components)
        self.assertNotIn("44.dp", components)

    def test_all_scheduled_task_editors_use_picker(self):
        main = (UI / "ModuleMainActivity.kt").read_text()
        editor = main.split("private fun TaskEditorDialog(", 1)[1].split("private fun EditorField(", 1)[0]
        self.assertIn("TaskTimePicker(", editor)
        self.assertIn("minMinutes = if (spec?.autoRead == true)", editor)
        self.assertNotIn("R.string.field_time_hint", editor)
        picker = (UI / "TaskTimePicker.kt").read_text()
        self.assertEqual(2, picker.count("NumberPicker("))
        self.assertNotIn("TextField(", picker)
        self.assertIn("range = (minimum / 60)..23", picker)
        self.assertIn("minimum % 60 else 0)..59", picker)
        self.assertIn("LaunchedEffect(value, minimum)", picker)

    def test_time_separator_is_centered_in_wheel_row_not_label_columns(self):
        picker = (UI / "TaskTimePicker.kt").read_text()
        expanded = picker.split("if (expanded) {", 1)[1]
        self.assertEqual(2, expanded.count("            Row("))
        labels, wheels = expanded.split("            Row(", 2)[1:]
        self.assertIn("hourLabel,", labels)
        self.assertIn("minuteLabel,", labels)
        self.assertIn("Spacer(Modifier.width(separatorWidth))", labels)
        self.assertNotIn("NumberPicker(", labels)
        self.assertEqual(2, wheels.count("NumberPicker("))
        self.assertIn("verticalAlignment = Alignment.CenterVertically", wheels)
        separator = wheels.split('text = ":"', 1)[1].split("NumberPicker(", 1)[0]
        self.assertIn("Modifier.width(separatorWidth)", separator)
        self.assertIn("textAlign = TextAlign.Center", separator)
        self.assertIn("style = pickerTextStyle", separator)
        self.assertNotIn("offset(", separator)
        self.assertEqual(2, wheels.count("Modifier.weight(1f).semantics"))

    def test_picker_and_separator_share_miuix_resolved_weight(self):
        picker = (UI / "TaskTimePicker.kt").read_text()
        self.assertIn("val basePickerTextStyle = MiuixTheme.textStyles.main", picker)
        self.assertIn("if (basePickerTextStyle.fontWeight == null)", picker)
        self.assertIn("basePickerTextStyle.copy(fontWeight = FontWeight.SemiBold)", picker)
        self.assertIn("else basePickerTextStyle", picker)
        self.assertEqual(2, picker.count("textStyle = pickerTextStyle,"))
        self.assertEqual(1, picker.count("style = pickerTextStyle,"))
        self.assertEqual(2, picker.count("itemHeight = 32.dp,"))
        self.assertEqual(2, picker.count("visibleItemCount = 3,"))

    def test_bilingual_resources_parse_and_have_no_duplicate_names(self):
        for folder in ("values", "values-en"):
            tree = ET.parse(ROOT / f"app/src/main/res/{folder}/strings.xml")
            names = [element.attrib["name"] for element in tree.getroot()]
            self.assertEqual(len(names), len(set(names)))
            for name in ("time_picker_hour", "time_picker_minute", "auto_read_time_lock_hint", "about_build_time"):
                self.assertIn(name, names)

if __name__ == "__main__":
    unittest.main()
