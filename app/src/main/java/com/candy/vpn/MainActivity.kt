// SPDX-License-Identifier: MIT
package com.candy.vpn

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.candy.vpn.data.LogRepository
import com.candy.vpn.data.Profile
import com.candy.vpn.data.ProfileStore
import com.candy.vpn.ui.CandyTheme
import com.candy.vpn.ui.HomeScreen
import com.candy.vpn.ui.LogsScreen
import com.candy.vpn.ui.ProfileEditScreen
import com.candy.vpn.ui.ProfilesScreen
import com.candy.vpn.ui.SettingsScreen
import com.candy.vpn.vpn.CandyVpnService
import com.candy.vpn.vpn.VpnStatus

class MainActivity : ComponentActivity() {

    /** Set while a connect request waits for the user to grant VPN consent. */
    private var pendingProfileId: String? = null

    /** Set when the user picked a JSON file to import. */
    private var importCallback: ((Int) -> Unit)? = null

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                CandyVpnService.start(this, pendingProfileId)
            } else {
                LogRepository.warn("用户拒绝了 VPN 授权，无法建立隧道")
            }
            pendingProfileId = null
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) LogRepository.warn("未授予通知权限，状态栏不会显示常驻通知")
        }

    private val documentPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val callback = importCallback
            importCallback = null
            if (uri == null) return@registerForActivityResult
            val count = runCatching {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: throw IllegalStateException("无法读取所选文件")
                ProfileStore.importProfiles(text)
            }.getOrElse { error ->
                LogRepository.error("导入失败：${error.message}")
                0
            }
            LogRepository.info("导入了 $count 个配置")
            callback?.invoke(count)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProfileStore.init(this)
        requestNotificationPermissionIfNeeded()

        setContent {
            val settings by ProfileStore.settings.collectAsState()
            CandyTheme(themeMode = settings.themeMode) {
                CandyRoot(
                    onConnect = ::connect,
                    onDisconnect = { CandyVpnService.stop(this) },
                    onExport = ::exportProfiles,
                    onImport = { callback ->
                        importCallback = callback
                        documentPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                )
            }
        }
    }

    /**
     * Android requires explicit user consent before an app may build a tunnel.
     * The grant is remembered, so this dialog only appears once.
     */
    private fun connect(profileId: String?) {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent == null) {
            CandyVpnService.start(this, profileId)
        } else {
            pendingProfileId = profileId
            vpnPermissionLauncher.launch(prepareIntent)
        }
    }

    private fun exportProfiles() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "candy-profiles.json")
            putExtra(Intent.EXTRA_SUBJECT, "candy-profiles.json")
            putExtra(Intent.EXTRA_TEXT, ProfileStore.exportProfiles())
        }
        startActivity(Intent.createChooser(intent, "导出配置"))
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private enum class Screen { Home, Profiles, Logs, Settings, Edit }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CandyRoot(
    onConnect: (String?) -> Unit,
    onDisconnect: () -> Unit,
    onExport: () -> Unit,
    onImport: (callback: (Int) -> Unit) -> Unit,
) {
    val profiles by ProfileStore.profiles.collectAsState()
    val activeId by ProfileStore.activeId.collectAsState()
    val state by VpnStatus.state.collectAsState()

    var screen by remember { mutableStateOf(Screen.Home) }
    var editing by remember { mutableStateOf<Profile?>(null) }

    BackHandler(enabled = screen == Screen.Settings || screen == Screen.Edit) {
        screen = if (screen == Screen.Edit) Screen.Profiles else Screen.Home
    }

    val title = when (screen) {
        Screen.Home -> "Candy"
        Screen.Profiles -> "配置"
        Screen.Logs -> "日志"
        Screen.Settings -> "设置"
        Screen.Edit -> if (editing == null) "新建配置" else "编辑配置"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                actions = {
                    if (screen == Screen.Home) {
                        IconButton(onClick = { screen = Screen.Settings }) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = screen == Screen.Home,
                    onClick = { screen = Screen.Home },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("连接") },
                )
                NavigationBarItem(
                    selected = screen == Screen.Profiles || screen == Screen.Edit,
                    onClick = { screen = Screen.Profiles },
                    icon = { Icon(Icons.Filled.List, contentDescription = null) },
                    label = { Text("配置") },
                )
                NavigationBarItem(
                    selected = screen == Screen.Logs,
                    onClick = { screen = Screen.Logs },
                    icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                    label = { Text("日志") },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (screen) {
                Screen.Home -> HomeScreen(
                    state = state,
                    profiles = profiles,
                    activeId = activeId,
                    onConnect = onConnect,
                    onDisconnect = onDisconnect,
                    onOpenProfiles = { screen = Screen.Profiles },
                    onOpenLogs = { screen = Screen.Logs },
                )

                Screen.Profiles -> ProfilesScreen(
                    profiles = profiles,
                    activeId = activeId,
                    onSelect = { ProfileStore.setActive(it.id) },
                    onCreate = {
                        editing = null
                        screen = Screen.Edit
                    },
                    onEdit = {
                        editing = it
                        screen = Screen.Edit
                    },
                    onDelete = { ProfileStore.delete(it.id) },
                    onExport = onExport,
                    onImport = onImport,
                )

                Screen.Edit -> ProfileEditScreen(
                    initial = editing,
                    onCancel = { screen = Screen.Profiles },
                    onSave = { profile ->
                        ProfileStore.upsert(profile)
                        screen = Screen.Profiles
                    },
                )

                Screen.Logs -> LogsScreen()

                Screen.Settings -> SettingsScreen(onBack = { screen = Screen.Home })
            }
        }
    }
}
