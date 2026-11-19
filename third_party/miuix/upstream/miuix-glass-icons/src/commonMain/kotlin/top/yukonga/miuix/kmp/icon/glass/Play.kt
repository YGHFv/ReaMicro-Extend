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

val MiuixIcons.Glass.Play: ImageVector
    get() = MiuixIcons.Glass.Regular.Play

val MiuixIcons.Glass.Regular.Play: ImageVector
    get() {
        if (_playRegular != null) return _playRegular!!
        _playRegular = ImageVector.Builder(
            name = "Play.Glass.Regular",
            defaultWidth = 28.0f.dp,
            defaultHeight = 28.0f.dp,
            viewportWidth = 1333.3833f,
            viewportHeight = 1333.3833f,
            autoMirror = false,
        ).apply {
            group(scaleY = -1.0f, translationX = 95.2417f, translationY = 1238.1417f) {
                addPath(
                    pathData = listOf(
                        PathNode.MoveTo(406.9f, 148.4f),
                        PathNode.LineTo(865.9f, 414.4f),
                        PathNode.QuadTo(931.9f, 452.4f, 958.9f, 472.4f),
                        PathNode.QuadTo(985.9f, 492.4f, 997.9f, 519.4f),
                        PathNode.QuadTo(1008.9f, 544.4f, 1008.9f, 572.4f),
                        PathNode.QuadTo(1008.9f, 600.4f, 997.9f, 625.4f),
                        PathNode.QuadTo(985.9f, 651.4f, 958.9f, 671.9f),
                        PathNode.QuadTo(931.9f, 692.4f, 865.9f, 729.4f),
                        PathNode.LineTo(406.9f, 995.4f),
                        PathNode.QuadTo(344.9f, 1031.4f, 311.9f, 1044.9f),
                        PathNode.QuadTo(278.9f, 1058.4f, 250.9f, 1055.4f),
                        PathNode.QuadTo(223.9f, 1053.4f, 199.9f, 1039.9f),
                        PathNode.QuadTo(175.9f, 1026.4f, 158.9f, 1003.4f),
                        PathNode.QuadTo(141.9f, 981.4f, 137.9f, 947.9f),
                        PathNode.QuadTo(133.9f, 914.4f, 133.9f, 838.4f),
                        PathNode.LineTo(133.9f, 307.4f),
                        PathNode.QuadTo(133.9f, 229.4f, 137.4f, 196.4f),
                        PathNode.QuadTo(140.9f, 163.4f, 157.9f, 139.4f),
                        PathNode.QuadTo(174.9f, 117.4f, 199.4f, 103.4f),
                        PathNode.QuadTo(223.9f, 89.4f, 249.9f, 87.4f),
                        PathNode.QuadTo(278.9f, 84.4f, 311.4f, 97.9f),
                        PathNode.QuadTo(343.9f, 111.4f, 406.9f, 148.4f),
                        PathNode.Close,
                        PathNode.MoveTo(231.9f, 192.4f),
                        PathNode.QuadTo(226.9f, 199.4f, 225.4f, 222.9f),
                        PathNode.QuadTo(223.9f, 246.4f, 223.9f, 307.4f),
                        PathNode.LineTo(223.9f, 838.4f),
                        PathNode.QuadTo(223.9f, 897.4f, 225.4f, 919.4f),
                        PathNode.QuadTo(226.9f, 941.4f, 230.9f, 948.4f),
                        PathNode.QuadTo(235.9f, 955.4f, 243.4f, 960.4f),
                        PathNode.QuadTo(250.9f, 965.4f, 259.9f, 966.4f),
                        PathNode.QuadTo(266.9f, 967.4f, 288.9f, 956.4f),
                        PathNode.QuadTo(310.9f, 945.4f, 360.9f, 917.4f),
                        PathNode.LineTo(821.9f, 651.4f),
                        PathNode.QuadTo(868.9f, 624.4f, 888.4f, 610.9f),
                        PathNode.QuadTo(907.9f, 597.4f, 912.9f, 589.4f),
                        PathNode.QuadTo(922.9f, 573.4f, 914.9f, 555.4f),
                        PathNode.QuadTo(910.9f, 547.4f, 891.4f, 533.9f),
                        PathNode.QuadTo(871.9f, 520.4f, 821.9f, 492.4f),
                        PathNode.LineTo(360.9f, 226.4f),
                        PathNode.QuadTo(308.9f, 196.4f, 288.9f, 186.4f),
                        PathNode.QuadTo(268.9f, 176.4f, 259.9f, 177.4f),
                        PathNode.QuadTo(250.9f, 178.4f, 243.4f, 181.9f),
                        PathNode.QuadTo(235.9f, 185.4f, 231.9f, 192.4f),
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
