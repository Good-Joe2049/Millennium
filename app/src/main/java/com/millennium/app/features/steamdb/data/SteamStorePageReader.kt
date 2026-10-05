package com.millennium.app.features.steamdb.data

import android.webkit.WebView
import androidx.core.net.toUri
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.Locale

/** Reads existing page data without injecting a bridge or making network requests. */
internal object SteamStorePageReader {
    fun appId(url: String?): Int? {
        val uri = runCatching { url?.toUri() }.getOrNull() ?: return null
        if (uri.scheme !in listOf("https", "http")) return null
        if (uri.host?.lowercase(Locale.ROOT) !in listOf("store.steampowered.com", "store.steamchina.com")) return null
        val path = uri.pathSegments
        if (path.size < 2 || path[0] != "app") return null
        return path[1].toIntOrNull()?.takeIf { it > 0 }
    }

    /** Uses Steam's stable URL slug for the compact panel header. */
    fun displayName(url: String?): String? {
        val uri = runCatching { url?.toUri() }.getOrNull() ?: return null
        if (uri.host?.lowercase(Locale.ROOT) !in listOf("store.steampowered.com", "store.steamchina.com")) return null
        return uri.pathSegments.getOrNull(2)
            ?.replace('-', ' ')
            ?.replace('_', ' ')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    fun read(webView: WebView, expectedAppId: Int, callback: (Result<SteamStorePage>) -> Unit) {
        if (!webView.settings.javaScriptEnabled || appId(webView.url) != expectedAppId) {
            callback(Result.failure(IOException("Store page unavailable or JavaScript disabled")))
            return
        }
        try {
            webView.evaluateJavascript(SCRIPT) { raw ->
                callback(runCatching {
                    val text = JSONTokener(raw ?: "null").nextValue() as? String
                        ?: throw IOException("Store page metadata unavailable")
                    val data = JSONObject(text)
                    if (appId(webView.url) != expectedAppId || data.optInt("appId") != expectedAppId) {
                        throw IOException("Store page changed while reading metadata")
                    }
                    SteamStorePage(
                        expectedAppId,
                        data.optString("currency").takeIf { it.matches(Regex("[A-Z]{3}(-[A-Z]+)?")) },
                        data.optBoolean("free"),
                        reviewCount(data, "positive"), reviewCount(data, "negative"),
                        data.optString("name").takeIf { it.isNotBlank() && it != "null" },
                    )
                })
            }
        } catch (error: RuntimeException) {
            callback(Result.failure(error))
        }
    }

    private fun reviewCount(data: JSONObject, key: String): Long? =
        data.opt(key)?.toString()?.toLongOrNull()?.takeIf { it in 0..Int.MAX_VALUE.toLong() }

    // Match BrowserExtension's review scope and regional currency mapping.
    // Parse account config locally, returning only currency and public review counts.
    private val SCRIPT = """
        (() => {
          const parse = value => { try { return JSON.parse(value || '{}'); } catch (_) { return {}; } };
          const app = location.pathname.match(/^\/app\/(\d+)(?:\/|$)/);
          const configElement = document.getElementById('application_config');
          const config = parse(configElement?.dataset?.config);
          const priceText = document.querySelector('meta[itemprop="price"]')?.content;
          const price = priceText ? Number.parseFloat(priceText.replace(',', '.')) : NaN;
          let currency = document.querySelector('meta[itemprop="priceCurrency"]')?.content || null;
          if (location.hostname === 'store.steamchina.com') currency = 'CNY-XC';
          if (!currency) {
            const codes = [null, 'USD','GBP','EUR','CHF','RUB','PLN','BRL','JPY','NOK','IDR',
              'MYR','PHP','SGD','THB','VND','KRW','TRY','UAH','MXN','CAD','AUD','NZD','CNY',
              'INR','CLP','PEN','COP','ZAR','HKD','TWD','SAR','AED','SEK','ARS','ILS','BYN',
              'KZT','KWD','QAR','CRC','UYU','BGN','HRK','CZK','DKK','HUF','RON'];
            const user = parse(configElement?.dataset?.store_user_config);
            const code = user?.accountcart?.cart?.subtotal?.currency_code ||
              user?.shoppingcart?.lineitems?.[0]?.package_item?.costwhenadded?.currencycode;
            currency = codes[code] || null;
          }
          if (currency === 'USD') {
            const country = typeof config.COUNTRY === 'string' ? config.COUNTRY.toUpperCase() : '';
            const regions = {
              'USD-CIS': 'AZ AM BY GE KG MD TJ TM UZ',
              'USD-SASIA': 'BD BT NP PK LK',
              'USD-LATAM': 'AR BO BZ EC GT GY HN NI PA PY SR SV VE',
              'USD-MENA': 'BH DZ EG IQ JO LB LY MA OM PS SD TN TR YE'
            };
            if (!country) currency = null; // Do not show the wrong dollar region's price.
            else for (const [region, countries] of Object.entries(regions)) {
              if (countries.split(' ').includes(country)) { currency = region; break; }
            }
          }
          const reviews = document.querySelector('div[data-featuretarget="appreviews"]');
          const name = (document.querySelector('.apphub_AppName')?.textContent ||
            document.querySelector('meta[property="og:title"]')?.content || '').trim();
          const filters = parse(reviews?.dataset?.props)?.filter_options;
          const count = value => Number.isSafeInteger(value) && value >= 0 ? value : null;
          return JSON.stringify({appId: app ? Number(app[1]) : null, currency, name,
            free: Number.isFinite(price) && price >= 0 && price < 0.01,
            positive: count(filters?.nReviewsPositive), negative: count(filters?.nReviewsNegative)});
        })()
    """.trimIndent()
}
