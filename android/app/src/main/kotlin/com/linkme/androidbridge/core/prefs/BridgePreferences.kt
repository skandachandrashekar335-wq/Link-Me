package com.linkme.androidbridge.core.prefs

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class BridgeAccentTheme(val key: String, val displayName: String) {
    LIQUID_BLUE("liquidBlue", "Electric Blue"),
    CRIMSON("crimson", "Crimson"),
    MINT("mint", "Mint"),
    AMBER("amber", "Amber"),
    ALABASTER("alabaster", "Alabaster");

    companion object {
        fun fromKey(raw: String?): BridgeAccentTheme =
            entries.firstOrNull { it.key == raw } ?: LIQUID_BLUE
    }
}

enum class ClipboardDirection(val key: String, val displayName: String) {
    BIDIRECTIONAL("bidirectional", "Bidirectional"),
    MAC_TO_ANDROID("macToAndroid", "Mac → Android"),
    ANDROID_TO_MAC("androidToMac", "Android → Mac"),
    DISABLED("disabled", "Disabled");

    fun allowsAndroidOutbound(): Boolean = this == BIDIRECTIONAL || this == ANDROID_TO_MAC
    fun allowsAndroidInbound(): Boolean = this == BIDIRECTIONAL || this == MAC_TO_ANDROID

    companion object {
        fun fromKey(raw: String?): ClipboardDirection =
            entries.firstOrNull { it.key == raw } ?: BIDIRECTIONAL
    }
}

data class BridgePreferencesState(
    val accentTheme: BridgeAccentTheme = BridgeAccentTheme.LIQUID_BLUE,
    val compactMode: Boolean = false,
    val showTelemetryFooter: Boolean = true,
    val wifiCooldownSeconds: Int = 15,
    val bleOnlyBatterySaver: Boolean = false,
    val autoAcceptFiles: Boolean = true,
    val clipboardDirection: ClipboardDirection = ClipboardDirection.BIDIRECTIONAL,
    val deviceAlias: String = DEFAULT_DEVICE_ALIAS
) {
    companion object {
        const val MIN_COOLDOWN_SECONDS = 5
        const val MAX_COOLDOWN_SECONDS = 60
        val DEFAULT_DEVICE_ALIAS: String = Build.MODEL?.takeIf { it.isNotBlank() } ?: "AndroidBridge"

        fun clampCooldown(seconds: Int): Int =
            seconds.coerceIn(MIN_COOLDOWN_SECONDS, MAX_COOLDOWN_SECONDS)
    }
}

interface PreferenceKeyValueStore {
    fun getString(key: String, defaultValue: String): String
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun getInt(key: String, defaultValue: Int): Int
    fun putString(key: String, value: String)
    fun putBoolean(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
}

class SharedPreferencesStoreAdapter(
    private val prefs: SharedPreferences
) : PreferenceKeyValueStore {
    override fun getString(key: String, defaultValue: String): String =
        prefs.getString(key, defaultValue) ?: defaultValue

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int): Int =
        prefs.getInt(key, defaultValue)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }
}

class InMemoryPreferenceStore : PreferenceKeyValueStore {
    private val map = linkedMapOf<String, Any>()

    override fun getString(key: String, defaultValue: String): String =
        map[key] as? String ?: defaultValue

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        map[key] as? Boolean ?: defaultValue

    override fun getInt(key: String, defaultValue: Int): Int =
        map[key] as? Int ?: defaultValue

    override fun putString(key: String, value: String) {
        map[key] = value
    }

    override fun putBoolean(key: String, value: Boolean) {
        map[key] = value
    }

    override fun putInt(key: String, value: Int) {
        map[key] = value
    }
}

/**
 * Reactive persisted customization and runtime transport configuration store.
 */
