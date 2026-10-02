// SPDX-License-Identifier: MIT
package com.candy.vpn

import android.app.Application
import com.candy.vpn.core.CandyNative
import com.candy.vpn.data.LogRepository
import com.candy.vpn.data.ProfileStore

class CandyApp : Application() {

    override fun onCreate() {
        super.onCreate()
        ProfileStore.init(this)

        runCatching { CandyNative.load() }
            .onFailure { error ->
                // Nothing works without the core, but a hard crash on start
                // would be worse than a clear message in the log view.
                LogRepository.error("加载内核失败：${error.message}")
            }

        CandyNative.setLogLevel(ProfileStore.settings.value.logLevel)
        LogRepository.info("Candy Android 已启动，内核版本 ${CandyNative.version}")
    }
}
