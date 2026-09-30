"""Source contracts only; not GPU/frame-time or device visual verification."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/java/com/reamicro/fix/ui"


class AboutEffectPerformanceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.main = (UI / "ModuleMainActivity.kt").read_text()
        cls.background = (UI / "effect/BgEffectBackground.kt").read_text()
        cls.node = (UI / "effect/BgEffectModifier.kt").read_text()
        cls.card = (UI / "effect/AboutEffectCard.kt").read_text()
        cls.branch = cls.main.split("val aboutPlaying by remember(", 1)[1].split("            Dialogs()", 1)[0]

    def test_playback_waits_for_settled_page(self):
        for token in ("derivedStateOf", "aboutEffectResumed.value", "currentPage == TAB_ABOUT",
                      "!isNavigating", "!pagerState.isScrollInProgress",
                      "pagerState.settledPage == TAB_ABOUT", "dynamicBackground = aboutPlaying"):
            self.assertIn(token, self.branch)
        self.assertNotIn("currentPageOffsetFraction", self.branch)

    def test_background_lifecycle_and_first_use(self):
        resume = self.main.split("override fun onResume()", 1)[1].split("override fun onPause()", 1)[0]
        pause = self.main.split("override fun onPause()", 1)[1].split("override fun onUserLeaveHint()", 1)[0]
        self.assertIn("aboutEffectResumed.value = true", resume)
        self.assertIn("aboutEffectResumed.value = false", pause)
        self.assertIn("var aboutEffectReady by remember { mutableStateOf(false) }", self.branch)
        self.assertIn("if (aboutPlaying) aboutEffectReady = true", self.branch)
        self.assertIn("effectBackground = aboutEffectReady", self.branch)
        self.assertIn("if (effectBackground)", self.node)

    def test_both_animation_drivers_pause_without_resetting_painter(self):
        self.assertIn("val painter = remember { BgEffectPainter() }", self.background)
        self.assertIn("if (!dynamicBackground || !effectBackground) return@LaunchedEffect", self.background)
        self.assertIn("playing = dynamicBackground && effectBackground", self.background)
        self.assertIn("animationJob?.cancel()", self.node)
        self.assertIn("startOffset = animTime", self.node)

    def test_independent_background_recording(self):
        self.assertIn("modifier = Modifier.fillMaxSize().graphicsLayer()", self.branch)
        self.assertIn("bgModifier = Modifier.layerBackdrop(aboutBackdrop)", self.branch)
        self.assertIn("LocalAboutBackdrop provides", self.branch)
        spacer = self.background.split("        Spacer(", 1)[1]
        self.assertLess(spacer.index(".graphicsLayer()"), spacer.index(".then(bgModifier)"))
        self.assertLess(spacer.index(".then(bgModifier)"), spacer.index(".bgEffectDraw("))

    def test_cards_use_reference_blur_and_dynamic_backdrop(self):
        about = self.main.split("private fun AboutPage()", 1)[1].split("private fun rootAction(", 1)[0]
        self.assertEqual(2, about.count("com.reamicro.fix.ui.effect.AboutEffectCard("))
        self.assertIn("LocalAboutBackdrop.current", self.card)
        self.assertIn("Modifier.textureBlur(", self.card)
        self.assertIn("blurRadius = 60f", self.card)
        self.assertIn("ColorBlendToken.Overlay_Thin_Light", self.card)
        self.assertIn("ColorBlendToken.Pured_Regular_Light", self.card)
        self.assertIn("remember(dark)", self.card)
        self.assertIn("Color.Transparent else MiuixTheme.colorScheme.surfaceContainer", self.card)
        self.assertNotIn("layerBackdrop(", self.card)

    def test_unsupported_devices_have_solid_fallback(self):
        self.assertIn("if (!isRuntimeShaderSupported())", self.background)
        self.assertIn("if (blurSupported && aboutEffectReady) aboutBackdrop else null", self.branch)
        self.assertIn("if (backdrop != null)", self.card)


if __name__ == "__main__":
    unittest.main()
