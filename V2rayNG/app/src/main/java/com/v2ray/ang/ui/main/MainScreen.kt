package com.v2ray.ang.ui.main

import androidx.compose.animation.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.v2ray.ang.R
import com.v2ray.ang.extension.toSpeedString
import com.v2ray.ang.extension.toTrafficString
import com.v2ray.ang.handler.SessionTraffic
import com.v2ray.ang.handler.TrafficSpeed
import com.v2ray.ang.handler.TrafficSpeedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.v2ray.ang.ui.compose.GlassSurface
import com.v2ray.ang.ui.compose.QRCodeDialog
import com.v2ray.ang.ui.compose.ResumePauseEffect
import com.v2ray.ang.ui.compose.LocalGlassBackdrop
import com.v2ray.ang.ui.compose.glassBackdropSource
import com.v2ray.ang.ui.compose.rememberGlassBackdrop

@Composable
fun PowerIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        // Толщина от размера, а не в пикселях: на плотных экранах фиксированные
        // 5 пикселей превращались в волосок
        val strokeW = size.minDimension * 0.1f
        val side = size.minDimension - strokeW
        drawArc(
            color = color,
            startAngle = -240f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(strokeW / 2f, strokeW / 2f),
            size = Size(side, side),
            style = Stroke(width = strokeW, cap = StrokeCap.Round)
        )
        drawLine(
            color = color,
            start = center.copy(y = strokeW / 2f),
            end = center.copy(y = center.y),
            strokeWidth = strokeW,
            cap = StrokeCap.Round
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mainViewModel: MainViewModel,
    onAction: (MainAction) -> Unit,
    onNavigate: (String) -> Unit
) {
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val subscriptions by mainViewModel.subscriptions.collectAsStateWithLifecycle()
    val isImporting by mainViewModel.isImporting.collectAsStateWithLifecycle()
    val importError by mainViewModel.importError.collectAsStateWithLifecycle()

    val showImportMenu by mainViewModel.showImportSheet.collectAsStateWithLifecycle()

    LaunchedEffect(importError) {
        if (importError != null) {
            delay(4000)
            mainViewModel.importError.value = null
        }
    }

    // Крутится, пока служба не отчиталась о новом состоянии (или об ошибке запуска)
    var isConnecting by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isRunning, uiState.statusText) { isConnecting = false }
    LaunchedEffect(isConnecting) {
        if (isConnecting) {
            delay(20000)
            isConnecting = false
        }
    }

    var uptime by remember { mutableLongStateOf(0L) }
    LaunchedEffect(uiState.isRunning, uiState.serviceStartTime) {
        if (uiState.isRunning && uiState.serviceStartTime != null) {
            while (true) {
                uptime = System.currentTimeMillis() - uiState.serviceStartTime!!
                delay(1000L)
            }
        } else {
            uptime = 0L
        }
    }
    val seconds = (uptime / 1000) % 60
    val minutes = (uptime / (1000 * 60)) % 60
    val hours = (uptime / (1000 * 60 * 60))
    val timeString = "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"

    val haptics = LocalHapticFeedback.current

    // Отклик на смену состояния: подключились или отвалились - это чувствуется рукой
    var lastRunning by remember { mutableStateOf(uiState.isRunning) }
    LaunchedEffect(uiState.isRunning) {
        if (uiState.isRunning != lastRunning) {
            lastRunning = uiState.isRunning
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // Скорость считает служба - тем же замером, что кормит уведомление
    val speed by TrafficSpeedState.speed.collectAsStateWithLifecycle()
    val speedHistory by TrafficSpeedState.history.collectAsStateWithLifecycle()
    val session by TrafficSpeedState.session.collectAsStateWithLifecycle()

    // Пузырёк капсулы. Из настроек он должен приехать с шестерёнки, а не оказаться
    // на «Главной» мгновенно, поэтому при возврате панель пересобирается уже с
    // подсветкой на настройках, а следом уезжает на место
    var barItem by remember { mutableStateOf(GlassBarItem.HOME) }
    var barResetToken by remember { mutableIntStateOf(0) }
    var leftForSettings by rememberSaveable { mutableStateOf(false) }

    ResumePauseEffect(
        onResume = {
            if (leftForSettings) {
                leftForSettings = false
                barItem = GlassBarItem.SETTINGS
                barResetToken++
            }
        },
        onPause = {}
    )

    LaunchedEffect(barItem) {
        if (barItem == GlassBarItem.SETTINGS) {
            // Даём кроссфейду улечься, чтобы переезд было видно целиком
            delay(140)
            barItem = GlassBarItem.HOME
        }
    }

    // Сервера, добавленные ключом: свой раздел над подписками, пустым не показывается
    val standaloneServers by remember { mainViewModel.serversForGroup(STANDALONE_GROUP_ID) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val showStandalone = standaloneServers.isNotEmpty()

    // Свёрнутые карточки: «Скрыть все» прячет списки серверов, шапки остаются
    var collapsedGuids by rememberSaveable { mutableStateOf(listOf<String>()) }
    val collapsibleGuids = buildList {
        if (showStandalone) add(STANDALONE_GROUP_ID)
        subscriptions.forEach { add(it.guid) }
    }
    val allCollapsed = collapsibleGuids.isNotEmpty() && collapsedGuids.size >= collapsibleGuids.size

    // Стекло размывает именно то, что под ним, поэтому экран пишется в слой
    val backdrop = rememberGlassBackdrop()

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            // ДИНАМИЧЕСКИЙ ФОН: Подстраивается под тему (белый, серый или черный AMOLED)
            containerColor = MaterialTheme.colorScheme.background,
            modifier = Modifier.glassBackdropSource(backdrop)
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Центральная кнопка
                PowerButton(
                    isConnected = uiState.isRunning,
                    isConnecting = isConnecting,
                    timeString = timeString,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        isConnecting = true
                        onAction(MainAction.ToggleService)
                    }
                )

                SpeedRow(
                    visible = uiState.isRunning,
                    speed = speed,
                    history = speedHistory,
                    session = session
                )

                // Действия под кнопкой оформлены чипами, а не голым текстом
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ActionChip(
                        text = stringResource(R.string.main_action_test),
                        modifier = Modifier.weight(1f),
                        onClick = { onAction(MainAction.TestCurrentServer) }
                    )
                    ActionChip(
                        text = stringResource(if (allCollapsed) R.string.main_action_expand_all else R.string.main_action_collapse_all),
                        onClick = {
                            collapsedGuids = if (allCollapsed) emptyList() else collapsibleGuids
                        }
                    )
                }

                if (subscriptions.isEmpty() && !showStandalone) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painterResource(id = R.drawable.ic_cloud_download_24dp), 
                                contentDescription = null, 
                                modifier = Modifier.size(64.dp), 
                                tint = MaterialTheme.colorScheme.outlineVariant // ДИНАМИЧЕСКИЙ СЕРЫЙ
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.main_empty_profiles),
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), // ДИНАМИЧЕСКИЙ СЕРЫЙ
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        contentPadding = PaddingValues(bottom = 110.dp)
                    ) {
                        if (showStandalone) {
                            item(key = "standalone-servers") {
                                StandaloneServersCard(
                                    servers = standaloneServers,
                                    selectedGuid = uiState.selectedGuid,
                                    expanded = STANDALONE_GROUP_ID !in collapsedGuids,
                                    onToggleExpanded = {
                                        collapsedGuids = if (STANDALONE_GROUP_ID in collapsedGuids) {
                                            collapsedGuids - STANDALONE_GROUP_ID
                                        } else {
                                            collapsedGuids + STANDALONE_GROUP_ID
                                        }
                                    },
                                    onAction = onAction,
                                    onPingGroup = {
                                        onAction(MainAction.SelectGroup(STANDALONE_GROUP_ID))
                                        onAction(MainAction.TestProfilePing(STANDALONE_GROUP_ID))
                                    },
                                    onSelectServer = { guid -> onAction(MainAction.SelectServer(guid)) },
                                    onEditServer = { guid, profile ->
                                        onAction(MainAction.EditServer(guid, profile))
                                    }
                                )
                            }
                        }

                        itemsIndexed(
                            items = subscriptions,
                            key = { _, item -> item.guid + (item.subscription.remarks ?: "") }
                        ) { index, subCache ->
                            val serversFlow = remember(subCache.guid) { mainViewModel.serversForGroup(subCache.guid) }
                            val servers by serversFlow.collectAsStateWithLifecycle(initialValue = emptyList())

                            // Карточки въезжают снизу по очереди при первом показе
                            var appeared by rememberSaveable(subCache.guid) { mutableStateOf(false) }
                            LaunchedEffect(subCache.guid) {
                                if (!appeared) {
                                    delay(index * 60L)
                                    appeared = true
                                }
                            }
                            val itemAlpha by animateFloatAsState(
                                targetValue = if (appeared) 1f else 0f,
                                animationSpec = tween(360),
                                label = "cardAlpha"
                            )
                            val itemOffset by animateDpAsState(
                                targetValue = if (appeared) 0.dp else 24.dp,
                                animationSpec = tween(360),
                                label = "cardOffset"
                            )

                            Box(
                                modifier = Modifier
                                    .graphicsLayer {
                                        alpha = itemAlpha
                                        translationY = itemOffset.toPx()
                                    }
                            ) {
                            ProfileCard(
                                subscription = subCache,
                                servers = servers,
                                selectedGuid = uiState.selectedGuid,
                                expanded = subCache.guid !in collapsedGuids,
                                onToggleExpanded = {
                                    collapsedGuids = if (subCache.guid in collapsedGuids) {
                                        collapsedGuids - subCache.guid
                                    } else {
                                        collapsedGuids + subCache.guid
                                    }
                                },
                                onAction = onAction,
                                onPingProfile = { guid -> 
                                    onAction(MainAction.SelectGroup(guid))
                                    onAction(MainAction.TestProfilePing(guid)) 
                                },
                                onUpdateSubscription = { subId -> 
                                    mainViewModel.updateSubscription(subId)
                                },
                                onSelectServer = { guid -> 
                                    onAction(MainAction.SelectServer(guid)) 
                                },
                                onDeleteSubscription = { subId ->
                                    mainViewModel.removeSubscription(subId)
                                },
                                onEditServer = { guid, profile ->
                                    onAction(MainAction.EditServer(guid, profile))
                                }
                            )
                            }
                        }
                    }
                }
            }
        }
        
        key(barResetToken) {
            LiquidGlassBar(
                backdrop = backdrop,
                selected = barItem,
                onSelect = { item ->
                    when (item) {
                        GlassBarItem.HOME -> Unit
                        GlassBarItem.SETTINGS -> {
                            leftForSettings = true
                            onNavigate("settings")
                        }
                        GlassBarItem.ADD -> mainViewModel.showImportSheet.value = true
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 18.dp)
            )
        }

        if (showImportMenu) {
            ImportSheet(
                onDismiss = { mainViewModel.showImportSheet.value = false },
                onAction = { action ->
                    mainViewModel.showImportSheet.value = false
                    onAction(action)
                }
            )
        }

        TopProgressBanner(
            visible = isImporting,
            text = stringResource(R.string.main_updating_subscription),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)
        )

        // Same banner for the ping run, carrying the "x / y left" progress
        TopProgressBanner(
            visible = uiState.isTesting && !isImporting,
            text = uiState.statusText,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
            onCancel = { onAction(MainAction.CancelTesting) }
        )

        // QR-код сервера: битмап готовит модель по действию из меню строки
        uiState.shareQRCodeBitmap?.let { bitmap ->
            QRCodeDialog(
                bitmap = bitmap,
                onDismiss = { onAction(MainAction.DismissQRCodeDialog) }
            )
        }

        AnimatedVisibility(
            visible = importError != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
        ) {
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), // ДИНАМИЧЕСКИЙ ЦВЕТ ОШИБКИ
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Text(
                    text = importError ?: "", 
                    color = MaterialTheme.colorScheme.onErrorContainer, // ДИНАМИЧЕСКИЙ ЦВЕТ ТЕКСТА ОШИБКИ
                    fontWeight = FontWeight.Bold, 
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

/** Крестик в том же проволочном стиле, что и остальные рисованные иконки. */
@Composable
private fun CrossIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawLine(color, Offset(0f, 0f), Offset(w, h), strokeWidth = 4f, cap = StrokeCap.Round)
        drawLine(color, Offset(w, 0f), Offset(0f, h), strokeWidth = 4f, cap = StrokeCap.Round)
    }
}

