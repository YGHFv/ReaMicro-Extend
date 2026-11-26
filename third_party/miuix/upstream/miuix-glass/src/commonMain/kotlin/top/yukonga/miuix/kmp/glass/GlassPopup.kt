// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package top.yukonga.miuix.kmp.glass

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.material.EdgeStroke
import top.yukonga.miuix.kmp.material.GlassDefaults
import top.yukonga.miuix.kmp.material.GlassEdgeStrokes
import top.yukonga.miuix.kmp.material.GlassMaterial
import top.yukonga.miuix.kmp.material.GlassMaterials
import top.yukonga.miuix.kmp.material.GlassShadow
import top.yukonga.miuix.kmp.material.GlassShadows
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.GlassStyle
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.MiuixIndication

/** Default values for [GlassPopup]. */
object GlassPopupDefaults {

    /** Minimum popup width. */
    val MinWidth: Dp = 200.dp

    /** Maximum popup width. */
    val MaxWidth: Dp = 288.dp

    /** Maximum popup height before content scrolls. */
    val MaxHeight: Dp = 416.dp

    /** Minimum margin from the window's safe area and the popup host edges. */
    val SafeMargin: Dp = 12.dp

    /** Vertical content padding. */
    val ContentPaddingVertical: Dp = 8.dp

    /** Corner radius of an anchored menu. */
    val CornerRadius: Dp = 24.dp

    /** Corner radius of a list dropdown. */
    val DropdownCornerRadius: Dp = 24.dp

    /** Minimum menu item height. */
    val ItemMinHeight: Dp = 44.dp

    /** Horizontal menu item padding. */
    val ItemPaddingHorizontal: Dp = 20.dp

    /** Vertical menu item padding. */
    val ItemPaddingVertical: Dp = 10.dp

    /** Leading icon size. */
    val ItemIconSize: Dp = 24.dp

    /** Spacing between an item label and adjacent icons. */
    val ItemIconGap: Dp = 8.dp

    /** Horizontal divider inset. */
    val DividerPaddingHorizontal: Dp = 20.dp

    /** Vertical divider padding. */
    val DividerPaddingVertical: Dp = 7.75.dp

    /** Divider thickness. */
    val DividerThickness: Dp = 0.5.dp

    /** Selected item checkmark size. */
    val ItemTickSize: Dp = 24.dp

    /** Submenu chevron size. */
    val ItemArrowSize: Dp = 16.dp

    /** Horizontal inset of the pressed item background. */
    val ItemPressInset: Dp = 7.dp

    /** Corner radius of the pressed item background. */
    val ItemPressRadius: Dp = 15.dp

    /** Pressed item background color. */
    @Composable
    @ReadOnlyComposable
    fun itemPressedColor(): Color = if (MiuixTheme.colorScheme.background.luminance() < 0.5f) {
        Color.White.copy(alpha = 0.1f)
    } else {
        Color.Black.copy(alpha = 0.1f)
    }

    /** Overlay color for a primary menu behind a secondary menu. */
    @Composable
    @ReadOnlyComposable
    fun maskColor(): Color = if (MiuixTheme.colorScheme.background.luminance() < 0.5f) {
        Color.Black.copy(alpha = 0.4f)
    } else {
        Color.White.copy(alpha = 0.4f)
    }

    /** Creates the popup surface appearance. */
    @Composable
    fun visuals(
        style: GlassStyle = GlassDefaults.Style,
        alpha: Float = 1f,
        stroke: EdgeStroke? = stroke(),
        shadow: GlassShadow? = GlassShadows.Regular,
        material: GlassMaterial? = material(),
        containerColor: Color = containerColor(),
    ): GlassPopupVisuals = remember(style, alpha, stroke, shadow, material, containerColor) {
        GlassPopupVisuals(style, alpha, stroke, shadow, material, containerColor)
    }

    /** Creates the popup shape. */
    @Composable
    fun shape(cornerRadius: Dp = CornerRadius): GlassShape = remember(cornerRadius) { GlassShape(cornerRadius) }

    /** Default popup material for the current theme. */
    @Composable
    fun material(): GlassMaterial = dynamicGlassMaterial(
        GlassMaterials.popupViewGlass(
            isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f,
        ),
        tint = MiuixTheme.colorScheme.surfaceContainer,
        alpha = 0.72f,
    )

