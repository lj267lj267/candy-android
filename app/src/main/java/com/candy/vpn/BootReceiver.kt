// SPDX-License-Identifier: MIT
package com.candy.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.candy.vpn.data.LogRepository
import com.candy.vpn.data.ProfileStore
import com.candy.vpn.vpn.CandyVpnService

/**
 * Brings the tunnel back up after a reboot, when the user asked for it.
 *
 * Receiving ACTION_BOOT_COMPLETED is one of the documented exemptions from the
 * Android 12+ background foreground-service start restriction, so this works
 * without the user having to open the app first.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        ProfileStore.init(context)
        if (!ProfileStore.settings.value.autoConnectOnBoot) return
        if (ProfileStore.activeProfile() == null) return

        LogRepository.info("开机自启：正在恢复上次的连接")
        runCatching { CandyVpnService.start(context) }
            .onFailure { LogRepository.warn("开机自启失败：${it.message}") }
    }
}
