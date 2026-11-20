package com.echolicense.vpn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echolicense.vpn.core.VpnUiState
import com.echolicense.vpn.data.ConnectionState
import com.echolicense.vpn.data.ServerProfile
import com.echolicense.vpn.ui.theme.*
import java.util.Locale

@Composable
fun MainScreen(
    uiState: VpnUiState,
    onPowerClick: () -> Unit,
    onImportInput: (String) -> Unit,
    onSelectServer: (ServerProfile) -> Unit,
    onSmartPingClick: () -> Unit,
    onToggleIranSplit: (Boolean) -> Unit,
    onToggleFragment: (Boolean) -> Unit
) {
    var showImportDialog by remember { mutableStateOf(false) }
    var showServerSheet by remember { mutableStateOf(false) }

    LiquidAmbientBackground(state = uiState.connectionState) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                            fontSize = 12.sp
                        )
                    }
                }

                LiquidGlassCard(
                    cornerRadius = 16.dp,
                    modifier = Modifier.clickable { showImportDialog = true }
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

            Spacer(Modifier.height(26.dp))

            ExpressGlassPowerButton(
                state = uiState.connectionState,
                connectedSeconds = uiState.connectedSeconds,
                onClick = onPowerClick
            )

            Spacer(Modifier.height(24.dp))

            LiquidGlassCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showServerSheet = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Public, null, tint = CyanLiquid)
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
                                text = "$proto$pingTxt",
                                color = Color.White.copy(alpha = 0.65f),
                                fontSize = 12.sp
                            )
                        }
                    }
                    Icon(Icons.Rounded.ChevronLeft, null, tint = Color.White.copy(alpha = 0.7f))
                }
            }

            Spacer(Modifier.height(14.dp))
            TrafficQuotaCard(uiState)
            Spacer(Modifier.height(14.dp))

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

                LiquidGlassCard(modifier = Modifier.weight(1f), cornerRadius = 22.dp) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("DNS خودکار", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = uiState.activeDns?.name ?: "تست خودکار",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = uiState.activeDns?.let { "${it.ipv4} (${it.latencyMs}ms)" } ?: "Failover فعال",
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

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
                        Text("حالت ضد اختلال (TLS Fragment)", color = Color.White, fontSize = 13.sp)
                        Switch(
                            checked = uiState.fragmentEnabled,
                            onCheckedChange = onToggleFragment,
                            colors = SwitchDefaults.colors(checkedTrackColor = CyanLiquid)
                        )
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        ImportSubscriptionDialog(
            onDismiss = { showImportDialog = false },
            onConfirm = { input ->
                showImportDialog = false
                onImportInput(input)
            }
        )
    }

    if (showServerSheet) {
        ServerPickerDialog(
            servers = uiState.servers,
            activeId = uiState.activeServer?.id,
            onSelect = {
                onSelectServer(it)
                showServerSheet = false
            },
            onSmartPing = onSmartPingClick,
            onDismiss = { showServerSheet = false }
        )
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
                    .size(188.dp)
                    .scale(if (state == ConnectionState.DISCONNECTED) 1f else pulseScale)
                    .clip(CircleShape)
                    .background(ringColor.copy(alpha = 0.14f))
                    .border(1.5.dp, ringColor.copy(alpha = 0.45f), CircleShape)
            )
            Box(
                modifier = Modifier
                    .size(148.dp)
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
                    modifier = Modifier.size(62.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))
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
private fun TrafficQuotaCard(uiState: VpnUiState) {
    val sub = uiState.subscriptionInfo
    val borderAccent = if (sub.isLowTraffic) AmberWarn else GlassBorderTop

    LiquidGlassCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 22.dp,
        highlightColor = borderAccent
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("حجم باقی‌مانده اشتراک", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (sub.totalBytes > 0) {
                    val remStr = String.format(Locale.US, "%.2f", sub.remainingGb)
                    val totStr = String.format(Locale.US, "%.1f", sub.totalGb)
                    Text(
                        text = "$remStr از $totStr GB",
                        color = if (sub.isLowTraffic) AmberWarn else EmeraldGlow,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                } else {
                    Text("نامشخص", color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { if (sub.totalBytes > 0) (1f - sub.usageProgress) else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(8.dp)),
                color = if (sub.isLowTraffic) AmberWarn else EmeraldGlow,
                trackColor = Color.White.copy(alpha = 0.12f)
            )

            if (sub.isLowTraffic) {
                Spacer(Modifier.height(8.dp))
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
private fun ImportSubscriptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF13192B),
        title = { Text("افزودن لینک اشتراک یا کانفیگ", color = Color.White) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("لینک ساب 3x-ui، لینک vless:// یا متن کامل کانفیگ WireGuard...") },
                minLines = 3,
                maxLines = 6,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { if (input.isNotBlank()) onConfirm(input.trim()) }) {
                Text("ثبت و دریافت", color = EmeraldGlow)
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
private fun ServerPickerDialog(
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
                Text("انتخاب سرور", color = Color.White)
                TextButton(onClick = onSmartPing) {
                    Text("⚡ تست پینگ", color = CyanLiquid)
                }
            }
        },
        text = {
            if (servers.isEmpty()) {
                Text("هنوز سروری اضافه نشده است.", color = Color.White.copy(alpha = 0.7f))
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
                                Text(srv.protocol.name, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
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
