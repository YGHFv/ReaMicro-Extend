// Copyright 2025, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0
// ReaMicro Android subset: only the used Regular glyph and its default alias.

package top.yukonga.miuix.kmp.icon.extended

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.icon.MiuixIcons

val MiuixIcons.Info: ImageVector
    get() = MiuixIcons.Regular.Info

val MiuixIcons.Regular.Info: ImageVector
    get() {
        if (_infoRegular != null) return _infoRegular!!
        _infoRegular = ImageVector.Builder(
            name = "Info.Regular",
            defaultWidth = 24.0f.dp,
            defaultHeight = 24.0f.dp,
            viewportWidth = 1225.2f,
            viewportHeight = 1225.2f,
        ).apply {
            group(scaleY = -1.0f, translationY = 1225.2f) {
                addPath(
                    pathData = listOf(
                        PathNode.MoveTo(1123.1f, 612.1f),
                        PathNode.QuadTo(1123.1f, 751.1f, 1054.6f, 868.1f),
                        PathNode.QuadTo(986.1f, 985.1f, 869.1f, 1054.1f),
                        PathNode.QuadTo(752.1f, 1123.1f, 613.1f, 1123.1f),
                        PathNode.QuadTo(474.1f, 1123.1f, 357.1f, 1054.1f),
                        PathNode.QuadTo(240.1f, 985.1f, 171.1f, 868.1f),
                        PathNode.QuadTo(102.1f, 751.1f, 102.1f, 612.1f),
                        PathNode.QuadTo(102.1f, 473.1f, 171.1f, 356.1f),
                        PathNode.QuadTo(240.1f, 239.1f, 357.1f, 170.6f),
                        PathNode.QuadTo(474.1f, 102.1f, 613.1f, 102.1f),
                        PathNode.QuadTo(752.1f, 102.1f, 869.1f, 170.6f),
                        PathNode.QuadTo(986.1f, 239.1f, 1054.6f, 356.1f),
                        PathNode.QuadTo(1123.1f, 473.1f, 1123.1f, 612.1f),
                        PathNode.Close,
                        PathNode.MoveTo(188.1f, 612.1f),
                        PathNode.QuadTo(188.1f, 728.1f, 245.1f, 825.6f),
                        PathNode.QuadTo(302.1f, 923.1f, 399.6f, 980.1f),
                        PathNode.QuadTo(497.1f, 1037.1f, 613.1f, 1037.1f),
                        PathNode.QuadTo(728.1f, 1037.1f, 825.6f, 980.1f),
                        PathNode.QuadTo(923.1f, 923.1f, 980.6f, 825.6f),
                        PathNode.QuadTo(1038.1f, 728.1f, 1038.1f, 612.1f),
                        PathNode.QuadTo(1038.1f, 496.1f, 980.6f, 398.6f),
                        PathNode.QuadTo(923.1f, 301.1f, 825.6f, 244.1f),
                        PathNode.QuadTo(728.1f, 187.1f, 613.1f, 187.1f),
                        PathNode.QuadTo(497.1f, 187.1f, 399.6f, 244.1f),
                        PathNode.QuadTo(302.1f, 301.1f, 245.1f, 398.6f),
                        PathNode.QuadTo(188.1f, 496.1f, 188.1f, 612.1f),
                        PathNode.Close,
                        PathNode.MoveTo(667.1f, 848.1f),
                        PathNode.QuadTo(667.1f, 871.1f, 651.6f, 886.6f),
                        PathNode.QuadTo(636.1f, 902.1f, 613.1f, 902.1f),
                        PathNode.QuadTo(590.1f, 902.1f, 574.1f, 886.1f),
                        PathNode.QuadTo(558.1f, 870.1f, 558.1f, 848.1f),
                        PathNode.QuadTo(558.1f, 826.1f, 574.6f, 809.6f),
                        PathNode.QuadTo(591.1f, 793.1f, 613.1f, 793.1f),
                        PathNode.QuadTo(635.1f, 793.1f, 651.1f, 809.1f),
                        PathNode.QuadTo(667.1f, 825.1f, 667.1f, 848.1f),
                        PathNode.Close,
                        PathNode.MoveTo(655.1f, 350.1f),
                        PathNode.VerticalTo(709.1f),
                        PathNode.QuadTo(655.1f, 721.1f, 648.1f, 728.6f),
                        PathNode.QuadTo(641.1f, 736.1f, 626.1f, 736.1f),
                        PathNode.HorizontalTo(598.1f),
                        PathNode.QuadTo(585.1f, 736.1f, 577.6f, 728.1f),
                        PathNode.QuadTo(570.1f, 720.1f, 570.1f, 709.1f),
                        PathNode.VerticalTo(350.1f),
                        PathNode.QuadTo(570.1f, 337.1f, 578.1f, 330.1f),
                        PathNode.QuadTo(586.1f, 323.1f, 599.1f, 323.1f),
                        PathNode.HorizontalTo(627.1f),
                        PathNode.QuadTo(640.1f, 323.1f, 647.6f, 330.1f),
                        PathNode.QuadTo(655.1f, 337.1f, 655.1f, 350.1f),
                        PathNode.Close,
                    ),
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    pathFillType = PathFillType.NonZero,
                )
            }
        }.build()
        return _infoRegular!!
    }

private var _infoRegular: ImageVector? = null
