package com.millennium.app.features.steamguard.export

import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.io.InputStream

internal data class SteamMaFile(
    val json: String,
    val steamId: String,
    val accountName: String,
    val sharedSecret: String,
    val accessToken: String,
    val refreshToken: String,
) {
    val key: String get() = steamId.ifEmpty {
        "local_" + MessageDigest.getInstance("SHA-256")
            .digest((accountName + sharedSecret).toByteArray()).joinToString("") { "%02x".format(it) }
    }
    val fileName: String get() = (steamId.ifEmpty { accountName }.filter {
        it.isLetterOrDigit() || it == '_' || it == '-'
    }.take(100).ifEmpty { "Steam" }) + ".maFile"
}

/** Single-account, plaintext Monica/SDA JSON. Unknown fields survive a round trip. */
internal object SteamMaFileCodec {
    const val MAX_BYTES = MaFileInput.MAX_BYTES
    private val idPattern = Regex("[0-9]{17}")
    private val idNames = arrayOf("steamid", "steam_id", "SteamID", "steam64", "steam_id64", "steamID64", "SteamID64", "sbeamid")

    fun readBytes(stream: InputStream, limit: Int = MAX_BYTES): ByteArray = MaFileInput.readBytes(stream, limit)

    fun parse(raw: String, fileName: String = ""): SteamMaFile {
        require(raw.toByteArray().size <= MAX_BYTES)
        // Bound JSON recursion before entering the platform parser.
        var depth = 0
        var quoted = false
        var escaped = false
        for (c in raw) {
            if (quoted) {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 64) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(!quoted && depth == 0)
        val reader = JSONTokener(raw.removePrefix("\uFEFF"))
        val root = reader.nextValue() as? JSONObject ?: error("object required")
        require(reader.nextClean() == '\u0000')
        val session = root.optJSONObject("Session") ?: root.optJSONObject("session") ?: JSONObject()
        val cookie = root.text("steamLoginSecure", "steam_login_secure")
            .ifEmpty { session.text("SteamLoginSecure", "steamLoginSecure") }
            .replace("%7C", "|", ignoreCase = true)
        val ids = (idNames.map { root.text(it) } +
                listOf(session.text("SteamID", "steamid", "steam_id"), cookie.substringBefore("||", "")))
            .filter(String::isNotEmpty).distinct()
        require(ids.size <= 1 && ids.all(idPattern::matches))
        val missingId = root.optBoolean("monica_missing_steamid") || root.optBoolean("monicaMissingSteamId")
        val steamId = ids.firstOrNull() ?: if (!missingId) {
            fileName.substringBeforeLast('.').takeIf(idPattern::matches).orEmpty()
        } else ""
        val accountName = root.text("account_name", "accountName", "AccountName")
            .ifEmpty { session.text("AccountName", "account_name") }
        require(accountName.length <= 256)
        val access = root.text("access_token", "accessToken", "oauth_token", "OAuthToken")
            .ifEmpty { session.text("AccessToken", "access_token", "OAuthToken", "oauth_token") }
            .ifEmpty { cookie.substringAfter("||", "") }
        val refresh = root.text("refresh_token", "refreshToken")
            .ifEmpty { session.text("RefreshToken", "refresh_token") }
        val secretField = root.text("shared_secret", "sharedSecret")
        val uriSecret = listOf("uri", "Uri", "otp_uri", "otpUri", "otpauth_uri", "otpauthUri", "steam_uri", "steamUri", "url", "URL")
            .firstNotNullOfOrNull { secretFromUri(root.text(it)) } ?: secretFromUri(secretField)
        val secret = if (uriSecret != null) normalizeSecret(uriSecret.first, uriSecret.second)
        else if (secretField.isNotEmpty()) normalizeSecret(secretField, false) else ""
        require(secret.isNotEmpty() || (steamId.isNotEmpty() && (access.isNotEmpty() || refresh.isNotEmpty())))
        val device = root.text("device_id", "deviceId")
            .ifEmpty { session.text("DeviceID", "device_id", "deviceId") }
        fun put(key: String, value: String) { if (value.isNotEmpty()) root.put(key, value) }
        fun putSession(key: String, value: String) { if (value.isNotEmpty()) session.put(key, value) }
        put("steamid", steamId)
        put("account_name", accountName)
        put("shared_secret", secret)
        put("identity_secret", root.text("identity_secret", "identitySecret"))
        put("revocation_code", root.text("revocation_code", "revocationCode"))
        put("token_gid", root.text("token_gid", "tokenGid"))
        put("device_id", device)
        put("access_token", access)
        put("refresh_token", refresh)
        val secureCookie = if (steamId.isNotEmpty() && access.isNotEmpty()) "$steamId||$access" else cookie
        put("steamLoginSecure", secureCookie)
        putSession("SteamID", steamId)
        putSession("AccountName", accountName)
        putSession("DeviceID", device)
        putSession("AccessToken", access)
        putSession("OAuthToken", access)
        putSession("RefreshToken", refresh)
        putSession("SteamLoginSecure", secureCookie)
        root.put("Session", session)
        if (secret.isEmpty()) root.put("monica_session_only_login", true)
        else { root.remove("monica_session_only_login"); root.remove("monicaSessionOnlyLogin") }
        if (steamId.isEmpty()) root.put("monica_missing_steamid", true)
        else listOf("monica_missing_steamid", "monicaMissingSteamId", "monica_local_steamid", "monicaLocalSteamId").forEach(root::remove)
        return SteamMaFile(root.toString(), steamId, accountName, secret, access, refresh)
    }

    fun live(guardJson: String?, authJson: String?): List<SteamMaFile> {
        val guard = guardJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val auth = authJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        val accounts = guard?.optJSONObject("accounts") ?: JSONObject()
        val users = auth?.optJSONObject("userContexts") ?: JSONObject()
        val active = auth?.text("activeSteamID")
        return (accounts.keys().asSequence().toList() + users.keys().asSequence().toList())
            .distinct().sortedBy { it != active }.mapNotNull { id ->
                if (!idPattern.matches(id)) return@mapNotNull null
                runCatching {
                    val account = accounts.optJSONObject(id)?.let { JSONObject(it.toString()) } ?: JSONObject()
                    val storedId = account.text(*idNames)
                    require(storedId.isEmpty() || storedId == id)
                    account.put("steamid", id)
                    guard?.text("uuid_key")?.takeIf(String::isNotEmpty)?.let { account.put("device_id", it) }
                    // The guard store has no current session. Only merge the same SteamID from AuthContext_1.
                    listOf("Session", "session", "access_token", "accessToken", "oauth_token", "OAuthToken",
                        "refresh_token", "refreshToken", "steamLoginSecure", "steam_login_secure").forEach(account::remove)
                    val user = users.optJSONObject(id)
                    if (user != null) {
                        account.put("account_name", user.text("accountName").ifEmpty { account.text("account_name") })
                        account.put("access_token", user.text("accessToken"))
                        account.put("refresh_token", user.text("refreshToken"))
                    }
                    parse(account.toString())
                }.getOrNull()
            }
    }

    enum class LoginIssue { Missing, Invalid, Expired }

    fun canRestoreGuard(file: SteamMaFile): Boolean =
        (file.steamId.toLongOrNull() ?: 0L) in 76561197960265728L..76561202255233023L &&
                file.accountName.isNotEmpty() && file.sharedSecret.isNotEmpty()

    /** One account per SteamID; a newly imported file replaces the entire matching record. */
    fun accounts(live: List<SteamMaFile>, imported: List<SteamMaFile>, restored: Set<String> = emptySet(), guardLoaded: Boolean = true): List<SteamMaFile> {
        val merged = (live + imported).associateByTo(linkedMapOf(), SteamMaFile::key)
        live.filter { it.key in restored && (guardLoaded || merged[it.key]?.sharedSecret.isNullOrEmpty()) }
            .forEach { merged[it.key] = it }
        return merged.values.toList()
    }

    fun mergeGuard(existing: String?, file: SteamMaFile): String {
        require(canRestoreGuard(file))
        val root = existing?.let(::JSONObject) ?: JSONObject().put("accounts", JSONObject())
        val accounts = root.getJSONObject("accounts")
        // Replace, rather than mixing a new shared_secret with stale identity_secret/token_gid.
        val account = JSONObject(file.json)
        listOf("Session", "session", "uri", "otp_uri", "otpauth_uri").forEach(account::remove)
        accounts.put(file.steamId, account)
        return root.toString()
    }

    /** Claims are local consistency checks, never proof of signature or server acceptance. */
    fun loginIssue(file: SteamMaFile, now: Long = System.currentTimeMillis() / 1000): LoginIssue? {
        if (file.steamId.isEmpty() || file.accountName.isEmpty() || file.refreshToken.isEmpty())
            return LoginIssue.Missing
        if ((file.steamId.toLongOrNull() ?: 0L) !in 76561197960265728L..76561202255233023L) return LoginIssue.Invalid
        for ((token, refresh) in listOf(file.accessToken to false, file.refreshToken to true)) {
            // useInitAuth refreshes missing/expired access tokens using a current mobile refresh token.
            if (!refresh && token.isEmpty()) continue
            val claims = runCatching {
                val parts = token.split('.')
                require(parts.size == 3 && parts.all(String::isNotEmpty))
                JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
            }.getOrNull() ?: return LoginIssue.Invalid
            val audience = claims.optJSONArray("aud") ?: return LoginIssue.Invalid
            val audiences = (0 until audience.length()).map { audience.optString(it) }
            if (claims.text("sub") != file.steamId || "mobile" !in audiences ||
                (refresh && "renew" !in audiences) || claims.optLong("nbf", 0) > now + 60)
                return LoginIssue.Invalid
            if (refresh && claims.optLong("exp", 0) <= now + 60) return LoginIssue.Expired
        }
        return null
    }

    fun mergeLogin(existing: String?, file: SteamMaFile): String {
        require(loginIssue(file) == null)
        val root = existing?.let(::JSONObject) ?: JSONObject().put("userContexts", JSONObject())
        val users = root.getJSONObject("userContexts")
        val user = JSONObject()
        user.put("accountName", file.accountName).put("accessToken", file.accessToken.ifEmpty { null } ?: JSONObject.NULL)
            .put("refreshToken", file.refreshToken)
        users.put(file.steamId, user)
        return root.put("activeSteamID", file.steamId).toString()
    }

    private fun secretFromUri(value: String): Pair<String, Boolean>? = runCatching {
        val uri = URI(value)
        if (uri.scheme !in listOf("otpauth", "steam")) return null
        uri.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == "secret" }
            ?.substringAfter('=')?.let {
                URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") to (uri.scheme == "otpauth")
            }
    }.getOrNull()

