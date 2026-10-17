package com.millennium.app.features.ui.settings

import com.millennium.app.core.ModuleLanguage

import android.content.Context
import androidx.core.content.edit

internal enum class SteamWorkingMode(val key: String) {
    Disabled("disabled"), Shizuku("shizuku"), Dhizuku("dhizuku"), Root("root"),
}

internal enum class SteamGuardDefaultPage(val routeName: String) {
    GuardCode("CodeFallback"), ScanQrCode("GuardHome"),
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
    private const val STEAM_GUARD_DEFAULT_PAGE = "steam_guard_default_page"
    private const val STEAM_GUARD_NEXT_CODE_ENABLED = "steam_guard_next_code_enabled"
    private const val STEAM_LIBRARY_PINCH_ZOOM_ENABLED = "steam_library_pinch_zoom_enabled"

    fun isSteamLibraryPinchZoomEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Preserve the existing library behavior when upgrading to this setting.
            .getBoolean(STEAM_LIBRARY_PINCH_ZOOM_ENABLED, true)

    fun setSteamLibraryPinchZoomEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(STEAM_LIBRARY_PINCH_ZOOM_ENABLED, enabled)
        }
    }

    fun isSteamBottomGlassEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(STEAM_BOTTOM_GLASS_ENABLED, false)

    fun setSteamBottomGlassEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(STEAM_BOTTOM_GLASS_ENABLED, enabled) }
    }

    fun steamGuardDefaultPage(context: Context): SteamGuardDefaultPage {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(STEAM_GUARD_DEFAULT_PAGE, null)
        return SteamGuardDefaultPage.entries.firstOrNull { it.routeName == value }
            ?: SteamGuardDefaultPage.ScanQrCode
    }

    fun setSteamGuardDefaultPage(context: Context, page: SteamGuardDefaultPage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(STEAM_GUARD_DEFAULT_PAGE, page.routeName)
        }
    }

    fun isSteamGuardNextCodeEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(STEAM_GUARD_NEXT_CODE_ENABLED, false)

    fun setSteamGuardNextCodeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(STEAM_GUARD_NEXT_CODE_ENABLED, enabled)
        }
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
