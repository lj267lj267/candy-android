// SPDX-License-Identifier: MIT
package com.candy.vpn.core

/**
 * Thin Kotlin façade over the vendored candy core (`libcandycore.so`).
 *
 * One native "instance" is a running `candy::client::run()` loop: it keeps a
 * signalling websocket to the server open, punches P2P holes to the other peers
 * and pumps packets between them and the VpnService tunnel. The core retries on
 * its own, so a dropped connection heals without help from the UI.
 */
object CandyNative {

    private const val LIBRARY = "candycore"

    /** Set once after the library is loaded so a second call is a no-op. */
    @Volatile
    private var loaded = false

    /**
     * Loads the native library and binds the native -> Kotlin callback class.
     * Must be called before any other member.
     */
    fun load() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary(LIBRARY)
            nativeInit()
            loaded = true
        }
    }

    val isLoaded: Boolean get() = loaded

    /** Version of the vendored candy core, e.g. "6.1.11". */
    val version: String
        get() = if (loaded) nativeVersion() else "unavailable"

    /**
     * Starts a client instance.
     *
     * @param instanceId identifier used to address the instance later
     * @param configJson candy client configuration; every key the core reads
     *        must be present, see [com.candy.vpn.data.Profile.toCandyJson]
     * @return true when a new instance was created
     */
    fun start(instanceId: String, configJson: String): Boolean =
        loaded && nativeStart(instanceId, configJson)

    /** Stops the instance and blocks until its threads have unwound. */
    fun stop(instanceId: String): Boolean =
        loaded && nativeStop(instanceId)

    fun isRunning(instanceId: String): Boolean =
        loaded && nativeIsRunning(instanceId)

    /** 0=critical .. 8=trace, matching Poco::Message::Priority. */
    fun setLogLevel(level: Int) {
        if (loaded) nativeSetLogLevel(level)
    }

    private external fun nativeInit()

    private external fun nativeVersion(): String

    private external fun nativeSetLogLevel(level: Int)

    private external fun nativeStart(instanceId: String, configJson: String): Boolean

    private external fun nativeStop(instanceId: String): Boolean

    private external fun nativeIsRunning(instanceId: String): Boolean
}
