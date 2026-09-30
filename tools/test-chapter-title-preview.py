"""Source-only regression checks. Does not compile or execute Kotlin."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/reamicro/fix"


class ChapterTitlePreviewSourceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.native = (JAVA / "hook/EpubStructureView.kt").read_text()
        cls.preview = (JAVA / "epub/editor/EpubChapterTitlePreview.kt").read_text()
        cls.row = cls.native.split("@Composable private fun FileRow(", 1)[1].split("@Composable private fun Thumbnail(", 1)[0]

    def test_native_lazy_row_reads_on_io_not_initial_load(self):
        self.assertIn('if (file.kind == "html")', self.row)
        self.assertIn('produceState("", file.path, titleRevision)', self.row)
        self.assertLess(self.row.index("withContext(Dispatchers.IO)"), self.row.index("EpubChapterTitlePreview.read("))
        initial = self.native.split("LaunchedEffect(revision)", 1)[1].split("SideEffect", 1)[0]
        self.assertNotIn("EpubChapterTitlePreview", initial)
        self.assertNotIn('call("readDecoration"', self.row)

    def test_reads_and_cache_are_bounded(self):
        self.assertIn("const val MAX_BYTES = 64 * 1024", self.preview)
        self.assertIn("ByteArray(MAX_BYTES)", self.preview)
        self.assertIn("minOf(4096, bytes.size - count)", self.preview)
        for expensive in ("readText(", "readBytes(", "EpubTextFiles.load(", "MessageDigest", "walkTopDown"):
            self.assertNotIn(expensive, self.preview)
        self.assertIn("size > 256", self.native)
        self.assertIn("titleReadMutex.withLock", self.row)
        self.assertIn("chapterTitleCache[key] ?: try", self.row)

    def test_refresh_and_cancellation(self):
        self.assertIn("val key = titleRevision to file.path", self.row)
        self.assertIn('value = ""', self.row)
        self.assertIn("context.ensureActive()", self.row)
        self.assertIn("catch (e: CancellationException)", self.row)
        self.assertIn("throw e", self.row)
        self.assertIn("checkCancelled()", self.preview)

    def test_summary_replaces_type_size_only_when_title_exists(self):
        self.assertIn("Text(file.name.substringBeforeLast", self.row)
        self.assertIn("if (chapterTitle.isNotBlank())", self.row)
        self.assertIn("Text(chapterTitle, maxLines = 1, overflow = TextOverflow.Ellipsis", self.row)
        self.assertIn("else FlowRow", self.row)
        self.assertIn("Text(file.size", self.row)
        self.assertIn("if (file.kind == \"image\") Thumbnail(file)", self.row)


if __name__ == "__main__":
    unittest.main()
