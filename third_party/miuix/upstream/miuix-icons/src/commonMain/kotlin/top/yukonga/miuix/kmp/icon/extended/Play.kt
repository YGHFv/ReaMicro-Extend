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

val MiuixIcons.Play: ImageVector
    get() = MiuixIcons.Regular.Play

val MiuixIcons.Regular.Play: ImageVector
    get() {
        if (_playRegular != null) return _playRegular!!
        _playRegular = ImageVector.Builder(
            name = "Play.Regular",
            defaultWidth = 24.0f.dp,
            defaultHeight = 24.0f.dp,
            viewportWidth = 1168.8f,
            viewportHeight = 1168.8f,
        ).apply {
            group(scaleY = -1.0f, translationY = 1168.8f) {
                addPath(
                    pathData = listOf(
                        PathNode.MoveTo(419.9f, 161.4f),
                        PathNode.LineTo(878.9f, 427.4f),
                        PathNode.QuadTo(944.9f, 465.4f, 971.9f, 485.4f),
                        PathNode.QuadTo(998.9f, 505.4f, 1010.9f, 532.4f),
                        PathNode.QuadTo(1021.9f, 557.4f, 1021.9f, 585.4f),
                        PathNode.QuadTo(1021.9f, 613.4f, 1010.9f, 638.4f),
                        PathNode.QuadTo(998.9f, 664.4f, 971.9f, 684.9f),
                        PathNode.QuadTo(944.9f, 705.4f, 878.9f, 742.4f),
                        PathNode.LineTo(419.9f, 1008.4f),
                        PathNode.QuadTo(357.9f, 1044.4f, 324.9f, 1057.9f),
                        PathNode.QuadTo(291.9f, 1071.4f, 263.9f, 1068.4f),
                        PathNode.QuadTo(236.9f, 1066.4f, 212.9f, 1052.9f),
                        PathNode.QuadTo(188.9f, 1039.4f, 171.9f, 1016.4f),
                        PathNode.QuadTo(154.9f, 994.4f, 150.9f, 960.9f),
                        PathNode.QuadTo(146.9f, 927.4f, 146.9f, 851.4f),
                        PathNode.VerticalTo(320.4f),
                        PathNode.QuadTo(146.9f, 242.4f, 150.4f, 209.4f),
                        PathNode.QuadTo(153.9f, 176.4f, 170.9f, 152.4f),
                        PathNode.QuadTo(187.9f, 130.4f, 212.4f, 116.4f),
                        PathNode.QuadTo(236.9f, 102.4f, 262.9f, 100.4f),
                        PathNode.QuadTo(291.9f, 97.4f, 324.4f, 110.9f),
                        PathNode.QuadTo(356.9f, 124.4f, 419.9f, 161.4f),
                        PathNode.Close,
                        PathNode.MoveTo(244.9f, 205.4f),
                        PathNode.QuadTo(239.9f, 212.4f, 238.4f, 235.9f),
                        PathNode.QuadTo(236.9f, 259.4f, 236.9f, 320.4f),
                        PathNode.VerticalTo(851.4f),
                        PathNode.QuadTo(236.9f, 910.4f, 238.4f, 932.4f),
                        PathNode.QuadTo(239.9f, 954.4f, 243.9f, 961.4f),
                        PathNode.QuadTo(248.9f, 968.4f, 256.4f, 973.4f),
                        PathNode.QuadTo(263.9f, 978.4f, 272.9f, 979.4f),
                        PathNode.QuadTo(279.9f, 980.4f, 301.9f, 969.4f),
                        PathNode.QuadTo(323.9f, 958.4f, 373.9f, 930.4f),
                        PathNode.LineTo(834.9f, 664.4f),
                        PathNode.QuadTo(881.9f, 637.4f, 901.4f, 623.9f),
                        PathNode.QuadTo(920.9f, 610.4f, 925.9f, 602.4f),
                        PathNode.QuadTo(935.9f, 586.4f, 927.9f, 568.4f),
                        PathNode.QuadTo(923.9f, 560.4f, 904.4f, 546.9f),
                        PathNode.QuadTo(884.9f, 533.4f, 834.9f, 505.4f),
                        PathNode.LineTo(373.9f, 239.4f),
                        PathNode.QuadTo(321.9f, 209.4f, 301.9f, 199.4f),
                        PathNode.QuadTo(281.9f, 189.4f, 272.9f, 190.4f),
                        PathNode.QuadTo(253.9f, 192.4f, 244.9f, 205.4f),
                        PathNode.Close,
                    ),
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    pathFillType = PathFillType.NonZero,
                )
            }
        }.build()
        return _playRegular!!
    }

private var _playRegular: ImageVector? = null
