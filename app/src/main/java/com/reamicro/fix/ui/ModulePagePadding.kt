package com.reamicro.fix.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Keep Scaffold's live padding wrapper intact. LazyColumn consumes it during measurement, so
 * collapsing the top bar changes layout without rebuilding the list's content.
 */
@Composable
internal fun rememberModulePagePadding(
    top: PaddingValues,
    bottom: PaddingValues,
    topGap: Dp = 4.dp,
    bottomGap: Dp = 8.dp,
): PaddingValues = remember(top, bottom, topGap, bottomGap) {
    object : PaddingValues {
        override fun calculateLeftPadding(layoutDirection: LayoutDirection) = 0.dp
        override fun calculateRightPadding(layoutDirection: LayoutDirection) = 0.dp
        override fun calculateTopPadding() = top.calculateTopPadding() + topGap
        override fun calculateBottomPadding() = bottom.calculateBottomPadding() + bottomGap
    }
}

/** Parent containers own horizontal insets; defer those reads as well. */
@Composable
internal fun rememberModuleHorizontalPadding(insets: PaddingValues): PaddingValues = remember(insets) {
    object : PaddingValues {
        override fun calculateLeftPadding(layoutDirection: LayoutDirection) =
            insets.calculateLeftPadding(layoutDirection)
        override fun calculateRightPadding(layoutDirection: LayoutDirection) =
            insets.calculateRightPadding(layoutDirection)
        override fun calculateTopPadding() = 0.dp
        override fun calculateBottomPadding() = 0.dp
    }
}
