"""Source regression checks; device gestures still require UI verification."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main/java/com/reamicro/fix/ui/ModuleMainActivity.kt"


class AboutPageLayoutTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = MAIN.read_text()
        cls.about = cls.main.split("private fun AboutPage()", 1)[1].split("private fun rootAction(", 1)[0]
        cls.scroll = cls.main.split("val scrollContent: @Composable () -> Unit =", 1)[1].split("if (page == TAB_TASKS)", 1)[0]

    def test_about_keeps_vertical_scroll_without_phantom_top_bar(self):
        self.assertIn(
            ".then(if (page == TAB_ABOUT) Modifier else Modifier.nestedScroll(pageScroll.nestedScrollConnection))",
            self.scroll,
        )
        self.assertIn(".verticalScroll(rememberScrollState(), overscrollEffect = null)", self.scroll)
        self.assertIn(".overScrollVertical()", self.scroll)
        self.assertIn("Spacer(Modifier.height(padding.calculateBottomPadding()))", self.scroll)

    def test_logo_matches_miuix_example(self):
        self.assertIn("Modifier.size(88.dp)", self.about)
        self.assertIn("Modifier.fillMaxSize().graphicsLayer", self.about)
        self.assertNotIn("Modifier.size(74.dp)", self.about)
        self.assertIn(".clip(RoundedCornerShape(24.dp))", self.about)
        self.assertIn("Modifier.padding(top = 16.dp, bottom = 5.dp)", self.about)
        self.assertIn("fontSize = 35.sp", self.about)
        self.assertNotIn("Modifier.size(100.dp)", self.about)

    def test_spacing_includes_hidden_navigation_bar_height(self):
        self.assertIn("val brandTopPadding = 52.dp + 40.dp + 52.dp", self.about)
        self.assertIn("val brandBottomPadding = 126.dp", self.about)
        self.assertIn(".padding(top = brandTopPadding, bottom = brandBottomPadding)", self.about)
        self.assertNotIn("brandVerticalPadding", self.about)
        self.assertIn(".padding(top = if (page == TAB_ABOUT) 0.dp else 4.dp, bottom = 4.dp)", self.scroll)
        self.assertIn("WindowInsets.statusBars.asPaddingValues().calculateTopPadding()", self.scroll)
        self.assertIn("if (currentPage != TAB_ABOUT) TopAppBar(", self.main)
        self.assertIn("uiText(R.string.about_build_time, buildTimeText())", self.about)

    def test_background_and_acknowledgements_are_preserved(self):
        self.assertIn("com.reamicro.fix.ui.effect.BgEffectBackground(", self.main)
        for name in ('"KernelSU"', '"Miuix"', '"Scripta"'):
            self.assertIn("title = " + name, self.about)


if __name__ == "__main__":
    unittest.main()
