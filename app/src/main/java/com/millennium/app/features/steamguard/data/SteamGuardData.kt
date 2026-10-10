package com.millennium.app.features.steamguard.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.json.JSONObject

internal data class SteamGuardAccount(val username: String, val secret: String)

/** Process-only cache. Dialog fields are parsed when the export UI requests them. */
internal object SteamGuardData {
    private val cachedJson = MutableStateFlow<String?>(null)

    val accounts: Flow<List<SteamGuardAccount>?> = cachedJson.map(::parseAccounts).distinctUntilChanged()

    fun currentAccounts(): List<SteamGuardAccount>? = parseAccounts(cachedJson.value)

    fun accept(enhancedJson: String): Boolean {
        // decryptItem is also used by unrelated SecureStore entries.
        val accounts = runCatching { JSONObject(enhancedJson).optJSONObject("accounts") }.getOrNull()
            ?: return false
        if (accounts.length() > 0 && accounts.keys().asSequence().none { key ->
                accounts.optJSONObject(key)?.has("account_name") == true
            }
        ) return false
        cachedJson.value = enhancedJson
        return true
    }

    private fun parseAccounts(json: String?): List<SteamGuardAccount>? {
        if (json == null) return null
        val accounts = JSONObject(json).getJSONObject("accounts")
        return accounts.keys().asSequence().mapNotNull { key ->
            val account = accounts.optJSONObject(key) ?: return@mapNotNull null
            val username = account.opt("account_name") as? String ?: return@mapNotNull null
            val secret = runCatching {
                Uri.parse(account.optString("uri")).getQueryParameter("secret")
            }.getOrNull().orEmpty()
            SteamGuardAccount(username, secret)
        }.toList()
    }

    // Moved unchanged from ModuleMain: preserve the existing exported JSON and conversion.
    fun enhanceSteamGuardJson(rawJson: String, context: Context?): String {
        val steamGuard = JSONObject(rawJson)
        val accounts = steamGuard.optJSONObject("accounts")
        if (accounts != null) {
            val keys = accounts.keys()
            while (keys.hasNext()) {
                val account = accounts.optJSONObject(keys.next()) ?: continue
                if (account.optString("uri").isNotEmpty()) continue
                val sharedSecret = account.optString("shared_secret")
                if (sharedSecret.isEmpty()) continue
                val decoded = Base64.decode(sharedSecret, Base64.DEFAULT)
                val base32 = encodeBase32(decoded)
                account.put(
                    "uri",
                    "otpauth://totp/Steam:${account.optString("account_name")}" +
                            "?secret=$base32&issuer=Steam"
                )
            }
        }
        val uuid = context?.getSharedPreferences("steam.uuid", Context.MODE_PRIVATE)
            ?.getString("uuidKey", null)
        if (!uuid.isNullOrEmpty()) steamGuard.put("uuid_key", uuid)
        return steamGuard.toString()
    }

    private fun encodeBase32(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val output = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                output.append(alphabet[(buffer shr bits) and 0x1f])
            }
        }
        if (bits > 0) output.append(alphabet[(buffer shl (5 - bits)) and 0x1f])
        return output.toString()
    }
}
