package com.v2ray.ang.ui.compose

import android.graphics.Canvas
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import com.v2ray.ang.R
import com.v2ray.ang.handler.AppIconManager
import com.v2ray.ang.handler.AppIconOption

/**
 * Вариант акцента: [id] хранится в настройках, [seed] - исходный цвет,
 * из которого считаются все оттенки палитры.
 */
data class AccentOption(
    val id: String,
    val seed: Color,
    @StringRes val titleRes: Int
)

object AccentPalette {

    /**
     * Родное индиго: его оттенки подобраны руками в теме, поэтому для него
     * ничего не пересчитывается - палитра остаётся ровно такой, какой была.
     */
    const val DEFAULT_ID = "default"

    val options = listOf(
        AccentOption(DEFAULT_ID, Color(0xFF4F46E5), R.string.accent_indigo),
        AccentOption("blue", Color(0xFF2563EB), R.string.accent_blue),
        AccentOption("cyan", Color(0xFF0891B2), R.string.accent_cyan),
        AccentOption("teal", Color(0xFF0D9488), R.string.accent_teal),
        AccentOption("green", Color(0xFF16A34A), R.string.accent_green),
        AccentOption("amber", Color(0xFFF59E0B), R.string.accent_amber),
        AccentOption("orange", Color(0xFFF97316), R.string.accent_orange),
        AccentOption("red", Color(0xFFDC2626), R.string.accent_red),
        AccentOption("pink", Color(0xFFDB2777), R.string.accent_pink),
        AccentOption("purple", Color(0xFF9333EA), R.string.accent_purple)
    )

    fun find(id: String?): AccentOption =
        options.firstOrNull { it.id == id } ?: options.first()
}

/**
 * Тот же цвет с заданной светлотой: так из одного зерна получается вся линейка
 * оттенков - от заливки кнопки до контейнера под текстом.
 *
 * @param lightness Светлота в HSL, от 0 (чёрный) до 1 (белый).
 * @param saturation Множитель насыщенности: приглушает крикливые оттенки.
 */
private fun Color.tone(lightness: Float, saturation: Float = 1f): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[1] = (hsl[1] * saturation).coerceIn(0f, 1f)
    hsl[2] = lightness.coerceIn(0f, 1f)
    return Color(ColorUtils.HSLToColor(hsl))
}

/**
 * Пересобирает семейства primary и secondary вокруг выбранного цвета.
 * Фон, поверхности и семантические цвета (пинг, ошибки) не трогаются:
 * чёрный AMOLED-фон и красный «сервер не отвечает» от акцента не зависят.
 */
fun ColorScheme.withAccent(option: AccentOption, dark: Boolean): ColorScheme {
    if (option.id == AccentPalette.DEFAULT_ID) return this
    val seed = option.seed

    return if (dark) {
        copy(
            primary = seed.tone(0.72f, 0.9f),
            onPrimary = seed.tone(0.14f, 0.8f),
            primaryContainer = seed.tone(0.28f, 0.75f),
            onPrimaryContainer = seed.tone(0.9f, 0.9f),
            inversePrimary = seed.tone(0.45f),
            surfaceTint = seed.tone(0.72f, 0.9f),
            secondary = seed.tone(0.68f, 0.6f),
            onSecondary = seed.tone(0.14f, 0.6f),
            secondaryContainer = seed.tone(0.3f, 0.5f),
            onSecondaryContainer = seed.tone(0.9f, 0.6f)
        )
    } else {
        copy(
            primary = seed.tone(0.45f),
            onPrimary = Color.White,
            primaryContainer = seed.tone(0.9f, 0.8f),
            onPrimaryContainer = seed.tone(0.18f, 0.9f),
            inversePrimary = seed.tone(0.78f, 0.8f),
            surfaceTint = seed.tone(0.45f),
            secondary = seed.tone(0.45f, 0.75f),
            onSecondary = Color.White,
            secondaryContainer = seed.tone(0.9f, 0.6f),
            onSecondaryContainer = seed.tone(0.2f, 0.7f)
        )
    }
}

