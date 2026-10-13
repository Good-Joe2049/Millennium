package com.millennium.app.features.steammanagement

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.millennium.app.R
import com.millennium.app.features.ui.settings.SteamModuleSettings
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.sui.Sui

/** Authorization belongs to Steam's UID, including when Millennium is embedded by NPatch. */
internal object SteamPrivilegeAccess {
    private const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    private val main = Handler(Looper.getMainLooper())
    private var application: Application? = null
    private var requestSequence = 4100
    private var pendingCode: Int? = null
    private var connectionGeneration = 0
    private var connecting = false
    private var suiAttempted = false
    private var focusLostRequest: Int? = null

    var enabled by mutableStateOf(false)
        private set
    var ready by mutableStateOf(false)
        private set
    var requesting by mutableStateOf(false)
        private set
    var message by mutableStateOf<Int?>(null)
        private set

    fun initialize(host: Context) {
        if (application != null) {
            refresh()
            return
        }
        application = host.applicationContext as Application
        enabled = SteamModuleSettings.isPrivilegedModeEnabled(host)
        Shizuku.addBinderReceivedListenerSticky {
            connecting = false
            onConnected()
        }
        Shizuku.addBinderDeadListener {
            connecting = false
            suiAttempted = false
            connectionGeneration++
            ready = false
            cancelRequest()
            message = R.string.management_service_unavailable
        }
        Shizuku.addRequestPermissionResultListener { code, result ->
            if (code == pendingCode) {
                cancelRequest()
                if (result == PackageManager.PERMISSION_GRANTED && hasPermission()) {
                    saveMode(true)
                    ready = true
                    message = null
                } else {
                    saveMode(false)
                    message = R.string.management_permission_denied
                }
            }
        }
        if (enabled) connect()
    }

    fun selectMode(usePrivilege: Boolean) {
        if (application == null) return
        cancelRequest()
        message = null
        if (!usePrivilege) {
            saveMode(false)
            return
        }
        pendingCode = ++requestSequence
        requesting = true
        // Never optimistically select or save Shizuku(Sui).
        connect()
    }

    fun cancelRequest() {
        pendingCode = null
        focusLostRequest = null
        requesting = false
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        val code = pendingCode ?: return
        if (!hasFocus) {
            focusLostRequest = code
        } else if (focusLostRequest == code) {
            // Some permission UIs dismiss without sending a result. Reconcile on return,
            // allowing the ordinary permission callback to arrive first.
            main.postDelayed({
                if (pendingCode == code && focusLostRequest == code) {
                    val granted = hasPermission()
                    cancelRequest()
                    saveMode(granted)
                    ready = granted
                    message = if (granted) null else R.string.management_permission_denied
                }
            }, 300)
        }
    }

    fun refresh() {
        if (application == null) return
        if (binderReady()) {
            ready = enabled && hasPermission()
            if (enabled && !ready) {
                saveMode(false)
                message = R.string.management_permission_denied
            }
        } else {
            ready = false
            if (enabled && !connecting) connect()
        }
    }

    private fun saveMode(value: Boolean) {
        enabled = value
        if (!value) ready = false
        application?.let { SteamModuleSettings.setPrivilegedModeEnabled(it, value) }
    }

    private fun binderReady(): Boolean = runCatching {
        Shizuku.pingBinder() && Shizuku.getVersion() >= 11
    }.getOrDefault(false)

    private fun hasPermission(): Boolean = runCatching {
        binderReady() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED &&
                // SDK 13.1.5 caches successful grants; also ask the server about revocation.
                IShizukuService.Stub.asInterface(Shizuku.getBinder()).checkSelfPermission() &&
                Shizuku.getUid() in intArrayOf(0, 2000)
    }.getOrDefault(false)

    private fun onConnected() {
        if (!binderReady()) return
        message = null
        ready = enabled && hasPermission()
        val code = pendingCode
        if (code == null) {
            if (enabled && !ready) {
                saveMode(false)
                message = R.string.management_permission_denied
            }
            return
        }
        if (hasPermission()) {
            cancelRequest()
            saveMode(true)
            ready = true
        } else {
            try {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    cancelRequest()
                    message = R.string.management_permission_denied
                } else {
                    Shizuku.requestPermission(code)
                }
            } catch (error: Exception) {
                failConnection(error)
            }
        }
    }

    private fun connect() {
        if (binderReady()) {
            onConnected()
            return
        }
        if (connecting) return
        val host = application ?: return
        connecting = true
        val generation = ++connectionGeneration
        try {
            if (!suiAttempted) {
                suiAttempted = true
                if (runCatching { Sui.init(host.packageName) }.getOrDefault(false)) {
                    scheduleConnectionTimeout(generation)
                    return
                }
            }
            // Steam has no ShizukuProvider. Use Shizuku's existing rish Binder handshake;
            // only this private callback Binder is sent to the explicit manager receiver.
            val receiver = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code != 1) return super.onTransact(code, data, reply, flags)
                    val binder = data.readStrongBinder()
                    main.post {
                        if (generation == connectionGeneration && connecting) {
                            if (binder == null) failConnection()
                            else try {
                                attachBinder(binder, host.packageName)
                            } catch (error: Exception) {
                                failConnection(error)
                            }
                        }
                    }
                    return true
                }
            }
            host.sendBroadcast(
                Intent("rikka.shizuku.intent.action.REQUEST_BINDER")
                    .setComponent(ComponentName(MANAGER_PACKAGE, "moe.shizuku.manager.receiver.BinderRequestReceiver"))
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra("data", Bundle().apply { putBinder("binder", receiver) }),
            )
            scheduleConnectionTimeout(generation)
        } catch (error: Exception) {
            failConnection(error)
        }
    }

    @SuppressLint("RestrictedApi")
    private fun attachBinder(binder: IBinder, packageName: String) {
        // Custom transport only: authentication and all privileged calls still use Shizuku API.
        Shizuku.onBinderReceived(binder, packageName)
    }

    private fun scheduleConnectionTimeout(generation: Int) {
        main.postDelayed({
            if (generation == connectionGeneration && connecting) failConnection()
        }, 5000)
    }

    private fun failConnection(error: Exception? = null) {
        if (error != null) Log.w("MillenniumSteamManagement", "Privilege connection failed", error)
        connecting = false
        connectionGeneration++
        ready = false
        cancelRequest()
        message = R.string.management_service_unavailable
    }
}
