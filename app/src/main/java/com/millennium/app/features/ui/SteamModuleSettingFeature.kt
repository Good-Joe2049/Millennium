package com.millennium.app.features.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.util.IdentityHashMap

/** Module configuration, independent of the SteamDB data panel. */
internal class SteamModuleSettingFeature(
    private val emit: (priority: Int, message: String) -> Unit,
    private val onSteamDbChanged: (Activity, Boolean) -> Unit,
) {
    private val dialogs = IdentityHashMap<Activity, AlertDialog>()

    fun show(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (dialogs[activity]?.isShowing == true) return

        val dialog = AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Millennium 设置")
            .setMultiChoiceItems(
                arrayOf("SteamDB 悬浮面板"),
                booleanArrayOf(SteamModuleSettings.isSteamDbEnabled(activity)),
            ) { _, _, enabled ->
                SteamModuleSettings.setSteamDbEnabled(activity, enabled)
                onSteamDbChanged(activity, enabled)
                emit(Log.INFO, "module setting changed steamDbEnabled=$enabled")
            }
            .setPositiveButton("完成", null)
            .create()
        dialog.setOnDismissListener { dialogs.remove(activity, dialog) }
        dialogs[activity] = dialog
        dialog.show()
        emit(Log.INFO, "module setting opened")
    }

    fun detach(activity: Activity) {
        dialogs.remove(activity)?.dismiss()
    }
}

internal object SteamModuleSettings {
    private const val PREFS = "millennium_module_settings"
    private const val STEAM_DB_ENABLED = "steamdb_floating_panel_enabled"

    fun isSteamDbEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(STEAM_DB_ENABLED, true)

    fun setSteamDbEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putBoolean(STEAM_DB_ENABLED, enabled)
        }
    }
}
