"""Source contracts for the module-owned 1.3-style structure UI. No device/UI actions."""
from pathlib import Path
import json
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/reamicro/fix"
def read(path): return (JAVA / path).read_text()

class StructureRefinementTest(unittest.TestCase):
    def test_window_owner_bound_before_show(self):
        source = read("hook/EpubWebEditorPanel.kt")
        self.assertLess(source.index("structure?.bindWindow(dialog.window)"), source.index("        dialog.show()"))
        native = read("hook/EpubStructureView.kt")
        self.assertIn("findViewById<View>(android.R.id.content)", native)
        self.assertIn("setViewTreeLifecycleOwner(owner)", native)
        self.assertIn("setViewTreeSavedStateRegistryOwner(owner)", native)

    def test_full_screen_but_system_bars_are_not_hidden(self):
        styles = ET.parse(ROOT / "app/src/main/res/values/styles.xml").getroot()
        style = next(s for s in styles if s.get("name") == "EpubFullScreenDialog")
        props = {x.get("name"):x.text for x in style}
        self.assertEqual("false", props["android:windowIsFloating"])
        self.assertEqual("@android:color/transparent", props["android:statusBarColor"])
        for file in ("EpubWebEditorPanel.kt", "EpubScriptaPanel.kt"):
            s = read("hook/" + file)
            self.assertIn("R.style.EpubFullScreenDialog", s)
            self.assertNotIn("FLAG_FULLSCREEN", s)
        self.assertIn("if (legacyActive) insets.systemWindowInsetTop else 0",
                      read("hook/EpubWebEditorPanel.kt"))

    def test_about_primary_tab_has_no_top_bar(self):
        s = read("ui/ModuleMainActivity.kt")
        self.assertIn("if (currentPage != TAB_ABOUT) TopAppBar(", s)
        self.assertIn("if (page == TAB_ABOUT)", s)
        self.assertIn("WindowInsets.statusBars.asPaddingValues().calculateTopPadding()", s)

    def test_native_routes_preserve_existing_operations(self):
        native = read("hook/EpubStructureView.kt")
        for token in ("DrawerContent",):  # The mapping is documented by the source classes below.
            self.assertIn("X4/o", native)
        for action in ("createTextFile", "renameFile", "deleteFile", "setCover", "setBanner", "pickFile",
                       "saveMetadataField", "cssRules"):
            self.assertIn('"' + action + '"', native)
        self.assertIn("openScripta(entry.path)", native)
        self.assertIn("openLegacy(", native)
        legacy = read("hook/EpubWebEditorPanel.kt")
        self.assertIn("window.FileEditorNative={openLegacyPath,", legacy)
        self.assertIn("function replaceAll()", legacy)
        self.assertIn("fun onActivityResult", legacy)

    def test_font_samples_and_drawer_match_reference_port(self):
        legacy = read("hook/EpubWebEditorPanel.kt")
        line = next(x for x in legacy.splitlines() if x.startswith("const HOST_FONT_SAMPLES="))
        samples = json.JSONDecoder().raw_decode(line.split("=", 1)[1])[0]
        native = read("hook/EpubStructureView.kt")
        self.assertEqual(5, len(samples))
        for label, sample in samples:
            self.assertIn(json.dumps(label, ensure_ascii=False), native)
            self.assertIn(json.dumps(sample, ensure_ascii=False), native)
        self.assertIn("screenWidthDp * .67f", native)
        self.assertIn("padding(start = 32.dp)", native)
        self.assertIn("size(30.dp, 36.dp)", native)

    def test_no_global_css_invalidation_and_no_blind_source_save(self):
        css = read("epub/editor/HostStyleSheetPreview.kt")
        self.assertNotIn('getMethod("clear")', css)
        self.assertIn('getMethod("invalidate", Any::class.java)', css)
        legacy = read("hook/EpubWebEditorPanel.kt")
        self.assertIn("fun writeTextVersioned", legacy)
        self.assertIn("loaded.snapshot.fingerprint.sha256 == expectedToken", legacy)
        self.assertIn("api.writeTextVersioned", legacy)

    def test_fanqie_scope_is_request_scoped_not_endpoint_hardcoded(self):
        s = read("association/network/AssociationNetworkScope.java")
        for old in ("219.154.201.122", '"/search"', "5006"):
            self.assertNotIn(old, s)
        self.assertIn("ThreadLocal", s)
        self.assertNotIn("InheritableThreadLocal", s)
        self.assertIn("getRawUserInfo() == null", s)
        hook = read("hook/AssociationNetworkHook.kt")
        self.assertIn('getDeclaredMethod("isCleartextTrafficPermitted", String::class.java)', hook)

    def test_standalone_string_resource_contract(self):
        def strings(lang):
            rows = ET.parse(ROOT / ("app/src/main/res/" + lang + "/strings.xml")).getroot()
            names = [x.get("name") for x in rows if x.tag == "string"]
            self.assertEqual(len(names), len(set(names)))
            return {x.get("name"): "".join(x.itertext()) for x in rows if x.tag == "string"}
        zh, en = strings("values"), strings("values-en")
        self.assertEqual(set(zh), set(en))
        for key in zh:
            self.assertNotIn("\ufffd", zh[key])
            self.assertNotIn("\ufffd", en[key])
            pattern = r"%(?:[0-9]+\$)?[sdfoxegcbhSDTF]"
            self.assertEqual(sorted(re.findall(pattern, zh[key])), sorted(re.findall(pattern, en[key])), key)

if __name__ == "__main__":
    unittest.main()
