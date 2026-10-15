package com.millennium.app.features.ui.settings

import com.millennium.app.core.ModuleLanguage

import android.content.Context
import androidx.core.content.edit

internal enum class SteamWorkingMode(val key: String) {
    Disabled("disabled"), Shizuku("shizuku"), Root("root"),
}

/** Persistent values used by the module settings page. */
internal object SteamModuleSettings {
    private const val PREFS = "millennium_module_settings"
    private const val STEAM_DB_ENABLED = "steamdb_floating_panel_enabled"
    private const val LANGUAGE = "module_language"
    private const val PRIVILEGED_MODE = "module_privileged_mode"
    private const val WORKING_MODE = "module_working_mode"
    private const val LIQUID_GLASS_ENABLED = "module_setting_liquid_glass_enabled"
    private const val STEAM_BOTTOM_GLASS_ENABLED = "steam_bottom_glass_enabled"

    fun isSteamBottomGlassEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(STEAM_BOTTOM_GLASS_ENABLED, false)

    fun setSteamBottomGlassEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(STEAM_BOTTOM_GLASS_ENABLED, enabled) }
    }

    fun isSteamDbEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(STEAM_DB_ENABLED, true)

    fun setSteamDbEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(STEAM_DB_ENABLED, enabled)
        }
    }

    fun isLiquidGlassEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(LIQUID_GLASS_ENABLED, false)

    fun setLiquidGlassEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(LIQUID_GLASS_ENABLED, enabled)
        }
    }
    fun language(context: Context): ModuleLanguage = ModuleLanguage.fromTag(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(LANGUAGE, null),
    )

    fun setLanguage(context: Context, language: ModuleLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(LANGUAGE, language.tag)
        }
    }

    fun workingMode(context: Context): SteamWorkingMode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(WORKING_MODE)) {
            return SteamWorkingMode.entries.firstOrNull { it.key == prefs.getString(WORKING_MODE, null) }
                ?: SteamWorkingMode.Disabled
        }
        return if (prefs.getBoolean(PRIVILEGED_MODE, false)) SteamWorkingMode.Shizuku else SteamWorkingMode.Disabled
    }

    fun setWorkingMode(context: Context, mode: SteamWorkingMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(WORKING_MODE, mode.key)
            remove(PRIVILEGED_MODE)
        }
    }
}
