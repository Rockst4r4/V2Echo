package com.echolicense.vpn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echolicense.vpn.core.VpnUiState
import com.echolicense.vpn.data.ConnectionState
import com.echolicense.vpn.data.DynamicAnnouncement
import com.echolicense.vpn.data.MuxConfig
import com.echolicense.vpn.data.ServerProfile
import com.echolicense.vpn.ui.theme.*
import java.util.Locale

private val AiProAccent = Color(0xFFA855F7)

@Composable
fun MainScreen(
    uiState: VpnUiState,
    onPowerClick: () -> Unit,
    onImportInput: (String) -> Unit,
    onRefreshSubscription: () -> Unit,
    onDeleteSubscription: () -> Unit,
    onSelectServer: (ServerProfile) -> Unit,
    onSmartPingClick: () -> Unit,
    onToggleIranSplit: (Boolean) -> Unit,
    onToggleAiPro: () -> Unit,
    onChangeMtu: (Int) -> Unit,
    onApplyReconnect: () -> Unit,
    onSaveManualDns: (String) -> Unit,
    onUpdateMuxConfig: (MuxConfig) -> Unit,
    onDismissAnnouncement: (Int) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenBot: () -> Unit
) {
    var showSubDialog by remember { mutableStateOf(false) }
    var showServerSheet by remember { mutableStateOf(false) }
    var showDnsDialog by remember { mutableStateOf(false) }
    var showMuxDialog by remember { mutableStateOf(false) }

    val visibleServers = remember(uiState.servers, uiState.aiProOnly) {
        if (uiState.aiProOnly) uiState.servers.filter { it.isAiPro() } else uiState.servers
    }

    LiquidAmbientBackground(state = uiState.connectionState) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. نوار بالایی + مدیریت اشتراک
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = if (uiState.connectionState == ConnectionState.CONNECTED) EmeraldGlow else CyanLiquid,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Echolicense",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = uiState.statusMessage,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                LiquidGlassCard(
                    cornerRadius = 16.dp,
                    modifier = Modifier.clickable { showSubDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.AddLink, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("اشتراک", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // 2. بنر داینامیک اطلاعیه و کد تخفیف
            uiState.announcement?.takeIf { it.active }?.let { promo ->
                Spacer(Modifier.height(14.dp))
                DynamicAnnouncementBanner(
                    item = promo,
                    onActionClick = { if (promo.actionUrl.isNotBlank()) onOpenUrl(promo.actionUrl) else onOpenBot() },
                    onDismiss = { onDismissAnnouncement(promo.id) }
                )
            }

            Spacer(Modifier.height(20.dp))

            // 3. دکمه پاور مرکزی
            ExpressGlassPowerButton(
                state = uiState.connectionState,
                connectedSeconds = uiState.connectedSeconds,
                onClick = onPowerClick
            )

            Spacer(Modifier.height(20.dp))

            // 4. حالت اختصاصی AI Pro
            AiProModeCard(
                enabled = uiState.aiProOnly,
                aiServersCount = uiState.servers.count { it.isAiPro() },
                onClick = onToggleAiPro
            )

            Spacer(Modifier.height(12.dp))

            // 5. انتخاب سرور
            LiquidGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showServerSheet = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (uiState.aiProOnly) Icons.Rounded.AutoAwesome else Icons.Rounded.Public,
                                contentDescription = null,
                                tint = if (uiState.aiProOnly) AiProAccent else CyanLiquid
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = uiState.activeServer?.name ?: "سروری انتخاب نشده",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val proto = uiState.activeServer?.protocol?.name ?: "لینک ساب یا WireGuard اضافه کنید"
                            val pingTxt = uiState.activeServer?.pingMs?.takeIf { it > 0 }?.let { " • ${it}ms" } ?: ""
                            Text(
                                text = "$proto • Full TUN$pingTxt",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 12.sp
                            )
                        }
                    }
                    Icon(Icons.Rounded.ChevronLeft, null, tint = Color.White.copy(alpha = 0.7f))
                }
            }

            Spacer(Modifier.height(12.dp))

            // 6. کارت حجم باقی‌مانده + دکمه رفرش دستی
            TrafficQuotaCard(
                uiState = uiState,
                onRefresh = onRefreshSubscription
            )

            Spacer(Modifier.height(12.dp))

            // 7. کارت سرعت لحظه‌ای و DNS (قابل کلیک برای تنظیم دستی DNS)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LiquidGlassCard(modifier = Modifier.weight(1f), cornerRadius = 22.dp) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("سرعت لحظه‌ای", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "↓ ${uiState.downloadSpeedKbps} KB/s",
                            color = EmeraldGlow,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "↑ ${uiState.uploadSpeedKbps} KB/s",
                            color = CyanLiquid,
                            fontSize = 12.sp
                        )
                    }
                }

                LiquidGlassCard(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showDnsDialog = true },
                    cornerRadius = 22.dp,
                    highlightColor = if (uiState.manualDns.isNotBlank()) CyanLiquid else GlassBorderTop
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (uiState.manualDns.isNotBlank()) "DNS دستی" else "DNS خودکار",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 11.sp
                            )
                            Icon(Icons.Rounded.Dns, null, tint = CyanLiquid, modifier = Modifier.size(15.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (uiState.manualDns.isNotBlank()) {
                                uiState.manualDns
                            } else {
                                uiState.activeDns?.name ?: "تست خودکار"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (uiState.manualDns.isNotBlank()) "برای ویرایش لمس کنید" else "لمس جهت تنظیم دستی",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 8. کنترل MTU
            MtuControlCard(
                mtu = uiState.mtu,
                isConnected = uiState.connectionState == ConnectionState.CONNECTED,
                onChangeMtu = onChangeMtu,
                onApplyReconnect = onApplyReconnect
            )

            Spacer(Modifier.height(12.dp))

            // 9. تنظیمات پیشرفته: بایپس ایران + Mux Mode با پارامترهای پیشنهادی
            LiquidGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("بایپس خودکار اپ‌ها و سایت‌های ایران", color = Color.White, fontSize = 13.sp)
                        Switch(
                            checked = uiState.iranSplitEnabled,
                            onCheckedChange = onToggleIranSplit,
                            colors = SwitchDefaults.colors(checkedTrackColor = EmeraldGlow)
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showMuxDialog = true }
                                .padding(vertical = 4.dp)
                        ) {
                            Column {
                                Text("حالت Mux Mode (مالتی‌پلکس)", color = Color.White, fontSize = 13.sp)
                                Text(
                                    text = "Concurrency: ${uiState.muxConfig.concurrency} • XUDP: ${uiState.muxConfig.xudpConcurrency} • تنظیمات ⚙",
                                    color = CyanLiquid.copy(alpha = 0.85f),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Switch(
                            checked = uiState.muxConfig.enabled,
                            onCheckedChange = { enabled ->
                                onUpdateMuxConfig(uiState.muxConfig.copy(enabled = enabled))
                            },
                            colors = SwitchDefaults.colors(checkedTrackColor = CyanLiquid)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 10. دکمه ورود به ربات تلگرام @echopress_bot در پایین صفحه
            LiquidGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenBot),
                cornerRadius = 22.dp,
                highlightColor = CyanLiquid.copy(alpha = 0.7f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.horizontalGradient(
                                listOf(CyanLiquid.copy(alpha = 0.18f), EmeraldGlow.copy(alpha = 0.12f))
                            )
                        )
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(CyanLiquid.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Send, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "ورود به ربات تلگرام اکولایسنس",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "@${uiState.botUsername} • تمدید، خرید و پشتیبانی",
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 12.sp
                            )
                        }
                    }
                    Icon(Icons.Rounded.ChevronLeft, null, tint = Color.White)
                }
            }

            Spacer(Modifier.height(18.dp))
        }
    }

    if (showSubDialog) {
        SubscriptionManagerDialog(
            currentSubUrl = uiState.currentSubUrl,
            serversCount = uiState.servers.size,
            onDismiss = { showSubDialog = false },
            onAddNew = { input ->
                showSubDialog = false
                onImportInput(input)
            },
            onRefresh = {
                showSubDialog = false
                onRefreshSubscription()
            },
            onDeleteCurrent = {
                showSubDialog = false
                onDeleteSubscription()
            }
        )
    }

    if (showServerSheet) {
        ServerPickerDialog(
            title = if (uiState.aiProOnly) "سرورهای AI Pro" else "انتخاب سرور",
            servers = visibleServers,
            activeId = uiState.activeServer?.id,
            onSelect = {
                onSelectServer(it)
                showServerSheet = false
            },
            onSmartPing = onSmartPingClick,
            onDismiss = { showServerSheet = false }
        )
    }

    if (showDnsDialog) {
        ManualDnsDialog(
            currentManualDns = uiState.manualDns,
            onDismiss = { showDnsDialog = false },
            onSave = { newDns ->
                showDnsDialog = false
                onSaveManualDns(newDns)
            }
        )
    }

    if (showMuxDialog) {
        MuxSettingsDialog(
            current = uiState.muxConfig,
            onDismiss = { showMuxDialog = false },
            onSave = { updated ->
                showMuxDialog = false
                onUpdateMuxConfig(updated)
            }
        )
    }
}

