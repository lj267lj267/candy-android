// SPDX-License-Identifier: MIT
package com.candy.vpn.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the tunnel is doing right now.
 *
 * `Connecting` covers the whole window between starting the core and the
 * server assigning an address: the signalling websocket is up, but the
 * VpnService tunnel does not exist until the address is known.
 */
sealed interface VpnState {

    data object Idle : VpnState

    data class Connecting(val title: String) : VpnState

    data class Connected(
        val title: String,
        val address: String,
        val prefix: Int,
        val routes: List<String>,
        val since: Long,
    ) : VpnState {
        val cidr: String get() = "$address/$prefix"
    }

    data class Failed(val title: String, val reason: String) : VpnState

    val isActive: Boolean get() = this is Connecting || this is Connected
}

/**
 * Process-wide tunnel state. The service writes, the UI observes; keeping it
 * out of the service means the UI survives service restarts and does not need
 * to bind.
 */
object VpnStatus {

    private val _state = MutableStateFlow<VpnState>(VpnState.Idle)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    val current: VpnState get() = _state.value

    internal fun update(state: VpnState) {
        _state.value = state
    }
}
