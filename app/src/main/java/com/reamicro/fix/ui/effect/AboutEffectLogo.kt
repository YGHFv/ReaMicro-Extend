// See assets/licenses/KernelSU-effect-LICENSE.txt. Artwork belongs to ReaMicro.
package com.reamicro.fix.ui.effect

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.reamicro.fix.R
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun aboutBrandTexture(): Modifier {
    val backdrop = LocalAboutBackdrop.current ?: return Modifier
    val dark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val colors = remember(dark) {
        BlurColors(
            blendColors = if (dark) {
                listOf(
                    BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
                    BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                    BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
                )
            } else {
                listOf(
                    BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
                    BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
                    BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
                )
            },
        )
    }
    return Modifier.textureBlur(
        backdrop = backdrop,
        shape = RoundedCornerShape(0.dp),
        blurRadius = 150f,
        colors = colors,
        contentBlendMode = BlendMode.DstIn,
        enabled = true,
    )
}

@Composable
internal fun AboutEffectLogo(
    @DrawableRes foreground: Int,
    @ColorRes background: Int,
    modifier: Modifier = Modifier,
) {
    if (LocalAboutBackdrop.current == null) {

        Box(
            modifier = modifier.size(100.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(colorResource(background)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = 1.5f
                    scaleY = 1.5f
                },
            )
        }
    } else {
        Box(
            modifier = modifier.size(100.dp).clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            Image(

                painter = painterResource(R.drawable.ic_reamicrofix_logo),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onBackground),

                modifier = Modifier.requiredSize(191.771.dp).then(aboutBrandTexture()),
            )
        }
    }
}
