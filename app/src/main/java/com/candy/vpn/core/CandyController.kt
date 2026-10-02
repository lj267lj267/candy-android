// SPDX-License-Identifier: MIT
package com.candy.vpn.core

import com.candy.vpn.data.LogRepository
import com.candy.vpn.vpn.CandyVpnService

/**
 * Entry points the native core calls back into.
 *
 * The names and signatures here are looked up by the JNI bridge in
 * `jvm_bridge.cc` — renaming a method silently breaks the bridge, so keep the
 * two in sync. Every method must tolerate being called at any time, including
 * after the service has gone away.
 */
object CandyController {

    /**
     * Builds and establishes the VpnService tunnel for the address the server
     * assigned, then hands the raw file descriptor to the native core, which
     * takes ownership of it.
     *
     * Called from the core's TUN message thread, so [CandyVpnService] must be
     * prepared to work off the main thread.
     *
     * @return the descriptor, or -1 to tell the core that the tunnel is gone.
     */
    @JvmStatic
    fun establishTun(cidr: String, prefix: Int, mtu: Int): Int {
        val service = CandyVpnService.current ?: return -1
        return service.establishTun(cidr, prefix, mtu)
    }

    /** Notification that the core closed a tunnel descriptor. */
    @JvmStatic
    fun closeTun(fd: Int) {
        CandyVpnService.current?.onTunClosed(fd)
    }

    /**
     * A route pushed by the server. Android does not let us add routes to a
     * tunnel that is already up, so the route is remembered and the tunnel is
     * re-created once the burst of route messages settles.
     *
     * @return true when the caller should expect the tunnel to be re-created.
     */
    @JvmStatic
    fun onRouteAdded(dst: String, prefix: Int, nexthop: String): Boolean {
        val service = CandyVpnService.current ?: return false
        return service.onRouteAdded(dst, prefix, nexthop)
    }

    /** Poco log record, level 0=critical .. 8=trace. */
    @JvmStatic
    fun onNativeLog(level: Int, message: String) {
        LogRepository.append(level, message)
    }
}
