package com.millennium.app.features.steamguard.export

import java.util.UUID

/** No secrets in Intent extras. The result is an exact, short-lived read grant. */
internal object MonicaMaFileContract {
    const val PACKAGE = "takagi.ru.monica.steamapp"
    const val ACTIVITY = "takagi.ru.monica.steam.backup.share.SteamMaFileExportActivity"
    const val ACTION = "takagi.ru.monica.steamapp.action.REQUEST_MAFILE"
    const val EXTRA_VERSION = "protocol_version"
    const val EXTRA_REQUEST_ID = "request_id"
    const val VERSION = 1
    private val uriPattern = Regex("content://takagi\\.ru\\.monica\\.steamapp\\.mafile-handoff/v1/[0-9a-f]{64}")

    fun validResult(version: Int, requestId: String?, expected: String, uri: String?): Boolean =
        version == VERSION && requestId == expected &&
            expected.length == 36 && runCatching { UUID.fromString(expected).toString() == expected }.getOrDefault(false) &&
            uri != null && uriPattern.matches(uri)
}
