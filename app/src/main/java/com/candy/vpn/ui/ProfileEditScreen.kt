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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.candy.vpn.data.Cidr
import com.candy.vpn.data.Profile

@Composable
fun ProfileEditScreen(
    initial: Profile?,
    onCancel: () -> Unit,
    onSave: (Profile) -> Unit,
) {
    val base = initial ?: Profile(title = "新配置")

    var title by remember { mutableStateOf(base.title) }
    var websocket by remember { mutableStateOf(base.websocket) }
    var password by remember { mutableStateOf(base.password) }
    var name by remember { mutableStateOf(base.name) }
    var tun by remember { mutableStateOf(base.tun) }
    var stun by remember { mutableStateOf(base.stun) }
    var dns by remember { mutableStateOf(base.dns) }
    var mtu by remember { mutableStateOf(base.mtu.toString()) }
    var discovery by remember { mutableStateOf(base.discovery.toString()) }
    var routeCost by remember { mutableStateOf(base.routeCost.toString()) }
    var port by remember { mutableStateOf(base.port.toString()) }
    var localhost by remember { mutableStateOf(base.localhost) }
    var extraRoutes by remember { mutableStateOf(base.extraRoutes.joinToString(", ")) }

    var showErrors by remember { mutableStateOf(false) }

    val titleError = title.isBlank()
    val websocketError = websocket.isNotBlank() &&
        !websocket.startsWith("ws://") && !websocket.startsWith("wss://")
    val tunError = tun.isNotBlank() && Cidr.parse(tun) == null
    val routeError = Cidr.parseList(extraRoutes).size != extraRoutes.split(',').count { it.isNotBlank() }
    val hasError = titleError || websocketError || tunError || routeError || websocket.isBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle("基本信息")

        Field(
            value = title,
            onValueChange = { title = it },
            label = "名称",
            hint = "仅用于在本机区分配置",
            isError = showErrors && titleError,
            errorText = "名称不能为空",
        )

        Field(
            value = websocket,
            onValueChange = { websocket = it },
            label = "信令地址",
            hint = "例如 wss://server.example.com/ws 或 ws://192.168.1.10:8080/ws",
            isError = showErrors && (websocketError || websocket.isBlank()),
            errorText = if (websocket.isBlank()) "信令地址不能为空" else "必须以 ws:// 或 wss:// 开头",
        )

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("密码 / 预共享密钥") },
            supportingText = { Text("密码用于鉴权与 P2P 加密，服务端不会收到明文") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle("地址")

        Field(
            value = tun,
            onValueChange = { tun = it },
            label = "虚拟地址（可选）",
            hint = "留空则由服务端动态分配；填写固定地址可避免每次变化，例如 192.168.202.10/24",
            isError = showErrors && tunError,
            errorText = "格式应为 IPv4/前缀，例如 192.168.202.10/24",
        )

        Field(
            value = extraRoutes,
            onValueChange = { extraRoutes = it },
            label = "额外路由（可选）",
            hint = "多个用逗号分隔，例如 10.0.0.0/24, 172.16.0.0/16。candy 是虚拟局域网，不会接管默认路由",
            isError = showErrors && routeError,
            errorText = "存在无法解析的网段",
        )

        Field(
            value = dns,
            onValueChange = { dns = it },
            label = "DNS 服务器",
            hint = "多个用逗号分隔，例如 223.5.5.5",
        )

        SectionTitle("穿透与性能")

        Field(
            value = stun,
            onValueChange = { stun = it },
            label = "STUN 地址（可选）",
            hint = "例如 stun://stun.example.com。不填则只能在局域网内直连",
        )

        Field(
            value = localhost,
            onValueChange = { localhost = it },
            label = "本地出口 IP（可选）",
            hint = "留空自动选择第一个物理网卡；多网卡设备可手动指定",
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(
                value = mtu,
                onValueChange = { mtu = it.filter(Char::isDigit) },
                label = "MTU",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            Field(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                label = "本地端口",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field(
                value = discovery,
                onValueChange = { discovery = it.filter(Char::isDigit) },
                label = "发现间隔（秒）",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
            Field(
                value = routeCost,
                onValueChange = { routeCost = it.filter(Char::isDigit) },
                label = "中继开销",
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("参数说明", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "· MTU 默认 1400，移动网络下建议不超过 1400\n" +
                        "· 发现间隔 > 0 时会周期性广播发现包，需要同时配置 STUN\n" +
                        "· 中继开销 > 0 时本机会为其他节点转发流量，手机上建议保持 0\n" +
                        "· 本地端口 0 表示由系统随机分配",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (base.learnedRoutes.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("已学习路由", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = base.learnedRoutes.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = {
                        onSave(base.copy(learnedRoutes = emptyList()))
                    }) { Text("清除") }
                }
            }
        }

        HorizontalDivider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(onClick = onCancel) { Text("取消") }
            Spacer(Modifier.width(12.dp))
            Button(onClick = {
                showErrors = true
                if (hasError || websocket.isBlank()) return@Button
                onSave(
                    base.copy(
                        title = title.trim(),
                        websocket = websocket.trim(),
                        password = password,
                        name = name.trim(),
                        tun = tun.trim(),
                        stun = stun.trim(),
                        dns = dns.trim().ifBlank { "8.8.8.8" },
                        mtu = mtu.toIntOrNull()?.coerceIn(576, 1500) ?: 1400,
                        discovery = discovery.toIntOrNull()?.coerceIn(0, 86400) ?: 0,
                        routeCost = routeCost.toIntOrNull()?.coerceIn(0, 1000) ?: 0,
                        port = port.toIntOrNull()?.coerceIn(0, 65535) ?: 0,
                        localhost = localhost.trim(),
                        extraRoutes = Cidr.parseList(extraRoutes).map { it.toString() },
                    ).ensureVmac(),
                )
            }) { Text(if (initial == null) "创建" else "保存") }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    hint: String? = null,
    isError: Boolean = false,
    errorText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = {
            when {
                isError && errorText != null -> Text(errorText, color = MaterialTheme.colorScheme.error)
                hint != null -> Text(hint)
            }
        },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier,
    )
}
