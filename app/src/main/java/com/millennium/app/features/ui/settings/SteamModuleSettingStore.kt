package com.millennium.app.features.ui.settings

import android.content.Context
import androidx.core.content.edit

/** Persistent values used by the module settings page. */
internal object SteamModuleSettings {
    private const val PREFS = "millennium_module_settings"
    private const val STEAM_DB_ENABLED = "steamdb_floating_panel_enabled"
    private const val LIQUID_GLASS_ENABLED = "module_setting_liquid_glass_enabled"

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
}
