package com.reamicro.fix.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.TextView
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reamicro.fix.hook.EmbeddedHostUi
import com.reamicro.fix.hook.ModuleDialogTheme

/** 所有弹窗动作使用手动编辑预览按钮的实色、小圆角和最小高度。 */
@Composable
internal fun DialogActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled,
        shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = .38f)),
        content = content)
}

internal fun applyDialogActionStyle(view: TextView, context: Context = view.context) {
    val palette = ModuleDialogTheme.palette(context)
    val density = context.resources.displayMetrics.density
    EmbeddedHostUi.text(view, "LabelLarge")
    view.setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(palette.onPrimary, com.reamicro.fix.hook.colorWithAlpha(palette.title, 97))))
    view.minHeight = (48 * density).toInt()
    view.setPadding((12 * density).toInt(), (10 * density).toInt(),
        (12 * density).toInt(), (10 * density).toInt())
    val shape = GradientDrawable().apply {
        cornerRadius = 4 * density
        setColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(palette.primary, palette.border)))
    }
    view.background = RippleDrawable(ColorStateList.valueOf(
        com.reamicro.fix.hook.colorWithAlpha(palette.onPrimary, 40)), shape, null)
}
