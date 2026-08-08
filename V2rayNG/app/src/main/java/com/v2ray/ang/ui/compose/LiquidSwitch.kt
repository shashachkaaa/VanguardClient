package com.v2ray.ang.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.abs

/** Размеры переключателя. Материаловский - 52x32, наш чуть компактнее. */
private val SwitchWidth = 50.dp
private val SwitchHeight = 28.dp

/** Капля в покое не круглая, а слегка вытянутая - так она и выглядит в референсе. */
private const val ThumbAspect = 1.18f

/**
 * Переключатель со стеклянной каплей вместо ползунка.
 *
 * Капля прозрачная: сквозь неё виден трек, а у её края трек преломляется и по ободку
 * расходится в радугу - этим она и читается как стекло, а не как белый кружок. Всё
 * рисуется в один проход: трек пишется в слой, слой прогоняется через линзу, сверху
 * ложатся плёнка и блик.
 *
 * На Android 12 и ниже шейдера нет, и капля рисуется сплошной - как обычный ползунок.
 *
 * @param checked Включён ли переключатель.
 * @param onCheckedChange Обработчик нажатия или null, если нажатие обрабатывает строка.
 * @param enabled Доступен ли переключатель.
 * @param checkedTrackColor Цвет трека во включённом состоянии.
 */
@Composable
fun LiquidSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checkedTrackColor: Color = MaterialTheme.colorScheme.secondary
) {
    val scheme = MaterialTheme.colorScheme
    val isDark = LocalDarkTheme.current

    val lens = rememberLiquidLens()
    val trackLayer = rememberGraphicsLayer()
    val lensLayer = rememberGraphicsLayer()

    val progress = remember { Animatable(if (checked) 1f else 0f) }

    LaunchedEffect(checked) {
        progress.animateTo(
            targetValue = if (checked) 1f else 0f,
            animationSpec = spring(
                dampingRatio = 0.62f,
                stiffness = Spring.StiffnessMediumLow
            )
        )
    }

    val trackOff = if (isDark) Color.White.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.12f)
    val disabledAlpha = if (enabled) 1f else 0.38f

    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange
        )
    } else {
        Modifier
    }

    Canvas(modifier = modifier.size(SwitchWidth, SwitchHeight).then(toggle)) {
        val w = size.width
        val h = size.height
        val trackRadius = h / 2f
        val inset = h * 0.11f
        val thumbHeight = h - inset * 2f
        val thumbRadius = thumbHeight / 2f

        // Капля тянется тем сильнее, чем быстрее едет, и сама собирается уже на
        // месте: отдельной анимации растяжения не нужно - скорость о движении
        // знает всё. Шире трека она не станет
        val speed = abs(progress.velocity)
        val room = w - inset * 2f
        val thumbWidth = (thumbHeight * (ThumbAspect + (speed * 0.16f).coerceAtMost(1f)))
            .coerceAtMost(room)

        // Ход считаем уже по растянутой капле, иначе на пике растяжения она
        // вылезала бы за трек
        val from = inset + thumbWidth / 2f
        val to = w - inset - thumbWidth / 2f
        val cx = lerp(from, to.coerceAtLeast(from), progress.value)
        val cy = h / 2f

        val track = lerp(trackOff, checkedTrackColor, progress.value)
            .let { it.copy(alpha = it.alpha * disabledAlpha) }

        trackLayer.record {
            drawRoundRect(
                color = track,
                cornerRadius = CornerRadius(trackRadius, trackRadius)
            )
        }

        val effect = lens?.effect(
            layerSize = size,
            center = Offset(cx, cy),
            halfExtent = Size(thumbWidth / 2f, thumbHeight / 2f),
            radius = thumbRadius,
            thickness = thumbHeight * 0.5f,
            refraction = thumbHeight * 0.34f,
            dispersion = 0.3f,
            highlight = 0.26f
        )

        if (effect != null) {
            lensLayer.renderEffect = effect
            lensLayer.record { drawLayer(trackLayer) }
            drawLayer(lensLayer)
        } else {
            drawLayer(trackLayer)
        }

        val topLeft = Offset(cx - thumbWidth / 2f, cy - thumbHeight / 2f)
        val thumbSize = Size(thumbWidth, thumbHeight)
        val corner = CornerRadius(thumbRadius, thumbRadius)
        val top = cy - thumbHeight / 2f
        val bottom = cy + thumbHeight / 2f

        if (effect == null) {
            // Без линзы прозрачная капля была бы неотличима от трека
            drawRoundRect(
                color = scheme.surface.copy(alpha = disabledAlpha),
                topLeft = topLeft,
                size = thumbSize,
                cornerRadius = corner
            )
        } else {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.26f * disabledAlpha),
                        Color.White.copy(alpha = 0.06f * disabledAlpha)
                    ),
                    startY = top,
                    endY = bottom
                ),
                topLeft = topLeft,
                size = thumbSize,
                cornerRadius = corner
            )
        }

        // Грань капли: сверху ловит свет, снизу почти гаснет
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.75f * disabledAlpha),
                    Color.White.copy(alpha = 0.12f * disabledAlpha)
                ),
                startY = top,
                endY = bottom
            ),
            topLeft = topLeft,
            size = thumbSize,
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx())
        )

        // Трек тоже стеклянный: тонкая тёмная грань отделяет его от карточки
        drawRoundRect(
            color = Color.Black.copy(alpha = if (isDark) 0.22f else 0.08f),
            cornerRadius = CornerRadius(trackRadius, trackRadius),
            style = Stroke(width = 1.dp.toPx())
        )
    }
}
