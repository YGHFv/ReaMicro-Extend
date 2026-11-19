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

val MiuixIcons.Glass.Tasks: ImageVector
    get() = MiuixIcons.Glass.Regular.Tasks

val MiuixIcons.Glass.Regular.Tasks: ImageVector
    get() {
        if (_tasksRegular != null) return _tasksRegular!!
        _tasksRegular = ImageVector.Builder(
            name = "Tasks.Glass.Regular",
            defaultWidth = 28.0f.dp,
            defaultHeight = 28.0f.dp,
            viewportWidth = 1333.3833f,
            viewportHeight = 1333.3833f,
            autoMirror = false,
        ).apply {
            group(scaleY = -1.0f, translationX = 95.2417f, translationY = 1238.1417f) {
                addPath(
                    pathData = listOf(
                        PathNode.MoveTo(946.4f, 123.4f),
                        PathNode.QuadTo(994.4f, 147.4f, 1019.4f, 195.4f),
                        PathNode.QuadTo(1031.4f, 220.4f, 1034.4f, 255.4f),
                        PathNode.QuadTo(1037.4f, 290.4f, 1037.4f, 371.4f),
                        PathNode.LineTo(1037.4f, 771.4f),
                        PathNode.QuadTo(1037.4f, 853.4f, 1034.4f, 887.9f),
                        PathNode.QuadTo(1031.4f, 922.4f, 1019.4f, 947.4f),
                        PathNode.QuadTo(994.4f, 995.4f, 946.4f, 1019.4f),
                        PathNode.QuadTo(922.4f, 1031.4f, 887.9f, 1034.4f),
                        PathNode.QuadTo(853.4f, 1037.4f, 771.4f, 1037.4f),
                        PathNode.LineTo(371.4f, 1037.4f),
                        PathNode.QuadTo(289.4f, 1037.4f, 254.9f, 1034.4f),
                        PathNode.QuadTo(220.4f, 1031.4f, 195.4f, 1019.4f),
                        PathNode.QuadTo(148.4f, 994.4f, 123.4f, 947.4f),
                        PathNode.QuadTo(111.4f, 922.4f, 108.4f, 887.9f),
                        PathNode.QuadTo(105.4f, 853.4f, 105.4f, 771.4f),
                        PathNode.LineTo(105.4f, 371.4f),
                        PathNode.QuadTo(105.4f, 290.4f, 108.4f, 255.4f),
                        PathNode.QuadTo(111.4f, 220.4f, 123.4f, 195.4f),
                        PathNode.QuadTo(148.4f, 148.4f, 195.4f, 123.4f),
                        PathNode.QuadTo(220.4f, 111.4f, 254.9f, 108.4f),
                        PathNode.QuadTo(289.4f, 105.4f, 371.4f, 105.4f),
                        PathNode.LineTo(771.4f, 105.4f),
                        PathNode.QuadTo(853.4f, 105.4f, 887.9f, 108.4f),
                        PathNode.QuadTo(922.4f, 111.4f, 946.4f, 123.4f),
                        PathNode.Close,
                        PathNode.MoveTo(487.4f, 372.4f),
                        PathNode.LineTo(305.4f, 554.4f),
                        PathNode.QuadTo(299.4f, 560.4f, 299.4f, 569.4f),
                        PathNode.QuadTo(299.4f, 578.4f, 305.4f, 584.4f),
                        PathNode.LineTo(318.4f, 597.4f),
                        PathNode.QuadTo(325.4f, 604.4f, 333.9f, 604.4f),
                        PathNode.QuadTo(342.4f, 604.4f, 349.4f, 597.4f),
                        PathNode.LineTo(506.4f, 440.4f),
                        PathNode.LineTo(795.4f, 817.4f),
                        PathNode.QuadTo(800.4f, 824.4f, 809.4f, 825.4f),
                        PathNode.QuadTo(818.4f, 826.4f, 825.4f, 821.4f),
                        PathNode.LineTo(840.4f, 809.4f),
                        PathNode.QuadTo(847.4f, 804.4f, 848.4f, 795.4f),
                        PathNode.QuadTo(849.4f, 786.4f, 844.4f, 779.4f),
                        PathNode.LineTo(533.4f, 375.4f),
                        PathNode.QuadTo(524.4f, 363.4f, 511.4f, 362.4f),
                        PathNode.QuadTo(498.4f, 361.4f, 487.4f, 372.4f),
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
