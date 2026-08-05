package com.v2ray.ang.ui.main

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.ui.compose.DeleteConfirmDialog
import com.v2ray.ang.ui.compose.colorPingSlow
import com.v2ray.ang.ui.compose.GlassMenuShape
import com.v2ray.ang.ui.compose.GlassSurface
import com.v2ray.ang.ui.compose.glassPanel
import com.v2ray.ang.ui.subscription.SubEditActivity
import com.v2ray.ang.util.CustomConfigUtil
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChevronDown(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeW = 4f
        drawLine(color, Offset(size.width * 0.2f, size.height * 0.3f), Offset(size.width * 0.5f, size.height * 0.7f), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.5f, size.height * 0.7f), Offset(size.width * 0.8f, size.height * 0.3f), strokeWidth = strokeW, cap = StrokeCap.Round)
    }
}

@Composable
fun ChevronRight(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeW = 4f
        drawLine(color, Offset(size.width * 0.3f, size.height * 0.2f), Offset(size.width * 0.7f, size.height * 0.5f), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.7f, size.height * 0.5f), Offset(size.width * 0.3f, size.height * 0.8f), strokeWidth = strokeW, cap = StrokeCap.Round)
    }
}

@Composable
fun ClockIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeW = 4f
        drawCircle(color, style = Stroke(width = strokeW))
        drawLine(color, center, center.copy(y = center.y - size.width * 0.25f), strokeWidth = strokeW, cap = StrokeCap.Round)
        drawLine(color, center, center.copy(x = center.x + size.width * 0.2f, y = center.y + size.width * 0.2f), strokeWidth = strokeW, cap = StrokeCap.Round)
    }
}

@Composable
fun WireframeGlobe(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeW = 3f
        drawCircle(color, style = Stroke(width = strokeW))
        drawOval(color, topLeft = Offset(size.width * 0.25f, 0f), size = Size(size.width * 0.5f, size.height), style = Stroke(width = strokeW))
        drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = strokeW)
    }
}

// Форматер даты
fun formatDate(millis: Long, format: String = "dd.MM.yyyy", fallback: String = ""): String {
    if (millis <= 0L) return fallback
    val formatter = SimpleDateFormat(format, Locale.getDefault())
    return formatter.format(Date(millis))
}

