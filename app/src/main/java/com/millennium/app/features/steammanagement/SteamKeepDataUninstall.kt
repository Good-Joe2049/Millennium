package com.millennium.app.features.steammanagement

import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.ResultReceiver
import com.millennium.app.R
import com.millennium.app.core.ModuleLocalization
import com.millennium.app.features.ui.settings.SteamWorkingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Keeps data for the current Android user, using the selected and authorized backend. */
internal object SteamKeepDataUninstall {
    private const val STEAM_PACKAGE = "com.valvesoftware.android.steam.community"
    // Android IBinder's stable '_CMD' transaction; hidden in the public SDK.
    private const val SHELL_COMMAND_TRANSACTION = 0x5f434d44

    suspend fun execute(context: Context): Unit = withContext(Dispatchers.IO) {
        check(context.packageName == STEAM_PACKAGE) { "This action is only available inside Steam" }
        val mode = SteamPrivilegeAccess.mode
        check(mode != SteamWorkingMode.Disabled && SteamPrivilegeAccess.ready && !SteamPrivilegeAccess.requesting) {
            ModuleLocalization.text(context, R.string.management_enable_mode)
        }
        when (mode) {
            SteamWorkingMode.Root -> {
                uninstallWithRoot(context)
                return@withContext
            }
            SteamWorkingMode.Dhizuku -> {
                SteamDhizukuAccess.uninstallKeepingData(context)
                return@withContext
            }
            SteamWorkingMode.Shizuku -> Unit
            SteamWorkingMode.Disabled -> error("No working mode selected")
        }
        val packageService = checkNotNull(SystemServiceHelper.getSystemService("package"))
        val pipe = ParcelFileDescriptor.createPipe()
        val output = async {
            ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).bufferedReader().use { reader ->
                val captured = StringBuilder()
                val buffer = CharArray(1024)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    // Drain the pipe even once enough error text has been captured.
                    if (captured.length < 4096) captured.appendRange(buffer, 0, minOf(count, 4096 - captured.length))
                }
                captured.toString().trim()
            }
        }
        val finished = CountDownLatch(1)
        var exitCode = -1
        val receiver = object : ResultReceiver(null) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                exitCode = resultCode
                finished.countDown()
            }
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            ParcelFileDescriptor.open(File("/dev/null"), ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                data.writeFileDescriptor(input.fileDescriptor)
                data.writeFileDescriptor(pipe[1].fileDescriptor)
                data.writeFileDescriptor(pipe[1].fileDescriptor)
                // Fixed target, current Android user, and unconditional KEEP_DATA. No fallback.
                data.writeStringArray(arrayOf(
                    "uninstall", "-k", "--user", (Process.myUid() / 100000).toString(), STEAM_PACKAGE,
                ))
                data.writeStrongBinder(null) // ShellCallback: no files need opening.
                receiver.writeToParcel(data, 0)
                // Server checks live authorization again, even if the SDK cached a grant.
                // system_server owns the operation after dispatch, so Steam may safely exit.
                ShizukuBinderWrapper(packageService).transact(SHELL_COMMAND_TRANSACTION, data, reply, 0)
                reply.readException()
            }
        } finally {
            data.recycle()
            reply.recycle()
            pipe[1].close()
        }
        check(finished.await(5, TimeUnit.SECONDS)) {
            ModuleLocalization.text(context, R.string.management_result_unknown)
        }
        val details = output.await()
        check(exitCode == 0) {
            details.ifBlank { ModuleLocalization.text(context, R.string.management_uninstall_failed) }
        }
    }

    private suspend fun uninstallWithRoot(context: Context) {
        val result = try {
            SteamRootShell.execute(context.cacheDir, SteamRootShell.uninstallCommand(Process.myUid() / 100000))
        } catch (error: IOException) {
            throw IllegalStateException(ModuleLocalization.text(context, R.string.management_root_unavailable), error)
        } catch (error: SecurityException) {
            throw IllegalStateException(ModuleLocalization.text(context, R.string.management_root_unavailable), error)
        } catch (error: TimeoutException) {
            throw IllegalStateException(ModuleLocalization.text(context, R.string.management_result_unknown), error)
        }
        check(result.code != 126) {
            ModuleLocalization.text(context, R.string.management_root_not_root)
        }
        check(result.code == 0) {
            result.output.ifBlank { ModuleLocalization.text(context, R.string.management_uninstall_failed) }
        }
    }
}
