package com.reamicro.fix.hook

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.reamicro.fix.R

@Composable
internal fun StructureOpenedFilesBadge(count: Int, fonts: StructureHome130Style.Fonts, foreground: Color, background: Color) {
    Box(Modifier.semantics(mergeDescendants = true) { contentDescription = "已打开 $count 个文件" }) {
        Icon(painterResource(R.drawable.ic_structure_opened_square), null,
            Modifier.align(Alignment.Center).size(24.dp), tint = foreground)
        Text(count.toString(), Modifier.align(Alignment.BottomEnd).background(background).padding(start = 1.dp),
            style = StructureHome130Style.openedCount(fonts), color = foreground, maxLines = 1)
    }
}
