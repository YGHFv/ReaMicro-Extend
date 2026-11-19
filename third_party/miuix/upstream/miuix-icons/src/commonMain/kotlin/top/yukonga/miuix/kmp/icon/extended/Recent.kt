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

val MiuixIcons.Recent: ImageVector
    get() = MiuixIcons.Regular.Recent

val MiuixIcons.Regular.Recent: ImageVector
    get() {
        if (_recentRegular != null) return _recentRegular!!
        _recentRegular = ImageVector.Builder(
            name = "Recent.Regular",
            defaultWidth = 24.0f.dp,
            defaultHeight = 24.0f.dp,
            viewportWidth = 1198.8f,
            viewportHeight = 1198.8f,
        ).apply {
            group(scaleY = -1.0f, translationY = 1198.8f) {
                addPath(
                    pathData = listOf(
                        PathNode.MoveTo(1098.4f, 599.9f),
                        PathNode.QuadTo(1098.4f, 734.9f, 1031.4f, 849.9f),
                        PathNode.QuadTo(964.4f, 964.9f, 849.9f, 1031.9f),
                        PathNode.QuadTo(735.4f, 1098.9f, 599.4f, 1098.9f),
                        PathNode.QuadTo(463.4f, 1098.9f, 348.9f, 1031.9f),
                        PathNode.QuadTo(234.4f, 964.9f, 167.4f, 849.9f),
                        PathNode.QuadTo(100.4f, 734.9f, 100.4f, 599.9f),
                        PathNode.QuadTo(100.4f, 463.9f, 167.4f, 349.4f),
                        PathNode.QuadTo(234.4f, 234.9f, 348.9f, 167.4f),
                        PathNode.QuadTo(463.4f, 99.9f, 599.4f, 99.9f),
                        PathNode.QuadTo(735.4f, 99.9f, 849.9f, 167.4f),
                        PathNode.QuadTo(964.4f, 234.9f, 1031.4f, 349.4f),
                        PathNode.QuadTo(1098.4f, 463.9f, 1098.4f, 599.9f),
                        PathNode.Close,
                        PathNode.MoveTo(566.4f, 599.9f),
                        PathNode.VerticalTo(930.9f),
                        PathNode.QuadTo(566.4f, 942.9f, 571.9f, 947.9f),
                        PathNode.QuadTo(577.4f, 952.9f, 590.4f, 952.9f),
                        PathNode.HorizontalTo(608.4f),
                        PathNode.QuadTo(621.4f, 952.9f, 626.9f, 947.9f),
                        PathNode.QuadTo(632.4f, 942.9f, 632.4f, 930.9f),
                        PathNode.VerticalTo(632.9f),
                        PathNode.HorizontalTo(860.4f),
                        PathNode.QuadTo(871.4f, 632.9f, 876.9f, 626.9f),
                        PathNode.QuadTo(882.4f, 620.9f, 882.4f, 611.9f),
                        PathNode.VerticalTo(586.9f),
                        PathNode.QuadTo(882.4f, 577.9f, 876.9f, 571.9f),
                        PathNode.QuadTo(871.4f, 565.9f, 860.4f, 565.9f),
                        PathNode.HorizontalTo(589.4f),
                        PathNode.QuadTo(575.4f, 565.9f, 570.9f, 573.9f),
                        PathNode.QuadTo(566.4f, 581.9f, 566.4f, 599.9f),
                        PathNode.Close,
                    ),
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    pathFillType = PathFillType.NonZero,
                )
            }
        }.build()
        return _recentRegular!!
    }

private var _recentRegular: ImageVector? = null
