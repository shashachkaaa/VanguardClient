package com.v2ray.ang.ui.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

/**
 * Размеры сняты с референса: трек 2.25:1, капля занимает по ширине две трети трека
 * и почти всю его высоту. Из-за этого ход у капли короткий - и это правильно,
 * основную работу в анимации делает не переезд, а раздувание.
 */
private val SwitchWidth = 54.dp
private val SwitchHeight = 24.dp
private const val ThumbAspect = 1.55f
private const val ThumbHeightRatio = 0.92f

/** Сколько длится переброс. Один на все фазы - они заданы долями от него. */
private const val FlipDurationMs = 900

/**
 * Переключатель со стеклянной каплей.
 *
 * Капля не переезжает от края к краю, как обычный ползунок. Она раздувается почти
 * во весь трек и делается полупрозрачной, держится так, пока трек под ней
 * перекрашивается, и уже потом схлопывается к другому концу, снова становясь
 * плотной белой. Смена состояния таким образом происходит *сквозь* стекло - в этом
 * весь фокус.
 *
 * Радужная кайма показывается только на раздутой капле. В покое её нет и быть не
 * может: капля стоит у конца трека, под ней ровный цвет, и разводить по каналам
 * нечего. Поэтому яркость каймы привязана к раздуванию.
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
    val isDark = LocalDarkTheme.current

    val lens = rememberLiquidLens()
    val trackLayer = rememberGraphicsLayer()
    val lensLayer = rememberGraphicsLayer()

    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(durationMillis = FlipDurationMs, easing = FastOutSlowInEasing),
        label = "switchFlip"
    )

    val trackOff = if (isDark) Color.White.copy(alpha = 0.26f) else Color.Black.copy(alpha = 0.20f)
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
        val thumbHeight = h * ThumbHeightRatio
        val thumbRadius = thumbHeight / 2f
        val inset = (h - thumbHeight) / 2f
        val room = w - inset * 2f

        // Раздувание: быстро вырастает, держится, потом схлопывается. Границы фаз
        // сняты с записи - на плато капля стоит примерно половину времени
        val swell = when {
            progress < 0.25f -> smoothstep(0f, 0.25f, progress)
            progress > 0.75f -> 1f - smoothstep(0.75f, 1f, progress)
            else -> 1f
        }

        val thumbWidth = lerp(thumbHeight * ThumbAspect, room, swell)

        // Ход считаем по уже раздутой капле. Когда она во весь трек, оба края
        // сходятся в центр - и капля сама встаёт посередине, без отдельного расчёта
        val from = inset + thumbWidth / 2f
        val to = w - inset - thumbWidth / 2f
        val cx = lerp(from, to.coerceAtLeast(from), progress)
        val cy = h / 2f

        // Цвет трека меняется на плато - под раздутой каплей, а не рядом с ней
        val track = lerp(trackOff, checkedTrackColor, smoothstep(0.3f, 0.7f, progress))
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
            refraction = thumbHeight * 0.3f,
            dispersion = 0.22f,
            highlight = 0.08f
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
        val bottom = cy + thumbHeight / 2f

        // Раздутая капля почти чистое стекло: сквозь неё и видно, как
        // перекрашивается трек. Белила тут глушили цвет, и вместо тёмного трека
        // под стеклом получалось светло-серое пятно
        val body = lerp(1f, 0.12f, swell) * disabledAlpha

        // Тень нужна только плотной капле - у раздутой ей неоткуда взяться
        val shadow = (1f - swell) * disabledAlpha
        if (shadow > 0.01f) {
            repeat(3) { step ->
                val grow = (step + 1) * 1.dp.toPx()
                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.05f * shadow),
                    topLeft = Offset(topLeft.x - grow, topLeft.y - grow * 0.4f),
                    size = Size(thumbSize.width + grow * 2f, thumbSize.height + grow * 2f),
                    cornerRadius = CornerRadius(thumbRadius + grow, thumbRadius + grow)
                )
            }
        }

        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White, Color(0xFFF0F1F3)),
                startY = topLeft.y,
                endY = bottom
            ),
            topLeft = topLeft,
            size = thumbSize,
            cornerRadius = corner,
            alpha = body
        )

        // Кайма живёт только на раздутой капле - там, где её край лежит поперёк
        // границ трека и в референсе действительно расходится в цвет
        if (swell > 0.01f) {
            // По записи кайма идёт сверху тёплой, снизу холодной, а по бокам её
            // нет. Радуга кольцом была отсебятиной, да ещё и с белым посередине -
            // от него по краям вылезали белые засветки
            drawRoundRect(
                brush = Brush.verticalGradient(
                    0f to Color(0xFFFFD08A),
                    0.4f to Color.Transparent,
                    0.6f to Color.Transparent,
                    1f to Color(0xFF86B8FF),
                    startY = topLeft.y,
                    endY = bottom
                ),
                topLeft = topLeft,
                size = thumbSize,
                cornerRadius = corner,
                style = Stroke(width = 1.2.dp.toPx()),
                alpha = 0.75f * swell * disabledAlpha
            )
        }

        // Блик по верхней кромке: он и делает каплю выпуклой
        drawRoundRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.White, Color.White.copy(alpha = 0f)),
                startY = topLeft.y,
                endY = cy
            ),
            topLeft = topLeft,
            size = thumbSize,
            cornerRadius = corner,
            style = Stroke(width = 1.dp.toPx()),
            alpha = 0.35f * disabledAlpha
        )
    }
}

/** Плавный переход от 0 к 1 между границами - без него фазы стыкуются рывком. */
private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
