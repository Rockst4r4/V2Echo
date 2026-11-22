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
import com.echolicense.vpn.core.LivePingMonitor
import com.echolicense.vpn.core.VpnUiState
import com.echolicense.vpn.data.*
import com.echolicense.vpn.ui.theme.*
import java.util.Locale

private val AiProAccent = Color(0xFFA855F7)

@Composable
fun MainScreen(
    uiState: VpnUiState,
    onToggleLanguage: () -> Unit,
    onScanQrClick: () -> Unit,
    onPowerClick: () -> Unit,
    onAddSubscription: (String, String) -> Unit,
    onSwitchSubscription: (SubscriptionItem) -> Unit,
    onDeleteSubscription: (SubscriptionItem) -> Unit,
    onRefreshSubscription: () -> Unit,
    onSelectServer: (ServerProfile) -> Unit,
    onSmartPingClick: () -> Unit,
    onToggleIranSplit: (Boolean) -> Unit,
    onToggleLanMirrorBypass: (Boolean) -> Unit,
    onToggleAdBlocker: (Boolean) -> Unit,
    onToggleAiPro: () -> Unit,
    onToggleGaming: () -> Unit,
    onToggleAutoMtu: (Boolean) -> Unit,
    onChangeMtu: (Int) -> Unit,
    onApplyReconnect: () -> Unit,
    onSaveManualDns: (String) -> Unit,
    onUpdateMuxConfig: (MuxConfig) -> Unit,
    onDismissAnnouncement: (Int) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenBot: () -> Unit
) {
    val s = remember(uiState.language) { stringsFor(uiState.language) }
    var showSubDialog by remember { mutableStateOf(false) }
    var showServerSheet by remember { mutableStateOf(false) }
    var showDnsDialog by remember { mutableStateOf(false) }
    var showMuxDialog by remember { mutableStateOf(false) }

    val visibleServers = remember(uiState.servers, uiState.aiProOnly, uiState.gamingOnly) {
        when {
            uiState.gamingOnly -> {
                val exact = uiState.servers.filter { it.isGaming() }
                if (exact.isNotEmpty()) exact else uiState.servers.filter { it.protocol == VpnProtocol.WIREGUARD }
            }
            uiState.aiProOnly -> uiState.servers.filter { it.isAiPro() }
            else -> uiState.servers
        }
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
            // 1. نوار بالایی (لوگو + تغییر زبان + اسکن QR + اشتراک‌ها)
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
                        Text("Echolicense", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = uiState.statusMessage,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    LiquidGlassCard(
                        cornerRadius = 14.dp,
                        modifier = Modifier.bounceClick(onClick = onToggleLanguage)
                    ) {
                        Text(
                            text = if (uiState.language == AppLanguage.FA) "EN" else "FA",
                            color = CyanLiquid,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 8.dp)
                        )
                    }

                    LiquidGlassCard(
                        cornerRadius = 14.dp,
                        highlightColor = EmeraldGlow.copy(alpha = 0.7f),
                        modifier = Modifier.bounceClick(onClick = onScanQrClick)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.QrCodeScanner,
                            contentDescription = s.scanQr,
                            tint = EmeraldGlow,
                            modifier = Modifier.padding(8.dp).size(19.dp)
                        )
                    }

                    LiquidGlassCard(
                        cornerRadius = 14.dp,
                        modifier = Modifier.bounceClick { showSubDialog = true }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.AddLink, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(s.subscriptions, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            // 2. بنر داینامیک اطلاعیه و کد تخفیف
            uiState.announcement?.takeIf { it.active }?.let { promo ->
                Spacer(Modifier.height(14.dp))
                DynamicAnnouncementBanner(
                    s = s,
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
                tapHint = s.tapToConnect,
                onClick = onPowerClick
            )

            Spacer(Modifier.height(20.dp))

            // 4. کارت‌های دوگانه AI Pro و Gaming (قرمز مینیمال)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ModeMiniCard(
                    modifier = Modifier.weight(1f),
                    title = "AI Pro",
                    subtitle = "${uiState.servers.count { it.isAiPro() }} srv",
                    icon = Icons.Rounded.AutoAwesome,
                    accent = AiProAccent,
                    enabled = uiState.aiProOnly,
                    badge = if (uiState.aiProOnly) s.active else s.off,
                    onClick = onToggleAiPro
                )
                val gamingCount = uiState.servers.count { it.isGaming() }
                    .let { if (it > 0) it else uiState.servers.count { srv -> srv.protocol == VpnProtocol.WIREGUARD } }
                ModeMiniCard(
                    modifier = Modifier.weight(1f),
                    title = s.gamingTitle,
                    subtitle = "WG • $gamingCount",
                    icon = Icons.Rounded.SportsEsports,
                    accent = GamingRed,
                    enabled = uiState.gamingOnly,
                    badge = if (uiState.gamingOnly) s.active else s.off,
                    onClick = onToggleGaming
                )
            }

            Spacer(Modifier.height(12.dp))

            // 5. انتخاب سرور + دکمه Ping All
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LiquidGlassCard(
                    modifier = Modifier
                        .weight(1f)
                        .bounceClick { showServerSheet = true }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
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
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = uiState.activeServer?.name ?: s.noServerSelected,
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val proto = uiState.activeServer?.protocol?.name ?: s.addSubHint
                                val pingTxt = uiState.activeServer?.pingMs?.takeIf { it > 0 }?.let { " • ${it}ms" } ?: ""
                                Text(
                                    text = "$proto$pingTxt",
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Icon(Icons.Rounded.ChevronLeft, null, tint = Color.White.copy(alpha = 0.7f))
                    }
                }

                LiquidGlassCard(
                    cornerRadius = 22.dp,
                    highlightColor = if (uiState.isPinging) EmeraldGlow else CyanLiquid.copy(alpha = 0.65f),
                    modifier = Modifier.bounceClick { if (!uiState.isPinging) onSmartPingClick() }
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (uiState.isPinging) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = EmeraldGlow,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Speed,
                                contentDescription = s.pingAllBtn,
                                tint = EmeraldGlow,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (uiState.isPinging) s.pingingNow else s.pingAllBtn,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 6. کارت حجم باقی‌مانده
            TrafficQuotaCard(s = s, uiState = uiState, onRefresh = onRefreshSubscription)

            Spacer(Modifier.height(12.dp))

            // 7. سرعت لحظه‌ای و DNS دستی/خودکار
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LiquidGlassCard(modifier = Modifier.weight(1f), cornerRadius = 22.dp) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(s.liveSpeed, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("↓ ${uiState.downloadSpeedKbps} KB/s", color = EmeraldGlow, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text("↑ ${uiState.uploadSpeedKbps} KB/s", color = CyanLiquid, fontSize = 12.sp)
                    }
                }

                LiquidGlassCard(
                    modifier = Modifier
                        .weight(1f)
                        .bounceClick { showDnsDialog = true },
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
                                text = if (uiState.manualDns.isNotBlank()) s.manualDns else s.autoDns,
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 11.sp
                            )
                            Icon(Icons.Rounded.Dns, null, tint = CyanLiquid, modifier = Modifier.size(15.dp))
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (uiState.manualDns.isNotBlank()) uiState.manualDns else (uiState.activeDns?.name ?: s.autoTest),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (uiState.manualDns.isNotBlank()) s.tapToEdit else s.tapForManual,
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 7.5 نوار تست لحظه‌ای پینگ 1.1.1.1 و سنجش کیفیت اینترنت
            LivePingQualityCard(uiState = uiState)

            Spacer(Modifier.height(12.dp))

            // 8. کنترل MTU (خودکار + دستی)
            MtuControlCard(
                s = s,
                autoMtu = uiState.autoMtuEnabled,
                mtu = uiState.mtu,
                isConnected = uiState.connectionState == ConnectionState.CONNECTED,
                onToggleAutoMtu = onToggleAutoMtu,
                onChangeMtu = onChangeMtu,
                onApplyReconnect = onApplyReconnect
            )

            Spacer(Modifier.height(12.dp))

            // 9. تنظیمات: بایپس ایران + AdBlocker + Mux Mode
            LiquidGlassCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 22.dp) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(s.iranBypass, color = Color.White, fontSize = 13.sp)
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
                        Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Text(s.lanMirrorBypassTitle, color = Color.White, fontSize = 13.sp)
                            Text(s.lanMirrorBypassSub, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                        }
                        Switch(
                            checked = uiState.lanMirrorBypassEnabled,
                            onCheckedChange = onToggleLanMirrorBypass,
                            colors = SwitchDefaults.colors(checkedTrackColor = CyanLiquid)
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Text(s.adBlockerTitle, color = Color.White, fontSize = 13.sp)
                            Text(s.adBlockerSub, color = Color.White.copy(alpha = 0.6f), fontSize = 10.sp)
                        }
                        Switch(
                            checked = uiState.adBlockerEnabled,
                            onCheckedChange = onToggleAdBlocker,
                            colors = SwitchDefaults.colors(checkedTrackColor = EmeraldGlow)
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .bounceClick { showMuxDialog = true }
                                .padding(vertical = 4.dp)
                        ) {
                            Text(s.muxMode, color = Color.White, fontSize = 13.sp)
                            Text(
                                text = "Concurrency: ${uiState.muxConfig.concurrency} • XUDP: ${uiState.muxConfig.xudpConcurrency} ⚙",
                                color = CyanLiquid.copy(alpha = 0.85f),
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = uiState.muxConfig.enabled,
                            onCheckedChange = { onUpdateMuxConfig(uiState.muxConfig.copy(enabled = it)) },
                            colors = SwitchDefaults.colors(checkedTrackColor = CyanLiquid)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 10. دکمه ورود به ربات تلگرام @echopress_bot
            LiquidGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(onClick = onOpenBot),
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
                            Icon(Icons.Rounded.Send, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(s.openBotTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(s.openBotSub(uiState.botUsername), color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
                        }
                    }
                    Icon(Icons.Rounded.ChevronLeft, null, tint = Color.White)
                }
            }

            Spacer(Modifier.height(18.dp))
        }
    }

    if (showSubDialog) {
        MultiSubscriptionDialog(
            s = s,
            subscriptions = uiState.subscriptions,
            activeId = uiState.activeSubscriptionId,
            onDismiss = { showSubDialog = false },
            onScanQr = {
                showSubDialog = false
                onScanQrClick()
            },
            onAddNew = { name, input ->
                showSubDialog = false
                onAddSubscription(name, input)
            },
            onSwitch = { item ->
                showSubDialog = false
                onSwitchSubscription(item)
            },
            onDelete = { item -> onDeleteSubscription(item) }
        )
    }

    if (showServerSheet) {
        ServerPickerDialog(
            s = s,
            title = when { uiState.gamingOnly -> s.gamingServers; uiState.aiProOnly -> s.aiProServers; else -> s.selectServer },
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
            onSave = {
                showDnsDialog = false
                onSaveManualDns(it)
            }
        )
    }

    if (showMuxDialog) {
        MuxSettingsDialog(
            current = uiState.muxConfig,
            onDismiss = { showMuxDialog = false },
            onSave = {
                showMuxDialog = false
                onUpdateMuxConfig(it)
            }
        )
    }
}

