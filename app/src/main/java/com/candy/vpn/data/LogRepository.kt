// SPDX-License-Identifier: MIT
package com.candy.vpn.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ring buffer of everything the candy core logged.
 *
 * The core logs through Poco, and the JNI bridge forwards each record here, so
 * the log view shows exactly what the desktop client would print.
 */
object LogRepository {

    const val LEVEL_FATAL = 1
    const val LEVEL_CRITICAL = 2
    const val LEVEL_ERROR = 3
    const val LEVEL_WARNING = 4
    const val LEVEL_NOTICE = 5
    const val LEVEL_INFORMATION = 6
    const val LEVEL_DEBUG = 7
    const val LEVEL_TRACE = 8

    private const val MAX_ENTRIES = 2000

    data class Entry(val timeMs: Long, val level: Int, val message: String) {
        val isProblem: Boolean get() = level <= LEVEL_ERROR
        val label: String
            get() = when (level) {
                0, LEVEL_FATAL -> "FATAL"
                LEVEL_CRITICAL -> "CRIT"
                LEVEL_ERROR -> "ERROR"
                LEVEL_WARNING -> "WARN"
                LEVEL_NOTICE -> "NOTE"
                LEVEL_INFORMATION -> "INFO"
                LEVEL_DEBUG -> "DEBUG"
                else -> "TRACE"
            }
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    /** Called from native threads, so this has to stay cheap and lock-free-ish. */
    fun append(level: Int, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, message)
        synchronized(this) {
            val current = _entries.value
            val updated = if (current.size >= MAX_ENTRIES) {
                current.subList(current.size - MAX_ENTRIES + 1, current.size) + entry
            } else {
                current + entry
            }
            _entries.value = updated
        }
    }

    /** Convenience for events raised by the app itself rather than the core. */
    fun info(message: String) = append(LEVEL_INFORMATION, message)

    fun warn(message: String) = append(LEVEL_WARNING, message)

    fun error(message: String) = append(LEVEL_ERROR, message)

    fun clear() {
        synchronized(this) { _entries.value = emptyList() }
    }

    fun dump(): String = _entries.value.joinToString("\n") { entry ->
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(entry.timeMs))
        "$time [${entry.label}] ${entry.message}"
    }
}
