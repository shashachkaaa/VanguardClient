package com.v2ray.ang.ui.compose

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/**
 * Линза «жидкого стекла».
 *
 * Размытие делает фон под стеклом мягким, но стеклом от этого он не выглядит: у
 * настоящей капли край работает линзой - фон рядом с границей смещается, а на самом
 * ободке расходится по цветам (красный уходит дальше синего). Ни того, ни другого
 * штатными эффектами не сделать, поэтому здесь свой шейдер на AGSL.
 *
 * AGSL появился в Android 13, так что ниже линзы нет и стекло остаётся прежним -
 * размытие плюс тонировка.
 *
 * Как устроен шейдер ниже:
 *
 *  - `sdRoundRect` даёт знаковое расстояние до границы формы: внутри отрицательное,
 *    снаружи положительное. Снаружи линзы нет, фон отдаётся как есть.
 *  - Нормаль к границе берётся как численный градиент этого расстояния - вдоль неё
 *    и смещается фон.
 *  - `uThickness` задаёт стенку капли: у самого края фон гнётся в полную силу, к
 *    середине сход на нет. Середина капли плоская, вся оптика живёт в стенке.
 *  - Красный и синий каналы смещаются чуть сильнее и чуть слабее зелёного - отсюда
 *    радужная кайма на ободке.
 *  - Координаты выборки зажимаются размером слоя: за его границей пусто, и без
 *    зажима по краю пошли бы дыры.
 *
 * Комментарии внутри самого шейдера английские: его текст разбирает лексер SkSL,
 * и проверять на живом устройстве, как он относится к кириллице, себе дороже.
 */
private const val LIQUID_GLASS_AGSL = """
uniform shader content;
uniform float2 uSize;
uniform float2 uCenter;
uniform float2 uHalf;
uniform float uRadius;
uniform float uThickness;
uniform float uRefraction;
uniform float uDispersion;
uniform float uHighlight;

// Signed distance to a rounded rectangle: negative inside, positive outside.
float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

half4 main(float2 coord) {
    float2 p = coord - uCenter;
    float d = sdRoundRect(p, uHalf, uRadius);

    // Outside the lens the backdrop passes through untouched.
    if (d > 0.0) {
        return content.eval(coord);
    }

    // Surface normal is the gradient of the distance field.
    float e = 1.0;
    float2 n = float2(
        sdRoundRect(p + float2(e, 0.0), uHalf, uRadius) - sdRoundRect(p - float2(e, 0.0), uHalf, uRadius),
        sdRoundRect(p + float2(0.0, e), uHalf, uRadius) - sdRoundRect(p - float2(0.0, e), uHalf, uRadius)
    );
    float nl = length(n);
    n = nl > 0.0001 ? n / nl : float2(0.0, 1.0);

    // Full bend at the rim, none in the middle.
    float t = clamp(1.0 + d / uThickness, 0.0, 1.0);
    float2 shift = n * (t * t * uRefraction);

    // Split the channels apart for the chromatic fringe.
    float2 lo = float2(0.5);
    float2 hi = uSize - float2(0.5);
    float2 uvR = clamp(coord + shift * (1.0 + uDispersion), lo, hi);
    float2 uvG = clamp(coord + shift, lo, hi);
    float2 uvB = clamp(coord + shift * (1.0 - uDispersion), lo, hi);

    half4 mid = content.eval(uvG);
    half4 c = half4(content.eval(uvR).r, mid.g, content.eval(uvB).b, mid.a);

    // Specular rim, brightest where the edge faces the light.
    float lit = 0.5 + 0.5 * dot(n, normalize(float2(-0.6, -1.0)));
    float glow = uHighlight * t * t * t * lit;

    return half4(c.rgb + half3(glow), c.a);
}
"""

/** Есть ли на этом устройстве шейдеры: линза работает с Android 13. */
val liquidGlassSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * Линза для слоя с фоном.
 *
 * Интерфейс нужен, чтобы наружу не торчали типы Android 13: сам [RuntimeShader]
 * живёт только внутри реализации, и вызывающему не приходится обкладывать каждое
 * обращение проверкой версии - её достаточно один раз, при создании.
 */
interface LiquidLens {
    /**
     * @param layerSize Размер слоя, в котором лежит фон.
     * @param center Центр линзы внутри слоя.
     * @param halfExtent Полуразмеры формы линзы.
     * @param radius Радиус скругления формы.
     * @param thickness Ширина стенки, на которой гнётся фон.
     * @param refraction Величина смещения фона у края, в пикселях.
     * @param dispersion Расхождение цветовых каналов, доля от смещения.
     * @param highlight Яркость блика по ободку.
     * @return Эффект или null, если собрать его не вышло.
     */
    fun effect(
        layerSize: Size,
        center: Offset,
        halfExtent: Size,
        radius: Float,
        thickness: Float,
        refraction: Float,
        dispersion: Float,
        highlight: Float
    ): RenderEffect?
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class ShaderLens(private val shader: RuntimeShader) : LiquidLens {
    override fun effect(
        layerSize: Size,
        center: Offset,
        halfExtent: Size,
        radius: Float,
        thickness: Float,
        refraction: Float,
        dispersion: Float,
        highlight: Float
    ): RenderEffect? = runCatching {
        shader.setFloatUniform("uSize", layerSize.width, layerSize.height)
        shader.setFloatUniform("uCenter", center.x, center.y)
        shader.setFloatUniform("uHalf", halfExtent.width, halfExtent.height)
        shader.setFloatUniform("uRadius", radius)
        shader.setFloatUniform("uThickness", thickness)
        shader.setFloatUniform("uRefraction", refraction)
        shader.setFloatUniform("uDispersion", dispersion)
        shader.setFloatUniform("uHighlight", highlight)
        android.graphics.RenderEffect
            .createRuntimeShaderEffect(shader, "content")
            .asComposeRenderEffect()
    }.getOrNull()
}

/**
 * Ошибку сборки шейдера глотаем намеренно: драйвер, который его не осилил, - не повод
 * ронять экран. Без линзы стекло просто останется прежним.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun createLens(): LiquidLens? =
    runCatching { ShaderLens(RuntimeShader(LIQUID_GLASS_AGSL)) }.getOrNull()

/** Линза для элемента или null, если устройство её не тянет. */
@Composable
fun rememberLiquidLens(): LiquidLens? = remember {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) createLens() else null
}
