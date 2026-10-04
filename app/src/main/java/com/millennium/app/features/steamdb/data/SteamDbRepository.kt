package com.millennium.app.features.steamdb.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.millennium.app.features.steamdb.network.SteamDbHttpClient
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.Executors

/** Shared endpoint parsing, bounded caches and coalescing of simultaneous requests. */
internal class SteamDbRepository(private val emit: (Int, String) -> Unit) {
    private val http = SteamDbHttpClient(emit)
    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val apps = Requests<Int, SteamDbAppInfo>(60_000L)
    private val prices = Requests<Pair<Int, String>, SteamDbLowestPrice>(300_000L)
    private val players = Requests<Int, Int>(30_000L)

    fun appInfo(context: Context, appId: Int, callback: (Result<SteamDbAppInfo>) -> Unit) {
        val appContext = context.applicationContext
        apps.load(appId, {
            val data = steamDbData(appContext, "steamdb", "ExtensionApp/?appid=$appId")
            emit(Log.DEBUG, "steamdb data fields received source=steamdb appId=$appId " +
                    "cp=${data.opt("cp")} mdp=${data.opt("mdp")} mp=${data.opt("mp")} f=${data.opt("f")} u=${data.opt("u")}")
            SteamDbAppInfo(
                count(data, "cp"), count(data, "mdp"), count(data, "mp"), count(data, "f"), timestamp(data, "u"),
            )
        }, callback)
    }

    fun lowestPrice(context: Context, appId: Int, currency: String, callback: (Result<SteamDbLowestPrice>) -> Unit) {
        val appContext = context.applicationContext
        prices.load(appId to currency, {
            val encoded = URLEncoder.encode(currency, "UTF-8")
            val data = steamDbData(appContext, "steamdb-price", "ExtensionAppPrice/?appid=$appId&currency=$encoded")
            val price = data.optString("p").takeIf { it.isNotBlank() && it != "null" }
                ?: throw IOException("SteamDB price response missing p")
            SteamDbLowestPrice(
                price, count(data, "d")?.takeIf { it in 1..100 },
                data.optString("l").takeIf { it.isNotBlank() && it != "null" },
                count(data, "c"), timestamp(data, "t"),
            ).also {
                emit(Log.DEBUG, "steamdb data price received appId=$appId currency=$currency price=${it.price} " +
                        "discount=${it.discount} occurrences=${it.occurrences} lastAt=${it.lastAt}")
            }
        }, callback)
    }

    fun currentPlayers(context: Context, appId: Int, callback: (Result<Int>) -> Unit) {
        val appContext = context.applicationContext
        players.load(appId, {
            val response = http.get(
                appContext, "steam-current-players",
                "https://api.steampowered.com/ISteamUserStats/GetNumberOfCurrentPlayers/v1/" +
                        "?origin=https%3A%2F%2Fstore.steampowered.com&appid=$appId",
            ).optJSONObject("response") ?: throw IOException("Steam response missing response")
            if (response.optInt("result") != 1) throw IOException("Steam player query unsuccessful")
            count(response, "player_count") ?: throw IOException("Steam response missing player_count")
        }, callback)
    }

    private fun steamDbData(context: Context, source: String, path: String): JSONObject {
        val response = http.get(context, source, "https://extension.steamdb.info/api/$path")
        if (!response.optBoolean("success")) {
            emit(Log.WARN, "steamdb data API unsuccessful source=$source path=$path success=${response.opt("success")}")
            throw IOException("SteamDB success=false")
        }
        return response.optJSONObject("data") ?: run {
            emit(Log.ERROR, "steamdb data parse failed source=$source path=$path reason=missing-data")
            throw IOException("SteamDB response missing data")
        }
    }

    // A real zero is valid; missing or malformed values stay unavailable, never become zero.
    private fun count(json: JSONObject, key: String): Int? =
        json.opt(key)?.toString()?.replace(",", "")?.toIntOrNull()?.takeIf { it >= 0 }

    private fun timestamp(json: JSONObject, key: String): Long? =
        json.opt(key)?.toString()?.toLongOrNull()?.takeIf { it in 1..253_402_300_799L }

    /** Accessed only on the main thread. Network workers never own Activity or View state. */
    private inner class Requests<K, V>(private val ttl: Long) {
        private val cache = LinkedHashMap<K, Pair<Long, V>>()
        private val pending = HashMap<K, MutableList<(Result<V>) -> Unit>>()

        fun load(key: K, loader: () -> V, callback: (Result<V>) -> Unit) {
            check(Looper.myLooper() == Looper.getMainLooper())
            cache[key]?.let { (expires, value) ->
                if (SystemClock.elapsedRealtime() < expires) {
                    callback(Result.success(value))
                    return
                }
                cache.remove(key)
            }
            pending[key]?.let {
                it.add(callback)
                return
            }
            pending[key] = mutableListOf(callback)
            executor.execute {
                val result = runCatching(loader)
                main.post {
                    result.onSuccess {
                        cache[key] = SystemClock.elapsedRealtime() + ttl to it
                        while (cache.size > 32) cache.remove(cache.keys.first())
                    }
                    pending.remove(key)?.forEach { it(result) }
                }
            }
        }
    }
}