/** Цвет кружка в выборе: ровно то, чем станет акцент интерфейса. */
fun AccentOption.previewColor(dark: Boolean): Color =
    (if (dark) DarkColor else LightColor).withAccent(this, dark).primary

/**
 * Строка настроек с выбором акцента. При включённых цветах из обоев палитру
 * задаёт система, поэтому выбор глохнет - иначе он молча ничего не менял бы.
 */
@Composable
fun AccentColorSetting(enabled: Boolean = true) {
    val accentId by ThemeManager.accentColor.collectAsState()
    val selected = AccentPalette.find(accentId)
    var showDialog by remember { mutableStateOf(false) }

    SettingsMenuItem(
        title = stringResource(R.string.title_pref_accent_color),
        subtitle = if (enabled) {
            stringResource(selected.titleRes)
        } else {
            stringResource(R.string.summary_pref_accent_color_dynamic)
        },
        enabled = enabled,
        onClick = { showDialog = true }
    )

    if (showDialog) {
        AccentColorDialog(
            selectedId = selected.id,
            onSelected = {
                ThemeManager.setAccentColor(it)
                showDialog = false
            },
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun AccentColorDialog(
    selectedId: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val dark = LocalDarkTheme.current

    GlassAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_pref_accent_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentPalette.options.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { option ->
                            AccentSwatch(
                                option = option,
                                color = option.previewColor(dark),
                                selected = option.id == selectedId,
                                onClick = { onSelected(option.id) }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}

@Composable
private fun AccentSwatch(
    option: AccentOption,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = CircleShape
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_action_done),
                contentDescription = stringResource(option.titleRes),
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/**
 * Строка настроек со сменой значка приложения.
 *
 * Значок меняется включением одного из псевдонимов из манифеста, поэтому лаунчеру
 * нужно время, чтобы его перерисовать - об этом честно предупреждаем.
 */
@Composable
fun AppIconSetting() {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(AppIconManager.current()) }
    var showDialog by remember { mutableStateOf(false) }

    SettingsMenuItem(
        title = stringResource(R.string.title_pref_app_icon),
        subtitle = stringResource(selected.titleRes),
        onClick = { showDialog = true }
    )

    if (showDialog) {
        GlassAlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.title_pref_app_icon)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppIconManager.options.forEach { option ->
                            AppIconPreview(
                                option = option,
                                selected = option.id == selected.id,
                                onClick = {
                                    AppIconManager.apply(context, option)
                                    selected = AppIconManager.current()
                                    showDialog = false
                                }
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.app_icon_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun AppIconPreview(
    option: AppIconOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    val preview = rememberIconBitmap(option.previewRes)

    val shape = RoundedCornerShape(12.dp)
    val border = Modifier
        .size(46.dp)
        .clip(shape)
        .border(
            width = if (selected) 3.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
            shape = shape
        )
        .clickable { onClick() }

    if (preview != null) {
        Image(
            bitmap = preview,
            contentDescription = stringResource(option.titleRes),
            modifier = border
        )
    } else {
        Box(modifier = border.background(MaterialTheme.colorScheme.surfaceContainerHighest))
    }
}

/**
 * Значок приложения в виде картинки.
 *
 * Через painterResource его не загрузить: с Android 8 значок описан как
 * adaptive-icon, а это ни вектор, ни растр - попытка открыть его роняла экран.
 * Поэтому рисуем системный drawable в картинку сами, как это делает лаунчер.
 */
@Composable
private fun rememberIconBitmap(@DrawableRes resId: Int): ImageBitmap? {
    val context = LocalContext.current
    return remember(resId) {
        runCatching {
            val drawable = ContextCompat.getDrawable(context, resId) ?: return@runCatching null
            val size = 144
            val bitmap = createBitmap(size, size)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(bitmap))
            bitmap.asImageBitmap()
        }.getOrNull()
    }
}
