// SPDX-License-Identifier: MIT
package com.candy.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.candy.vpn.data.LogRepository
import com.candy.vpn.data.Profile
import com.candy.vpn.vpn.VpnState

@Composable
fun HomeScreen(
    state: VpnState,
    profiles: List<Profile>,
    activeId: String?,
    onConnect: (String?) -> Unit,
    onDisconnect: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenLogs: () -> Unit,
) {
    val active = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    val logs by LogRepository.entries.collectAsState()

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusCard(state = state, activeProfile = active)

        ConnectButton(
            state = state,
            enabled = active != null,
            onConnect = { onConnect(active?.id) },
            onDisconnect = onDisconnect,
        )

        if (active == null) {
            EmptyProfilesCard(onOpenProfiles = onOpenProfiles)
        } else {
            ProfileSelector(
                profiles = profiles,
                active = active,
                onSelect = { onConnect(it.id) },
                onManage = onOpenProfiles,
            )
        }

        if (state is VpnState.Connected) {
            RoutesCard(state)
        }

        RecentLogsCard(
            entries = logs.takeLast(4).reversed(),
            onOpenLogs = onOpenLogs,
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatusCard(state: VpnState, activeProfile: Profile?) {
    val (label, tint) = when (state) {
        is VpnState.Idle -> "未连接" to MaterialTheme.colorScheme.outline
        is VpnState.Connecting -> "连接中…" to MaterialTheme.colorScheme.tertiary
        is VpnState.Connected -> "已连接" to MaterialTheme.colorScheme.primary
        is VpnState.Failed -> "连接失败" to MaterialTheme.colorScheme.error
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(tint),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(12.dp))

            when (state) {
                is VpnState.Connected -> {
                    KeyValue("虚拟地址", state.cidr)
                    KeyValue("配置", state.title)
                    KeyValue("已连接", formatDuration(System.currentTimeMillis() - state.since))
                }

                is VpnState.Connecting -> {
                    KeyValue("配置", state.title)
                    Text(
                        text = "正在等待服务端分配地址…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                is VpnState.Failed -> {
                    KeyValue("配置", state.title.ifBlank { "-" })
                    Text(
                        text = state.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                else -> {
                    Text(
                        text = activeProfile?.let { "当前配置：${it.title}" } ?: "尚未创建任何配置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (activeProfile != null) {
                        KeyValue("信令地址", activeProfile.websocket.ifBlank { "-" })
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
    ) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(84.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ConnectButton(
    state: VpnState,
    enabled: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val connected = state is VpnState.Connected || state is VpnState.Connecting
    val label = if (connected) "断开" else "连接"
    val container = if (connected) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primary
    }
    val content = if (connected) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimary
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled) { if (connected) onDisconnect() else onConnect() },
        color = if (enabled) container else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                color = if (enabled) content else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun EmptyProfilesCard(onOpenProfiles: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("还没有配置", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = "先添加一个 candy 服务端地址和密码才能连接。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onOpenProfiles) { Text("去添加配置") }
        }
    }
}

@Composable
private fun ProfileSelector(
    profiles: List<Profile>,
    active: Profile,
    onSelect: (Profile) -> Unit,
    onManage: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("配置", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(active.title, style = MaterialTheme.typography.titleSmall)
            }
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "切换配置")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            profiles.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(profile.title) },
                    onClick = {
                        expanded = false
                        onSelect(profile)
                    },
                )
            }
        }
        TextButton(onClick = onManage, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)) {
            Text("管理配置")
        }
    }
}

@Composable
private fun RoutesCard(state: VpnState.Connected) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("隧道路由", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.cidr + "（本机）",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.routes.isEmpty()) {
                Text(
                    text = "暂无额外路由",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.routes.forEach { route ->
                    Text(
                        text = route,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentLogsCard(entries: List<LogRepository.Entry>, onOpenLogs: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("最近日志", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onOpenLogs) { Text("全部") }
            }
            if (entries.isEmpty()) {
                Text(
                    text = "暂无日志",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                entries.forEach { entry ->
                    Text(
                        text = "[${entry.label}] ${entry.message}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        fontFamily = FontFamily.Monospace,
                        color = if (entry.isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d 小时 %d 分".format(hours, minutes) else "%d 分 %d 秒".format(minutes, seconds)
}
