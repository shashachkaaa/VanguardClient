package com.v2ray.ang.ui.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * Размеры сняты с референса: трек 2.25:1, капля занимает по ширине две трети трека
 * и почти всю его высоту. Из-за этого ход у капли короткий - и это правильно,
 * основную работу в анимации делает не переезд, а раздувание.
 */
private val SwitchWidth = 54.dp
private val SwitchHeight = 24.dp
private const val ThumbAspect = 1.55f
private const val ThumbHeightRatio = 0.92f

/**
 * Запас вокруг трека. Под нажатием капля вырастает за его край, и ей нужно место:
 * это же поле - холст для линзы, а гнуть фон она может только внутри своего слоя.
 */
private val Overflow = 7.dp

/**
 * Под пальцем капля расплющивается: вширь заметно сильнее, чем в высоту. Так ведёт
 * себя капля, на которую надавили, - и именно ширины ей не хватало.
 */
private const val PressWidthScale = 1.45f
private const val PressHeightScale = 1.1f

/** Сколько длится переброс. */
private const val FlipDurationMs = 900

/** Раздувание при перебросе: подъём, плато, спад. Длительности постоянные. */
private const val SwellRiseMs = 220
private const val SwellHoldMs = 440

/**
 * Переключатель со стеклянной каплей.
 *
 * Капля не переезжает от края к краю, как обычный ползунок. При перебросе она
 * раздувается почти во весь трек и делается полупрозрачной, держится так, пока трек
 * под ней перекрашивается, и уже потом схлопывается к другому концу, снова
 * становясь плотной белой. Смена состояния происходит *сквозь* стекло - в этом весь
 * приём.
 *
 * Под пальцем капля вырастает за край трека, а если палец повести - едет за ним и
 * растягивается тем сильнее, чем быстрее движение. Отпущенная, она доезжает до
 * ближайшего края и переключает состояние.
 *
 * Кайма показывается только на раздутой капле. В покое её нет и быть не может:
 * капля стоит у конца трека, под ней ровный цвет, и разводить по каналам нечего.
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
    val density = LocalDensity.current

    val lens = rememberLiquidLens()
    val trackLayer = rememberGraphicsLayer()
    val lensLayer = rememberGraphicsLayer()

    // Геометрия нужна и в композиции - жест переводит координату пальца в долю хода
    val overPx = with(density) { Overflow.toPx() }
    val trackW = with(density) { SwitchWidth.toPx() }
    val trackH = with(density) { SwitchHeight.toPx() }
    val thumbH = trackH * ThumbHeightRatio
    val inset = (trackH - thumbH) / 2f
    val baseThumbW = thumbH * ThumbAspect
    val fromX = overPx + inset + baseThumbW / 2f
    val toX = overPx + trackW - inset - baseThumbW / 2f

    val swellAnim = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }

    // Цель хранится в состоянии, а гонится за ней одна непрерывная анимация.
    // Раньше каждое событие пальца запускало свою корутину с animateTo, и каждая
    // отменяла предыдущую - от этой чехарды капля и дёргалась
    var dragTarget by remember { mutableStateOf<Float?>(null) }
    val target = dragTarget ?: if (checked) 1f else 0f
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = if (dragging) {
            spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)
        } else {
            tween(FlipDurationMs, easing = FastOutSlowInEasing)
        },
        label = "switchProgress"
    )

    // Отставание от цели заменяет скорость: у пружины оно ей прямо пропорционально,
    // а считать его можно без обращения к внутренностям анимации
    val lag = abs(target - progress)

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressAmount by animateFloatAsState(
        targetValue = if ((pressed || dragging) && enabled) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "switchPress"
    )

    // Раздувание идёт своей дорожкой с постоянными длительностями. Считать их от
    // оставшегося пути было ошибкой: после микро-перетаскивания путь почти нулевой,
    // и всё раздувание схлопывалось в пару кадров - это и выглядело рывком
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(checked) {
        if (!settled) {
            settled = true
            return@LaunchedEffect
        }
        if (dragging) return@LaunchedEffect
        swellAnim.animateTo(1f, tween(SwellRiseMs, easing = FastOutSlowInEasing))
        delay(SwellHoldMs.toLong())
        swellAnim.animateTo(0f, tween(SwellRiseMs, easing = FastOutSlowInEasing))
    }

    val trackOff = if (isDark) Color.White.copy(alpha = 0.26f) else Color.Black.copy(alpha = 0.20f)
    val disabledAlpha = if (enabled) 1f else 0.38f

    val input = if (onCheckedChange != null && enabled) {
        Modifier
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                enabled = true,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .pointerInput(fromX, toX) {
                var travelled = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        travelled = 0f
                        dragTarget = if (checked) 1f else 0f
                    },
                    onDragEnd = {
                        // Короткое движение - это промахнувшийся тап, а не
                        // перетаскивание: жест перехватил его у нажатия, значит и
                        // отработать за него должен он же
                        val far = abs(travelled) > (toX - fromX) * 0.15f
                        val result = if (far) (dragTarget ?: 0f) >= 0.5f else !checked
                        dragging = false
                        dragTarget = null
                        if (result != checked) onCheckedChange(result)
                    },
                    onDragCancel = {
                        dragging = false
                        dragTarget = null
                    }
                ) { change, amount ->
                    change.consume()
                    travelled += amount
                    // Смещением, а не прыжком к пальцу: от прыжка капля
                    // телепортировалась через весь трек на первом же движении
                    dragTarget = ((dragTarget ?: 0f) + amount / (toX - fromX))
                        .coerceIn(0f, 1f)
                }
            }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .size(SwitchWidth + Overflow * 2, SwitchHeight + Overflow * 2)
            .then(input)
    ) {
        // Растяжение на ходу: тем сильнее, чем дальше капля отстала от пальца
        val dragStretch = if (dragging) (lag * 3.5f).coerceAtMost(0.45f) else 0f
        val swell = maxOf(swellAnim.value, dragStretch)

        val room = trackW - inset * 2f
        // Под пальцем капля расплющивается: вширь заметно больше, чем в высоту -
        // так ведёт себя капля, которую придавили
        val thumbHeight = thumbH * lerp(1f, PressHeightScale, pressAmount)
        val thumbWidth = lerp(baseThumbW, room, swell) * lerp(1f, PressWidthScale, pressAmount)
        val thumbRadius = thumbHeight / 2f

        // Ход считаем по уже раздутой капле. Когда она во весь трек, оба края
        // сходятся в центр - и капля сама встаёт посередине, без отдельного расчёта
        val from = overPx + inset + thumbWidth / 2f
        val to = overPx + trackW - inset - thumbWidth / 2f
        val cx = lerp(from, to.coerceAtLeast(from), progress)
        val cy = size.height / 2f

        // Цвет трека меняется на плато - под раздутой каплей, а не рядом с ней
        val track = lerp(trackOff, checkedTrackColor, smoothstep(0.3f, 0.7f, progress))
            .let { it.copy(alpha = it.alpha * disabledAlpha) }

        val trackTopLeft = Offset(overPx, (size.height - trackH) / 2f)
        trackLayer.record {
            drawRoundRect(
                color = track,
                topLeft = trackTopLeft,
                size = Size(trackW, trackH),
                cornerRadius = CornerRadius(trackH / 2f, trackH / 2f)
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
        // перекрашивается трек
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
        // границ трека. Сверху тёплая, снизу холодная, по бокам её нет
        if (swell > 0.01f) {
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
