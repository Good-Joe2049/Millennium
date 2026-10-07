package com.millennium.app.features.steamdb.network

import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/** Shared JSON requests over HttpURLConnection. */
internal class SteamDbHttpClient(private val emit: (Int, String) -> Unit) {
    private val steamDbRetryAt = AtomicLong()

    fun get(source: String, endpoint: String): JSONObject {
        val steamDb = URL(endpoint).host == "extension.steamdb.info"
        if (steamDb && SystemClock.elapsedRealtime() < steamDbRetryAt.get()) {
            emit(Log.WARN, "steamdb network rate limited source=$source endpoint=$endpoint")
            throw IOException("SteamDB rate limited; waiting for Retry-After")
        }
        val trace = SteamDbRequestTrace(source, emit)
        trace.log(Log.INFO, "http start", "endpoint=$endpoint transport=http-url-connection")
        val headers = requestHeaders(steamDb)
        val response = try {
            trace.withNetworkTracing { urlConnection(endpoint, headers, trace) }
        } catch (error: IOException) {
            trace.log(Log.ERROR, "failure", "endpoint=$endpoint elapsedMs=${trace.elapsedMs} error=${Log.getStackTraceString(error)}")
            throw error
        }
        trace.log(
            Log.INFO,
            "http response",
            "transport=http-url-connection " +
                    "status=${response.status} contentType=${response.header("Content-Type")} " +
                    "server=${response.header("Server")} retryAfter=${response.header("Retry-After")} " +
                    "elapsedMs=${trace.elapsedMs}",
        )
        if (steamDb && response.status == 429) {
            val delay = retryDelayMillis(response.header("Retry-After"))
            steamDbRetryAt.updateAndGet { maxOf(it, SystemClock.elapsedRealtime() + delay) }
        }
        val preview = response.body.replace('\n', ' ').replace('\r', ' ').take(240).ifBlank { "<empty>" }
        if (response.status !in 200..299) {
            trace.log(Log.WARN, "interface rejected", "status=${response.status} bodyPreview=$preview")
            throw IOException("HTTP ${response.status}")
        }
        trace.log(Log.DEBUG, "http body", "length=${response.body.length} preview=$preview")
        return try {
            trace.measure("json_parse") { JSONObject(response.body) }
        } catch (error: org.json.JSONException) {
            trace.log(Log.ERROR, "JSON parse failed", "bodyPreview=$preview error=$error")
            throw IOException("Invalid JSON from $source", error)
        }
    }

    private fun urlConnection(
        endpoint: String,
        headers: Map<String, String>,
        trace: SteamDbRequestTrace,
    ): SteamDbHttpResponse {
        val connection = trace.measure("open_connection") {
            (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
        }
        return try {
            trace.log(
                Log.INFO,
                "http config",
                "connectTimeoutMs=${connection.connectTimeout} readTimeoutMs=${connection.readTimeout} " +
                        "implementation=${connection.javaClass.name} connectScope=dns+tcp+tls-or-reuse",
            )
            // This phase records the explicit setup call; the platform may defer some work until
            // responseCode, so a lazy DNS/TCP/TLS delay can instead appear in response_headers.
            trace.measure("connect") { connection.connect() }
            // Automatic redirects or retries can also reconnect while fetching response headers.
            val status = trace.measure("response_headers") { connection.responseCode }
            trace.log(
                Log.INFO,
                "http headers",
                "status=$status finalHost=${connection.url.host} " +
                        "redirected=${connection.url.toExternalForm() != endpoint}",
            )
            val body = trace.measure("response_body") {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }
            SteamDbHttpResponse(
                status, body,
                connection.headerFields.filterKeys { it != null }.mapKeys { it.key!! },
            )
        } finally {
            trace.measure("disconnect") { connection.disconnect() }
        }
    }

    private fun requestHeaders(steamDb: Boolean): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("User-Agent", USER_AGENT)
        put("sec-ch-ua", "\"Chromium\";v=\"154\", \"Google Chrome\";v=\"154\", \"Not A(Brand\";v=\"99\"")
        put("sec-ch-ua-mobile", "?0")
        put("sec-ch-ua-platform", "\"Windows\"")
        put("Accept-Language", "zh-CN,zh;q=0.9")
        put("sec-fetch-dest", "empty")
        put("sec-fetch-mode", "cors")
        put("priority", "u=1, i")
        if (steamDb) {
            put("Origin", "chrome-extension://kdbmhfkmnlmbkgbabkdealhhbfhlmmon")
            put("X-Requested-With", "SteamDB")
            put("sec-fetch-site", "cross-site")
        } else {
            put("Origin", "https://store.steampowered.com")
            put("sec-fetch-site", "same-site")
        }
    }

    private fun retryDelayMillis(value: String): Long {
        val seconds = value.toLongOrNull()
        if (seconds != null) return seconds.coerceIn(1, 86_400) * 1_000L
        return runCatching {
            ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli() - System.currentTimeMillis()
        }.getOrDefault(60_000L).coerceIn(1_000L, 86_400_000L)
    }

    companion object {
        private const val TIMEOUT_MS = 8_000
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/154.0.0.0 Safari/537.36"
    }
}

private data class SteamDbHttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>>,
) {
    fun header(name: String): String = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull() ?: "unknown"
}
