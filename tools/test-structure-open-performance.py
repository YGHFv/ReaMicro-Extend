"""Source regression contracts, not a device latency benchmark."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
HOOK = ROOT / "app/src/main/java/com/reamicro/fix/hook"


class StructureOpenPerformanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.panel = (HOOK / "EpubWebEditorPanel.kt").read_text()
        cls.native = (HOOK / "EpubStructureView.kt").read_text()

    def test_native_open_does_not_start_webview(self):
        show = self.panel.split("    fun show() {", 1)[1].split("    private fun configureWindow", 1)[0]
        for token in ("WebView(activity)", "loadDataWithBaseURL(", "editorHtml()", "ensureLegacyWebView()"):
            self.assertNotIn(token, show)
        self.assertIn("uiFontFileProvider = { globalUiFontFile }", show)
        self.assertIn("structure!!.create()", show)

    def test_legacy_editor_remains_lazy_and_reusable(self):
        helper = self.panel.split("private fun ensureLegacyWebView()", 1)[1].split("    fun show()", 1)[0]
        self.assertIn("if (::webView.isInitialized) return", helper)
        self.assertIn("WebView(activity)", helper)
        self.assertIn("loadDataWithBaseURL(", helper)
        self.assertIn("container.addView(webView, 0,", helper)
        self.assertIn("if (disposed || !dialog.isShowing) return@runOnUiThread", helper)
        legacy = self.panel.split("private fun openLegacy(", 1)[1].split("private fun returnToStructure()", 1)[0]
        self.assertLess(legacy.index("ensureLegacyWebView()"), legacy.index("legacyActive = true"))
        self.assertIn("else pendingLegacyScript = script", legacy)

    def test_native_callbacks_do_not_require_webview(self):
        self.assertIn("if (dialog.isShowing && ::webView.isInitialized) webView.evaluateJavascript(", self.panel)
        self.assertIn("if (::webView.isInitialized) runCatching {", self.panel)
        self.assertIn("webView.stopLoading()", self.panel)
        for result in ("onImportResult", "onCoverResult"):
            self.assertIn(
                'if (::webView.isInitialized) webView.evaluateJavascript(\n'
                '            "window.FileEditorNative && window.FileEditorNative.' + result,
                self.panel,
            )

    def test_single_load_reuses_file_snapshot_for_metadata_and_cover(self):
        initial = self.panel.split("fun initialData()", 1)[1].split("fun listFiles()", 1)[0]
        self.assertEqual(1, initial.count("allFiles()"))
        self.assertIn("metadataJson(files = files)", initial)
        self.assertIn("filesJson(files)", initial)
        self.assertIn("opfOverride ?: if (files != null)", self.panel)
        self.assertIn("findCoverFile(it, files)", self.panel)
        self.assertIn("(files ?: allFiles()).firstOrNull", self.panel)

    def test_sort_comparisons_do_not_resolve_paths(self):
        listing = self.panel.split("private fun allFiles()", 1)[1].split("private fun fileDetail(", 1)[0]
        self.assertEqual(1, listing.count("relativePath(file)"))
        self.assertEqual(1, listing.count("naturalName(file.name)"))
        self.assertIn('.sortedWith(compareBy<IndexedFile> { it.group }.thenBy { it.name })', listing)
        self.assertIn('relative.startsWith("META-INF/", ignoreCase = true)', listing)
        self.assertIn(".map { it.file }", listing)

    def test_font_and_entry_conversion_off_main_thread(self):
        font = self.native.split("val uiFont by produceState", 1)[1].split("val typography", 1)[0]
        self.assertLess(font.index("withContext(Dispatchers.IO)"), font.index("uiFontFileProvider()"))
        loading = self.native.split("LaunchedEffect(revision)", 1)[1].split("SideEffect", 1)[0]
        self.assertLess(loading.index("withContext(Dispatchers.IO)"), loading.index("entries(snapshot"))
        self.assertIn("catch (e: CancellationException) { throw e }", loading)
        self.assertIn("val groupedFiles = remember(files) { files.groupBy { it.group } }", self.native)
        self.assertIn("groupedFiles.forEach", self.native)


if __name__ == "__main__":
    unittest.main()
