package com.reamicro.fix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.PaddingValues
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.reamicro.fix.R
import top.yukonga.miuix.kmp.basic.NumberPicker
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Native miuix hour/minute wheels; persistence remains locale-independent HH:mm. */
@Composable
internal fun TaskTimePicker(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    minMinutes: Int = 0,
) {
    val minimum = minMinutes.coerceIn(0, 1439)
    val parts = value.split(':')
    val inputHour = (parts.getOrNull(0)?.toIntOrNull() ?: 0).coerceIn(0, 23)
    val inputMinute = (parts.getOrNull(1)?.toIntOrNull() ?: 0).coerceIn(0, 59)
    val selected = (inputHour * 60 + inputMinute).coerceAtLeast(minimum)
    val hour = selected / 60
    val minute = selected % 60
    fun update(h: Int, m: Int) {
        val total = (h * 60 + m).coerceIn(minimum, 1439)
        onValueChange((total / 60).toString().padStart(2, '0') + ":" + (total % 60).toString().padStart(2, '0'))
    }
    // A duration increase also updates the stored editor value, not just the visible wheel.
    LaunchedEffect(value, minimum) {
        val normalized = hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
        if (normalized != value) onValueChange(normalized)
    }
    val hourLabel = stringResource(R.string.time_picker_hour)
    val minuteLabel = stringResource(R.string.time_picker_minute)
    var expanded by remember { mutableStateOf(false) }
    BasicComponent(
        title = label,
        insideMargin = PaddingValues(vertical = 8.dp),
        endActions = {
            Text(hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0'),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(end = 8.dp))
            DropdownArrowEndAction(actionColor = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        },
        onClick = { expanded = !expanded },
    )
    if (expanded) {
        // miuix 0.9.4 NumberPicker resolves an unspecified weight to SemiBold.
        // Share the resolved style with ":" instead of letting plain Text fall back to Normal.
        val basePickerTextStyle = MiuixTheme.textStyles.main
        val pickerTextStyle = if (basePickerTextStyle.fontWeight == null) {
            basePickerTextStyle.copy(fontWeight = FontWeight.SemiBold)
        } else basePickerTextStyle
        val separatorWidth = 24.dp
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            // Labels have their own row so they cannot shift the separator above the selected values.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    hourLabel,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.width(separatorWidth))
                Text(
                    minuteLabel,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                NumberPicker(
                    value = hour,
                    onValueChange = { update(it, minute) },
                    range = (minimum / 60)..23,
                    label = { it.toString().padStart(2, '0') },
                    visibleItemCount = 3,
                    itemHeight = 32.dp,
                    textStyle = pickerTextStyle,
                    modifier = Modifier.weight(1f).semantics { contentDescription = hourLabel },
                )
                Text(
                    text = ":",
                    modifier = Modifier.width(separatorWidth),
                    textAlign = TextAlign.Center,
                    style = pickerTextStyle,
                )
                NumberPicker(
                    value = minute,
                    onValueChange = { update(hour, it) },
                    range = (if (hour == minimum / 60) minimum % 60 else 0)..59,
                    label = { it.toString().padStart(2, '0') },
                    visibleItemCount = 3,
                    itemHeight = 32.dp,
                    textStyle = pickerTextStyle,
                    modifier = Modifier.weight(1f).semantics { contentDescription = minuteLabel },
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}
