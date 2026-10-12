package com.millennium.app.features.steamguard.export

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.millennium.app.R
import com.millennium.app.core.ModuleLocalization
import com.millennium.app.features.steamguard.data.SteamGuardAccount

internal object SteamGuardAuthenticatorExport {
    fun open(context: Context, account: SteamGuardAccount, format: SteamGuardQrFormat) {
        val intent = Intent(Intent.ACTION_VIEW, format.createUri(account))
            .setPackage(format.packageName)
        // Launch directly: package visibility queries can hide apps from the Steam process.
        val error = try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            R.string.guard_app_unavailable
        } catch (_: SecurityException) {
            R.string.guard_app_open_failed
        }
        Toast.makeText(
            context,
            ModuleLocalization.text(context, error, format.label),
            Toast.LENGTH_LONG,
        ).show()
    }
}
