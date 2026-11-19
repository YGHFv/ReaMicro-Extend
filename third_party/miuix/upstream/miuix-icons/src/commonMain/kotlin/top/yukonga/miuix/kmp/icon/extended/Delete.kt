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

val MiuixIcons.Delete: ImageVector
    get() = MiuixIcons.Regular.Delete

val MiuixIcons.Regular.Delete: ImageVector
    get() {
        if (_deleteRegular != null) return _deleteRegular!!
        _deleteRegular = ImageVector.Builder(
            name = "Delete.Regular",
            defaultWidth = 24.0f.dp,
            defaultHeight = 24.0f.dp,
            viewportWidth = 1278.0f,
            viewportHeight = 1278.0f,
        ).apply {
            group(scaleY = -1.0f, translationY = 1278.0f) {
                addPath(
                    pathData = listOf(
                        PathNode.MoveTo(900.5f, 120.5f),
                        PathNode.QuadTo(941.5f, 139.5f, 962.5f, 177.5f),
                        PathNode.QuadTo(973.5f, 197.5f, 978.0f, 226.0f),
                        PathNode.QuadTo(982.5f, 254.5f, 986.5f, 317.5f),
                        PathNode.LineTo(1029.5f, 911.5f),
                        PathNode.HorizontalTo(1100.5f),
                        PathNode.QuadTo(1110.5f, 911.5f, 1118.5f, 919.5f),
                        PathNode.QuadTo(1126.5f, 927.5f, 1126.5f, 939.5f),
                        PathNode.VerticalTo(968.5f),
                        PathNode.QuadTo(1126.5f, 980.5f, 1118.5f, 988.5f),
                        PathNode.QuadTo(1110.5f, 996.5f, 1100.5f, 996.5f),
                        PathNode.HorizontalTo(921.5f),
                        PathNode.QuadTo(897.5f, 996.5f, 887.5f, 1002.5f),
                        PathNode.QuadTo(877.5f, 1008.5f, 868.5f, 1028.5f),
                        PathNode.LineTo(846.5f, 1075.5f),
                        PathNode.LineTo(839.5f, 1089.5f),
                        PathNode.QuadTo(831.5f, 1107.5f, 824.5f, 1120.0f),
                        PathNode.QuadTo(817.5f, 1132.5f, 809.5f, 1140.5f),
                        PathNode.QuadTo(791.5f, 1158.5f, 770.5f, 1165.5f),
                        PathNode.QuadTo(758.5f, 1169.5f, 740.0f, 1170.5f),
                        PathNode.QuadTo(721.5f, 1171.5f, 695.5f, 1171.5f),
                        PathNode.HorizontalTo(582.5f),
                        PathNode.QuadTo(557.5f, 1171.5f, 539.0f, 1170.5f),
                        PathNode.QuadTo(520.5f, 1169.5f, 508.5f, 1165.5f),
                        PathNode.QuadTo(486.5f, 1158.5f, 468.5f, 1140.5f),
                        PathNode.QuadTo(460.5f, 1131.5f, 451.5f, 1115.0f),
                        PathNode.QuadTo(442.5f, 1098.5f, 431.5f, 1075.5f),
                        PathNode.LineTo(406.5f, 1023.5f),
                        PathNode.QuadTo(398.5f, 1005.5f, 390.0f, 1001.0f),
                        PathNode.QuadTo(381.5f, 996.5f, 355.5f, 996.5f),
                        PathNode.HorizontalTo(179.5f),
                        PathNode.QuadTo(169.5f, 996.5f, 160.5f, 989.0f),
                        PathNode.QuadTo(151.5f, 981.5f, 151.5f, 965.5f),
                        PathNode.VerticalTo(941.5f),
                        PathNode.QuadTo(151.5f, 926.5f, 160.5f, 919.0f),
                        PathNode.QuadTo(169.5f, 911.5f, 179.5f, 911.5f),
                        PathNode.HorizontalTo(239.5f),
                        PathNode.LineTo(280.5f, 318.5f),
                        PathNode.QuadTo(284.5f, 254.5f, 289.0f, 226.0f),
                        PathNode.QuadTo(293.5f, 197.5f, 304.5f, 177.5f),
                        PathNode.QuadTo(328.5f, 138.5f, 366.5f, 120.5f),
                        PathNode.QuadTo(386.5f, 110.5f, 414.5f, 108.5f),
                        PathNode.QuadTo(442.5f, 106.5f, 508.5f, 106.5f),
                        PathNode.HorizontalTo(759.5f),
                        PathNode.QuadTo(825.5f, 106.5f, 853.0f, 108.5f),
                        PathNode.QuadTo(880.5f, 110.5f, 900.5f, 120.5f),
                        PathNode.Close,
                        PathNode.MoveTo(410.5f, 200.5f),
                        PathNode.QuadTo(390.5f, 209.5f, 379.5f, 229.5f),
                        PathNode.QuadTo(374.5f, 238.5f, 372.0f, 256.5f),
                        PathNode.QuadTo(369.5f, 274.5f, 367.5f, 299.5f),
                        PathNode.LineTo(324.5f, 911.5f),
                        PathNode.HorizontalTo(944.5f),
                        PathNode.LineTo(901.5f, 299.5f),
                        PathNode.QuadTo(899.5f, 274.5f, 897.0f, 256.5f),
                        PathNode.QuadTo(894.5f, 238.5f, 889.5f, 229.5f),
                        PathNode.QuadTo(878.5f, 209.5f, 858.5f, 200.5f),
                        PathNode.QuadTo(848.5f, 195.5f, 834.5f, 194.5f),
                        PathNode.QuadTo(820.5f, 193.5f, 787.5f, 193.5f),
                        PathNode.HorizontalTo(481.5f),
                        PathNode.QuadTo(448.5f, 193.5f, 434.5f, 194.5f),
                        PathNode.QuadTo(420.5f, 195.5f, 410.5f, 200.5f),
                        PathNode.Close,
                        PathNode.MoveTo(585.5f, 357.5f),
                        PathNode.LineTo(569.5f, 773.5f),
                        PathNode.QuadTo(568.5f, 783.5f, 561.0f, 791.0f),
                        PathNode.QuadTo(553.5f, 798.5f, 542.5f, 798.5f),
                        PathNode.HorizontalTo(510.5f),
                        PathNode.QuadTo(499.5f, 798.5f, 491.5f, 790.5f),
                        PathNode.QuadTo(483.5f, 782.5f, 483.5f, 771.5f),
                        PathNode.LineTo(500.5f, 355.5f),
                        PathNode.QuadTo(501.5f, 344.5f, 509.0f, 337.0f),
                        PathNode.QuadTo(516.5f, 329.5f, 527.5f, 329.5f),
                        PathNode.HorizontalTo(559.5f),
                        PathNode.QuadTo(570.5f, 329.5f, 578.5f, 338.0f),
                        PathNode.QuadTo(586.5f, 346.5f, 585.5f, 357.5f),
                        PathNode.Close,
                        PathNode.MoveTo(768.5f, 355.5f),
                        PathNode.LineTo(785.5f, 771.5f),
                        PathNode.QuadTo(785.5f, 782.5f, 777.5f, 790.5f),
                        PathNode.QuadTo(769.5f, 798.5f, 758.5f, 798.5f),
                        PathNode.HorizontalTo(726.5f),
                        PathNode.QuadTo(715.5f, 798.5f, 708.0f, 791.0f),
                        PathNode.QuadTo(700.5f, 783.5f, 699.5f, 773.5f),
                        PathNode.LineTo(683.5f, 357.5f),
                        PathNode.QuadTo(682.5f, 346.5f, 690.5f, 338.0f),
                        PathNode.QuadTo(698.5f, 329.5f, 709.5f, 329.5f),
                        PathNode.HorizontalTo(741.5f),
                        PathNode.QuadTo(752.5f, 329.5f, 760.0f, 337.0f),
                        PathNode.QuadTo(767.5f, 344.5f, 768.5f, 355.5f),
                        PathNode.Close,
                        PathNode.MoveTo(493.5f, 1005.5f),
                        PathNode.LineTo(515.5f, 1050.5f),
                        PathNode.QuadTo(525.5f, 1072.5f, 538.0f, 1079.0f),
                        PathNode.QuadTo(550.5f, 1085.5f, 576.5f, 1085.5f),
                        PathNode.HorizontalTo(701.5f),
                        PathNode.QuadTo(728.5f, 1085.5f, 740.0f, 1079.0f),
                        PathNode.QuadTo(751.5f, 1072.5f, 761.5f, 1052.5f),
                        PathNode.LineTo(785.5f, 1005.5f),
                        PathNode.QuadTo(787.5f, 1002.5f, 786.0f, 999.5f),
                        PathNode.QuadTo(784.5f, 996.5f, 780.5f, 996.5f),
                        PathNode.HorizontalTo(498.5f),
                        PathNode.QuadTo(493.5f, 996.5f, 492.5f, 999.0f),
                        PathNode.QuadTo(491.5f, 1001.5f, 493.5f, 1005.5f),
                        PathNode.Close,
                    ),
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    pathFillType = PathFillType.NonZero,
                )
            }
        }.build()
        return _deleteRegular!!
    }

private var _deleteRegular: ImageVector? = null
