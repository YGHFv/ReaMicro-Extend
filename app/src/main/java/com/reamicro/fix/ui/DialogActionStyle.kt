package com.reamicro.fix.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.widget.TextView
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reamicro.fix.hook.EmbeddedHostUi
import com.reamicro.fix.hook.ModuleDialogTheme

/** 阅微内的模块弹窗动作使用手动编辑预览按钮的实色、小圆角和最小高度。 */
@Composable
internal fun DialogActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled,
        cornerRadius = 4.dp, minHeight = 48.dp,
        colors = ButtonDefaults.buttonColors(
            color = ModuleTheme.colorScheme.primary,
            contentColor = ModuleTheme.colorScheme.onPrimary,
            disabledColor = ModuleTheme.colorScheme.surfaceContainerHighest,
            disabledContentColor = ModuleTheme.colorScheme.onBackground.copy(alpha = .38f))) {
        ProvideModuleButtonText { content() }
    }
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
