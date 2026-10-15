package com.millennium.app.features.steammanagement

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** su is invoked as Steam's UID; Magisk/KernelSU authorize the host, not the module APK. */
internal object SteamRootShell {
    enum class Access { Granted, Unavailable, Denied, NotRoot, TimedOut }
    data class Result(val code: Int, val output: String)

    suspend fun probe(cacheDir: File): Access = try {
        val result = execute(cacheDir, "/system/bin/id -u")
        when {
            result.code != 0 -> Access.Denied
            result.output.trim() != "0" -> Access.NotRoot
            else -> Access.Granted
        }
    } catch (_: IOException) {
        Access.Unavailable
    } catch (_: SecurityException) {
        Access.Unavailable
    } catch (_: TimeoutException) {
        Access.TimedOut
    }

    fun uninstallCommand(userId: Int): String {
        require(userId >= 0)
        // Recheck UID in the SAME su invocation. Only the host package/current user and KEEP_DATA.
        return "[ \"$(/system/bin/id -u)\" = 0 ] || exit 126; " +
                "exec /system/bin/cmd package uninstall -k --user $userId com.valvesoftware.android.steam.community"
    }

    internal suspend fun execute(
        cacheDir: File,
        command: String,
        timeoutMillis: Long = 30_000,
    ): Result = withContext(Dispatchers.IO) {
        // Redirect to a private temporary file so a full pipe cannot deadlock authorization or
        // package-manager output. The file contains command results, never account credentials.
        val output = File.createTempFile("millennium-root-", ".tmp", cacheDir)
        var process: Process? = null
        try {
            val running = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true).redirectOutput(output).start()
            process = running
            running.outputStream.close()
            if (!runInterruptible { running.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) }) {
                throw TimeoutException("Root command timed out")
            }
            val text = output.reader().use { reader ->
                val chars = CharArray(4096)
                var length = 0
                while (length < chars.size) {
                    val count = reader.read(chars, length, chars.size - length)
                    if (count < 0) break
                    length += count
                }
                String(chars, 0, length).trim()
            }
            Result(running.exitValue(), text)
        } finally {
            process?.let { running ->
                if (running.isAlive) running.destroyForcibly()
                runCatching { running.inputStream.close() }
                runCatching { running.errorStream.close() }
                runCatching { running.outputStream.close() }
            }
            output.delete()
        }
    }
}
