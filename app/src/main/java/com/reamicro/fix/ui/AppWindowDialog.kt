package com.reamicro.fix.ui

import android.os.Build
import android.view.RoundedCorner
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.reamicro.fix.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun AppWindowDialog(
    show: Boolean, title: String, onClose: () -> Unit,
    primaryLabel: String? = null, onPrimary: (() -> Unit)? = null,
    primaryEnabled: Boolean = true, dangerous: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    WindowDialog(
        show = show, title = title, onDismissRequest = onClose,
        insideMargin = DpSize(DIALOG_INSIDE_DP.dp, DIALOG_INSIDE_DP.dp),

        defaultWindowInsetsPadding = true,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier.weight(1f, fill = false).heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                content = content,
            )
            AppDialogFooter(onClose, primaryLabel, onPrimary, primaryEnabled, dangerous)
        }
    }
}

@Composable
private fun AppDialogFooter(
    onClose: () -> Unit, primaryLabel: String?, onPrimary: (() -> Unit)?,
    primaryEnabled: Boolean, dangerous: Boolean,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val cornerPx = if (Build.VERSION.SDK_INT >= 31) maxOf(
        view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0,
        view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius ?: 0,
    ) else 0
    val safe = dialogFooterPadding(cornerPx / density.density)
    Row(
        Modifier.fillMaxWidth().padding(start = safe.horizontalExtra.dp, end = safe.horizontalExtra.dp,
            top = 12.dp, bottom = safe.bottomExtra.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Button(onClick = onClose, modifier = Modifier.weight(1f), minHeight = 48.dp) {
            Text(stringResource(if (onPrimary == null) R.string.action_close else R.string.action_cancel),
                textAlign = TextAlign.Center)
        }
        if (onPrimary != null && primaryLabel != null) {
            Button(onClick = onPrimary, modifier = Modifier.weight(1f), minHeight = 48.dp,
                enabled = primaryEnabled,
                colors = if (dangerous) ButtonDefaults.buttonColorsPrimary(
                    color = Color(0xFFC63838), contentColor = Color.White)
                else ButtonDefaults.buttonColorsPrimary()) {
                Text(primaryLabel, textAlign = TextAlign.Center)
            }
        }
    }
}
