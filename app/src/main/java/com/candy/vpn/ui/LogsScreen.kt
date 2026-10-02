// SPDX-License-Identifier: MIT
package com.candy.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.candy.vpn.data.LogRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
fun LogsScreen() {
    val all by LogRepository.entries.collectAsState()
    var minimumLevel by remember { mutableIntStateOf(LogRepository.LEVEL_TRACE) }
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    // Poco levels count *down* towards the severe end, so "higher than X" means
    // a numerically smaller value.
    val filtered = remember(all, minimumLevel) {
        all.filter { it.level <= minimumLevel }
    }

    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LevelChip("全部", LogRepository.LEVEL_TRACE, minimumLevel) { minimumLevel = it }
            LevelChip("信息", LogRepository.LEVEL_INFORMATION, minimumLevel) { minimumLevel = it }
            LevelChip("警告", LogRepository.LEVEL_WARNING, minimumLevel) { minimumLevel = it }
            LevelChip("错误", LogRepository.LEVEL_ERROR, minimumLevel) { minimumLevel = it }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                text = "${filtered.size} / ${all.size} 条",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
            Column(Modifier.weight(1f)) {}
            TextButton(onClick = {
                if (all.isNotEmpty()) clipboard.setText(AnnotatedString(LogRepository.dump()))
            }) { Text("复制") }
            TextButton(onClick = { LogRepository.clear() }) { Text("清空") }
        }

        if (filtered.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text("暂无日志", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "连接后这里会显示内核的完整输出，与桌面端一致。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                itemsIndexed(filtered) { _, entry ->
                    Text(
                        text = "${TIME_FORMAT.format(Date(entry.timeMs))} [${entry.label}] ${entry.message}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            entry.level <= LogRepository.LEVEL_ERROR -> MaterialTheme.colorScheme.error
                            entry.level == LogRepository.LEVEL_WARNING -> MaterialTheme.colorScheme.tertiary
                            entry.level >= LogRepository.LEVEL_DEBUG -> MaterialTheme.colorScheme.outline
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelChip(
    label: String,
    level: Int,
    current: Int,
    onSelect: (Int) -> Unit,
) {
    FilterChip(
        selected = current == level,
        onClick = { onSelect(level) },
        label = { Text(label) },
    )
}
