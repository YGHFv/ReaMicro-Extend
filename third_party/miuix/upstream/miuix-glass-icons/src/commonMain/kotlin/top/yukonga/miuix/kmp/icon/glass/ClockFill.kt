// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// ReaMicro Android subset: only the used Regular glyph and its default alias.

package top.yukonga.miuix.kmp.icon.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.icon.MiuixIcons

val MiuixIcons.Glass.ClockFill: ImageVector
    get() = MiuixIcons.Glass.Regular.ClockFill

val MiuixIcons.Glass.Regular.ClockFill: ImageVector
    get() {
        if (_worldClockRegular != null) return _worldClockRegular!!
        _worldClockRegular = ImageVector.Builder(
            name = "ClockFill.Glass.Regular",
            defaultWidth = 28.0f.dp,
            defaultHeight = 28.0f.dp,
            viewportWidth = 1333.3833f,
            viewportHeight = 1333.3833f,
            autoMirror = false,
        ).apply {
            group(scaleY = -1.0f, translationX = 95.2417f, translationY = 1238.1417f) {
                addPath(
                    pathData = listOf(
                        PathNode.MoveTo(1070.4f, 571.4f),
                        PathNode.QuadTo(1070.4f, 706.4f, 1003.4f, 821.4f),
                        PathNode.QuadTo(936.4f, 936.4f, 821.9f, 1003.4f),
                        PathNode.QuadTo(707.4f, 1070.4f, 571.4f, 1070.4f),
                        PathNode.QuadTo(435.4f, 1070.4f, 320.9f, 1003.4f),
                        PathNode.QuadTo(206.4f, 936.4f, 139.4f, 821.4f),
                        PathNode.QuadTo(72.4f, 706.4f, 72.4f, 571.4f),
                        PathNode.QuadTo(72.4f, 435.4f, 139.4f, 320.9f),
                        PathNode.QuadTo(206.4f, 206.4f, 320.9f, 139.4f),
                        PathNode.QuadTo(435.4f, 72.4f, 571.4f, 72.4f),
                        PathNode.QuadTo(707.4f, 72.4f, 821.9f, 139.4f),
                        PathNode.QuadTo(936.4f, 206.4f, 1003.4f, 320.9f),
                        PathNode.QuadTo(1070.4f, 435.4f, 1070.4f, 571.4f),
                        PathNode.Close,
                        PathNode.MoveTo(538.4f, 572.4f),
                        PathNode.LineTo(538.4f, 903.4f),
                        PathNode.QuadTo(538.4f, 915.4f, 543.9f, 920.4f),
                        PathNode.QuadTo(549.4f, 925.4f, 562.4f, 925.4f),
                        PathNode.LineTo(580.4f, 925.4f),
                        PathNode.QuadTo(593.4f, 925.4f, 598.9f, 920.4f),
                        PathNode.QuadTo(604.4f, 915.4f, 604.4f, 903.4f),
                        PathNode.LineTo(604.4f, 605.4f),
                        PathNode.LineTo(832.4f, 605.4f),
                        PathNode.QuadTo(843.4f, 605.4f, 848.9f, 599.4f),
                        PathNode.QuadTo(854.4f, 593.4f, 854.4f, 584.4f),
                        PathNode.LineTo(854.4f, 559.4f),
                        PathNode.QuadTo(854.4f, 550.4f, 848.9f, 544.4f),
                        PathNode.QuadTo(843.4f, 538.4f, 832.4f, 538.4f),
                        PathNode.LineTo(561.4f, 538.4f),
                        PathNode.QuadTo(547.4f, 538.4f, 542.9f, 546.4f),
                        PathNode.QuadTo(538.4f, 554.4f, 538.4f, 572.4f),
                        PathNode.Close,
                    ),
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    pathFillType = PathFillType.NonZero,
                )
            }
        }.build()
        return _worldClockRegular!!
    }

private var _worldClockRegular: ImageVector? = null