/**
 * Pill that slides in from the top while a long running task is in progress.
 */
@Composable
private fun TopProgressBanner(
    visible: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (onCancel != null) {
                    Spacer(Modifier.width(10.dp))
                    IconButton(onClick = onCancel, modifier = Modifier.size(22.dp)) {
                        CrossIcon(
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Крупная круглая кнопка подключения: выпуклая шайба со стеклянным бликом,
 * вокруг неё кольца и свечение, при подключении по кольцу бежит дуга.
 */
@Composable
private fun PowerButton(
    isConnected: Boolean,
    isConnecting: Boolean,
    timeString: String,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    // Насколько ярко подкрашивать кнопку: на светлой теме мягкое свечение
    // расплывается в серое пятно, поэтому там оно почти не нужно
    val isLightTheme = scheme.background.luminance() > 0.5f

    // Одна величина на все слои: 0 - покой, 1 - соединение установлено.
    // Через неё кольца, свечение и обводка переезжают между состояниями разом
    val active by animateFloatAsState(
        targetValue = when {
            isConnected -> 1f
            isConnecting -> 0.55f
            else -> 0f
        },
        animationSpec = tween(500),
        label = "active"
    )
    val accent by animateColorAsState(
        targetValue = if (isConnected || isConnecting) scheme.primary else scheme.outlineVariant,
        animationSpec = tween(500),
        label = "accent"
    )

    val transition = rememberInfiniteTransition(label = "power")
    val sweepAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "sweep"
    )
    // Медленный блик по кольцу, когда соединение уже есть: без движения
    // кнопка выглядит наклейкой
    val orbitAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing)),
        label = "orbit"
    )
    val breath by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse),
        label = "breath"
    )

    // Нажатие вдавливает шайбу и убирает тень - как настоящую клавишу
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "press"
    )
    val elevation by animateDpAsState(
        targetValue = if (pressed) 2.dp else 12.dp,
        animationSpec = tween(160),
        label = "elevation"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        // Ореол под кнопкой
        val glow = (if (isLightTheme) 0.14f else 0.30f) * active
        Box(
            modifier = Modifier
                .size(250.dp)
                .scale(if (isConnecting) breath else 1f)
                .drawBehind {
                    if (glow <= 0.01f) return@drawBehind
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to accent.copy(alpha = glow),
                            0.55f to accent.copy(alpha = glow * 0.35f),
                            1f to Color.Transparent,
                            center = center,
                            radius = size.minDimension / 2f
                        )
                    )
                }
        )

        // Внешнее тонкое кольцо
        Box(
            modifier = Modifier
                .size(208.dp)
                .scale(if (isConnecting) breath else 1f)
                .border(
                    width = 1.dp,
                    color = accent.copy(alpha = 0.10f + 0.16f * active),
                    shape = CircleShape
                )
        )

        // Среднее кольцо, по нему бежит дуга подключения
        Canvas(
            modifier = Modifier
                .size(182.dp)
                .scale(if (isConnecting) breath else 1f)
        ) {
            drawCircle(
                color = accent.copy(alpha = 0.16f + 0.30f * active),
                style = Stroke(width = 2.dp.toPx())
            )
            if (isConnecting) {
                rotate(sweepAngle) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to Color.Transparent,
                            0.06f to accent,
                            0.28f to Color.Transparent,
                            1f to Color.Transparent,
                            center = center
                        ),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            } else if (active > 0.01f) {
                rotate(orbitAngle) {
                    drawArc(
                        color = accent.copy(alpha = 0.55f * active),
                        startAngle = 0f,
                        sweepAngle = 34f,
                        useCenter = false,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }

        // Шайба: заливка непрозрачная, сквозь полупрозрачную просвечивала бы
        // тень кнопки, а система рисует её многоугольником
        val tint = if (isLightTheme) 0.12f else 0.32f
        val fillCenter = lerp(scheme.surfaceContainerHigh, scheme.primary, tint * active)
        val fillEdge = if (isLightTheme) scheme.surface else lerp(scheme.surface, Color.Black, 0.3f)
        val glossAlpha = if (isLightTheme) 0.7f else 0.12f
        val shadeAlpha = if (isLightTheme) 0.05f else 0.22f

        Box(
            modifier = Modifier
                .size(152.dp)
                .scale(pressScale)
                .shadow(elevation = elevation, shape = CircleShape)
                .clip(CircleShape)
                .drawBehind {
                    val radius = size.minDimension / 2f
                    // Свет падает слева сверху: там центр заливки, там же блик,
                    // а снизу лёгкое затемнение - от этого шайба выглядит выпуклой
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(fillCenter, fillEdge),
                            center = Offset(size.width * 0.34f, size.height * 0.24f),
                            radius = radius * 1.7f
                        )
                    )
                    drawCircle(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.White.copy(alpha = glossAlpha), Color.Transparent),
                            startY = 0f,
                            endY = size.height * 0.62f
                        )
                    )
                    drawCircle(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = shadeAlpha)),
                            startY = size.height * 0.45f,
                            endY = size.height
                        )
                    )
                    val edge = 1.5.dp.toPx()
                    drawCircle(
                        brush = Brush.verticalGradient(
                            listOf(
                                accent.copy(alpha = 0.30f + 0.50f * active),
                                accent.copy(alpha = 0.10f + 0.25f * active)
                            )
                        ),
                        radius = radius - edge / 2f,
                        style = Stroke(width = edge)
                    )
                }
                .clickable(interactionSource = interaction, indication = ripple()) { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                PowerIcon(
                    color = lerp(scheme.onSurfaceVariant, scheme.primary, active),
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = when {
                        isConnecting && !isConnected -> stringResource(R.string.main_state_connecting)
                        isConnecting && isConnected -> stringResource(R.string.main_state_disconnecting)
                        isConnected -> stringResource(R.string.main_state_connected)
                        else -> stringResource(R.string.main_state_disconnected)
                    },
                    color = scheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 0.8.sp
                )
                AnimatedVisibility(
                    visible = isConnected,
                    enter = fadeIn(tween(400)) + expandVertically(tween(400)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = timeString,
                            color = scheme.onSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Скорость под кнопкой: приходит из того же замера, что и уведомление.
 * Под цифрами - график за последние замеры и объём, набежавший за сеанс.
 */
@Composable
private fun SpeedRow(
    visible: Boolean,
    speed: TrafficSpeed,
    history: List<TrafficSpeed>,
    session: SessionTraffic
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + expandVertically(tween(300)),
        exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(bottom = 10.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpeedValue(down = true, value = speed.totalDown)
                SpeedValue(down = false, value = speed.totalUp)
            }

            SpeedGraph(
                history = history,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(0.62f)
                    .height(34.dp)
            )

            Text(
                text = stringResource(
                    R.string.main_session_traffic,
                    session.down.toTrafficString(),
                    session.up.toTrafficString()
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/**
 * График скорости: заливка под линией для загрузки, линия потоньше для отдачи.
 * Масштаб плавающий - по максимуму окна, иначе на медленном соединении был бы прочерк.
 */
@Composable
private fun SpeedGraph(history: List<TrafficSpeed>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val downColor = scheme.primary
    val upColor = scheme.onSurfaceVariant

    Canvas(modifier = modifier) {
        val points = history.takeLast(TrafficSpeedState.HISTORY_SIZE)
        if (points.size < 2) return@Canvas

        val peak = points.maxOf { maxOf(it.totalDown, it.totalUp) }.coerceAtLeast(1L).toFloat()
        val stepX = size.width / (points.size - 1)

        fun path(values: (TrafficSpeed) -> Long, close: Boolean): Path = Path().apply {
            points.forEachIndexed { i, sample ->
                val x = i * stepX
                val y = size.height - (values(sample) / peak) * size.height
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            if (close) {
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
        }

        drawPath(
            path = path({ it.totalDown }, close = true),
            brush = Brush.verticalGradient(
                listOf(downColor.copy(alpha = 0.35f), downColor.copy(alpha = 0.02f))
            )
        )
        drawPath(
            path = path({ it.totalDown }, close = false),
            color = downColor,
            style = Stroke(width = 2.5f, cap = StrokeCap.Round)
        )
        drawPath(
            path = path({ it.totalUp }, close = false),
            color = upColor.copy(alpha = 0.7f),
            style = Stroke(width = 1.8f, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun SpeedValue(down: Boolean, value: Long) {
    val color = if (value > 0) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val tint by animateColorAsState(color, tween(400), label = "speedTint")

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (down) "↓" else "↑",
            color = tint,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = value.toSpeedString(),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Небольшой чип-действие: мягкая подложка, отклик на нажатие.
 */
@Composable
private fun ActionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(120),
        label = "chipScale"
    )

    // Подложка держится на акценте: на чёрной теме серый контейнер сливался с фоном
    // до полной невидимости, и чип читался как просто текст, а не как кнопка
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
        modifier = modifier
            .scale(scale)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(),
                onClick = onClick
            )
    ) {
        // Без fillMaxWidth: чип без веса забирал бы всю строку целиком,
        // и соседу с weight(1f) не оставалось ни пикселя - он просто исчезал
        Text(
            text = text,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

/**
 * Шторка импорта, которую открывает «+» в нижней капсуле. Стекло здесь настоящее:
 * шторка живёт в отдельном окне, поэтому ей можно отдать слой с содержимым экрана.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportSheet(
    onDismiss: () -> Unit,
    onAction: (MainAction) -> Unit
) {
    val sheetShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f),
        shape = sheetShape,
        dragHandle = null,
        // Стекло должно доходить до самого низа экрана, отступ под навигацию делаем сами
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }
    ) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = sheetShape,
            backdrop = LocalGlassBackdrop.current,
            blurRadius = 34.dp,
            opaqueness = 1.15f
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(top = 12.dp, bottom = 28.dp)
            ) {
                // Свой хват вместо системного: он должен лежать на стекле, а не над ним
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(bottom = 14.dp)
                        .size(width = 34.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
                Text(
                    text = stringResource(R.string.main_add_servers),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
                )
                ImportSheetItem(stringResource(R.string.main_import_clipboard), R.drawable.ic_copy) { onAction(MainAction.ImportClipboard) }
                ImportSheetItem(stringResource(R.string.main_import_qr), R.drawable.ic_qu_scan_24dp) { onAction(MainAction.ImportQRcode) }
                ImportSheetItem(stringResource(R.string.main_import_file), R.drawable.ic_file_24dp) { onAction(MainAction.ImportConfigLocal) }
            }
        }
    }
}

@Composable
private fun ImportSheetItem(
    text: String,
    iconRes: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painterResource(id = iconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
