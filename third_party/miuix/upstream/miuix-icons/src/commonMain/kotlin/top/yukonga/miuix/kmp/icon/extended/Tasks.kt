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

val MiuixIcons.Tasks: ImageVector
    get() = MiuixIcons.Regular.Tasks

val MiuixIcons.Regular.Tasks: ImageVector
    get() {
        if (_tasksRegular != null) return _tasksRegular!!
        _tasksRegular = ImageVector.Builder(
            name = "Tasks.Regular",
            defaultWidth = 24.0f.dp,
            defaultHeight = 24.0f.dp,
            viewportWidth = 1118.4f,
            viewportHeight = 1118.4f,
        ).apply {
            group(scaleY = -1.0f, translationY = 1118.4f) {
                addPath(
                    pathData = listOf(
                        PathNode.MoveTo(894.2f, 156.8f),
                        PathNode.QuadTo(938.7f, 178.9f, 961.6f, 223.3f),
                        PathNode.QuadTo(973.0f, 246.3f, 975.7f, 278.1f),
                        PathNode.QuadTo(978.3f, 309.9f, 978.3f, 382.3f),
                        PathNode.VerticalTo(736.2f),
                        PathNode.QuadTo(978.3f, 809.0f, 975.7f, 840.6f),
                        PathNode.QuadTo(973.0f, 872.1f, 961.6f, 895.1f),
                        PathNode.QuadTo(938.7f, 939.6f, 894.2f, 961.6f),
                        PathNode.QuadTo(871.8f, 973.0f, 840.4f, 975.7f),
                        PathNode.QuadTo(809.0f, 978.3f, 736.2f, 978.3f),
                        PathNode.HorizontalTo(382.3f),
                        PathNode.QuadTo(309.0f, 978.3f, 277.7f, 975.7f),
                        PathNode.QuadTo(246.3f, 973.0f, 223.3f, 961.6f),
                        PathNode.QuadTo(179.4f, 939.1f, 156.8f, 895.1f),
                        PathNode.QuadTo(145.4f, 872.1f, 142.7f, 840.6f),
                        PathNode.QuadTo(140.1f, 809.0f, 140.1f, 736.2f),
                        PathNode.VerticalTo(382.3f),
                        PathNode.QuadTo(140.1f, 309.9f, 142.7f, 278.1f),
                        PathNode.QuadTo(145.4f, 246.3f, 156.8f, 223.3f),
                        PathNode.QuadTo(179.4f, 179.4f, 223.3f, 156.8f),
                        PathNode.QuadTo(246.3f, 145.4f, 277.7f, 142.7f),
                        PathNode.QuadTo(309.0f, 140.1f, 382.3f, 140.1f),
                        PathNode.HorizontalTo(736.2f),
                        PathNode.QuadTo(809.0f, 140.1f, 840.4f, 142.7f),
                        PathNode.QuadTo(871.8f, 145.4f, 894.2f, 156.8f),
                        PathNode.Close,
                        PathNode.MoveTo(480.2f, 378.8f),
                        PathNode.LineTo(325.8f, 532.8f),
                        PathNode.QuadTo(319.0f, 540.0f, 319.0f, 550.2f),
                        PathNode.QuadTo(319.0f, 560.3f, 325.8f, 567.1f),
                        PathNode.LineTo(338.1f, 579.8f),
                        PathNode.QuadTo(345.7f, 587.4f, 355.6f, 587.3f),
                        PathNode.QuadTo(365.6f, 587.1f, 373.2f, 579.4f),
                        PathNode.LineTo(501.1f, 451.9f),
                        PathNode.LineTo(748.1f, 773.8f),
                        PathNode.QuadTo(753.6f, 781.5f, 764.2f, 782.7f),
                        PathNode.QuadTo(774.7f, 783.9f, 782.4f, 778.1f),
                        PathNode.LineTo(795.7f, 767.4f),
                        PathNode.QuadTo(804.1f, 761.5f, 805.2f, 751.3f),
                        PathNode.QuadTo(806.2f, 741.2f, 799.9f, 733.1f),
                        PathNode.LineTo(529.8f, 382.2f),
                        PathNode.QuadTo(520.4f, 369.7f, 505.9f, 368.6f),
                        PathNode.QuadTo(491.5f, 367.5f, 480.2f, 378.8f),
                        PathNode.Close,
                    ),
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    pathFillType = PathFillType.NonZero,
                )
            }
        }.build()
        return _tasksRegular!!
    }

private var _tasksRegular: ImageVector? = null
