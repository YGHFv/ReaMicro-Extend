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

val MiuixIcons.Settings: ImageVector
    get() = MiuixIcons.Regular.Settings

val MiuixIcons.Regular.Settings: ImageVector
    get() {
        if (_settingsRegular != null) return _settingsRegular!!
        _settingsRegular = ImageVector.Builder(
            name = "Settings.Regular",
            defaultWidth = 24.0f.dp,
            defaultHeight = 24.0f.dp,
            viewportWidth = 1239.6f,
            viewportHeight = 1239.6f,
        ).apply {
            group(scaleY = -1.0f, translationY = 1239.6f) {
                addPath(
                    pathData = listOf(
                        PathNode.MoveTo(643.8f, 108.3f),
                        PathNode.QuadTo(653.8f, 110.3f, 666.8f, 116.3f),
                        PathNode.QuadTo(679.8f, 122.3f, 694.8f, 131.3f),
                        PathNode.LineTo(712.8f, 141.3f),
                        PathNode.LineTo(993.8f, 299.3f),
                        PathNode.QuadTo(1023.8f, 316.3f, 1037.3f, 324.8f),
                        PathNode.QuadTo(1050.8f, 333.3f, 1059.8f, 343.3f),
                        PathNode.QuadTo(1077.8f, 362.3f, 1084.8f, 386.3f),
                        PathNode.QuadTo(1088.8f, 398.3f, 1089.8f, 418.3f),
                        PathNode.QuadTo(1090.8f, 438.3f, 1090.8f, 465.3f),
                        PathNode.VerticalTo(774.3f),
                        PathNode.QuadTo(1090.8f, 801.3f, 1089.8f, 820.8f),
                        PathNode.QuadTo(1088.8f, 840.3f, 1084.8f, 852.3f),
                        PathNode.QuadTo(1077.8f, 875.3f, 1059.8f, 896.3f),
                        PathNode.QuadTo(1051.8f, 905.3f, 1038.3f, 913.8f),
                        PathNode.QuadTo(1024.8f, 922.3f, 1005.8f, 932.3f),
                        PathNode.LineTo(993.8f, 939.3f),
                        PathNode.LineTo(712.8f, 1098.3f),
                        PathNode.QuadTo(690.8f, 1111.3f, 673.3f, 1119.8f),
                        PathNode.QuadTo(655.8f, 1128.3f, 643.8f, 1130.3f),
                        PathNode.QuadTo(619.8f, 1136.3f, 595.8f, 1130.3f),
                        PathNode.QuadTo(583.8f, 1128.3f, 566.3f, 1119.8f),
                        PathNode.QuadTo(548.8f, 1111.3f, 526.8f, 1098.3f),
                        PathNode.LineTo(245.8f, 939.3f),
                        PathNode.QuadTo(238.8f, 935.3f, 230.8f, 931.3f),
                        PathNode.QuadTo(213.8f, 921.3f, 200.3f, 912.8f),
                        PathNode.QuadTo(186.8f, 904.3f, 179.8f, 896.3f),
                        PathNode.QuadTo(161.8f, 875.3f, 154.8f, 852.3f),
                        PathNode.QuadTo(150.8f, 840.3f, 149.8f, 820.8f),
                        PathNode.QuadTo(148.8f, 801.3f, 148.8f, 774.3f),
                        PathNode.VerticalTo(465.3f),
                        PathNode.QuadTo(148.8f, 438.3f, 149.8f, 418.3f),
                        PathNode.QuadTo(150.8f, 398.3f, 154.8f, 386.3f),
                        PathNode.QuadTo(161.8f, 362.3f, 179.8f, 343.3f),
                        PathNode.QuadTo(187.8f, 334.3f, 201.8f, 325.3f),
                        PathNode.QuadTo(215.8f, 316.3f, 234.8f, 305.3f),
                        PathNode.LineTo(245.8f, 299.3f),
                        PathNode.LineTo(526.8f, 141.3f),
                        PathNode.LineTo(544.8f, 131.3f),
                        PathNode.QuadTo(559.8f, 122.3f, 572.8f, 116.3f),
                        PathNode.QuadTo(585.8f, 110.3f, 595.8f, 108.3f),
                        PathNode.QuadTo(619.8f, 103.3f, 643.8f, 108.3f),
                        PathNode.Close,
                        PathNode.MoveTo(234.8f, 438.3f),
                        PathNode.VerticalTo(807.3f),
                        PathNode.QuadTo(234.8f, 821.3f, 241.3f, 831.8f),
                        PathNode.QuadTo(247.8f, 842.3f, 262.8f, 851.3f),
                        PathNode.LineTo(587.8f, 1034.3f),
                        PathNode.QuadTo(606.8f, 1045.3f, 618.3f, 1045.8f),
                        PathNode.QuadTo(629.8f, 1046.3f, 646.8f, 1037.3f),
                        PathNode.LineTo(966.8f, 857.3f),
                        PathNode.QuadTo(991.8f, 843.3f, 998.8f, 831.3f),
                        PathNode.QuadTo(1005.8f, 819.3f, 1005.8f, 790.3f),
                        PathNode.VerticalTo(441.3f),
                        PathNode.QuadTo(1005.8f, 422.3f, 1000.8f, 410.8f),
                        PathNode.QuadTo(995.8f, 399.3f, 981.8f, 391.3f),
                        PathNode.LineTo(651.8f, 204.3f),
                        PathNode.QuadTo(632.8f, 193.3f, 620.3f, 193.3f),
                        PathNode.QuadTo(607.8f, 193.3f, 587.8f, 204.3f),
                        PathNode.LineTo(265.8f, 386.3f),
                        PathNode.QuadTo(247.8f, 396.3f, 241.3f, 406.8f),
                        PathNode.QuadTo(234.8f, 417.3f, 234.8f, 438.3f),
                        PathNode.Close,
                        PathNode.MoveTo(819.8f, 619.3f),
                        PathNode.QuadTo(819.8f, 674.3f, 792.8f, 719.8f),
                        PathNode.QuadTo(765.8f, 765.3f, 720.3f, 792.3f),
                        PathNode.QuadTo(674.8f, 819.3f, 619.8f, 819.3f),
                        PathNode.QuadTo(565.8f, 819.3f, 519.8f, 792.3f),
                        PathNode.QuadTo(473.8f, 765.3f, 446.8f, 719.8f),
                        PathNode.QuadTo(419.8f, 674.3f, 419.8f, 619.3f),
                        PathNode.QuadTo(419.8f, 565.3f, 446.8f, 519.3f),
                        PathNode.QuadTo(473.8f, 473.3f, 519.8f, 446.3f),
                        PathNode.QuadTo(565.8f, 419.3f, 619.8f, 419.3f),
                        PathNode.QuadTo(674.8f, 419.3f, 720.3f, 446.3f),
                        PathNode.QuadTo(765.8f, 473.3f, 792.8f, 519.3f),
                        PathNode.QuadTo(819.8f, 565.3f, 819.8f, 619.3f),
                        PathNode.Close,
                        PathNode.MoveTo(507.8f, 619.3f),
                        PathNode.QuadTo(507.8f, 666.3f, 540.8f, 698.8f),
                        PathNode.QuadTo(573.8f, 731.3f, 619.8f, 731.3f),
                        PathNode.QuadTo(666.8f, 731.3f, 699.3f, 698.8f),
                        PathNode.QuadTo(731.8f, 666.3f, 731.8f, 619.3f),
                        PathNode.QuadTo(731.8f, 573.3f, 699.3f, 540.3f),
                        PathNode.QuadTo(666.8f, 507.3f, 619.8f, 507.3f),
                        PathNode.QuadTo(573.8f, 507.3f, 540.8f, 540.3f),
                        PathNode.QuadTo(507.8f, 573.3f, 507.8f, 619.3f),
                        PathNode.Close,
                    ),
                    fill = SolidColor(Color.Black),
                    fillAlpha = 1f,
                    pathFillType = PathFillType.NonZero,
                )
            }
        }.build()
        return _settingsRegular!!
    }

private var _settingsRegular: ImageVector? = null
