package com.reamicro.fix.ui
import org.junit.Assert.*
import org.junit.Test
class DialogInsetsTest {
    @Test fun `without gesture indicator native padding still stays at 24 dp`() {
        assertEquals(24f, DIALOG_INSIDE_DP + dialogFooterPadding(0f).bottomExtra)
    }
    @Test fun `normal rounded corners do not double the native margin`() {
        assertEquals(0f, dialogFooterPadding(48f).bottomExtra)
    }
    @Test fun `large corners increase horizontal and bottom clearance equally`() {
        val p = dialogFooterPadding(96f)
        assertEquals(48f, DIALOG_INSIDE_DP + p.bottomExtra)
        assertEquals(48f, DIALOG_INSIDE_DP + p.horizontalExtra)
    }
    @Test fun `invalid radius cannot produce invalid padding`() {
        assertEquals(0f, dialogFooterPadding(Float.NaN).bottomExtra)
        assertEquals(0f, dialogFooterPadding(Float.POSITIVE_INFINITY).bottomExtra)
        assertEquals(0f, dialogFooterPadding(-1f).bottomExtra)
    }
    @Test fun `very large radius remains safe rather than silently clamped`() {
        assertEquals(80f, DIALOG_INSIDE_DP + dialogFooterPadding(160f).bottomExtra)
    }
}
