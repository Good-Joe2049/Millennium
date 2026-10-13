package com.millennium.app.features.steamguard.data

import java.security.MessageDigest

/** Logs complete AuthContext values locally. Deduplication retains hashes, not credentials. */
internal class SteamSessionLog(
    private val plaintextEnabled: Boolean,
    private val emit: (String) -> Unit,
) {
    private val lastSnapshots = linkedMapOf<String, ByteArray>()
    private var dumpId = 0L

    @Synchronized
    fun report(source: String, key: String, rawJson: String?) {
        val summary = SteamSessionSummary.describe(rawJson)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update((rawJson ?: "<missing>").toByteArray(Charsets.UTF_8))
        val fingerprint = digest.digest(summary.joinToString("\n").toByteArray(Charsets.UTF_8))
        if (lastSnapshots[key]?.contentEquals(fingerprint) == true) return

        val prefix = "source=$source key=$key operation=success"
        summary.forEach { emit("$prefix $it") }
        if (plaintextEnabled && rawJson != null) {
            val id = ++dumpId
            val parts = plaintextParts(rawJson)
            emit("$prefix dump=$id plaintext=begin chars=${rawJson.length} parts=${parts.size}")
            parts.forEachIndexed { index, part ->
                emit("dump=$id plaintext part=${index + 1}/${parts.size} data=$part")
            }
            emit("dump=$id plaintext=end")
        }
        // Cache only after every log call succeeded, so a failed dump can be retried.
        lastSnapshots[key] = fingerprint
        if (lastSnapshots.size > 8) lastSnapshots.remove(lastSnapshots.keys.first())
    }

    private fun plaintextParts(value: String): List<String> = buildList {
        var start = 0
        while (start < value.length) {
            // At most 2,400 UTF-8 bytes; leaves room for LSPosed's Logcat prefix.
            var end = minOf(start + 800, value.length)
            if (end < value.length && value[end - 1].isHighSurrogate() && value[end].isLowSurrogate()) {
                end--
            }
            add(value.substring(start, end))
            start = end
        }
    }
}