    private fun normalizeSecret(value: String, preferBase32: Boolean): String {
        val compact = value.filterNot(Char::isWhitespace)
        val base64 = runCatching { Base64.getDecoder().decode(compact) }.getOrNull()
            ?: runCatching { Base64.getUrlDecoder().decode(compact) }.getOrNull()
        val base32 = runCatching {
            val chars = compact.trimEnd('=').uppercase()
            require(chars.isNotEmpty() && chars.all { it in "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" })
            var buffer = 0
            var bits = 0
            val result = ArrayList<Byte>()
            for (c in chars) {
                buffer = (buffer shl 5) or "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c)
                bits += 5
                if (bits >= 8) { bits -= 8; result.add((buffer shr bits).toByte()) }
            }
            require(buffer and ((1 shl bits) - 1) == 0)
            result.toByteArray()
        }.getOrNull()
        val bytes = (if (preferBase32) listOf(base32, base64) else listOf(base64, base32))
            .firstOrNull { it?.size == 20 } ?: error("invalid Steam secret")
        return Base64.getEncoder().encodeToString(bytes)
    }

    internal fun JSONObject.text(vararg names: String): String = names.firstNotNullOfOrNull { name ->
        val value = opt(name) ?: return@firstNotNullOfOrNull null
        when (value) {
            is String -> value.trim().takeIf(String::isNotEmpty)
            is Long, is Int -> value.toString()
            else -> null
        }
    }.orEmpty()
}
