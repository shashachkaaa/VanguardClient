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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.util.lerp
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
 * Запас вокруг капсулы. Под пальцем капля вырастает за её край, а стекло само себя
 * обрезает по форме - значит капля должна жить не внутри него, а поверх, в этом поле.
 */
private val BarOverflow = 10.dp

/**
 * Под пальцем капля расплющивается: вширь заметно сильнее, чем в высоту - так ведёт
 * себя капля, на которую надавили.
 */
private const val PressWidthScale = 1.4f
private const val PressHeightScale = 1.12f

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

    val items = listOf(GlassBarItem.HOME, GlassBarItem.SETTINGS, GlassBarItem.ADD)
    val selectedIndex = items.indexOf(selected).coerceAtLeast(0)

    val itemPx = with(density) { ItemSize.toPx() }
    val padPx = with(density) { BarPadding.toPx() } + with(density) { BarOverflow.toPx() }
    val centerOf = { index: Int -> padPx + itemPx * index + itemPx / 2f }

    val lens = rememberLiquidLens()
    val dropLayer = rememberGraphicsLayer()

    var dragging by remember { mutableStateOf(false) }

    // Цель хранится в состоянии, а гонится за ней одна непрерывная анимация.
    // Раньше каждое событие пальца запускало свою корутину с animateTo, и каждая
    // отменяла предыдущую - от этой чехарды капля и дёргалась
    var dragTarget by remember { mutableStateOf<Float?>(null) }
    val target = dragTarget ?: centerOf(selectedIndex)
    val dropX by animateFloatAsState(
        targetValue = target,
        animationSpec = if (dragging) {
            spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium)
        } else {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        },
        label = "barDropX"
    )

    // Отставание от цели заменяет скорость: у пружины оно ей прямо пропорционально
    val lag = abs(target - dropX)

    // Экранные координаты капли: слой с фоном общий на весь экран, и вырезать из
    // него нужный участок можно только по общим координатам
    var canvasOrigin by remember { mutableStateOf(Offset.Zero) }

    val minX = centerOf(0)
    val maxX = centerOf(items.lastIndex)

    // Нажатие любой из кнопок раздувает каплю. Источники держим врозь, иначе
    // круги от нажатия расходились бы сразу по всем трём
    val sources = remember { List(items.size) { MutableInteractionSource() } }
    val anyPressed = sources.map { it.collectIsPressedAsState() }.any { it.value }
    val pressAmount by animateFloatAsState(
        targetValue = if (anyPressed || dragging) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "barPress"
    )

    val barWidth = ItemSize * items.size + BarPadding * 2

    Box(
        modifier = modifier
            .width(barWidth + BarOverflow * 2)
            .height(BarHeight + BarOverflow * 2)
            .pointerInput(itemPx, padPx, items.size) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        dragTarget = centerOf(selectedIndex)
                    },
                    onDragEnd = {
                        val index = (((dragTarget ?: minX) - padPx - itemPx / 2f) / itemPx)
                            .roundToInt()
                            .coerceIn(0, items.lastIndex)
                        dragging = false
                        dragTarget = null
                        onSelect(items[index])
                    },
                    onDragCancel = {
                        dragging = false
                        dragTarget = null
                    }
                ) { change, amount ->
                    change.consume()
                    // Смещением, а не прыжком к пальцу: от прыжка капля
                    // перескакивала через всю панель на первом же движении
                    dragTarget = ((dragTarget ?: minX) + amount).coerceIn(minX, maxX)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        GlassSurface(
            modifier = Modifier.width(barWidth).height(BarHeight),
            shape = GlassCapsuleShape,
            backdrop = backdrop,
            fallbackColor = scheme.surfaceContainerHigh.copy(alpha = 0.96f)
        )

        // Капля рисуется поверх стекла и вне его обрезки - иначе под пальцем ей
        // некуда было бы вырасти
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { canvasOrigin = it.positionOnScreen() }
        ) {
            val cx = dropX
            val cy = size.height / 2f
            val dropHeight = (itemPx - 8.dp.toPx()) * lerp(1f, PressHeightScale, pressAmount)
            val dropRadius = dropHeight / 2f

            // Тянется тем сильнее, чем дальше отстала от цели, и расплющивается
            // под пальцем
            val stretch = (lag / itemPx).coerceAtMost(0.8f)
            val dropWidth = dropHeight * (1f + stretch) * lerp(1f, PressWidthScale, pressAmount)

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
                        // Снимок берём размытый, тот же, что под капсулой. С резким
                        // капля выходила дыркой в панели: сквозь неё читался текст
                        // списка, да ещё и сдвинутый линзой. Стекло гнёт своё
                        // матовое содержимое, а не прорезает окно наружу
                        translate(
                            left = backdrop.origin.x - canvasOrigin.x,
                            top = backdrop.origin.y - canvasOrigin.y
                        ) {
                            drawLayer(backdrop.blurred)
                        }
                    }
                    drawLayer(dropLayer)
                }.onSuccess { refracted = true }
            }

            val topLeft = Offset(cx - dropWidth / 2f, cy - dropHeight / 2f)
            val dropSize = Size(dropWidth, dropHeight)
            val corner = CornerRadius(dropRadius, dropRadius)

            if (refracted) {
                // Плёнка возвращает капле матовость капсулы: сам слой её тонировку
                // собой закрыл. В референсе капля от панели отличается едва-едва,
                // поэтому плёнка лёгкая - каплю выдаёт грань, а не заливка
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (isDark) 0.16f else 0.26f),
                            Color.White.copy(alpha = if (isDark) 0.05f else 0.10f)
                        ),
                        startY = topLeft.y,
                        endY = topLeft.y + dropHeight
                    ),
                    topLeft = topLeft,
                    size = dropSize,
                    cornerRadius = corner
                )
            }

            // Акцент лишь подкрашивает каплю: какой пункт активен, и так видно по
            // цвету иконки. Без линзы подкрасить приходится плотнее - больше
            // отметить активный пункт нечем
            drawRoundRect(
                color = scheme.primary.copy(
                    alpha = when {
                        !refracted -> if (isDark) 0.30f else 0.18f
                        isDark -> 0.10f
                        else -> 0.07f
                    }
                ),
                topLeft = topLeft,
                size = dropSize,
                cornerRadius = corner
            )

            // Кайма разгорается на ходу и гаснет на месте. Так это и на записи:
            // над мягким размытым фоном её нет, а стоит капле поехать - по ободку
            // проходит цветной след
            val fringe = if (refracted) (stretch * 1.1f).coerceIn(0f, 0.7f) else 0f
            if (fringe > 0.01f) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        0f to Color(0xFFFFD08A),
                        0.4f to Color.Transparent,
                        0.6f to Color.Transparent,
                        1f to Color(0xFF86B8FF),
                        startY = topLeft.y,
                        endY = topLeft.y + dropHeight
                    ),
                    topLeft = topLeft,
                    size = dropSize,
                    cornerRadius = corner,
                    style = Stroke(width = 1.4.dp.toPx()),
                    alpha = fringe
                )
            }

            // Грань: сверху ловит свет, книзу гаснет - от этого капля выпуклая
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (isDark) 0.65f else 0.95f),
                        Color.White.copy(alpha = 0f)
                    ),
                    startY = topLeft.y,
                    endY = cy
                ),
                topLeft = topLeft,
                size = dropSize,
                cornerRadius = corner,
                style = Stroke(width = 1.2.dp.toPx())
            )
        }

        Row(
            modifier = Modifier
                .width(barWidth)
                .height(BarHeight)
                .padding(horizontal = BarPadding),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                GlassBarButton(
                    item = item,
                    active = item == selected,
                    interactionSource = sources[index],
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
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
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
