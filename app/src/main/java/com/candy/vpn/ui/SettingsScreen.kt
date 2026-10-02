// SPDX-License-Identifier: MIT
package com.candy.vpn.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.candy.vpn.BuildConfig
import com.candy.vpn.core.CandyNative
import com.candy.vpn.data.AppSettings
import com.candy.vpn.data.LogRepository
import com.candy.vpn.data.ProfileStore
import com.candy.vpn.vpn.CandyVpnService

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings by ProfileStore.settings.collectAsState()
    val profiles by ProfileStore.profiles.collectAsState()
    val context = LocalContext.current

    fun update(block: (AppSettings) -> AppSettings) {
        val next = block(settings)
        ProfileStore.updateSettings(next)
        CandyNative.setLogLevel(next.logLevel)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("启动与运行", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))

                SwitchRow(
                    title = "开机自动连接",
                    subtitle = "重启后自动恢复上次使用的配置",
                    checked = settings.autoConnectOnBoot,
                    onChange = { value -> update { it.copy(autoConnectOnBoot = value) } },
                )

                SwitchRow(
                    title = "保持 CPU 唤醒",
                    subtitle = "长时间后台运行时更稳定，但会增加耗电",
                    checked = settings.holdWakeLock,
                    onChange = { value -> update { it.copy(holdWakeLock = value) } },
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("日志等级", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "等级越低输出越少；排查连接问题时可临时调高。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "错误" to LogRepository.LEVEL_ERROR,
                        "警告" to LogRepository.LEVEL_WARNING,
                        "信息" to LogRepository.LEVEL_INFORMATION,
                        "调试" to LogRepository.LEVEL_DEBUG,
                    ).forEach { (label, level) ->
                        FilterChip(
                            selected = settings.logLevel == level,
                            onClick = { update { it.copy(logLevel = level) } },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("外观", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2).forEach { (label, mode) ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { update { it.copy(themeMode = mode) } },
                            label = { Text(label) },
                        )
                    }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("维护", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "服务端下发过的路由会被记住，以便下次连接时一次性生效。"
                        + "如果换了服务端或网段，可以在这里清除。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        profiles.forEach { profile ->
                            if (profile.learnedRoutes.isNotEmpty()) {
                                ProfileStore.upsert(profile.copy(learnedRoutes = emptyList()))
                            }
                        }
                        LogRepository.info("已清除所有已学习的路由")
                    },
                    enabled = profiles.any { it.learnedRoutes.isNotEmpty() },
                ) { Text("清除已学习路由") }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("关于", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                InfoRow("应用版本", BuildConfig.VERSION_NAME)
                InfoRow("内核版本", CandyNative.version)
                InfoRow("内核来源", "lanthora/candy")
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "本应用把 candy 官方 C++ 客户端内核交叉编译进 APK，"
                        + "隧道由 Android VpnService 提供，协议与服务端完全一致。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()

        OutlinedButton(
            onClick = {
                CandyVpnService.stop(context)
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("断开连接并返回") }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun InfoRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
