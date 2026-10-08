package com.reamicro.fix.hook

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

internal object StructureHome130Icons {
    private fun icon(name: String, path: String, mirrored: Boolean = false) = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f, autoMirror = mirrored,
    ).apply {
        addPath(PathParser().parsePathString(path).toNodes(),
            fill = SolidColor(Color(0xFF231F20)))
    }.build()

    val Back = icon("AutoMirrored.Filled.ArrowBack",
        "M20,11 H7.83 l5.59,-5.59 L12,4 l-8,8 8,8 1.41,-1.41 L7.83,13 H20 Z", mirrored = true)

    val Import = icon("Plus",
        "M19,11 H13 V5 a1,1 0 0,0 -2,0 v6 H5 a1,1 0 0,0 0,2 h6 v6 a1,1 0 0,0 2,0 V13 h6 a1,1 0 0,0 0,-2 Z")

    val Expanded = icon("ArrowIosDownward",
        "M12,16 a1,1 0 0,1 -0.64,-0.23 l-6,-5 A1,1 0 1,1 6.64,9.23 L12,13.71 l5.36,-4.32 a1,1 0 0,1 1.41,0.15 A1,1 0 0,1 18.63,11 l-6,4.83 A1,1 0 0,1 12,16 Z")

    val Collapsed = icon("ArrowIosForward",
        "M10,19 a1,1 0 0,1 -0.64,-0.23 a1,1 0 0,1 -0.13,-1.41 L13.71,12 L9.39,6.63 a1,1 0 0,1 0.15,-1.41 A1,1 0 0,1 11,5.37 l4.83,6 a1,1 0 0,1 0,1.27 l-5,6 A1,1 0 0,1 10,19 Z")

    val Close = icon("Close",
        "M13.41,12 l4.3,-4.29 a1,1 0 1,0 -1.42,-1.42 L12,10.59 L7.71,6.29 A1,1 0 0,0 6.29,7.71 L10.59,12 l-4.3,4.29 a1,1 0 0,0 0,1.42 a1,1 0 0,0 1.42,0 L12,13.41 l4.29,4.3 a1,1 0 0,0 1.42,0 a1,1 0 0,0 0,-1.42 Z")

    val SearchNavigationClose = ImageVector.Builder(
        name = "SearchNavigation.Close", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        addPath(PathParser().parsePathString(
            "M6,5 L18,19 M18,5 L6,19"
        ).toNodes(), fill = null, stroke = SolidColor(Color(0xFF231F20)),
            strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round)
    }.build()
    val Edit2 = icon("Edit2",
        "M19,20 H5 a1,1 0 0,0 0,2 H19 a1,1 0 0,0 0,-2 Z " +
        "M5,18 h0.09 l4.17,-0.38 a2,2 0 0,0 1.21,-0.57 l9,-9 a1.92,1.92 0 0,0 -0.07,-2.71 h0 L16.66,2.6 A2,2 0 0,0 14,2.53 l-9,9 a2,2 0 0,0 -0.57,1.21 L4,16.91 a1,1 0 0,0 0.29,0.8 A1,1 0 0,0 5,18 Z " +
        "M15.27,4 L18,6.73 L16,8.68 L13.32,6 Z M6.37,12.91 L12,7.32 l2.7,2.7 l-5.6,5.6 l-3,0.28 Z")
    val ImageOutline = icon("Outlined.Image",
        "M19,5 v14 L5,19 L5,5 h14 m0,-2 L5,3 c-1.1,0 -2,0.9 -2,2 v14 c0,1.1 0.9,2 2,2 h14 c1.1,0 2,-0.9 2,-2 L21,5 c0,-1.1 -0.9,-2 -2,-2 Z M14.14,11.86 l-3,3.87 L9,13.14 L6,17 h12 l-3.86,-5.14 Z")

    val DeleteOutline = icon("Outlined.DeleteOutline",
        "M6,19 c0,1.1 0.9,2 2,2 h8 c1.1,0 2,-0.9 2,-2 L18,7 L6,7 v12 Z M8,9 h8 v10 L8,19 L8,9 Z M15.5,4 l-1,-1 h-5 l-1,1 L5,4 v2 h14 L19,4 h-3.5 Z")
    val DeleteForever = icon("Filled.DeleteForever",
        "M6,19 c0,1.1 0.9,2 2,2 h8 c1.1,0 2,-0.9 2,-2 L18,7 L6,7 v12 Z M8.46,11.88 l1.41,-1.41 L12,12.59 l2.12,-2.12 1.41,1.41 L13.41,14 l2.12,2.12 -1.41,1.41 L12,15.41 l-2.12,2.12 -1.41,-1.41 L10.59,14 l-2.13,-2.12 Z M15.5,4 l-1,-1 h-5 l-1,1 L5,4 v2 h14 L19,4 Z")

    val Copy = icon("Outlined.ContentCopy",
        "M16,1 H4 C2.9,1 2,1.9 2,3 v14 h2 V3 h12 V1 Z M19,5 H8 C6.9,5 6,5.9 6,7 v14 c0,1.1 0.9,2 2,2 h11 c1.1,0 2,-0.9 2,-2 V7 c0,-1.1 -0.9,-2 -2,-2 Z M19,21 H8 V7 h11 v14 Z")
    val NavigateNext = icon("AutoMirrored.Filled.NavigateNext",
        "M10,6 L8.59,7.41 L13.17,12 L8.59,16.59 L10,18 L16,12 Z", mirrored = true)
}
