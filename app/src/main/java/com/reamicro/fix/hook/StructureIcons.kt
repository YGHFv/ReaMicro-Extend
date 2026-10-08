package com.reamicro.fix.hook

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

internal object StructureIcons {
    private fun icon(name: String, data: String) = ImageVector.Builder(
        name, 24.dp, 24.dp, 24f, 24f,
    ).apply { addPath(PathParser().parsePathString(data).toNodes(), fill = SolidColor(Color.Black)) }.build()
    val ArrowBack = icon("ArrowBack", "M20,11H7.83l5.59,-5.59L12,4l-8,8l8,8l1.41,-1.41L7.83,13H20z")
    val Folder = icon("Folder", "M10,4H2v16h20V6H12z M4,8h16v10H4z")
    val FolderOpen = icon("FolderOpen", "M20,6h-8l-2,-2H2v16h18l4,-12h-4z M4,6h5.17l2,2H20l-2.67,8H4z")
    val Close = icon("Close", "M19,6.41L17.59,5L12,10.59L6.41,5L5,6.41L10.59,12L5,17.59L6.41,19L12,13.41L17.59,19L19,17.59L13.41,12z")
    val Edit = icon("Edit", "M3,17.25V21h3.75L17.81,9.94l-3.75,-3.75z M20.71,7.04a1,1 0,0 0,0,-1.41l-2.34,-2.34a1,1 0,0 0,-1.41,0l-1.83,1.83l3.75,3.75z")
    val MoreVert = icon("MoreVert", "M12,4a2,2 0,1 0,0,4a2,2 0,1 0,0,-4z M12,10a2,2 0,1 0,0,4a2,2 0,1 0,0,-4z M12,16a2,2 0,1 0,0,4a2,2 0,1 0,0,-4z")
    val Add = icon("Add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6z")
    val FileUpload = icon("FileUpload", "M5,20h14v-2H5z M11,16h2V8h4l-5,-5l-5,5h4z")
    val ChevronRight = icon("ChevronRight", "M9,6l6,6l-6,6l-1.41,-1.41L12.17,12L7.59,7.41z")
    val ExpandMore = icon("ExpandMore", "M7.41,8.59L12,13.17l4.59,-4.58L18,10l-6,6l-6,-6z")
    val ExpandLess = icon("ExpandLess", "M12,8l6,6l-1.41,1.41L12,10.83l-4.59,4.58L6,14z")
    val DriveFileRenameOutline = Edit
    val Image = icon("Image", "M3,3h18v18H3z M5,5v14h14V5z M6,17l4,-5l3,3l2,-2l3,4z")
    val Panorama = icon("Panorama", "M2,5h20v14H2z M4,7v10h16V7z M5,16l4,-5l4,4l3,-4l3,5z")
    val FindReplace = icon("FindReplace", "M10,3a7,7 0,1 0,4.9,12l5.7,5.7L22,19.3l-5.7,-5.7A7,7 0,0 0,10,3z M10,5a5,5 0,1 1,0,10a5,5 0,1 1,0,-10z")
    val DeleteOutline = icon("DeleteOutline", "M6,7h12v14H6z M8,9v10h8V9z M9,3h6l1,2h4v2H4V5h4z")
}
