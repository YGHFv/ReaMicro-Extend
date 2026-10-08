package top.yukonga.scripta.editor.render

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import android.graphics.RenderEffect as AndroidRenderEffect

private var shaderCache: RuntimeShader? = null

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun obtainShader(): RuntimeShader =
    shaderCache ?: RuntimeShader(MAGNIFIER_GLASS_SHADER).also { shaderCache = it }

actual fun magnifierGlassRenderEffect(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    cornerRadius: Float,
    refractionHeight: Float,
    refractionAmount: Float,
    depthEffect: Float,
    chromaticAberration: Float,
): RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    if (width <= 0f || height <= 0f || refractionHeight <= 0f || refractionAmount <= 0f) return null
    val shader = obtainShader()
    shader.setFloatUniform("size", width, height)
    shader.setFloatUniform("offset", -left, -top)
    shader.setFloatUniform("cornerRadii", cornerRadius, cornerRadius, cornerRadius, cornerRadius)
    shader.setFloatUniform("refractionHeight", refractionHeight)
    shader.setFloatUniform("refractionAmount", -refractionAmount)
    shader.setFloatUniform("depthEffect", depthEffect)
    shader.setFloatUniform("chromaticAberration", chromaticAberration)
    return AndroidRenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
}

actual fun isMagnifierGlassSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
