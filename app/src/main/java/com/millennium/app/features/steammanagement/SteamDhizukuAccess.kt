package com.millennium.app.features.steammanagement

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.VersionedPackage
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Process
import android.util.Log
import com.millennium.app.R
import com.millennium.app.core.ModuleLocalization
import com.rosan.dhizuku.api.Dhizuku
import com.rosan.dhizuku.api.DhizukuRequestPermissionListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** Dhizuku owns the remote calls; the client and permission grant belong to Steam's UID. */
internal object SteamDhizukuAccess {
    private const val STEAM_PACKAGE = "com.valvesoftware.android.steam.community"
    private const val DELETE_KEEP_DATA = 1

    enum class Access(val message: Int?) {
        Granted(null),
        Unavailable(R.string.management_dhizuku_unavailable),
        Unsupported(R.string.management_dhizuku_version),
        Denied(R.string.management_dhizuku_denied),
    }

    suspend fun checkAccess(context: Context): Access = withContext(Dispatchers.IO) {
        try {
            when {
                !Dhizuku.init(context.applicationContext) -> Access.Unavailable
                Dhizuku.getVersionCode() < 5 -> Access.Unsupported
                Dhizuku.isPermissionGranted() -> Access.Granted
                else -> Access.Denied
            }
        } catch (error: Exception) {
            Log.w("MillenniumSteamManagement", "Dhizuku connection failed", error)
            Access.Unavailable
        }
    }

    /** Called on the main thread after checkAccess; the listener runs on a Binder thread. */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        Dhizuku.requestPermission(object : DhizukuRequestPermissionListener() {
            override fun onRequestPermission(grantResult: Int) {
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        })
    }

    @SuppressLint("PrivateApi") // The public API cannot pass KEEP_DATA; unsupported platforms fail closed.
    suspend fun uninstallKeepingData(context: Context): Unit = withContext(Dispatchers.IO) {
        val host = context.applicationContext
        check(host.packageName == STEAM_PACKAGE) { "This action is only available inside Steam" }
        val access = checkAccess(host)
        check(access == Access.Granted) {
            ModuleLocalization.text(context, access.message ?: R.string.management_dhizuku_unavailable)
        }

        // Resolve the exact KEEP_DATA-capable API before dispatch. Never fall back to
        // the public uninstall() overload (flags = 0) or to shell commands.
        val remoteInstaller: Any
        val uninstall: Method
        try {
            remoteInstaller = installerForOwner(host)
            uninstall = platformMethod(
                Class.forName("android.content.pm.IPackageInstaller"), "uninstall",
                VersionedPackage::class.java, String::class.java, Int::class.javaPrimitiveType!!,
                IntentSender::class.java, Int::class.javaPrimitiveType!!,
            )
        } catch (error: Exception) {
            throw IllegalStateException(
                ModuleLocalization.text(context, R.string.management_dhizuku_uninstall_unsupported), error,
            )
        }

        val action = "$STEAM_PACKAGE.millennium.UNINSTALL_RESULT.${UUID.randomUUID()}"
        val completion = CompletableDeferred<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == action) completion.complete(intent)
            }
        }
        registerResultReceiver(host, receiver, action)
        var callback: PendingIntent? = null
        try {
            // PackageInstaller fills in the result extras. Restrict this mutable callback
            // to our host package; no manifest receiver or Steam manifest changes needed.
            callback = PendingIntent.getBroadcast(
                host, 0, Intent(action).setPackage(STEAM_PACKAGE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            try {
                uninstall.invoke(
                    remoteInstaller, VersionedPackage(STEAM_PACKAGE, -1),
                    Dhizuku.getOwnerPackageName(), DELETE_KEEP_DATA,
                    callback.intentSender, Process.myUid() / 100000,
                )
            } catch (error: InvocationTargetException) {
                throw error.targetException
            }
            // Successful self-uninstall normally kills Steam before this callback arrives.
            // If Steam remains alive, report the system result without assuming success.
            val result = checkNotNull(withTimeoutOrNull(10.seconds) { completion.await() }) {
                ModuleLocalization.text(context, R.string.management_result_unknown)
            }
            val status = result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            check(status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // Do not launch a regular uninstall confirmation if silent owner access failed.
                ModuleLocalization.text(context, R.string.management_dhizuku_uninstall_unsupported)
            }
            check(status == PackageInstaller.STATUS_SUCCESS) {
                result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.takeIf { it.isNotBlank() }
                    ?: ModuleLocalization.text(context, R.string.management_uninstall_failed)
            }
        } finally {
            callback?.cancel()
            host.unregisterReceiver(receiver)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerResultReceiver(context: Context, receiver: BroadcastReceiver, action: String) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            // ContextCompat's backport requires a permission in the host manifest, which
            // Steam does not declare. The legacy callback uses a fresh unguessable action.
            context.registerReceiver(receiver, IntentFilter(action))
        }
    }

    @SuppressLint("PrivateApi") // Use a separate owner proxy without mutating the host's installer.
    private fun installerForOwner(context: Context): Any {
        val fields = if (Build.VERSION.SDK_INT >= 28) {
            HiddenApiBypass.getInstanceFields(PackageInstaller::class.java)
        } else {
            PackageInstaller::class.java.declaredFields.toList()
        }
        val installer = fields.single { it.name == "mInstaller" }.apply { isAccessible = true }
            .get(context.packageManager.packageInstaller) as IInterface
        // Wrap a separate Binder proxy. Do not replace Steam's shared PackageInstaller.
        return checkNotNull(platformMethod(
            Class.forName($$"android.content.pm.IPackageInstaller$Stub"), "asInterface", IBinder::class.java,
        ).invoke(null, Dhizuku.binderWrapper(installer.asBinder())))
    }

    private fun platformMethod(type: Class<*>, name: String, vararg parameters: Class<*>): Method =
        (if (Build.VERSION.SDK_INT >= 28) {
            HiddenApiBypass.getDeclaredMethod(type, name, *parameters)
        } else {
            type.getDeclaredMethod(name, *parameters)
        }).apply { isAccessible = true }
}
