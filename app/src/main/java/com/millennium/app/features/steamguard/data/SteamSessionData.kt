package com.millennium.app.features.steamguard.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Current public-Steam session only; credentials never enter saved UI state. */
internal object SteamSessionData {
    private val snapshot = MutableStateFlow<String?>(null)
    val json = snapshot.asStateFlow()

    fun accept(key: String, raw: String?) {
        if (key != "AuthContext_1") return
        snapshot.value = raw?.takeIf {
            runCatching { JSONObject(it).optJSONObject("userContexts") != null }.getOrDefault(false)
        }
    }
}