fun getProtocolDescription(context: Context, profile: ProfileItem, guid: String): String {
    val configTypeName = profile.configType.name.uppercase()
    
    // 1. Стандартные профили
    if (configTypeName != "CUSTOM") {
        val parts = mutableListOf(configTypeName)
        val network = profile.network?.uppercase()
        if (!network.isNullOrBlank() && network != "TCP") parts.add(network)
        else if (configTypeName != "HYSTERIA2") parts.add("TCP")
        
        val security = profile.security?.uppercase()
        if (!security.isNullOrBlank() && security != "NONE") parts.add(security)
        
        return parts.joinToString(" / ")
    }

    // 2. Парсинг CUSTOM (JSON) профилей
    return try {
        // Сырой конфиг лежит в MMKV (файлы — устаревший путь), см. CustomConfigUtil
        val rawData = CustomConfigUtil.getRawConfig(context, guid, profile.server) ?: return "CUSTOM"

        val root = CustomConfigUtil.parseConfig(rawData) ?: return "CUSTOM"
        val outbounds = root.getAsJsonArray("outbounds") ?: return "CUSTOM"

        for (i in 0 until outbounds.size()) {
            val outbound = outbounds.get(i).asJsonObject
            var protocol = outbound.get("protocol")?.asString?.uppercase() ?: continue

            if (protocol.isEmpty() || protocol == "FREEDOM" || protocol == "BLACKHOLE") continue

            val streamSettings = outbound.getAsJsonObject("streamSettings")
            
            if (protocol == "HYSTERIA" && streamSettings != null) {
                val hSettings = streamSettings.getAsJsonObject("hysteriaSettings")
                if (hSettings != null && hSettings.get("version")?.asInt == 2) {
                    protocol = "HYSTERIA2"
                }
            }

            val parts = mutableListOf(protocol)

            if (streamSettings != null) {
                val network = streamSettings.get("network")?.asString?.uppercase() ?: ""
                if (network.isNotEmpty() && !protocol.startsWith(network)) {
                    parts.add(network)
                } else if (protocol != "HYSTERIA" && protocol != "HYSTERIA2") {
                    parts.add("TCP") 
                }

                val security = streamSettings.get("security")?.asString?.uppercase() ?: ""
                if (security.isNotEmpty() && security != "NONE") {
                    parts.add(security)
                }
            }
            return parts.joinToString(" / ")
        }
        "AUTO / TCP"
    } catch (e: Exception) {
        e.printStackTrace()
        "CUSTOM"
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileCard(
    subscription: SubscriptionCache,
    servers: List<ServersCache>,
    selectedGuid: String?,
    expanded: Boolean = true,
    onToggleExpanded: () -> Unit = {},
    onAction: (MainAction) -> Unit,
    onPingProfile: (String) -> Unit,
    onUpdateSubscription: (String) -> Unit,
    onSelectServer: (String) -> Unit,
    onDeleteSubscription: (String) -> Unit,
    onEditServer: (String, ProfileItem) -> Unit
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var showMenu by remember { mutableStateOf(false) }

    val sub = subscription.subscription

    val title = sub.remarks.takeIf { !it.isNullOrBlank() } ?: stringResource(R.string.main_untitled)
    val lastUpdateStr = formatDate(sub.lastUpdated, "dd.MM.yyyy HH:mm", stringResource(R.string.main_never))
    val intervalHours = sub.updateInterval / 60
    val updateStatus = "- $intervalHours ч. $lastUpdateStr"

    val gbDivisor = 1073741824.0
    val usedTraffic = sub.trafficUpload + sub.trafficDownload
    val usedStr = String.format(Locale.US, "%.1fGB", usedTraffic / gbDivisor)
    val totalStr = if (sub.trafficTotal == 0L) "∞" else String.format(Locale.US, "%.1fGB", sub.trafficTotal / gbDivisor)
    val trafficDisplay = "$usedStr/$totalStr"

    val expireMillis = if (sub.trafficExpire > 9999999999L) sub.trafficExpire else sub.trafficExpire * 1000
    val expireDisplay = stringResource(
        R.string.main_expires,
        if (expireMillis > 0L) formatDate(expireMillis) else "∞"
    )

    val announceText = sub.announce ?: ""
    val supportUrl = sub.supportUrl ?: ""

    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.4f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 14.dp)) {
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Шапка сворачивает свой список; стрелка показывает, куда он поедет
                    val chevronRotation by animateFloatAsState(
                        targetValue = if (expanded) 0f else -90f,
                        animationSpec = tween(220),
                        label = "chevronRotation"
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(onClick = onToggleExpanded)
                            .padding(vertical = 2.dp)
                    ) {
                        ChevronDown(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(14.dp)
                                .graphicsLayer { rotationZ = chevronRotation }
                        )
                        Spacer(Modifier.width(8.dp))

                        Column(Modifier.weight(1f)) {
                            Text(
                                text = title, 
                                fontSize = 16.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = updateStatus,
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Обновление и пинг живут в общей стеклянной таблетке.
                    // Слой сюда передавать нельзя: карточка сама пишется в него
                    GlassSurface(
                        shape = GlassCapsuleShape,
                        opaqueness = 0.85f,
                        fallbackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        ) {
                            IconButton(onClick = { onUpdateSubscription(subscription.guid) }, modifier = Modifier.size(28.dp)) {
                                Icon(painterResource(id = R.drawable.ic_restore_24dp), contentDescription = stringResource(R.string.title_sub_update), tint = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.width(10.dp))
                            IconButton(onClick = { onPingProfile(subscription.guid) }, modifier = Modifier.size(28.dp)) {
                                ClockIcon(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(28.dp)) {
                            Icon(painterResource(id = R.drawable.ic_more_vert_24dp), contentDescription = stringResource(R.string.main_menu), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // Меню - отдельное окно, оно уже вне записи слоя, поэтому размытие настоящее
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            shape = GlassMenuShape,
                            containerColor = Color.Transparent,
                            shadowElevation = 0.dp,
                            modifier = Modifier.glassPanel(GlassMenuShape)
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_edit), color = MaterialTheme.colorScheme.onSurface) },
                                onClick = {
                                    showMenu = false
                                    context.startActivity(Intent(context, SubEditActivity::class.java).putExtra("subId", subscription.guid))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    onDeleteSubscription(subscription.guid)
                                }
                            )
                        }
                    }
                }
                
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                    thickness = 1.dp,
                    modifier = Modifier.padding(vertical = 10.dp)
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically, 
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = {
                            val subUrl = sub.url
                            if (!subUrl.isNullOrBlank()) try { uriHandler.openUri(subUrl) } catch(e: Exception) { Toast.makeText(context, R.string.main_link_unavailable, Toast.LENGTH_SHORT).show() }
                            else Toast.makeText(context, R.string.main_sub_no_url, Toast.LENGTH_SHORT).show()
                        }, 
                        modifier = Modifier.size(20.dp)
                    ) {
                        Icon(painterResource(id = R.drawable.ic_about_24dp), contentDescription = "Info", tint = MaterialTheme.colorScheme.primary)
                    }
                    
                    Spacer(Modifier.width(8.dp))
                    
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                        Text(
                            text = trafficDisplay,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                        )
                    }
                    
                    Text(
                        text = expireDisplay, 
                        fontSize = 11.sp, 
                        fontWeight = FontWeight.Bold, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant, 
                        modifier = Modifier.weight(1f), 
                        textAlign = TextAlign.Center
                    )
                    
                    if (supportUrl.isNotBlank()) {
                        IconButton(
                            onClick = { try { uriHandler.openUri(supportUrl) } catch(e: Exception){} }, 
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(painterResource(id = R.drawable.ic_telegram_24dp), contentDescription = "Support", tint = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        Spacer(Modifier.size(20.dp))
                    }
                }
                
                // Полоса заполнения лимита: без лимита её не рисуем - показывать нечего
                if (sub.trafficTotal > 0L) {
                    val usedFraction = (usedTraffic.toFloat() / sub.trafficTotal).coerceIn(0f, 1f)
                    val barFraction by animateFloatAsState(
                        targetValue = usedFraction,
                        animationSpec = tween(600),
                        label = "trafficBar"
                    )
                    val barColor = when {
                        usedFraction >= 0.9f -> MaterialTheme.colorScheme.error
                        usedFraction >= 0.75f -> colorPingSlow
                        else -> MaterialTheme.colorScheme.primary
                    }
                    val barTint by animateColorAsState(barColor, tween(400), label = "trafficBarColor")

                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(barFraction)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(barTint)
                        )
                    }
                }

                if (announceText.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = announceText, 
                        fontSize = 11.sp, 
                        textAlign = TextAlign.Center, 
                        fontWeight = FontWeight.Bold, 
                        color = MaterialTheme.colorScheme.onSurface, 
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                    )
                }
            }
        }
        
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(220)),
            exit = shrinkVertically(animationSpec = tween(220)) + fadeOut(animationSpec = tween(160))
        ) {
        Column(modifier = Modifier.fillMaxWidth()) {
        servers.forEach { serverCache ->
            val isSelected = serverCache.guid == selectedGuid
            
            val finalDesc = remember(serverCache.guid) {
                val desc = getProtocolDescription(context, serverCache.profile, serverCache.guid)
                if (serverCache.profile.configType == com.v2ray.ang.enums.EConfigType.CUSTOM) {
                    "$desc | JSON"
                } else {
                    desc
                }
            }
            
            // Выбранная строка подсвечивается акцентом, нажатие даёт лёгкое проседание
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            val cardScale by animateFloatAsState(
                targetValue = if (pressed) 0.985f else 1f,
                animationSpec = tween(120),
                label = "serverScale"
            )
            val containerColor by animateColorAsState(
                targetValue = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f)
                },
                animationSpec = tween(300),
                label = "serverColor"
            )
            val accentWidth by animateDpAsState(
                targetValue = if (isSelected) 4.dp else 0.dp,
                animationSpec = tween(300),
                label = "serverAccent"
            )

            // Долгое нажатие открывает действия над сервером
            var showServerMenu by remember(serverCache.guid) { mutableStateOf(false) }
            var confirmDelete by remember(serverCache.guid) { mutableStateOf(false) }
            val haptics = LocalHapticFeedback.current

            if (confirmDelete) {
                DeleteConfirmDialog(
                    message = serverCache.profile.remarks,
                    onConfirm = { onAction(MainAction.RemoveServer(serverCache.guid)) },
                    onDismiss = { confirmDelete = false }
                )
            }

            Box {
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = containerColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .scale(cardScale)
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = ripple(),
                        onClick = { onSelectServer(serverCache.guid) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            showServerMenu = true
                        }
                    )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .padding(vertical = 10.dp, horizontal = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .padding(vertical = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(accentWidth)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Spacer(Modifier.width(8.dp))

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                },
                                shape = RoundedCornerShape(12.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        WireframeGlobe(
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(Modifier.weight(1f)) {
                        Text(
                            text = serverCache.profile.remarks ?: stringResource(R.string.main_untitled),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = finalDesc,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    
                    // Пинг подаётся плашкой, чтобы не сливался с описанием
                    val delay = serverCache.testDelayMillis
                    if (delay != 0L) {
                        val pingColor = when {
                            delay < 0L -> MaterialTheme.colorScheme.error
                            delay <= 300L -> Color(0xFF16A34A)
                            else -> Color(0xFFF59E0B)
                        }
                        Text(
                            text = if (delay > 0L) "$delay ms" else stringResource(R.string.main_ping_timeout),
                            style = MaterialTheme.typography.labelMedium,
                            color = pingColor,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(pingColor.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    
                    IconButton(
                        onClick = { onEditServer(serverCache.guid, serverCache.profile) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        ChevronRight(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            DropdownMenu(
                expanded = showServerMenu,
                onDismissRequest = { showServerMenu = false },
                shape = GlassMenuShape,
                containerColor = Color.Transparent,
                shadowElevation = 0.dp,
                modifier = Modifier.glassPanel(GlassMenuShape)
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.main_share_link), color = MaterialTheme.colorScheme.onSurface) },
                    onClick = {
                        showServerMenu = false
                        onAction(MainAction.ShareClipboard(serverCache.guid))
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.main_share_qr), color = MaterialTheme.colorScheme.onSurface) },
                    onClick = {
                        showServerMenu = false
                        onAction(MainAction.ShareQRCode(serverCache.guid))
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showServerMenu = false
                        confirmDelete = true
                    }
                )
            }
            }
        }
        }
        }
    }
}
