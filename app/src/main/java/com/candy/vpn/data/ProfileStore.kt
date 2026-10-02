// SPDX-License-Identifier: MIT
package com.candy.vpn.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists profiles and app settings in SharedPreferences.
 *
 * The store is a process-wide singleton because both the UI and the VpnService
 * need it, and the service may outlive the activity.
 */
object ProfileStore {

    private const val PREFS = "candy_prefs"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE = "active_profile_id"
    private const val KEY_SETTINGS = "settings"

    private lateinit var prefs: SharedPreferences

    private val _profiles = MutableStateFlow<List<Profile>>(emptyList())
    val profiles: StateFlow<List<Profile>> = _profiles.asStateFlow()

    private val _activeId = MutableStateFlow<String?>(null)
    val activeId: StateFlow<String?> = _activeId.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        synchronized(this) {
            if (::prefs.isInitialized) return
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            reload()
        }
    }

    private fun reload() {
        val raw = prefs.getString(KEY_PROFILES, null)
        val loaded = if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(Profile::fromJson) }
            }.getOrElse { emptyList() }
        }

        val active = prefs.getString(KEY_ACTIVE, null)?.takeIf { id -> loaded.any { it.id == id } }
            ?: loaded.firstOrNull()?.id

        _profiles.value = loaded
        _activeId.value = active

        _settings.value = prefs.getString(KEY_SETTINGS, null)?.let { settings ->
            runCatching { AppSettings.fromJson(JSONObject(settings)) }.getOrNull()
        } ?: AppSettings()
    }

    fun activeProfile(): Profile? {
        val id = _activeId.value ?: return null
        return _profiles.value.firstOrNull { it.id == id }
    }

    fun setActive(id: String) {
        _activeId.value = id
        prefs.edit().putString(KEY_ACTIVE, id).apply()
    }

    fun upsert(profile: Profile) {
        val current = _profiles.value
        val index = current.indexOfFirst { it.id == profile.id }
        val updated = if (index >= 0) {
            current.toMutableList().also { it[index] = profile }
        } else {
            current + profile
        }
        _profiles.value = updated
        persistProfiles(updated)
        if (_activeId.value == null) setActive(profile.id)
    }

    fun delete(id: String) {
        val updated = _profiles.value.filterNot { it.id == id }
        _profiles.value = updated
        persistProfiles(updated)
        if (_activeId.value == id) {
            _activeId.value = updated.firstOrNull()?.id
            prefs.edit().putString(KEY_ACTIVE, _activeId.value).apply()
        }
    }

    /** Records the address the server handed out, so the next run can ask for it again. */
    fun rememberAddress(id: String, cidr: String) {
        val profile = _profiles.value.firstOrNull { it.id == id } ?: return
        if (profile.lastAddress == cidr) return
        upsert(profile.copy(lastAddress = cidr))
    }

    /** Records a route pushed by the server so a later connection installs it. */
    fun rememberRoute(id: String, cidr: String): Boolean {
        val profile = _profiles.value.firstOrNull { it.id == id } ?: return false
        if (profile.learnedRoutes.contains(cidr)) return false
        upsert(profile.copy(learnedRoutes = profile.learnedRoutes + cidr))
        return true
    }

    fun clearLearnedRoutes(id: String) {
        val profile = _profiles.value.firstOrNull { it.id == id } ?: return
        upsert(profile.copy(learnedRoutes = emptyList()))
    }

    fun updateSettings(settings: AppSettings) {
        _settings.value = settings
        prefs.edit().putString(KEY_SETTINGS, settings.toJson().toString()).apply()
    }

    fun exportProfiles(): String {
        val array = JSONArray()
        _profiles.value.forEach { array.put(it.toJson()) }
        val root = JSONObject()
        root.put("version", 1)
        root.put("profiles", array)
        return root.toString(2)
    }

    /**
     * Imports profiles, replacing entries with the same id and keeping the rest.
     * @return how many profiles were imported
     */
    fun importProfiles(json: String): Int {
        val root = JSONObject(json)
        val array = root.optJSONArray("profiles") ?: JSONArray(json)
        var count = 0
        for (index in 0 until array.length()) {
            val object0 = array.optJSONObject(index) ?: continue
            upsert(Profile.fromJson(object0))
            count++
        }
        return count
    }

    private fun persistProfiles(profiles: List<Profile>) {
        val array = JSONArray()
        profiles.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply()
    }
}

/** App-wide preferences, separate from per-profile configuration. */
data class AppSettings(
    val autoConnectOnBoot: Boolean = false,
    /** 0=critical .. 8=trace, matching Poco::Message::Priority. */
    val logLevel: Int = 4,
    val holdWakeLock: Boolean = false,
    val themeMode: Int = 0,
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("autoConnectOnBoot", autoConnectOnBoot)
        json.put("logLevel", logLevel)
        json.put("holdWakeLock", holdWakeLock)
        json.put("themeMode", themeMode)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject) = AppSettings(
            autoConnectOnBoot = json.optBoolean("autoConnectOnBoot", false),
            logLevel = json.optInt("logLevel", 4),
            holdWakeLock = json.optBoolean("holdWakeLock", false),
            themeMode = json.optInt("themeMode", 0),
        )
    }
}