@Composable
private fun DynamicAnnouncementBanner(
    item: DynamicAnnouncement,
    onActionClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        highlightColor = AmberWarn
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(AmberWarn.copy(alpha = 0.22f), AiProAccent.copy(alpha = 0.16f))
                    )
                )
                .padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.Campaign, null, tint = AmberWarn, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = item.title.ifBlank { "اطلاعیه ویژه" },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "بستن",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(onClick = onDismiss)
                )
            }

            if (item.message.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.message,
                    color = Color.White.copy(alpha = 0.88f),
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }

            if (item.promoCode.isNotBlank() || item.buttonText.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (item.promoCode.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.35f))
                                .border(1.dp, AmberWarn.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                .clickable {
                                    clipboard.setText(AnnotatedString(item.promoCode))
                                    copied = true
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.ContentCopy, null, tint = AmberWarn, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (copied) "کپی شد: ${item.promoCode}" else "کد تخفیف: ${item.promoCode}",
                                color = AmberWarn,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (item.buttonText.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(EmeraldGlow.copy(alpha = 0.28f))
                                .border(1.dp, EmeraldGlow, RoundedCornerShape(12.dp))
                                .clickable(onClick = onActionClick)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = item.buttonText,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrafficQuotaCard(
    uiState: VpnUiState,
    onRefresh: () -> Unit
) {
    val sub = uiState.subscriptionInfo
    val borderAccent = if (sub.isLowTraffic) AmberWarn else GlassBorderTop

    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        highlightColor = borderAccent
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("حجم باقی‌مانده اشتراک", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (uiState.currentSubUrl.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "بروزرسانی حجم",
                            tint = CyanLiquid,
                            modifier = Modifier
                                .size(18.dp)
                                .clickable(onClick = onRefresh)
                        )
                    }
                }

                when {
                    sub.totalBytes > 0 -> {
                        val remStr = String.format(Locale.US, "%.2f", sub.remainingGb)
                        val totStr = String.format(Locale.US, "%.1f", sub.totalGb)
                        Text(
                            text = "$remStr از $totStr GB",
                            color = if (sub.isLowTraffic) AmberWarn else EmeraldGlow,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    sub.isUnlimited -> {
                        val usedStr = String.format(Locale.US, "%.2f", sub.usedGb)
                        Text(
                            text = "نامحدود (مصرف $usedStr GB)",
                            color = EmeraldGlow,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                    else -> {
                        Text(
                            text = if (uiState.currentSubUrl.isNotBlank()) "بروزرسانی بزنید" else "اشتراک ثبت نشده",
                            color = Color.White.copy(alpha = 0.55f),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = {
                    when {
                        sub.totalBytes > 0 -> (1f - sub.usageProgress)
                        sub.isUnlimited -> 1f
                        else -> 0f
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(8.dp)),
                color = if (sub.isLowTraffic) AmberWarn else EmeraldGlow,
                trackColor = Color.White.copy(alpha = 0.12f)
            )

            sub.remainingDays?.let { days ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (days > 0) "⏳ زمان باقی‌مانده: $days روز" else "🔴 مهلت اشتراک به پایان رسیده است",
                    color = if (days <= 2) AmberWarn else Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            }

            if (sub.isLowTraffic) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "⚠️ حجم اشتراک شما کمتر از ۵ گیگابایت است و در حال اتمام می‌باشد.",
                    color = AmberWarn,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun SubscriptionManagerDialog(
    currentSubUrl: String,
    serversCount: Int,
    onDismiss: () -> Unit,
    onAddNew: (String) -> Unit,
    onRefresh: () -> Unit,
    onDeleteCurrent: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF13192B),
        title = { Text("مدیریت اشتراک و کانفیگ", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (currentSubUrl.isNotBlank() || serversCount > 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "اشتراک فعلی ($serversCount سرور):",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        if (currentSubUrl.isNotBlank()) {
                            Text(
                                text = currentSubUrl,
                                color = CyanLiquid,
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (currentSubUrl.isNotBlank()) {
                                OutlinedButton(
                                    onClick = onRefresh,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(vertical = 6.dp)
                                ) {
                                    Icon(Icons.Rounded.Refresh, null, tint = EmeraldGlow, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("بروزرسانی", color = EmeraldGlow, fontSize = 12.sp)
                                }
                            }
                            OutlinedButton(
                                onClick = onDeleteCurrent,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(vertical = 6.dp)
                            ) {
                                Icon(Icons.Rounded.Delete, null, tint = DangerRed, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("حذف اشتراک", color = DangerRed, fontSize = 12.sp)
                            }
                        }
                    }
                }

                Text("افزودن اشتراک جدید یا کانفیگ:", color = Color.White, fontSize = 13.sp)
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("لینک ساب، vless:// یا کانفیگ WireGuard...") },
                    minLines = 3,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (input.isNotBlank()) onAddNew(input.trim()) }) {
                Text("ثبت و دریافت", color = EmeraldGlow)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("بستن", color = Color.White.copy(alpha = 0.7f))
            }
        }
    )
}

@Composable
private fun ManualDnsDialog(
    currentManualDns: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var dnsInput by remember { mutableStateOf(currentManualDns) }
    val presets = listOf(
        "خودکار" to "",
        "Cloudflare" to "1.1.1.1",
        "Google" to "8.8.8.8",
        "Quad9" to "9.9.9.9",
        "AdGuard" to "94.140.14.14",
        "شکن" to "178.22.122.100"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF13192B),
        title = { Text("تنظیم DNS (خودکار / دستی)", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "برای حالت خودکار فیلد را خالی بگذارید، یا IPv4 / آدرس DoH دلخواه وارد کنید:",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = dnsInput,
                    onValueChange = { dnsInput = it },
                    placeholder = { Text("مثلاً 1.1.1.1 یا https://1.1.1.1/dns-query") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )

                presets.chunked(3).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowItems.forEach { (label, value) ->
                            val selected = dnsInput.trim() == value
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) CyanLiquid.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, if (selected) CyanLiquid else Color.Transparent, RoundedCornerShape(10.dp))
                                    .clickable { dnsInput = value }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = Color.White, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(dnsInput.trim()) }) {
                Text("ذخیره و اعمال", color = EmeraldGlow)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("انصراف", color = Color.White.copy(alpha = 0.7f))
            }
        }
    )
}

@Composable
private fun MuxSettingsDialog(
    current: MuxConfig,
    onDismiss: () -> Unit,
    onSave: (MuxConfig) -> Unit
) {
    var enabled by remember { mutableStateOf(current.enabled) }
    var concurrency by remember { mutableStateOf(current.concurrency) }
    var xudp by remember { mutableStateOf(current.xudpConcurrency) }
    var udp443 by remember { mutableStateOf(current.xudpProxyUDP443) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF13192B),
        title = { Text("تنظیمات Mux Mode", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "پروفایل‌های پیشنهادی (Mux چند اتصال همزمان را در یک تانل ادغام می‌کند و تاخیر را کاهش می‌دهد):",
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 12.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        Triple("پیشنهادی", 8, 16),
                        Triple("پایدار", 4, 8),
                        Triple("پرسرعت", 16, 16)
                    ).forEach { (title, c, x) ->
                        val active = concurrency == c && xudp == x
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) EmeraldGlow.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f))
                                .border(1.dp, if (active) EmeraldGlow else Color.Transparent, RoundedCornerShape(10.dp))
                                .clickable {
                                    concurrency = c
                                    xudp = x
                                    udp443 = "reject"
                                    enabled = true
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Concurrency (تعداد اتصال):", color = Color.White, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { concurrency = (concurrency - 2).coerceAtLeast(2) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Rounded.Remove, null, tint = Color.White)
                        }
                        Text("$concurrency", color = EmeraldGlow, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { concurrency = (concurrency + 2).coerceAtMost(32) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Rounded.Add, null, tint = Color.White)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("XUDP Concurrency:", color = Color.White, fontSize = 12.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { xudp = (xudp - 2).coerceAtLeast(2) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Rounded.Remove, null, tint = Color.White)
                        }
                        Text("$xudp", color = CyanLiquid, fontWeight = FontWeight.Bold)
                        IconButton(onClick = { xudp = (xudp + 2).coerceAtMost(32) }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Rounded.Add, null, tint = Color.White)
                        }
                    }
                }

                Text("سیاست UDP پورت 443 (QUIC):", color = Color.White, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("reject" to "Reject (پیشنهادی)", "allow" to "Allow", "skip" to "Skip").forEach { (k, label) ->
                        val active = udp443 == k
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) CyanLiquid.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f))
                                .clickable { udp443 = k }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(MuxConfig(enabled, concurrency, xudp, udp443)) }) {
                Text("ذخیره", color = EmeraldGlow)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("بستن", color = Color.White.copy(alpha = 0.7f))
            }
        }
    )
}

