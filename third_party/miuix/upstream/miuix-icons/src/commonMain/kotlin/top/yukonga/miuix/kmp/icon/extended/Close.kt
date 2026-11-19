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

val MiuixIcons.Close: ImageVector
    get() = MiuixIcons.Regular.Close

val MiuixIcons.Regular.Close: ImageVector
    get() {
        if (_closeRegular != null) return _closeRegular!!
        _closeRegular = ImageVector.Builder(
            name = "Close.Regular",
            defaultWidth = 24.0f.dp,
            defaultHeight = 24.0f.dp,
            viewportWidth = 1047.6f,
            viewportHeight = 1047.6f,
        ).apply {
            group(scaleY = -1.0f, translationY = 1047.6f) {
                addPath(
                    pathData = listOf(
                        PathNode.MoveTo(523.8f, 593.3f),
                        PathNode.LineTo(832.7f, 903.9f),
                        PathNode.QuadTo(845.0f, 916.5f, 857.1f, 916.3f),
                        PathNode.QuadTo(869.2f, 916.2f, 881.2f, 903.9f),
                        PathNode.LineTo(897.5f, 887.6f),
                        PathNode.QuadTo(909.6f, 875.6f, 909.7f, 863.8f),
                        PathNode.QuadTo(909.9f, 852.0f, 897.5f, 839.1f),
                        PathNode.LineTo(588.6f, 528.4f),
                        PathNode.LineTo(898.8f, 218.3f),
                        PathNode.QuadTo(911.0f, 206.6f, 911.3f, 194.8f),
                        PathNode.QuadTo(911.7f, 182.9f, 898.8f, 170.5f),
                        PathNode.LineTo(881.0f, 152.7f),
                        PathNode.QuadTo(858.2f, 129.9f, 833.6f, 153.8f),
                        PathNode.LineTo(523.8f, 463.6f),
                        PathNode.LineTo(213.1f, 154.7f),
                        PathNode.QuadTo(201.5f, 142.7f, 189.2f, 142.5f),
                        PathNode.QuadTo(177.0f, 142.3f, 164.6f, 154.7f),
                        PathNode.LineTo(148.6f, 171.0f),
                        PathNode.QuadTo(136.3f, 182.7f, 136.3f, 194.9f),
                        PathNode.QuadTo(136.3f, 207.1f, 148.2f, 219.2f),
                        PathNode.LineTo(458.9f, 528.4f),
                        PathNode.LineTo(149.1f, 839.1f),
                        PathNode.QuadTo(137.1f, 852.0f, 137.1f, 863.8f),
                        PathNode.QuadTo(137.1f, 875.6f, 149.1f, 887.6f),
                        PathNode.LineTo(165.5f, 903.9f),
                        PathNode.QuadTo(178.4f, 916.5f, 190.0f, 916.5f),
                        PathNode.QuadTo(201.6f, 916.5f, 214.0f, 903.9f),
                        PathNode.Close,
                    ),
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    pathFillType = PathFillType.NonZero,
                )
            }
        }.build()
        return _closeRegular!!
    }

private var _closeRegular: ImageVector? = null