@Composable
private fun MultiSubscriptionDialog(
    s: AppStrings,
    subscriptions: List<SubscriptionItem>,
    activeId: String?,
    onDismiss: () -> Unit,
    onScanQr: () -> Unit,
    onAddNew: (String, String) -> Unit,
    onSwitch: (SubscriptionItem) -> Unit,
    onDelete: (SubscriptionItem) -> Unit
) {
    var nameInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF13192B),
        title = { Text(s.manageSubsTitle, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(EmeraldGlow.copy(alpha = 0.18f))
                        .border(1.dp, EmeraldGlow.copy(alpha = 0.65f), RoundedCornerShape(14.dp))
                        .bounceClick(onClick = onScanQr)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.QrCodeScanner, null, tint = EmeraldGlow, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(s.scanQr, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                if (subscriptions.isNotEmpty()) {
                    Text(s.savedSubs, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 150.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(subscriptions, key = { it.id }) { sub ->
                            val isActive = sub.id == activeId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isActive) EmeraldGlow.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f))
                                    .border(
                                        1.dp,
                                        if (isActive) EmeraldGlow else Color.Transparent,
                                        RoundedCornerShape(12.dp)
                                    )
                                    .bounceClick { onSwitch(sub) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = sub.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = sub.url,
                                        color = CyanLiquid.copy(alpha = 0.8f),
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { onDelete(sub) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Rounded.Delete, null, tint = DangerRed, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }

                Text(s.addNewSubTitle, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    placeholder = { Text(s.subNamePlaceholder, fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    placeholder = { Text(s.subUrlPlaceholder, fontSize = 12.sp) },
                    minLines = 2,
                    maxLines = 4,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (urlInput.isNotBlank()) {
                    onAddNew(nameInput.trim(), urlInput.trim())
                }
            }) {
                Text(s.addAndLoad, color = EmeraldGlow)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(s.close, color = Color.White.copy(alpha = 0.7f))
            }
        }
    )
}

@Composable
private fun MtuControlCard(
    s: AppStrings,
    autoMtu: Boolean,
    mtu: Int,
    isConnected: Boolean,
    onToggleAutoMtu: (Boolean) -> Unit,
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
                    Text("${s.mtuTitle}: $mtu", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (autoMtu) EmeraldGlow.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.1f))
                            .border(1.dp, if (autoMtu) EmeraldGlow else Color.Transparent, RoundedCornerShape(10.dp))
                            .bounceClick { onToggleAutoMtu(!autoMtu) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(s.auto, color = if (autoMtu) EmeraldGlow else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    if (isConnected) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(CyanLiquid.copy(alpha = 0.25f))
                                .bounceClick(onClick = onApplyReconnect)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(s.apply, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (!autoMtu) {
                Spacer(Modifier.height(10.dp))
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
                                .border(1.dp, if (selected) CyanLiquid else Color.Transparent, RoundedCornerShape(12.dp))
                                .bounceClick { onChangeMtu(preset) }
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
                }
            }
        }
    }
}

@Composable
private fun DynamicAnnouncementBanner(
    s: AppStrings,
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
                .background(Brush.horizontalGradient(listOf(AmberWarn.copy(alpha = 0.22f), AiProAccent.copy(alpha = 0.16f))))
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
                    Text(item.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp).clickable(onClick = onDismiss)
                )
            }

            if (item.message.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(item.message, color = Color.White.copy(alpha = 0.88f), fontSize = 12.sp, lineHeight = 18.sp)
            }

            if (item.promoCode.isNotBlank() || item.buttonText.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (item.promoCode.isNotBlank()) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.Black.copy(alpha = 0.35f))
                                .border(1.dp, AmberWarn.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                .bounceClick {
                                    clipboard.setText(AnnotatedString(item.promoCode))
                                    copied = true
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.ContentCopy, null, tint = AmberWarn, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (copied) "${s.copiedLabel}: ${item.promoCode}" else "${s.promoCodeLabel}: ${item.promoCode}",
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
                                .bounceClick(onClick = onActionClick)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(item.buttonText, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrafficQuotaCard(
    s: AppStrings,
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
                    Text(s.remainingQuota, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (uiState.currentSubUrl.isNotBlank()) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = CyanLiquid,
                            modifier = Modifier.size(18.dp).bounceClick(onClick = onRefresh)
                        )
                    }
                }

                when {
                    sub.totalBytes > 0 -> {
                        val remStr = String.format(Locale.US, "%.2f", sub.remainingGb)
                        val totStr = String.format(Locale.US, "%.1f", sub.totalGb)
                        Text(
                            text = "$remStr / $totStr GB",
                            color = if (sub.isLowTraffic) AmberWarn else EmeraldGlow,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    sub.isUnlimited -> {
                        val usedStr = String.format(Locale.US, "%.2f", sub.usedGb)
                        Text("${s.unlimited} ($usedStr GB)", color = EmeraldGlow, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    else -> {
                        Text(
                            text = if (uiState.currentSubUrl.isNotBlank()) s.tapRefresh else s.noSubAdded,
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
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(8.dp)),
                color = if (sub.isLowTraffic) AmberWarn else EmeraldGlow,
                trackColor = Color.White.copy(alpha = 0.12f)
            )

            sub.remainingDays?.let { days ->
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (days > 0) s.remainingDays(days) else s.expired,
                    color = if (days <= 2) AmberWarn else Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            }

            if (uiState.currentSubUrl.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("🔄 ${s.autoUpdate12h}", color = Color.White.copy(alpha = 0.48f), fontSize = 10.sp)
            }

            if (sub.isLowTraffic) {
                Spacer(Modifier.height(6.dp))
                Text(s.lowTrafficWarn, color = AmberWarn, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun AiProModeCard(
    s: AppStrings,
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
            .bounceClick(onClick = onClick),
        cornerRadius = 22.dp,
        highlightColor = borderColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (enabled) Brush.horizontalGradient(listOf(AiProAccent.copy(alpha = 0.28f), CyanLiquid.copy(alpha = 0.14f)))
                    else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (enabled) AiProAccent.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = if (enabled) Color.White else AiProAccent, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(s.aiProTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        text = if (enabled) s.aiProActiveSub(aiServersCount) else s.aiProIdleSub(aiServersCount),
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
                Text(if (enabled) s.active else s.off, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ExpressGlassPowerButton(
    state: ConnectionState,
    connectedSeconds: Long,
    tapHint: String,
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
                    .background(Brush.verticalGradient(listOf(Color(0x3DFFFFFF), Color(0x17FFFFFF))))
                    .border(2.dp, ringColor, CircleShape)
                    .bounceClick(scaleDown = 0.90f, onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    tint = if (state == ConnectionState.CONNECTED) EmeraldGlow else Color.White,
                    modifier = Modifier.size(56.dp)
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        val timerText = if (state == ConnectionState.CONNECTED) {
            val h = connectedSeconds / 3600
            val m = (connectedSeconds % 3600) / 60
            val sec = connectedSeconds % 60
            String.format(Locale.US, "%02d:%02d:%02d", h, m, sec)
        } else {
            tapHint
        }
        Text(timerText, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ManualDnsDialog(
    currentManualDns: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var dnsInput by remember { mutableStateOf(currentManualDns) }
    val presets = listOf(
        "Auto" to "",
        "Cloudflare" to "1.1.1.1",
        "Google" to "8.8.8.8",
        "Quad9" to "9.9.9.9",
        "AdGuard" to "94.140.14.14",
        "Shecan" to "178.22.122.100"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF13192B),
        title = { Text("DNS (Auto / Manual)", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = dnsInput,
                    onValueChange = { dnsInput = it },
                    placeholder = { Text("1.1.1.1 or https://1.1.1.1/dns-query") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    )
                )
                presets.chunked(3).forEach { rowItems ->
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        rowItems.forEach { (label, value) ->
                            val selected = dnsInput.trim() == value
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) CyanLiquid.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.08f))
                                    .border(1.dp, if (selected) CyanLiquid else Color.Transparent, RoundedCornerShape(10.dp))
                                    .bounceClick { dnsInput = value }
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
            TextButton(onClick = { onSave(dnsInput.trim()) }) { Text("Save", color = EmeraldGlow) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White.copy(alpha = 0.7f)) }
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
        title = { Text("Mux Mode Settings", color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(Triple("Recommended", 8, 16), Triple("Stable", 4, 8), Triple("Fast", 16, 16)).forEach { (title, c, x) ->
                        val active = concurrency == c && xudp == x
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) EmeraldGlow.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f))
                                .border(1.dp, if (active) EmeraldGlow else Color.Transparent, RoundedCornerShape(10.dp))
                                .bounceClick { concurrency = c; xudp = x; udp443 = "reject"; enabled = true }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(title, color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Concurrency:", color = Color.White, fontSize = 12.sp)
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(MuxConfig(enabled, concurrency, xudp, udp443)) }) { Text("Save", color = EmeraldGlow) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = Color.White.copy(alpha = 0.7f)) }
        }
    )
}

@Composable
private fun ServerPickerDialog(
    s: AppStrings,
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White)
                TextButton(onClick = onSmartPing) { Text(s.pingTest, color = CyanLiquid) }
            }
        },
        text = {
            if (servers.isEmpty()) {
                Text(s.noServersFound, color = Color.White.copy(alpha = 0.7f))
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(servers) { srv ->
                        val selected = srv.id == activeId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) EmeraldGlow.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
                                .bounceClick { onSelect(srv) }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(srv.name, color = Color.White, fontWeight = FontWeight.Medium)
                                val tag = if (srv.isAiPro()) "${srv.protocol.name} • AI Pro" else srv.protocol.name
                                Text(tag, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                            }
                            if (srv.pingMs > 0) Text("${srv.pingMs} ms", color = EmeraldGlow, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(s.close, color = Color.White) }
        }
    )
}

@Composable
private fun ModeMiniCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    enabled: Boolean,
    badge: String,
    onClick: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (enabled) accent else accent.copy(alpha = 0.35f),
        animationSpec = tween(300),
        label = "modeBorder"
    )
    LiquidGlassCard(
        modifier = modifier.bounceClick(onClick = onClick),
        cornerRadius = 20.dp,
        highlightColor = borderColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (enabled) accent.copy(alpha = 0.22f) else accent.copy(alpha = 0.06f)
                )
                .padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(if (enabled) accent.copy(alpha = 0.35f) else accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = if (enabled) Color.White else accent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, color = Color.White.copy(alpha = 0.65f), fontSize = 10.sp, maxLines = 1)
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (enabled) accent else Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(badge, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}


@Composable
private fun LivePingQualityCard(uiState: VpnUiState) {
    val isFa = uiState.language == AppLanguage.FA
    val isConnected = uiState.connectionState == ConnectionState.CONNECTED
    val report = LivePingMonitor.evaluateQuality(
        isConnected = isConnected,
        directPingMs = uiState.directPingMs,
        directJitterMs = uiState.directJitterMs,
        vpnPingMs = uiState.vpnPingMs,
        vpnJitterMs = uiState.vpnJitterMs
    )
    val qualityColor = when (report.level) {
        "excellent" -> EmeraldGlow
        "good" -> CyanLiquid
        "fair" -> Color(0xFFF59E0B)
        "poor" -> Color(0xFFFF5A6E)
        else -> Color.White.copy(alpha = 0.55f)
    }

    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        highlightColor = qualityColor.copy(alpha = 0.45f)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isFa) "تست لحظه‌ای پینگ (1.1.1.1)" else "Live Ping & Quality (1.1.1.1)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(qualityColor.copy(alpha = 0.16f))
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (report.score > 0) "${report.score}%" else "--",
                        color = qualityColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // نوار پیشرفت کیفیت اینترنت
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth((report.score / 100f).coerceIn(0.03f, 1f))
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(qualityColor)
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PingMetricMiniBox(
                    modifier = Modifier.weight(1f),
                    title = if (isFa) "اینترنت کاربر (بدون VPN)" else "User ISP (Direct)",
                    pingMs = uiState.directPingMs,
                    jitterMs = uiState.directJitterMs,
                    history = uiState.directPingHistory,
                    accent = CyanLiquid,
                    isFa = isFa
                )
                PingMetricMiniBox(
                    modifier = Modifier.weight(1f),
                    title = if (isFa) "با VPN (1.1.1.1)" else "With VPN (1.1.1.1)",
                    pingMs = if (isConnected) uiState.vpnPingMs else -1,
                    jitterMs = if (isConnected) uiState.vpnJitterMs else -1,
                    history = if (isConnected) uiState.vpnPingHistory else emptyList(),
                    accent = EmeraldGlow,
                    isFa = isFa
                )
            }

            Spacer(Modifier.height(8.dp))

            val deltaText = buildString {
                if (report.deltaPingMs != null) {
                    val sign = if (report.deltaPingMs >= 0) "+" else ""
                    append(if (isFa) "اختلاف پینگ: ${sign}${report.deltaPingMs}ms" else "Delta: ${sign}${report.deltaPingMs}ms")
                    if (report.deltaJitterMs != null) {
                        val jSign = if (report.deltaJitterMs >= 0) "+" else ""
                        append(if (isFa) " • نوسان: ${jSign}${report.deltaJitterMs}ms" else " • Jitter Δ: ${jSign}${report.deltaJitterMs}ms")
                    }
                    append(" — ")
                }
                append(if (isFa) report.labelFa else report.labelEn)
            }

            Text(
                text = deltaText,
                color = qualityColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PingMetricMiniBox(
    modifier: Modifier,
    title: String,
    pingMs: Int,
    jitterMs: Int,
    history: List<Int>,
    accent: Color,
    isFa: Boolean
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .padding(10.dp)
    ) {
        Text(
            text = title,
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = if (pingMs > 0) "${pingMs}ms" else "--",
                color = if (pingMs > 0) accent else Color.White.copy(alpha = 0.45f),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Text(
                text = if (jitterMs >= 0) "${if (isFa) "نوسان" else "Jitter"} ±${jitterMs}ms" else (if (isFa) "نوسان --" else "Jitter --"),
                color = Color.White.copy(alpha = 0.65f),
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(6.dp))
        // نمودار میله‌ای زنده نوسان پینگ
        val bars = history.takeLast(10)
        val maxVal = (bars.maxOrNull() ?: 120).coerceAtLeast(80)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            val pad = (10 - bars.size).coerceAtLeast(0)
            repeat(pad) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                )
            }
            bars.forEach { sample ->
                val frac = (sample.toFloat() / maxVal.toFloat()).coerceIn(0.18f, 1f)
                val barColor = when {
                    sample <= 110 -> accent
                    sample <= 220 -> Color(0xFFF59E0B)
                    else -> Color(0xFFFF5A6E)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height((18f * frac).dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(barColor.copy(alpha = 0.85f))
                )
            }
        }
    }
}