    /** Default popup bloom stroke for the current theme. */
    @Composable
    @ReadOnlyComposable
    fun stroke(): EdgeStroke = GlassEdgeStrokes.forTheme(
        isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f,
        light = GlassEdgeStrokes.SmallLight,
        dark = GlassEdgeStrokes.SmallDark,
    )

    /** Opaque fallback color for the current theme. */
    @Composable
    @ReadOnlyComposable
    fun containerColor(): Color = when {
        MiuixTheme.isDynamicColor -> MiuixTheme.colorScheme.surfaceContainer
        MiuixTheme.colorScheme.background.luminance() < 0.5f -> Color(0xFF2C2C2C)
        else -> Color.White
    }
}

/**
 * A menu item with an optional icon, summary, selection checkmark and submenu chevron.
 *
 * @param text The label.
 * @param onClick Called when the row is tapped.
 * @param modifier The modifier applied to the row.
 * @param icon Optional icon before the label.
 * @param summary Optional second line under the label. Usually the row's current setting.
 * @param enabled Whether the row responds to a tap.
 * @param selected Whether to show a selection checkmark.
 * @param showArrow Whether to show a submenu chevron.
 * @param arrowRotation Chevron rotation in degrees. An open submenu uses
 *   [CascadingPopupDefaults.arrowRotation].
 * @param contentColor Colour of the label and the icon.
 * @param summaryColor Colour of the second line.
 * @param selectedContentColor Colour of a selected row's label and its tick.
 * @param pressedColor Pressed background color.
 */
@Composable
fun GlassPopupItem(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    showArrow: Boolean = false,
    arrowRotation: () -> Float = { 0f },
    contentColor: Color = MiuixTheme.colorScheme.onSurfaceContainer,
    summaryColor: Color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
    selectedContentColor: Color = MiuixTheme.colorScheme.primary,
    pressedColor: Color = GlassPopupDefaults.itemPressedColor(),
) {
    val interactionSource = remember { MutableInteractionSource() }
    val itemShape = remember { RoundedCornerShape(GlassPopupDefaults.ItemPressRadius) }
    val itemIndication = remember(pressedColor) {
        MiuixIndication(
            color = pressedColor.copy(alpha = 1f),
            pressAlpha = pressedColor.alpha,
        )
    }
    val color = when {
        !enabled -> contentColor.copy(alpha = 0.4f)
        selected -> selectedContentColor
        else -> contentColor
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = GlassPopupDefaults.ItemMinHeight)
            .padding(horizontal = GlassPopupDefaults.ItemPressInset)
            .clip(itemShape)
            .selectable(
                selected = selected,
                enabled = enabled,
                interactionSource = interactionSource,
                indication = itemIndication,
            ) { onClick() }
            .padding(
                horizontal = GlassPopupDefaults.ItemPaddingHorizontal - GlassPopupDefaults.ItemPressInset,
                vertical = GlassPopupDefaults.ItemPaddingVertical,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(end = GlassPopupDefaults.ItemIconGap)
                    .size(
                        width = maxOf(GlassPopupDefaults.ItemIconSize, icon.defaultWidth),
                        height = maxOf(GlassPopupDefaults.ItemIconSize, icon.defaultHeight),
                    ),
                tint = color,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                fontSize = MiuixTheme.textStyles.body1.fontSize,
                fontWeight = FontWeight.Medium,
                color = color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (summary != null) {
                Text(
                    text = summary,
                    style = MiuixTheme.textStyles.body2,
                    color = summaryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = MiuixIcons.Basic.Check,
            contentDescription = null,
            modifier = Modifier
                .padding(start = GlassPopupDefaults.ItemIconGap)
                .size(GlassPopupDefaults.ItemTickSize),
            tint = if (selected) selectedContentColor else Color.Transparent,
        )
        if (showArrow) {
            Icon(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = GlassPopupDefaults.ItemIconGap)
                    .size(GlassPopupDefaults.ItemArrowSize)
                    .graphicsLayer { rotationZ = arrowRotation() },
                tint = summaryColor,
            )
        }
    }
}
