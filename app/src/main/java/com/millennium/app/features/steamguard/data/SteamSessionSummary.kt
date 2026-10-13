package com.millennium.app.features.steamguard.data

import org.json.JSONObject
import java.util.Base64

/** Emits only fixed labels, counts and booleans; JWT claims do not prove server-side validity. */
internal object SteamSessionSummary {
    fun describe(rawJson: String?, nowSeconds: Long = System.currentTimeMillis() / 1000): List<String> {
        if (rawJson == null) return listOf("entry=missing")
        val root = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return listOf("entry=invalid-json")
        val contexts = root.optJSONObject("userContexts")
            ?: return listOf("entry=userContexts-missing-or-invalid")
        val active = root.opt("activeSteamID") as? String
        return buildList {
            add("accounts=${contexts.length()} activeMatched=${!active.isNullOrBlank() && contexts.has(active)}")
            contexts.keys().asSequence().forEachIndexed { index, steamId ->
                val account = contexts.optJSONObject(steamId)
                if (account == null) {
                    add("accountIndex=${index + 1} entry=invalid-account")
                } else {
                    val access = tokenSummary(account.opt("accessToken"), steamId, nowSeconds)
                    val refresh = tokenSummary(account.opt("refreshToken"), steamId, nowSeconds)
                    val username = (account.opt("accountName") as? String)?.isNotBlank() == true
                    add("accountIndex=${index + 1} active=${steamId == active} " +
                            "steamIdFormat=${steamId.matches(Regex("[0-9]{17}"))} usernamePresent=$username " +
                            "accessToken=$access refreshToken=$refresh")
                }
            }
        }
    }

    private fun tokenSummary(value: Any?, steamId: String, nowSeconds: Long): String {
        if (value == null || value == JSONObject.NULL) return "missing"
        if (value !is String) return "invalid-type"
        if (value.isBlank()) return "missing"
        val parts = value.split('.')
        val claims = runCatching {
            if (parts.size != 3) return@runCatching null
            JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
        }.getOrNull() ?: return "present(claims=unreadable)"
        val expiry = claims.opt("exp") as? Number
        val expiryState = when {
            expiry == null -> "unknown"
            expiry.toDouble() <= nowSeconds -> "expired"
            else -> "unexpired"
        }
        val subject = claims.opt("sub") as? String
        val subjectState = when {
            subject.isNullOrBlank() -> "unknown"
            subject == steamId -> "match"
            else -> "mismatch"
        }
        return "present(exp=$expiryState,sub=$subjectState)"
    }
}
