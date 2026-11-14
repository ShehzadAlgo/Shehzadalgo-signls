package com.example.data.market

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.Instant
import java.util.concurrent.TimeUnit

data class MarketNewsItem(
    val id: String,
    val title: String,
    val source: String,
    val category: String,
    val publishedAtIso: String,
    val url: String = ""
)

class MarketNewsClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
) {

    suspend fun fetchLatestCryptoAndForexNews(maxItems: Int = 14): List<MarketNewsItem> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val cryptoJob = async { runCatching { fetchCryptoCompareNews(limit = 8) }.getOrDefault(emptyList()) }
                val yahooJob = async { runCatching { fetchYahooFinanceForexCryptoNews() }.getOrDefault(emptyList()) }
                val rssJob = async { runCatching { fetchGoogleForexRss(limit = 8) }.getOrDefault(emptyList()) }

                val combined = (cryptoJob.await() + yahooJob.await() + rssJob.await())
                    .filter { it.title.isNotBlank() }
                    .distinctBy { it.title.lowercase().take(48) }
                    .take(maxItems)

                if (combined.isEmpty()) {
                    throw IllegalStateException(
                        "Unable to fetch live Crypto/Forex news feeds. Verify network access and retry."
                    )
                }
                combined
            }
        }

    private fun fetchCryptoCompareNews(limit: Int): List<MarketNewsItem> {
        val url = "https://min-api.cryptocompare.com/data/v2/news/?lang=EN"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("CryptoCompare HTTP ${resp.code}")
            val body = resp.body?.string() ?: return emptyList()
            return parseCryptoCompareJson(body, limit)
        }
    }

    private fun fetchYahooFinanceForexCryptoNews(): List<MarketNewsItem> {
        val url = "https://query1.finance.yahoo.com/v1/finance/search?q=EURUSD%20USD%20Fed%20Bitcoin&quotesCount=0&newsCount=10"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36")
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Yahoo News HTTP ${resp.code}")
            val body = resp.body?.string() ?: return emptyList()
            return parseYahooNewsJson(body)
        }
    }

    private fun fetchGoogleForexRss(limit: Int): List<MarketNewsItem> {
        val url = "https://news.google.com/rss/search?q=forex+OR+EURUSD+OR+Federal+Reserve+OR+ECB+when:2d&hl=en-US&gl=US&ceid=US:en"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Forex RSS HTTP ${resp.code}")
            val body = resp.body?.string() ?: return emptyList()
            return parseRssXml(body, defaultCategory = "FOREX", limit = limit)
        }
    }

    companion object {
        internal fun parseCryptoCompareJson(rawJson: String, limit: Int = 8): List<MarketNewsItem> {
            val root = JSONObject(rawJson)
            val data = root.optJSONArray("Data") ?: return emptyList()
            val out = mutableListOf<MarketNewsItem>()
            for (i in 0 until minOf(data.length(), limit)) {
                val obj = data.optJSONObject(i) ?: continue
                val title = obj.optString("title").trim()
                if (title.isEmpty()) continue
                val publishedSec = obj.optLong("published_on", 0L)
                val iso = if (publishedSec > 0L) Instant.ofEpochSecond(publishedSec).toString() else ""
                val src = obj.optJSONObject("source_info")?.optString("name")
                    ?.takeIf { it.isNotBlank() }
                    ?: obj.optString("source", "CryptoCompare")
                out.add(
                    MarketNewsItem(
                        id = "cc_${obj.optString("id", i.toString())}",
                        title = title,
                        source = src,
                        category = "CRYPTO",
                        publishedAtIso = iso,
                        url = obj.optString("url")
                    )
                )
            }
            return out
        }

        internal fun parseYahooNewsJson(rawJson: String): List<MarketNewsItem> {
            val root = JSONObject(rawJson)
            val news = root.optJSONArray("news") ?: return emptyList()
            val out = mutableListOf<MarketNewsItem>()
            for (i in 0 until news.length()) {
                val obj = news.optJSONObject(i) ?: continue
                val title = obj.optString("title").trim()
                if (title.isEmpty()) continue
                val tsSec = obj.optLong("providerPublishTime", 0L)
                val iso = if (tsSec > 0L) Instant.ofEpochSecond(tsSec).toString() else ""
                val lower = title.lowercase()
                val category = when {
                    listOf("btc", "bitcoin", "eth", "ethereum", "crypto", "solana", "binance").any { it in lower } -> "CRYPTO"
                    listOf("eur", "usd", "jpy", "gbp", "forex", "currency", "fed", "ecb", "boe", "boj", "inflation", "cpi", "payroll").any { it in lower } -> "FOREX"
                    else -> "MACRO"
                }
                out.add(
                    MarketNewsItem(
                        id = "yf_${obj.optString("uuid", i.toString())}",
                        title = title,
                        source = obj.optString("publisher", "Yahoo Finance"),
                        category = category,
                        publishedAtIso = iso,
                        url = obj.optString("link")
                    )
                )
            }
            return out
        }

        internal fun parseRssXml(xml: String, defaultCategory: String = "FOREX", limit: Int = 8): List<MarketNewsItem> {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            val out = mutableListOf<MarketNewsItem>()
            var insideItem = false
            var title = ""
            var link = ""
            var pubDate = ""
            var source = "Market Wire"

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT && out.size < limit) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name.lowercase()) {
                            "item" -> {
                                insideItem = true
                                title = ""
                                link = ""
                                pubDate = ""
                                source = "Market Wire"
                            }
                            "title" -> if (insideItem) title = parser.nextText().trim()
                            "link" -> if (insideItem) link = parser.nextText().trim()
                            "pubdate" -> if (insideItem) pubDate = parser.nextText().trim()
                            "source" -> if (insideItem) source = parser.nextText().trim().ifBlank { source }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name.equals("item", ignoreCase = true) && insideItem) {
                            if (title.isNotEmpty()) {
                                val cleanTitle = title.substringBeforeLast(" - ").trim().ifBlank { title }
                                out.add(
                                    MarketNewsItem(
                                        id = "rss_${out.size}_${cleanTitle.hashCode()}",
                                        title = cleanTitle,
                                        source = source,
                                        category = defaultCategory,
                                        publishedAtIso = pubDate,
                                        url = link
                                    )
                                )
                            }
                            insideItem = false
                        }
                    }
                }
                eventType = parser.next()
            }
            return out
        }
    }
}
