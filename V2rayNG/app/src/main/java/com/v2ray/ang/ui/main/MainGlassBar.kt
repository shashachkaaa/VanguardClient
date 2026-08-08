package com.v2ray.ang.ui.main

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.Animatable
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.GlassBackdrop
import com.v2ray.ang.ui.compose.GlassSurface
import com.v2ray.ang.ui.compose.LocalDarkTheme
import com.v2ray.ang.ui.compose.rememberLiquidLens

/** Пункты нижней капсулы. */
enum class GlassBarItem { HOME, SETTINGS, ADD }

/** Форма стеклянных таблеток: и капсулы снизу, и кнопок на карточках. */
val GlassCapsuleShape = RoundedCornerShape(50)

private val ItemSize = 68.dp
private val BarHeight = 72.dp
private val BarPadding = 14.dp

/**
 * Нижняя капсула в духе жидкого стекла: под ней размывается то, что нарисовано на экране,
 * сверху ложится полупрозрачный слой темы, блик и тонкая светлая грань.
 *
 * Активный пункт отмечает стеклянная капля. Она не просто подсвечивает - она
 * преломляет то, что за панелью: под ней есть настоящее содержимое экрана, и в
 * отличие от ползунка переключателя гнуть ей есть что. Каплю можно таскать
 * пальцем в любое место панели; отпущенная, она сама доезжает до ближайшего
 * пункта и выбирает его.
 *
 * @param backdrop Слой с содержимым экрана, записанный тем, кто рисует контент.
 * @param selected Активный пункт.
 * @param onSelect Нажатие по пункту.
 */
@Composable
fun LiquidGlassBar(
    backdrop: GlassBackdrop,
    selected: GlassBarItem,
    onSelect: (GlassBarItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val isDark = LocalDarkTheme.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val items = listOf(GlassBarItem.HOME, GlassBarItem.SETTINGS, GlassBarItem.ADD)
    val selectedIndex = items.indexOf(selected).coerceAtLeast(0)

    val itemPx = with(density) { ItemSize.toPx() }
    val padPx = with(density) { BarPadding.toPx() }
    val centerOf = { index: Int -> padPx + itemPx * index + itemPx / 2f }

    val lens = rememberLiquidLens()
    val dropLayer = rememberGraphicsLayer()

    val dropX = remember { Animatable(centerOf(selectedIndex)) }
    var dragging by remember { mutableStateOf(false) }

    // Экранные координаты капли: слой с фоном общий на весь экран, и вырезать из
    // него нужный участок можно только по общим координатам
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(selectedIndex, dragging) {
        if (!dragging) {
            dropX.animateTo(
                targetValue = centerOf(selectedIndex),
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
    }

    val minX = centerOf(0)
    val maxX = centerOf(items.lastIndex)

    GlassSurface(
        modifier = modifier
            .height(BarHeight)
            .width(ItemSize * items.size + BarPadding * 2)
            .pointerInput(itemPx, padPx, items.size) {
                detectHorizontalDragGestures(
                    onDragStart = { start ->
                        dragging = true
                        scope.launch { dropX.snapTo(start.x.coerceIn(minX, maxX)) }
                    },
                    onDragEnd = {
                        // Ближайший пункт выбираем, а доехать до него капле
                        // поручает эффект выше - иначе он и этот вызов тянули бы
                        // её каждый в свою сторону
                        val index = ((dropX.value - padPx - itemPx / 2f) / itemPx)
                            .roundToInt()
                            .coerceIn(0, items.lastIndex)
                        dragging = false
                        onSelect(items[index])
                    },
                    onDragCancel = { dragging = false }
                ) { change, _ ->
                    change.consume()
                    // Не snapTo, а жёсткая пружина: капля чуть отстаёт от пальца,
                    // и от этого тянется - а заодно у неё появляется скорость,
                    // по которой считается растяжение
                    scope.launch {
                        dropX.animateTo(
                            targetValue = change.position.x.coerceIn(minX, maxX),
                            animationSpec = spring(
                                dampingRatio = 1f,
                                stiffness = Spring.StiffnessHigh
                            )
                        )
                    }
                }
            },
        shape = GlassCapsuleShape,
        backdrop = backdrop,
        fallbackColor = scheme.surfaceContainerHigh.copy(alpha = 0.96f)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { canvasOrigin = it.positionOnScreen() }
        ) {
            val cx = dropX.value
            val cy = size.height / 2f
            val dropHeight = itemPx - 14.dp.toPx()
            val dropRadius = dropHeight / 2f

            // Тянется тем сильнее, чем быстрее едет - что от пружины, что от пальца
            val speed = abs(dropX.velocity)
            val dropWidth = dropHeight * (1f + (speed / 2600f).coerceAtMost(0.85f))

            val effect = lens?.effect(
                layerSize = size,
                center = Offset(cx, cy),
                halfExtent = Size(dropWidth / 2f, dropHeight / 2f),
                radius = dropRadius,
                thickness = dropHeight * 0.42f,
                refraction = dropHeight * 0.3f,
                dispersion = 0.22f,
                highlight = 0.16f,
                mask = true
            )

            var refracted = false
            if (effect != null) {
                runCatching {
                    dropLayer.renderEffect = effect
                    dropLayer.record {
                        // Берём неразмытый снимок: размытое стекло капсулы уже под
                        // нами, и капля на его фоне должна читаться линзой, а не
                        // ещё одним пятном
                        translate(
                            left = backdrop.origin.x - canvasOrigin.x,
                            top = backdrop.origin.y - canvasOrigin.y
                        ) {
                            drawLayer(backdrop.layer)
                        }
                    }
                    drawLayer(dropLayer)
                }.onSuccess { refracted = true }
            }

            val topLeft = Offset(cx - dropWidth / 2f, cy - dropHeight / 2f)
            val dropSize = Size(dropWidth, dropHeight)
            val corner = CornerRadius(dropRadius, dropRadius)

            // Акцент виден и сквозь стекло, но без линзы он единственное, что
            // отмечает активный пункт, - поэтому там плотнее
            drawRoundRect(
                color = scheme.primary.copy(
                    alpha = when {
                        !refracted -> if (isDark) 0.30f else 0.18f
                        isDark -> 0.16f
                        else -> 0.10f
                    }
                ),
                topLeft = topLeft,
                size = dropSize,
                cornerRadius = corner
            )

            if (refracted) {
                // Кайма и блик по ободку: то, что делает каплю выпуклой
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFFFD27A),
                            Color(0xFFFFFFFF),
                            Color(0xFF7ADFFF),
                            Color(0xFFB98BFF),
                            Color(0xFFFFD27A)
                        ),
                        start = topLeft,
                        end = Offset(topLeft.x + dropWidth, topLeft.y + dropHeight)
                    ),
                    topLeft = topLeft,
                    size = dropSize,
                    cornerRadius = corner,
                    style = Stroke(width = 1.4.dp.toPx()),
                    alpha = 0.55f
                )
            }

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (isDark) 0.55f else 0.85f),
                        Color.White.copy(alpha = 0f)
                    ),
                    startY = topLeft.y,
                    endY = cy
                ),
                topLeft = topLeft,
                size = dropSize,
                cornerRadius = corner,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = BarPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                GlassBarButton(
                    item = item,
                    active = item == selected,
                    onClick = { onSelect(item) }
                )
            }
        }
    }
}

