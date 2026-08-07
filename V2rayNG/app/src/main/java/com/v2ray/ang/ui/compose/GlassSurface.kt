package com.v2ray.ang.ui.compose

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Радиус размытия фона под стеклом по умолчанию. */
val GlassBlurRadius = 30.dp

/** Форма выпадающих меню. */
val GlassMenuShape = RoundedCornerShape(20.dp)

/** Форма диалогов. */
val GlassDialogShape = RoundedCornerShape(28.dp)

/**
 * Слой с содержимым экрана, который тема отдаёт всем всплывающим окнам.
 *
 * Диалоги, меню и снекбар живут в отдельных окнах и потому не попадают в запись слоя -
 * им размытие доступно. Элементам внутри самого экрана слой отсюда брать нельзя.
 */
val LocalGlassBackdrop = compositionLocalOf<GlassBackdrop?> { null }

/**
 * Снимок экрана, который стекло размывает у себя под низом.
 *
 * Слой пишет тот, кто рисует содержимое ([glassBackdropSource]), а вместе со слоем
 * запоминается и его положение на экране: стекло может жить в другом окне (выпадающее
 * меню, шторка), поэтому координаты нужны общие - экранные, а не оконные.
 */
@Stable
class GlassBackdrop internal constructor(
    val layer: GraphicsLayer,
    /**
     * Тот же снимок, но уже размытый. Размываем один раз на кадр для всего экрана,
     * а не в каждом стекле по кусочку: кусочек приходилось растягивать по краям,
     * и на границах оставались смазанные хвосты.
     */
    val blurred: GraphicsLayer
) {
    /** Левый верхний угол записанного содержимого в координатах экрана. */
    var origin by mutableStateOf(Offset.Zero)
        internal set
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    return remember(layer, blurred) { GlassBackdrop(layer, blurred) }
}

/**
 * Пишет содержимое в [backdrop] и тут же рисует его на экране. Вешается на корень экрана,
 * чтобы стеклянные поверхности могли размыть именно то, что под ними.
 */
@Composable
fun Modifier.glassBackdropSource(
    backdrop: GlassBackdrop,
    blurRadius: Dp = GlassBlurRadius
): Modifier {
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    return this
        .onGloballyPositioned { backdrop.origin = it.positionOnScreen() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }

            if (canBlur) {
                val radius = blurRadius.toPx()
                runCatching {
                    backdrop.blurred.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                    backdrop.blurred.record { drawLayer(backdrop.layer) }
                }
            }

            drawLayer(backdrop.layer)
        }
}

/**
 * Фон «жидкого стекла»: размытая копия того, что под элементом, полупрозрачная тонировка
 * из цветов темы, блик сверху и тонкая светлая грань по контуру.
 *
 * [backdrop] можно передавать только тем элементам, которые сами не попадают в запись слоя:
 * рисовать слой внутри его же записи запрещено. Всё, что лежит внутри экрана-источника
 * (кнопки на карточках и т.п.), стекло получает без физического размытия - с [fallbackColor].
 *
 * @param shape Форма поверхности.
 * @param backdrop Слой с содержимым экрана или null, если размытие невозможно.
 * @param blurRadius Радиус размытия фона.
 * @param opaqueness Плотность тонировки: 1 - как у нижней капсулы, больше - матовее.
 * @param fallbackColor Подложка, когда размытия нет (Android 11 и ниже либо backdrop == null).
 */
@Composable
fun Modifier.glassBackground(
    shape: Shape,
    backdrop: GlassBackdrop? = null,
    blurRadius: Dp = GlassBlurRadius,
    opaqueness: Float = 1f,
    fallbackColor: Color? = null
): Modifier {
    val scheme = MaterialTheme.colorScheme
    val isDark = LocalDarkTheme.current

    val canBlur = backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    // Экранные координаты нужны, чтобы вырезать из слоя ровно тот кусок фона, который под нами.
    // Именно экранные: стекло часто живёт в своём окне, и оконные координаты у него свои
    var position by remember { mutableStateOf(Offset.Zero) }

    // Без размытия стекло должно быть плотнее, иначе сквозь него читается текст
    val solid = fallbackColor ?: scheme.surface.copy(alpha = if (isDark) 0.82f else 0.86f)
    val tint = glassTint(isDark, scheme.surface, opaqueness)
    val edge = glassEdge(isDark)

    return this
        .clip(shape)
        .onGloballyPositioned { position = it.positionOnScreen() }
        .drawBehind {
            val source = backdrop
            var blurred = false
            if (canBlur && source != null) {
                // Сдвигаем готовый размытый снимок так, чтобы под нами оказался
                // ровно тот участок экрана, над которым мы висим
                runCatching {
                    translate(
                        left = source.origin.x - position.x,
                        top = source.origin.y - position.y
                    ) {
                        drawLayer(source.blurred)
                    }
                }.onSuccess { blurred = true }
            }
            if (!blurred) drawRect(solid)
            drawRect(tint)
        }
        .border(width = 1.dp, brush = edge, shape = shape)
}

/**
 * Стекло для окон поверх экрана - диалогов, меню, шторок, снекбара. Слой берётся из темы,
 * так что ставить его вручную не нужно: достаточно сделать контейнер прозрачным.
 */
@Composable
fun Modifier.glassPanel(
    shape: Shape,
    blurRadius: Dp = GlassBlurRadius,
    opaqueness: Float = 0.7f,
    fallbackColor: Color? = null
): Modifier {
    // Плотность подложки нужна только там, где размытия нет: сквозь прозрачное
    // стекло без него читался бы текст под окном
    val dense = fallbackColor ?: MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f)
    return glassBackground(
        shape = shape,
        backdrop = LocalGlassBackdrop.current,
        blurRadius = blurRadius,
        opaqueness = opaqueness,
        fallbackColor = dense
    )
}

/**
 * Стеклянная поверхность с содержимым. Параметры - как у [glassBackground].
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape,
    backdrop: GlassBackdrop? = null,
    blurRadius: Dp = GlassBlurRadius,
    opaqueness: Float = 1f,
    fallbackColor: Color? = null,
    content: @Composable BoxScope.() -> Unit = {}
) {
    Box(
        modifier = modifier.glassBackground(
            shape = shape,
            backdrop = backdrop,
            blurRadius = blurRadius,
            opaqueness = opaqueness,
            fallbackColor = fallbackColor
        ),
        content = content
    )
}

/** Тонировка стекла: сверху светлее, снизу уходит в цвет поверхности. */
fun glassTint(isDark: Boolean, surface: Color, opaqueness: Float = 1f): Brush {
    // На тёмной теме плёнка светлая: фон здесь чёрный, и размытая чернота остаётся
    // чернотой - стекло читается только за счёт того, что оно светлее подложки
    val top = if (isDark) 0.10f else 0.22f
    val bottom = if (isDark) 0.03f else 0.10f
    val bottomColor = if (isDark) Color.White else surface
    return Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = (top * opaqueness).coerceIn(0f, 1f)),
            bottomColor.copy(alpha = (bottom * opaqueness).coerceIn(0f, 1f))
        )
    )
}

/** Светлая грань по контуру, гаснущая книзу. */
fun glassEdge(isDark: Boolean): Brush = Brush.verticalGradient(
    listOf(
        Color.White.copy(alpha = if (isDark) 0.22f else 0.7f),
        Color.White.copy(alpha = 0.04f)
    )
)

/**
 * Положение элемента на экране. Внутри окна Compose знает только оконные координаты,
 * а стекло и его фон могут оказаться в разных окнах, поэтому приводим к экранным.
 */

