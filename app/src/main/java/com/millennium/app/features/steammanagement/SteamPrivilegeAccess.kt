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
import com.millennium.app.features.ui.settings.SteamWorkingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.sui.Sui

/** Authorization belongs to Steam's UID, including when Millennium is embedded by NPatch. */
internal object SteamPrivilegeAccess {
    private const val MANAGER_PACKAGE = "moe.shizuku.privileged.api"
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var application: Application? = null
    private var rootJob: Job? = null
    private var requestSequence = 4100
    private var pendingCode: Int? = null
    private var connectionGeneration = 0
    private var connecting = false
    private var suiAttempted = false
    private var focusLostRequest: Int? = null
    private var permissionSentCode: Int? = null

    var mode by mutableStateOf(SteamWorkingMode.Disabled)
        private set
    val enabled: Boolean get() = mode != SteamWorkingMode.Disabled
    var ready by mutableStateOf(false)
        private set
    var requestingMode by mutableStateOf<SteamWorkingMode?>(null)
        private set
    val requesting: Boolean get() = requestingMode != null
    var message by mutableStateOf<Int?>(null)
        private set

    fun initialize(host: Context) {
        if (application != null) {
            refresh()
            return
        }
        application = host.applicationContext as Application
        mode = SteamModuleSettings.workingMode(host)
        Shizuku.addBinderReceivedListenerSticky {
            main.post {
                connecting = false
                onConnected()
            }
        }
        Shizuku.addBinderDeadListener {
            main.post {
                connecting = false
                suiAttempted = false
                connectionGeneration++
                if (mode == SteamWorkingMode.Shizuku) ready = false
                if (requestingMode == SteamWorkingMode.Shizuku) {
                    cancelRequest()
                    message = R.string.management_service_unavailable
                } else if (mode == SteamWorkingMode.Shizuku && !requesting) {
                    message = R.string.management_service_unavailable
                }
            }
        }
        Shizuku.addRequestPermissionResultListener { code, result ->
            main.post {
                if (requestingMode == SteamWorkingMode.Shizuku && code == pendingCode) {
                    finishRequest(
                        SteamWorkingMode.Shizuku,
                        result == PackageManager.PERMISSION_GRANTED && hasPermission(),
                        R.string.management_permission_denied,
                    )
                }
            }
        }
        refresh()
    }

    fun selectMode(value: SteamWorkingMode) {
        if (application == null) return
        cancelRequest()
        message = null
        when (value) {
            SteamWorkingMode.Disabled -> saveMode(value)
            SteamWorkingMode.Root -> requestRoot()
            SteamWorkingMode.Shizuku -> {
                pendingCode = ++requestSequence
                requestingMode = value
                // Keep the previous mode until the requested permission has been verified.
                connect()
            }
        }
    }

    fun cancelRequest() {
        rootJob?.cancel()
        rootJob = null
        pendingCode = null
        focusLostRequest = null
        permissionSentCode = null
        requestingMode = null
        connecting = false
        connectionGeneration++
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (requestingMode != SteamWorkingMode.Shizuku) return
        val code = pendingCode ?: return
        if (!hasFocus) {
            focusLostRequest = code
        } else if (focusLostRequest == code) {
            // Some permission UIs dismiss without sending a result. Reconcile on return,
            // allowing the ordinary permission callback to arrive first.
            main.postDelayed({
                if (requestingMode == SteamWorkingMode.Shizuku && pendingCode == code && focusLostRequest == code) {
                    finishRequest(SteamWorkingMode.Shizuku, hasPermission(), R.string.management_permission_denied)
                }
            }, 300)
        }
    }

    fun refresh() {
        if (application == null || requesting) return
        when (mode) {
            SteamWorkingMode.Disabled -> ready = false
            SteamWorkingMode.Root -> requestRoot()
            SteamWorkingMode.Shizuku -> {
                if (binderReady()) {
                    ready = hasPermission()
                    if (ready) {
                        message = null
                    } else {
                        saveMode(SteamWorkingMode.Disabled)
                        message = R.string.management_permission_denied
                    }
                } else {
                    ready = false
                    if (!connecting) connect()
                }
            }
        }
    }

    private fun saveMode(value: SteamWorkingMode) {
        mode = value
        if (value == SteamWorkingMode.Disabled) ready = false
        application?.let { SteamModuleSettings.setWorkingMode(it, value) }
    }

    private fun requestRoot() {
        val host = application ?: return
        val code = ++requestSequence
        pendingCode = code
        requestingMode = SteamWorkingMode.Root
        message = null
        rootJob = scope.launch {
            val result = SteamRootShell.probe(host.cacheDir)
            // Cancellation or a newer selection must never be overwritten by an old result.
            if (pendingCode != code || requestingMode != SteamWorkingMode.Root) return@launch
            rootJob = null
            val error = when (result) {
                SteamRootShell.Access.Granted -> null
                SteamRootShell.Access.Unavailable -> R.string.management_root_unavailable
                SteamRootShell.Access.Denied -> R.string.management_root_denied
                SteamRootShell.Access.NotRoot -> R.string.management_root_not_root
                SteamRootShell.Access.TimedOut -> R.string.management_root_timeout
            }
            finishRequest(SteamWorkingMode.Root, result == SteamRootShell.Access.Granted, error)
        }
    }

    private fun finishRequest(target: SteamWorkingMode, granted: Boolean, error: Int?) {
        cancelRequest()
        if (granted) {
            saveMode(target)
            ready = true
            message = null
        } else {
            if (mode == target) saveMode(SteamWorkingMode.Disabled)
            message = error
        }
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
        if (requestingMode != SteamWorkingMode.Shizuku) {
            if (mode == SteamWorkingMode.Shizuku && !requesting) refresh()
            return
        }
        val code = pendingCode ?: return
        if (hasPermission()) {
            finishRequest(SteamWorkingMode.Shizuku, true, null)
        } else if (permissionSentCode != code) {
            try {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    finishRequest(SteamWorkingMode.Shizuku, false, R.string.management_permission_denied)
                } else {
                    permissionSentCode = code
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
        if (mode == SteamWorkingMode.Shizuku) ready = false
        if (requestingMode == SteamWorkingMode.Shizuku) {
            cancelRequest()
            message = R.string.management_service_unavailable
        } else if (mode == SteamWorkingMode.Shizuku && !requesting) {
            message = R.string.management_service_unavailable
        }
    }
}