@Composable
private fun GlassBarButton(
    item: GlassBarItem,
    active: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "glassButtonScale"
    )
    val tint by animateColorAsState(
        targetValue = if (active) scheme.primary else scheme.onSurfaceVariant,
        animationSpec = tween(250),
        label = "glassButtonTint"
    )

    Box(
        modifier = Modifier
            .size(ItemSize)
            .scale(scale)
            .clip(GlassCapsuleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = false),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        when (item) {
            GlassBarItem.HOME -> HomeIcon(color = tint, modifier = Modifier.size(26.dp))
            GlassBarItem.SETTINGS -> Icon(
                painter = painterResource(R.drawable.ic_settings_24dp),
                contentDescription = stringResource(R.string.main_nav_settings),
                tint = tint,
                modifier = Modifier.size(26.dp)
            )

            GlassBarItem.ADD -> Icon(
                painter = painterResource(R.drawable.ic_add_24dp),
                contentDescription = stringResource(R.string.main_nav_add),
                tint = tint,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/** Домик в том же проволочном стиле, что и остальные рисованные иконки. */
@Composable
private fun HomeIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 4f, cap = StrokeCap.Round)

        // Крыша
        drawLine(color, Offset(w * 0.1f, h * 0.45f), Offset(w * 0.5f, h * 0.12f), strokeWidth = 4f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.5f, h * 0.12f), Offset(w * 0.9f, h * 0.45f), strokeWidth = 4f, cap = StrokeCap.Round)
        // Стены
        drawLine(color, Offset(w * 0.22f, h * 0.42f), Offset(w * 0.22f, h * 0.85f), strokeWidth = 4f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.78f, h * 0.42f), Offset(w * 0.78f, h * 0.85f), strokeWidth = 4f, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.22f, h * 0.85f), Offset(w * 0.78f, h * 0.85f), strokeWidth = 4f, cap = StrokeCap.Round)
        // Дверь
        drawRect(
            color = color,
            topLeft = Offset(w * 0.42f, h * 0.58f),
            size = androidx.compose.ui.geometry.Size(w * 0.16f, h * 0.27f),
            style = stroke
        )
    }
}