class BridgePreferencesStore(
    private val backend: PreferenceKeyValueStore
) {
    private val _state = MutableStateFlow(loadInitialState())
    val state: StateFlow<BridgePreferencesState> = _state.asStateFlow()

    private fun loadInitialState(): BridgePreferencesState {
        val defaultAlias = BridgePreferencesState.DEFAULT_DEVICE_ALIAS
        return BridgePreferencesState(
            accentTheme = BridgeAccentTheme.fromKey(backend.getString(KEY_ACCENT_THEME, BridgeAccentTheme.LIQUID_BLUE.key)),
            compactMode = backend.getBoolean(KEY_COMPACT_MODE, false),
            showTelemetryFooter = backend.getBoolean(KEY_TELEMETRY_FOOTER, true),
            wifiCooldownSeconds = BridgePreferencesState.clampCooldown(backend.getInt(KEY_WIFI_COOLDOWN, 15)),
            bleOnlyBatterySaver = backend.getBoolean(KEY_BLE_ONLY_SAVER, false),
            autoAcceptFiles = backend.getBoolean(KEY_AUTO_ACCEPT_FILES, true),
            clipboardDirection = ClipboardDirection.fromKey(
                backend.getString(KEY_CLIPBOARD_DIR, ClipboardDirection.BIDIRECTIONAL.key)
            ),
            deviceAlias = backend.getString(KEY_DEVICE_ALIAS, defaultAlias).trim().ifEmpty { defaultAlias }
        )
    }

    fun setAccentTheme(theme: BridgeAccentTheme) {
        backend.putString(KEY_ACCENT_THEME, theme.key)
        _state.update { it.copy(accentTheme = theme) }
    }

    fun setCompactMode(enabled: Boolean) {
        backend.putBoolean(KEY_COMPACT_MODE, enabled)
        _state.update { it.copy(compactMode = enabled) }
    }

    fun setShowTelemetryFooter(enabled: Boolean) {
        backend.putBoolean(KEY_TELEMETRY_FOOTER, enabled)
        _state.update { it.copy(showTelemetryFooter = enabled) }
    }

    fun setWifiCooldownSeconds(seconds: Int) {
        val clamped = BridgePreferencesState.clampCooldown(seconds)
        backend.putInt(KEY_WIFI_COOLDOWN, clamped)
        _state.update { it.copy(wifiCooldownSeconds = clamped) }
    }

    fun setBleOnlyBatterySaver(enabled: Boolean) {
        backend.putBoolean(KEY_BLE_ONLY_SAVER, enabled)
        _state.update { it.copy(bleOnlyBatterySaver = enabled) }
    }

    fun setAutoAcceptFiles(enabled: Boolean) {
        backend.putBoolean(KEY_AUTO_ACCEPT_FILES, enabled)
        _state.update { it.copy(autoAcceptFiles = enabled) }
    }

    fun setClipboardDirection(direction: ClipboardDirection) {
        backend.putString(KEY_CLIPBOARD_DIR, direction.key)
        _state.update { it.copy(clipboardDirection = direction) }
    }

    fun setDeviceAlias(alias: String) {
        val clean = alias.trim().take(48).ifEmpty { BridgePreferencesState.DEFAULT_DEVICE_ALIAS }
        backend.putString(KEY_DEVICE_ALIAS, clean)
        _state.update { it.copy(deviceAlias = clean) }
    }

    companion object {
        private const val PREFS_FILE = "linkme_bridge_prefs"
        private const val KEY_ACCENT_THEME = "accent_theme"
        private const val KEY_COMPACT_MODE = "compact_mode"
        private const val KEY_TELEMETRY_FOOTER = "show_telemetry_footer"
        private const val KEY_WIFI_COOLDOWN = "wifi_cooldown_seconds"
        private const val KEY_BLE_ONLY_SAVER = "ble_only_battery_saver"
        private const val KEY_AUTO_ACCEPT_FILES = "auto_accept_files"
        private const val KEY_CLIPBOARD_DIR = "clipboard_direction"
        private const val KEY_DEVICE_ALIAS = "device_alias"

        @Volatile
        private var instance: BridgePreferencesStore? = null

        fun forContext(context: Context): BridgePreferencesStore {
            return instance ?: synchronized(this) {
                instance ?: BridgePreferencesStore(
                    SharedPreferencesStoreAdapter(
                        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                    )
                ).also { instance = it }
            }
        }
    }
}
