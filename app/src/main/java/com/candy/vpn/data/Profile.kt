// SPDX-License-Identifier: MIT
package com.candy.vpn.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A `address/prefix` pair, both the user facing form and the parsed form. */
data class Cidr(val address: String, val prefix: Int) {
    override fun toString(): String = "$address/$prefix"

    companion object {
        /** Accepts "10.0.0.0/24" and returns null when it is not a valid IPv4 CIDR. */
        fun parse(text: String): Cidr? {
            val trimmed = text.trim()
            val slash = trimmed.indexOf('/')
            if (slash <= 0) return null
            val address = trimmed.substring(0, slash)
            val prefix = trimmed.substring(slash + 1).toIntOrNull() ?: return null
            if (prefix !in 0..32) return null
            if (!isIPv4(address)) return null
            return Cidr(address, prefix)
        }

        fun parseList(text: String): List<Cidr> =
            text.split(',', '\n', ' ')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { parse(it) }

        fun isIPv4(text: String): Boolean {
            val parts = text.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } && part.toInt() in 0..255
            }
        }
    }
}

/**
 * Everything needed to bring one candy client instance up.
 *
 * Note that candy is a *virtual LAN*, not an internet VPN: the tunnel only
 * carries traffic for the candy subnet and whatever extra routes the server
 * pushes (SD-WAN) or the user declares. There is deliberately no default route.
 */
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Default",
    /** Signalling endpoint, e.g. `wss://server.example.com/ws`. */
    val websocket: String = "",
    /** Pre-shared key, also used to derive the P2P encryption keys. */
    val password: String = "",
    /** Interface name suffix passed to the core; also the instance id. */
    val name: String = "",
    /** Static address, or blank to let the server hand one out (DHCP). */
    val tun: String = "",
    /** STUN endpoint for NAT traversal, e.g. `stun://stun.example.com`. */
    val stun: String = "",
    val mtu: Int = 1400,
    /** Seconds between discovery broadcasts; 0 disables them. */
    val discovery: Int = 0,
    /** Routing cost used when advertising routes; 0 disables relaying. */
    val routeCost: Int = 0,
    /** Fixed local UDP port for P2P; 0 lets the OS pick one. */
    val port: Int = 0,
    /** Local IPv4 used for P2P; blank auto-detects from the first real interface. */
    val localhost: String = "",
    /** DNS servers pushed into the tunnel, comma separated. */
    val dns: String = "8.8.8.8",
    /**
     * Virtual MAC identifying this client to the server. Stable per profile so
     * the server keeps handing out the same address.
     */
    val vmac: String = "",
    /** Last address assigned by the server, replayed on reconnect. */
    val lastAddress: String = "",
    /** Routes the user declared, applied to the tunnel up front. */
    val extraRoutes: List<String> = emptyList(),
    /** Routes learned from the server and persisted for the next connection. */
    val learnedRoutes: List<String> = emptyList(),
    /** When true, only [allowedApps] are routed through the tunnel. */
    val perAppEnabled: Boolean = false,
    val allowedApps: Set<String> = emptySet(),
    val disallowedApps: Set<String> = emptySet(),
) {

    /**
     * Identifier the core knows this instance by. It is also the TUN interface
     * name suffix on desktop, so it has to be sanitised, and [toCandyJson] must
     * agree with it or the core will refuse to start.
     */
    val instanceId: String get() = safeName()

    val routes: List<Cidr>
        get() = (extraRoutes + learnedRoutes).mapNotNull { Cidr.parse(it) }.distinct()

    val dnsServers: List<String>
        get() = dns.split(',', ' ').map { it.trim() }.filter { Cidr.isIPv4(it) }

    /**
     * The instance id has to be unique per running client and is also used as
     * the TUN interface name suffix, so sanitise it.
     */
    private fun safeName(): String {
        val cleaned = name.trim().ifEmpty { "default" }.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '-' }
        return cleaned.joinToString("")
    }

    /** Generates a fresh virtual MAC (16 random bytes as hex) when missing. */
    fun ensureVmac(): Profile {
        if (vmac.length == 32 && vmac.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return this
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        val hex = bytes.joinToString("") { "%02x".format(it) }
        return copy(vmac = hex)
    }

    /**
     * The configuration object the core expects. Every key is emitted because
     * `candy::client::run()` reads them unconditionally.
     */
    fun toCandyJson(): String {
        val json = JSONObject()
        json.put("name", safeName())
        json.put("password", password)
        json.put("websocket", websocket)
        json.put("tun", tun.trim())
        json.put("vmac", vmac)
        json.put("expt", lastAddress.ifBlank { "0.0.0.0/0" })
        json.put("stun", stun.trim())
        json.put("localhost", localhost.trim())
        json.put("discovery", discovery.coerceIn(0, 86400))
        json.put("route", routeCost.coerceIn(0, 1000))
        json.put("mtu", mtu.coerceIn(576, 1500))
        json.put("port", port.coerceIn(0, 65535))
        return json.toString()
    }

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("title", title)
        json.put("websocket", websocket)
        json.put("password", password)
        json.put("name", name)
        json.put("tun", tun)
        json.put("stun", stun)
        json.put("mtu", mtu)
        json.put("discovery", discovery)
        json.put("routeCost", routeCost)
        json.put("port", port)
        json.put("localhost", localhost)
        json.put("dns", dns)
        json.put("vmac", vmac)
        json.put("lastAddress", lastAddress)
        json.put("extraRoutes", JSONArray(extraRoutes))
        json.put("learnedRoutes", JSONArray(learnedRoutes))
        json.put("perAppEnabled", perAppEnabled)
        json.put("allowedApps", JSONArray(allowedApps.toList()))
        json.put("disallowedApps", JSONArray(disallowedApps.toList()))
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): Profile = Profile(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            title = json.optString("title", "Default"),
            websocket = json.optString("websocket", ""),
            password = json.optString("password", ""),
            name = json.optString("name", ""),
            tun = json.optString("tun", ""),
            stun = json.optString("stun", ""),
            mtu = json.optInt("mtu", 1400),
            discovery = json.optInt("discovery", 0),
            routeCost = json.optInt("routeCost", 0),
            port = json.optInt("port", 0),
            localhost = json.optString("localhost", ""),
            dns = json.optString("dns", "8.8.8.8"),
            vmac = json.optString("vmac", ""),
            lastAddress = json.optString("lastAddress", ""),
            extraRoutes = json.optJSONArray("extraRoutes").toStringList(),
            learnedRoutes = json.optJSONArray("learnedRoutes").toStringList(),
            perAppEnabled = json.optBoolean("perAppEnabled", false),
            allowedApps = json.optJSONArray("allowedApps").toStringList().toSet(),
            disallowedApps = json.optJSONArray("disallowedApps").toStringList().toSet(),
        )

        private fun JSONArray?.toStringList(): List<String> {
            if (this == null) return emptyList()
            return (0 until length()).mapNotNull { optString(it).takeIf { value -> value.isNotBlank() } }
        }
    }
}
