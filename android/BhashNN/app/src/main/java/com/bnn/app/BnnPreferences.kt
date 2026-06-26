package com.bnn.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ── Theme preference ──────────────────────────────────────────────────────────

enum class ThemeOption { SYSTEM, LIGHT, DARK }

object BnnThemePreference {

    private val _theme = MutableStateFlow(ThemeOption.SYSTEM)
    val theme: StateFlow<ThemeOption> = _theme.asStateFlow()

    fun load(context: Context) {
        val saved = context.getSharedPreferences("bnn_prefs", Context.MODE_PRIVATE)
            .getString("theme", ThemeOption.SYSTEM.name)
        _theme.value = runCatching { ThemeOption.valueOf(saved!!) }.getOrDefault(ThemeOption.SYSTEM)
    }

    fun set(option: ThemeOption, context: Context) {
        _theme.value = option
        context.getSharedPreferences("bnn_prefs", Context.MODE_PRIVATE)
            .edit().putString("theme", option.name).apply()
    }
}

// ── App settings ──────────────────────────────────────────────────────────────

object BnnSettings {

    private val _runInBackground = MutableStateFlow(false)
    val runInBackground: StateFlow<Boolean> = _runInBackground.asStateFlow()

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("bnn_prefs", Context.MODE_PRIVATE)
        _runInBackground.value = prefs.getBoolean("run_in_background", false)
    }

    fun setRunInBackground(enabled: Boolean, context: Context) {
        _runInBackground.value = enabled
        context.getSharedPreferences("bnn_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("run_in_background", enabled).apply()

        if (enabled) {
            context.startForegroundService(BnnForegroundService.startIntent(context))
        } else {
            context.stopService(BnnForegroundService.stopIntent(context))
        }
    }
}

// ── Unique Device Identifier for Mesh Routing ─────────────────────────────────

object BnnDeviceIdentifier {
    private var cachedId: String? = null

    fun get(context: Context): String {
        cachedId?.let { return it }
        val prefs = context.getSharedPreferences("bnn_prefs", Context.MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = "BNN_" + java.util.UUID.randomUUID().toString().substring(0, 6).uppercase()
            prefs.edit().putString("device_id", id).apply()
        }
        cachedId = id
        return id
    }
}