@Composable
private fun AiProModeCard(
    enabled: Boolean,
    aiServersCount: Int,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (enabled) AiProAccent else GlassBorderTop,
        animationSpec = tween(350),
        label = "aiBorder"
    )
    LiquidGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        cornerRadius = 22.dp,
        highlightColor = borderColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (enabled) {
                        Brush.horizontalGradient(
                            listOf(AiProAccent.copy(alpha = 0.28f), CyanLiquid.copy(alpha = 0.14f))
                        )
                    } else {
                        Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                    }
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (enabled) AiProAccent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = "AI Pro",
                        tint = if (enabled) Color.White else AiProAccent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "حالت اختصاصی AI Pro",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = if (enabled) {
                            "متصل انحصاری به سرورهای AI Pro ($aiServersCount سرور)"
                        } else {
                            "اتصال سریع فقط به سرورهای هوش مصنوعی ($aiServersCount سرور)"
                        },
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 11.sp
                    )
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (enabled) AiProAccent else Color.White.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = if (enabled) "فعال" else "خاموش",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MtuControlCard(
    mtu: Int,
    isConnected: Boolean,
    onChangeMtu: (Int) -> Unit,
    onApplyReconnect: () -> Unit
) {
    val presets = listOf(1280, 1360, 1420, 1500)
    LiquidGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Tune, null, tint = CyanLiquid, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("تنظیم MTU تونل", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onChangeMtu((mtu - 20).coerceAtLeast(1200)) },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Rounded.Remove, "کاهش MTU", tint = Color.White)
                    }
                    Text(
                        text = "$mtu",
                        color = EmeraldGlow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    IconButton(
                        onClick = { onChangeMtu((mtu + 20).coerceAtMost(1500)) },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Rounded.Add, "افزایش MTU", tint = Color.White)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                presets.forEach { preset ->
                    val selected = mtu == preset
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) CyanLiquid.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f))
                            .border(
                                width = 1.dp,
                                color = if (selected) CyanLiquid else Color.Transparent,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .clickable { onChangeMtu(preset) }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$preset",
                            color = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }

                if (isConnected) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(EmeraldGlow.copy(alpha = 0.25f))
                            .border(1.dp, EmeraldGlow, RoundedCornerShape(12.dp))
                            .clickable(onClick = onApplyReconnect)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("اعمال", color = EmeraldGlow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpressGlassPowerButton(
    state: ConnectionState,
    connectedSeconds: Long,
    onClick: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infinite.animateFloat(
        initialValue = 1f,
        targetValue = if (state == ConnectionState.CONNECTING) 1.12f else 1.04f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "scale"
    )
    val ringColor by animateColorAsState(
        targetValue = when (state) {
            ConnectionState.CONNECTED -> EmeraldGlow
            ConnectionState.CONNECTING -> CyanLiquid
            ConnectionState.ERROR -> DangerRed
            ConnectionState.DISCONNECTED -> Color.White.copy(alpha = 0.35f)
        },
        animationSpec = tween(500),
        label = "ring"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(174.dp)
                    .scale(if (state == ConnectionState.DISCONNECTED) 1f else pulseScale)
                    .clip(CircleShape)
                    .background(ringColor.copy(alpha = 0.14f))
                    .border(1.5.dp, ringColor.copy(alpha = 0.45f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(138.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0x3DFFFFFF), Color(0x17FFFFFF))
                        )
                    )
                    .border(2.dp, ringColor, CircleShape)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = "اتصال",
                    tint = if (state == ConnectionState.CONNECTED) EmeraldGlow else Color.White,
                    modifier = Modifier.size(56.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        val timerText = if (state == ConnectionState.CONNECTED) {
            val h = connectedSeconds / 3600
            val m = (connectedSeconds % 3600) / 60
            val s = connectedSeconds % 60
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            "برای اتصال لمس کنید"
        }
        Text(
            text = timerText,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ServerPickerDialog(
    title: String,
    servers: List<ServerProfile>,
    activeId: String?,
    onSelect: (ServerProfile) -> Unit,
    onSmartPing: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF13192B),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, color = Color.White)
                TextButton(onClick = onSmartPing) {
                    Text("⚡ تست پینگ", color = CyanLiquid)
                }
            }
        },
        text = {
            if (servers.isEmpty()) {
                Text("سروری در این دسته یافت نشد.", color = Color.White.copy(alpha = 0.7f))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(servers) { srv ->
                        val selected = srv.id == activeId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) EmeraldGlow.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
                                .clickable { onSelect(srv) }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(srv.name, color = Color.White, fontWeight = FontWeight.Medium)
                                val tag = if (srv.isAiPro()) "${srv.protocol.name} • AI Pro" else srv.protocol.name
                                Text(tag, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                            }
                            if (srv.pingMs > 0) {
                                Text("${srv.pingMs} ms", color = EmeraldGlow, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("بستن", color = Color.White) }
        }
    )
}
