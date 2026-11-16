package com.example.data.market

import com.example.domain.model.AssetClass
import com.example.domain.model.Candle
import com.example.domain.model.EconomicEvent
import com.example.domain.model.FundingPoint
import com.example.domain.model.Instrument
import com.example.domain.model.InstrumentUniverse
import com.example.domain.model.OpenInterestPoint
import com.example.domain.model.Timeframe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

data class MarketFeedBundle(
    val instrument: Instrument,
    val timeframe: Timeframe,
    val candles: List<Candle>,
    val dailyCandles: List<Candle>,
    val fundingHistory: List<FundingPoint>,
    val openInterestHistory: List<OpenInterestPoint>,
    val providerName: String,
    val fetchedAtEpochMs: Long,
    val errorMessage: String? = null
)

class MarketDataClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
) {
    private val csvImportedSeries = mutableMapOf<Pair<String, Timeframe>, List<Candle>>()

    fun registerCsvCandles(instrumentSymbol: String, timeframe: Timeframe, candles: List<Candle>) {
        csvImportedSeries[instrumentSymbol to timeframe] = candles.sortedBy { it.openTimeEpochMs }
    }

    fun getCsvCandles(instrumentSymbol: String, timeframe: Timeframe): List<Candle>? =
        csvImportedSeries[instrumentSymbol to timeframe]

    suspend fun fetchSpotFxRatesUsd(): Map<String, Double> = withContext(Dispatchers.IO) {
        val rates = mutableMapOf(
            "EURUSD" to 1.085,
            "GBPUSD" to 1.270,
            "USDJPY" to 153.5,
            "AUDUSD" to 0.655,
            "USDCAD" to 1.395,
            "USDCHF" to 0.880,
            "NZDUSD" to 0.595
        )
        try {
            val req = Request.Builder()
                .url("https://api.frankfurter.dev/v1/latest?base=USD&symbols=EUR,GBP,JPY,AUD,CAD,CHF,NZD")
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val body = resp.body?.string() ?: return@use
                val json = JSONObject(body)
                val r = json.optJSONObject("rates") ?: return@use
                r.optDouble("EUR").takeIf { it > 0.0 }?.let { rates["EURUSD"] = 1.0 / it }
                r.optDouble("GBP").takeIf { it > 0.0 }?.let { rates["GBPUSD"] = 1.0 / it }
                r.optDouble("JPY").takeIf { it > 0.0 }?.let { rates["USDJPY"] = it }
                r.optDouble("AUD").takeIf { it > 0.0 }?.let { rates["AUDUSD"] = 1.0 / it }
                r.optDouble("CAD").takeIf { it > 0.0 }?.let { rates["USDCAD"] = it }
                r.optDouble("CHF").takeIf { it > 0.0 }?.let { rates["USDCHF"] = it }
                r.optDouble("NZD").takeIf { it > 0.0 }?.let { rates["NZDUSD"] = 1.0 / it }
            }
        } catch (_: Exception) {
            // Keep reference FX rates if network unavailable
        }

        // Enrich with unblocked live Crypto spot prices (Binance Data Vision -> OKX fallback, zero HTTP 451)
        val cryptoRates = runCatching { fetchCryptoSpotPricesViaBinanceVision() }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching { fetchCryptoSpotPricesViaOkx() }.getOrDefault(emptyMap())
        rates.putAll(cryptoRates)

        rates
    }

    suspend fun fetchInstrumentBundle(
        instrument: Instrument,
        timeframe: Timeframe,
        limit: Int = 320
    ): MarketFeedBundle = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        csvImportedSeries[instrument.symbol to timeframe]?.let { imported ->
            return@withContext MarketFeedBundle(
                instrument = instrument,
                timeframe = timeframe,
                candles = imported,
                dailyCandles = emptyList(),
                fundingHistory = emptyList(),
                openInterestHistory = emptyList(),
                providerName = "CSV_IMPORT",
                fetchedAtEpochMs = nowMs
            )
        }

        if (instrument.assetClass == AssetClass.CRYPTO) {
            fetchCryptoBundle(instrument, timeframe, limit, nowMs)
        } else {
            fetchFxBundle(instrument, timeframe, nowMs)
        }
    }

    /**
     * Fetches live OHLCV market data for ANY user-searched FX, Metals, Crypto, or Index ticker.
     * Checks [InstrumentUniverse] first, then dynamically resolves arbitrary pairs such as
     * EURGBP, AUDJPY, XAGUSD, DOGEUSDT, AVAX, SUI, PEPE, GOLD, AAPL, or ^GSPC.
     */
    suspend fun fetchAnyPairBundle(
        rawQuery: String,
        timeframe: Timeframe = Timeframe.H1,
        limit: Int = 240
    ): MarketFeedBundle = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        val instrument = resolveAnyInstrument(rawQuery)
            ?: return@withContext MarketFeedBundle(
                instrument = Instrument(
                    symbol = rawQuery.trim().uppercase(),
                    displayName = rawQuery.trim().uppercase(),
                    assetClass = AssetClass.FX,
                    baseCurrency = "USD",
                    quoteCurrency = "USD",
                    tickSize = 0.0001,
                    pipSize = 0.001,
                    contractSize = 1.0,
                    minLot = 0.01,
                    lotStep = 0.01,
                    baseSpreadPrice = 0.0,
                    commissionPerUnitPct = 0.0
                ),
                timeframe = timeframe,
                candles = emptyList(),
                dailyCandles = emptyList(),
                fundingHistory = emptyList(),
                openInterestHistory = emptyList(),
                providerName = "Unknown Symbol",
                fetchedAtEpochMs = nowMs,
                errorMessage = "Enter a valid pair or ticker such as EURUSD, GBPUSD, XAUUSD, EURGBP, BTCUSDT, SOL, or DOGE."
            )

        InstrumentUniverse.find(instrument.symbol)?.let { known ->
            return@withContext fetchInstrumentBundle(known, timeframe, limit)
        }

        if (instrument.assetClass == AssetClass.CRYPTO) {
            return@withContext fetchCryptoBundle(instrument, timeframe, limit, nowMs)
        }

        val fxBundle = fetchFxBundle(instrument, timeframe, nowMs)
        if (fxBundle.candles.isNotEmpty()) return@withContext fxBundle

        // If the user typed a 3-5 char crypto ticker that wasn't auto-recognized, try <SYMBOL>USDT cleanly
        val alphaOnly = rawQuery.trim().uppercase().replace("/", "").replace("-", "").replace(" ", "")
        if (alphaOnly.length in 2..6 && alphaOnly !in FX_CURRENCIES) {
            val asCrypto = buildCustomCryptoInstrument(alphaOnly)
            val cryptoTry = fetchCryptoBundle(asCrypto, timeframe, limit, nowMs)
            if (cryptoTry.candles.isNotEmpty()) return@withContext cryptoTry
        }
        fxBundle
    }

    fun resolveAnyInstrument(rawQuery: String): Instrument? {
        val trimmed = rawQuery.trim().uppercase()
        if (trimmed.isEmpty()) return null
        val compact = trimmed.replace("/", "").replace("-", "").replace("_", "").replace(" ", "")

        InstrumentUniverse.find(compact)?.let { return it }

        when (compact) {
            "GOLD", "XAU", "XAUUSD" -> return InstrumentUniverse.find("XAUUSD")
            "SILVER", "XAG", "XAGUSD" -> return Instrument(
                symbol = "XAGUSD",
                displayName = "XAG/USD (Silver)",
                assetClass = AssetClass.FX,
                baseCurrency = "XAG",
                quoteCurrency = "USD",
                tickSize = 0.001,
                pipSize = 0.01,
                contractSize = 5_000.0,
                minLot = 0.01,
                lotStep = 0.01,
                baseSpreadPrice = 0.025,
                commissionPerUnitPct = 0.00004,
                yahooTicker = "SI=F"
            )
            "OIL", "WTI", "USOIL", "CLF" -> return Instrument(
                symbol = "USOIL",
                displayName = "WTI Crude Oil",
                assetClass = AssetClass.FX,
                baseCurrency = "WTI",
                quoteCurrency = "USD",
                tickSize = 0.01,
                pipSize = 0.01,
                contractSize = 1_000.0,
                minLot = 0.01,
                lotStep = 0.01,
                baseSpreadPrice = 0.03,
                commissionPerUnitPct = 0.00004,
                yahooTicker = "CL=F"
            )
            "NAS100", "USTEC", "NDX", "QQQ" -> return Instrument(
                symbol = "NAS100",
                displayName = "Nasdaq 100",
                assetClass = AssetClass.FX,
                baseCurrency = "NDX",
                quoteCurrency = "USD",
                tickSize = 0.1,
                pipSize = 1.0,
                contractSize = 1.0,
                minLot = 0.1,
                lotStep = 0.1,
                baseSpreadPrice = 1.5,
                commissionPerUnitPct = 0.00005,
                yahooTicker = "NQ=F"
            )
            "SPX500", "SP500", "US500" -> return Instrument(
                symbol = "SPX500",
                displayName = "S&P 500 Futures",
                assetClass = AssetClass.FX,
                baseCurrency = "SPX",
                quoteCurrency = "USD",
                tickSize = 0.1,
                pipSize = 1.0,
                contractSize = 1.0,
                minLot = 0.1,
                lotStep = 0.1,
                baseSpreadPrice = 0.5,
                commissionPerUnitPct = 0.00005,
                yahooTicker = "ES=F"
            )
        }

        // Standard 6-letter FX pair e.g. EURGBP, AUDJPY, CHFJPY
        if (compact.length == 6 && compact.all { it.isLetter() }) {
            val base = compact.substring(0, 3)
            val quote = compact.substring(3, 6)
            if (base in FX_CURRENCIES && quote in FX_CURRENCIES) {
                val isJpy = quote == "JPY"
                return Instrument(
                    symbol = compact,
                    displayName = "$base/$quote",
                    assetClass = AssetClass.FX,
                    baseCurrency = base,
                    quoteCurrency = quote,
                    tickSize = if (isJpy) 0.001 else 0.00001,
                    pipSize = if (isJpy) 0.01 else 0.0001,
                    contractSize = 100_000.0,
                    minLot = 0.01,
                    lotStep = 0.01,
                    baseSpreadPrice = if (isJpy) 0.020 else 0.00020,
                    commissionPerUnitPct = 0.000035,
                    yahooTicker = "${compact}=X"
                )
            }
        }

        // Explicit crypto suffix or short ticker
        if (compact.endsWith("USDT") || compact.endsWith("USDC") || compact.endsWith("BUSD") ||
            (compact.length > 3 && compact.endsWith("USD") && compact.substring(0, compact.length - 3) !in FX_CURRENCIES)
        ) {
            val base = when {
                compact.endsWith("USDT") -> compact.removeSuffix("USDT")
                compact.endsWith("USDC") -> compact.removeSuffix("USDC")
                compact.endsWith("BUSD") -> compact.removeSuffix("BUSD")
                else -> compact.removeSuffix("USD")
            }
            if (base.isNotEmpty()) return buildCustomCryptoInstrument(base)
        }

        if (compact.length in 2..6 && compact.all { it.isLetterOrDigit() }) {
            return buildCustomCryptoInstrument(compact)
        }

        // Direct Yahoo equity/index symbol fallback (preserve hyphens like ^GSPC or BRK-B)
        return Instrument(
            symbol = trimmed,
            displayName = trimmed,
            assetClass = AssetClass.FX,
            baseCurrency = trimmed.take(4),
            quoteCurrency = "USD",
            tickSize = 0.01,
            pipSize = 0.01,
            contractSize = 1.0,
            minLot = 0.01,
            lotStep = 0.01,
            baseSpreadPrice = 0.02,
            commissionPerUnitPct = 0.0001,
            yahooTicker = trimmed
        )
    }

    private fun buildCustomCryptoInstrument(baseTicker: String): Instrument {
        val base = baseTicker.uppercase()
        val sym = "${base}USDT"
        return InstrumentUniverse.find(sym) ?: Instrument(
            symbol = sym,
            displayName = "$base/USDT",
            assetClass = AssetClass.CRYPTO,
            baseCurrency = base,
            quoteCurrency = "USDT",
            tickSize = 0.0001,
            pipSize = 0.001,
            contractSize = 1.0,
            minLot = 0.01,
            lotStep = 0.01,
            baseSpreadPrice = 0.0005,
            commissionPerUnitPct = 0.0005,
            cryptoCluster = "ALT_SPOT_PERP",
            binanceSymbol = sym,
            bybitSymbol = sym
        )
    }

    private fun fetchCryptoBundle(
        instrument: Instrument,
        timeframe: Timeframe,
        limit: Int,
        nowMs: Long
    ): MarketFeedBundle {
        // Multi-provider globally unblocked waterfall (avoids geo-restricted HTTP 451/403 endpoints)
        val attemptErrors = mutableListOf<String>()

        // 1. OKX v5 Public Spot/Perp candles + OKX Swap funding (globally unblocked, HTTP 200)
        val okxResult = runCatching { fetchOkxKlines(instrument.baseCurrency, timeframe, limit) }
        val okxCandles = okxResult.getOrNull().orEmpty()
        if (okxCandles.isNotEmpty()) {
            val funding = runCatching { fetchOkxFunding(instrument.baseCurrency) }.getOrDefault(emptyList())
            val oi = runCatching { fetchBybitOpenInterest(instrument.bybitSymbol, timeframe) }.getOrDefault(emptyList())
            return MarketFeedBundle(
                instrument = instrument,
                timeframe = timeframe,
                candles = okxCandles,
                dailyCandles = if (timeframe == Timeframe.D1) okxCandles else emptyList(),
                fundingHistory = funding,
                openInterestHistory = oi,
                providerName = "OKX v5 Public",
                fetchedAtEpochMs = System.currentTimeMillis()
            )
        }
        okxResult.exceptionOrNull()?.localizedMessage?.let { attemptErrors.add("OKX: $it") }

        // 2. Binance Data Vision CDN (official Binance market-data mirror with NO HTTP 451 geo-block)
        val visionResult = runCatching { fetchBinanceVisionKlines(instrument.binanceSymbol, timeframe, limit) }
        val visionCandles = visionResult.getOrNull().orEmpty()
        if (visionCandles.isNotEmpty()) {
            val funding = runCatching { fetchOkxFunding(instrument.baseCurrency) }
                .getOrNull()?.takeIf { it.isNotEmpty() }
                ?: runCatching { fetchBybitFunding(instrument.bybitSymbol) }.getOrDefault(emptyList())
            return MarketFeedBundle(
                instrument = instrument,
                timeframe = timeframe,
                candles = visionCandles,
                dailyCandles = if (timeframe == Timeframe.D1) visionCandles else emptyList(),
                fundingHistory = funding,
                openInterestHistory = emptyList(),
                providerName = "Binance Vision Public",
                fetchedAtEpochMs = System.currentTimeMillis()
            )
        }
        visionResult.exceptionOrNull()?.localizedMessage?.let { attemptErrors.add("Vision: $it") }

        // 3. Bybit v5 (tries global mirror api.bytick.com + api.bybit.com)
        val bybitResult = runCatching { fetchBybitKlines(instrument.bybitSymbol, timeframe, limit) }
        val bybitCandles = bybitResult.getOrNull().orEmpty()
        if (bybitCandles.isNotEmpty()) {
            val funding = runCatching { fetchBybitFunding(instrument.bybitSymbol) }.getOrDefault(emptyList())
            val oi = runCatching { fetchBybitOpenInterest(instrument.bybitSymbol, timeframe) }.getOrDefault(emptyList())
            return MarketFeedBundle(
                instrument = instrument,
                timeframe = timeframe,
                candles = bybitCandles,
                dailyCandles = if (timeframe == Timeframe.D1) bybitCandles else emptyList(),
                fundingHistory = funding,
                openInterestHistory = oi,
                providerName = "Bybit v5 Public",
                fetchedAtEpochMs = System.currentTimeMillis()
            )
        }
        bybitResult.exceptionOrNull()?.localizedMessage?.let { attemptErrors.add("Bybit: $it") }

        // 4. Coinbase Exchange Public (US & globally accessible)
        val coinbaseResult = runCatching { fetchCoinbaseKlines(instrument.baseCurrency, timeframe, limit) }
        val coinbaseCandles = coinbaseResult.getOrNull().orEmpty()
        if (coinbaseCandles.isNotEmpty()) {
            val funding = runCatching { fetchOkxFunding(instrument.baseCurrency) }.getOrDefault(emptyList())
            return MarketFeedBundle(
                instrument = instrument,
                timeframe = timeframe,
                candles = coinbaseCandles,
                dailyCandles = if (timeframe == Timeframe.D1) coinbaseCandles else emptyList(),
                fundingHistory = funding,
                openInterestHistory = emptyList(),
                providerName = "Coinbase Public",
                fetchedAtEpochMs = System.currentTimeMillis()
            )
        }
        coinbaseResult.exceptionOrNull()?.localizedMessage?.let { attemptErrors.add("Coinbase: $it") }

        // 5. Kraken Public OHLC
        val krakenCandles = runCatching { fetchKrakenKlines(instrument.baseCurrency, timeframe) }.getOrNull().orEmpty()
        if (krakenCandles.isNotEmpty()) {
            return MarketFeedBundle(
                instrument = instrument,
                timeframe = timeframe,
                candles = krakenCandles,
                dailyCandles = if (timeframe == Timeframe.D1) krakenCandles else emptyList(),
                fundingHistory = emptyList(),
                openInterestHistory = emptyList(),
                providerName = "Kraken Public",
                fetchedAtEpochMs = System.currentTimeMillis()
            )
        }

        // 6. Yahoo Finance Crypto-USD chart fallback
        val yahooCandles = runCatching {
            fetchYahooFxCandles("${instrument.baseCurrency}-USD", timeframe)
        }.getOrNull().orEmpty()
        if (yahooCandles.isNotEmpty()) {
            return MarketFeedBundle(
                instrument = instrument,
                timeframe = timeframe,
                candles = yahooCandles,
                dailyCandles = if (timeframe == Timeframe.D1) yahooCandles else emptyList(),
                fundingHistory = emptyList(),
                openInterestHistory = emptyList(),
                providerName = "Yahoo Finance Chart v8",
                fetchedAtEpochMs = System.currentTimeMillis()
            )
        }

        // 7. Direct Binance Futures / Spot last fallback (skips exposing raw HTTP 451 when regional restriction applies)
        val binanceCandles = runCatching { fetchBinanceKlines(instrument.binanceSymbol, timeframe, limit) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: runCatching { fetchBinanceSpotKlines(instrument.binanceSymbol, timeframe, limit) }.getOrNull().orEmpty()
        if (binanceCandles.isNotEmpty()) {
            val funding = runCatching { fetchBinanceFunding(instrument.binanceSymbol) }.getOrDefault(emptyList())
            val oi = runCatching { fetchBinanceOpenInterest(instrument.binanceSymbol, timeframe) }.getOrDefault(emptyList())
            return MarketFeedBundle(
                instrument = instrument,
                timeframe = timeframe,
                candles = binanceCandles,
                dailyCandles = if (timeframe == Timeframe.D1) binanceCandles else emptyList(),
                fundingHistory = funding,
                openInterestHistory = oi,
                providerName = "Binance USD-M Public",
                fetchedAtEpochMs = System.currentTimeMillis()
            )
        }

        val summaryErr = attemptErrors.take(2).joinToString(" | ").ifBlank {
            "Unable to reach crypto market data feeds. Check internet connection or import CSV."
        }
        return MarketFeedBundle(
            instrument = instrument,
            timeframe = timeframe,
            candles = emptyList(),
            dailyCandles = emptyList(),
            fundingHistory = emptyList(),
            openInterestHistory = emptyList(),
            providerName = "Crypto Public Feed",
            fetchedAtEpochMs = nowMs,
            errorMessage = summaryErr
        )
    }

    private fun fetchFxBundle(
        instrument: Instrument,
        timeframe: Timeframe,
        nowMs: Long
    ): MarketFeedBundle {
        val primaryResult = runCatching { fetchYahooFxCandles(instrument.yahooTicker, timeframe) }
        val candles = primaryResult.getOrNull().orEmpty()
        val dailyCandles = if (timeframe == Timeframe.D1) {
            candles
        } else {
            runCatching { fetchYahooFxCandles(instrument.yahooTicker, Timeframe.D1) }.getOrDefault(emptyList())
        }

        if (candles.isNotEmpty()) {
            return MarketFeedBundle(
                instrument = instrument,
                timeframe = timeframe,
                candles = candles,
                dailyCandles = dailyCandles,
                fundingHistory = emptyList(),
                openInterestHistory = emptyList(),
                providerName = "Yahoo Finance Chart v8",
                fetchedAtEpochMs = System.currentTimeMillis()
            )
        }

        // Daily fallback via Frankfurter ECB timeseries if Yahoo unreachable
        if (timeframe == Timeframe.D1 && instrument.symbol != "XAUUSD") {
            val ecbCandles = runCatching { fetchFrankfurterDailyCandles(instrument) }.getOrDefault(emptyList())
            if (ecbCandles.isNotEmpty()) {
                return MarketFeedBundle(
                    instrument = instrument,
                    timeframe = timeframe,
                    candles = ecbCandles,
                    dailyCandles = ecbCandles,
                    fundingHistory = emptyList(),
                    openInterestHistory = emptyList(),
                    providerName = "Frankfurter ECB",
                    fetchedAtEpochMs = System.currentTimeMillis()
                )
            }
        }

        return MarketFeedBundle(
            instrument = instrument,
            timeframe = timeframe,
            candles = emptyList(),
            dailyCandles = emptyList(),
            fundingHistory = emptyList(),
            openInterestHistory = emptyList(),
            providerName = "FX Public Feed",
            fetchedAtEpochMs = nowMs,
            errorMessage = primaryResult.exceptionOrNull()?.localizedMessage ?: "FX public feed unavailable; import CSV to continue research."
        )
    }

    private fun fetchBybitKlines(symbol: String, tf: Timeframe, limit: Int): List<Candle> {
        val interval = when (tf) {
            Timeframe.M15 -> "15"
            Timeframe.H1 -> "60"
            Timeframe.H4 -> "240"
            Timeframe.D1 -> "D"
        }
        val cappedLimit = limit.coerceIn(50, 1000)
        val hosts = listOf("https://api.bytick.com", "https://api.bybit.com")
        var lastErr: Throwable? = null
        for (host in hosts) {
            try {
                val url = "$host/v5/market/kline?category=linear&symbol=$symbol&interval=$interval&limit=$cappedLimit"
                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("Bybit HTTP ${resp.code}")
                    val root = JSONObject(resp.body?.string() ?: error("Empty Bybit body"))
                    if (root.optInt("retCode", -1) != 0) error("Bybit error: ${root.optString("retMsg")}")
                    val list = root.getJSONObject("result").getJSONArray("list")
                    val tfMs = tf.durationMinutes * 60_000L
                    val out = ArrayList<Candle>(list.length())
                    for (i in list.length() - 1 downTo 0) {
                        val row = list.getJSONArray(i)
                        val openMs = row.getString(0).toLong()
                        out.add(
                            Candle(
                                openTimeEpochMs = openMs,
                                closeTimeEpochMs = openMs + tfMs,
                                open = row.getString(1).toDouble(),
                                high = row.getString(2).toDouble(),
                                low = row.getString(3).toDouble(),
                                close = row.getString(4).toDouble(),
                                volume = row.getString(5).toDouble(),
                                isClosed = true
                            )
                        )
                    }
                    return out
                }
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        throw (lastErr ?: IllegalStateException("Bybit market endpoint unreachable"))
    }

    private fun fetchOkxKlines(baseTicker: String, tf: Timeframe, limit: Int): List<Candle> {
        val bar = when (tf) {
            Timeframe.M15 -> "15m"
            Timeframe.H1 -> "1H"
            Timeframe.H4 -> "4H"
            Timeframe.D1 -> "1Dutc"
        }
        val instId = "${baseTicker.uppercase()}-USDT"
        val capped = limit.coerceIn(50, 300)
        val url = "https://www.okx.com/api/v5/market/candles?instId=$instId&bar=$bar&limit=$capped"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("OKX HTTP ${resp.code}")
            val root = JSONObject(resp.body?.string() ?: error("Empty OKX body"))
            if (root.optString("code") != "0") error("OKX error: ${root.optString("msg")}")
            val data = root.getJSONArray("data")
            val tfMs = tf.durationMinutes * 60_000L
            val out = ArrayList<Candle>(data.length())
            // OKX returns newest first
            for (i in data.length() - 1 downTo 0) {
                val row = data.getJSONArray(i)
                val openMs = row.getString(0).toLong()
                val confirm = row.optString(8, "1")
                out.add(
                    Candle(
                        openTimeEpochMs = openMs,
                        closeTimeEpochMs = openMs + tfMs,
                        open = row.getString(1).toDouble(),
                        high = row.getString(2).toDouble(),
                        low = row.getString(3).toDouble(),
                        close = row.getString(4).toDouble(),
                        volume = row.getString(5).toDouble(),
                        isClosed = confirm != "0"
                    )
                )
            }
            return out
        }
    }

    private fun fetchOkxFunding(baseTicker: String): List<FundingPoint> {
        val instId = "${baseTicker.uppercase()}-USDT-SWAP"
        val url = "https://www.okx.com/api/v5/public/funding-rate-history?instId=$instId&limit=100"
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val root = JSONObject(resp.body?.string() ?: return emptyList())
            if (root.optString("code") != "0") return emptyList()
            val data = root.optJSONArray("data") ?: return emptyList()
            val out = ArrayList<FundingPoint>(data.length())
            for (i in data.length() - 1 downTo 0) {
                val obj = data.getJSONObject(i)
                val t = obj.optString("fundingTime").toLongOrNull() ?: continue
                val rate = obj.optString("fundingRate").toDoubleOrNull() ?: continue
                out.add(FundingPoint(timestampEpochMs = t, fundingRate = rate))
            }
            return out
        }
    }

    private fun fetchBinanceVisionKlines(symbol: String, tf: Timeframe, limit: Int): List<Candle> {
        val interval = when (tf) {
            Timeframe.M15 -> "15m"
            Timeframe.H1 -> "1h"
            Timeframe.H4 -> "4h"
            Timeframe.D1 -> "1d"
        }
        val url = "https://data-api.binance.vision/api/v3/klines?symbol=$symbol&interval=$interval&limit=${limit.coerceIn(50, 1000)}"
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Binance Vision HTTP ${resp.code}")
            val arr = JSONArray(resp.body?.string() ?: error("Empty Binance Vision body"))
            return parseBinanceArrayCandles(arr)
        }
    }

    private fun fetchCoinbaseKlines(baseTicker: String, tf: Timeframe, limit: Int): List<Candle> {
        val granularitySec = when (tf) {
            Timeframe.M15 -> 900
            Timeframe.H1 -> 3600
            Timeframe.H4 -> 3600 // Resampled cleanly to 4H below
            Timeframe.D1 -> 86400
        }
        val productId = "${baseTicker.uppercase()}-USD"
        val url = "https://api.exchange.coinbase.com/products/$productId/candles?granularity=$granularitySec"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "ShehzadAlgo/1.0")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Coinbase HTTP ${resp.code}")
            val arr = JSONArray(resp.body?.string() ?: error("Empty Coinbase body"))
            val stepMs = granularitySec * 1000L
            val out = ArrayList<Candle>(arr.length())
            // Coinbase returns newest first: [timeSec, low, high, open, close, volume]
            for (i in arr.length() - 1 downTo 0) {
                val row = arr.getJSONArray(i)
                val openMs = row.getLong(0) * 1000L
                out.add(
                    Candle(
                        openTimeEpochMs = openMs,
                        closeTimeEpochMs = openMs + stepMs,
                        open = row.getDouble(3),
                        high = row.getDouble(2),
                        low = row.getDouble(1),
                        close = row.getDouble(4),
                        volume = row.getDouble(5),
                        isClosed = true
                    )
                )
            }
            val parsed = if (tf == Timeframe.H4) resampleTo4H(out) else out
            return parsed.takeLast(limit)
        }
    }

    private fun fetchKrakenKlines(baseTicker: String, tf: Timeframe): List<Candle> {
        val krakenBase = if (baseTicker.equals("BTC", ignoreCase = true)) "XBT" else baseTicker.uppercase()
        val interval = when (tf) {
            Timeframe.M15 -> 15
            Timeframe.H1 -> 60
            Timeframe.H4 -> 240
            Timeframe.D1 -> 1440
        }
        val url = "https://api.kraken.com/0/public/OHLC?pair=${krakenBase}USD&interval=$interval"
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Kraken HTTP ${resp.code}")
            val root = JSONObject(resp.body?.string() ?: error("Empty Kraken body"))
            val errs = root.optJSONArray("error")
            if (errs != null && errs.length() > 0) error("Kraken error: ${errs.optString(0)}")
            val res = root.getJSONObject("result")
            val pairKey = res.keys().asSequence().firstOrNull { it != "last" } ?: return emptyList()
            val arr = res.getJSONArray(pairKey)
            val tfMs = tf.durationMinutes * 60_000L
            val out = ArrayList<Candle>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.getJSONArray(i)
                val openMs = row.getLong(0) * 1000L
                out.add(
                    Candle(
                        openTimeEpochMs = openMs,
                        closeTimeEpochMs = openMs + tfMs,
                        open = row.getString(1).toDouble(),
                        high = row.getString(2).toDouble(),
                        low = row.getString(3).toDouble(),
                        close = row.getString(4).toDouble(),
                        volume = row.getString(6).toDouble(),
                        isClosed = true
                    )
                )
            }
            return out
        }
    }

    private fun fetchCryptoSpotPricesViaBinanceVision(): Map<String, Double> {
        val url = "https://data-api.binance.vision/api/v3/ticker/price?symbols=%5B%22BTCUSDT%22,%22ETHUSDT%22,%22SOLUSDT%22,%22BNBUSDT%22,%22XRPUSDT%22%5D"
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyMap()
            val arr = JSONArray(resp.body?.string() ?: return emptyMap())
            val map = mutableMapOf<String, Double>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val sym = obj.optString("symbol")
                val price = obj.optString("price").toDoubleOrNull() ?: continue
                if (sym.isNotBlank() && price > 0.0) {
                    map[sym] = price
                }
            }
            return map
        }
    }

    private fun fetchCryptoSpotPricesViaOkx(): Map<String, Double> {
        val url = "https://www.okx.com/api/v5/market/index-tickers?quoteCcy=USD"
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyMap()
            val root = JSONObject(resp.body?.string() ?: return emptyMap())
            if (root.optString("code") != "0") return emptyMap()
            val data = root.optJSONArray("data") ?: return emptyList<Pair<String, Double>>().toMap()
            val map = mutableMapOf<String, Double>()
            val wanted = setOf("BTC-USD", "ETH-USD", "SOL-USD", "BNB-USD", "XRP-USD")
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val instId = obj.optString("instId")
                if (instId !in wanted) continue
                val price = obj.optString("idxPx").toDoubleOrNull() ?: continue
                if (price > 0.0) {
                    map[instId.removeSuffix("-USD") + "USDT"] = price
                }
            }
            return map
        }
    }

    private fun parseBinanceArrayCandles(arr: JSONArray): List<Candle> {
        val out = ArrayList<Candle>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.getJSONArray(i)
            out.add(
                Candle(
                    openTimeEpochMs = row.getLong(0),
                    closeTimeEpochMs = row.getLong(6) + 1L,
                    open = row.getString(1).toDouble(),
                    high = row.getString(2).toDouble(),
                    low = row.getString(3).toDouble(),
                    close = row.getString(4).toDouble(),
                    volume = row.getString(5).toDouble(),
                    isClosed = true
                )
            )
        }
        return out
    }

    private fun fetchBybitFunding(symbol: String): List<FundingPoint> {
        for (host in listOf("https://api.bytick.com", "https://api.bybit.com")) {
            val res = runCatching {
                val url = "$host/v5/market/funding/history?category=linear&symbol=$symbol&limit=200"
                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val root = JSONObject(resp.body?.string() ?: return@use emptyList())
                    if (root.optInt("retCode", -1) != 0) return@use emptyList()
                    val list = root.getJSONObject("result").getJSONArray("list")
                    val out = ArrayList<FundingPoint>(list.length())
                    for (i in list.length() - 1 downTo 0) {
                        val obj = list.getJSONObject(i)
                        out.add(
                            FundingPoint(
                                timestampEpochMs = obj.getString("fundingRateTimestamp").toLong(),
                                fundingRate = obj.getString("fundingRate").toDouble()
                            )
                        )
                    }
                    out
                }
            }.getOrDefault(emptyList())
            if (res.isNotEmpty()) return res
        }
        return emptyList()
    }

    private fun fetchBybitOpenInterest(symbol: String, tf: Timeframe): List<OpenInterestPoint> {
        val interval = when (tf) {
            Timeframe.M15 -> "15min"
            Timeframe.H1 -> "1h"
            Timeframe.H4 -> "4h"
            Timeframe.D1 -> "1d"
        }
        for (host in listOf("https://api.bytick.com", "https://api.bybit.com")) {
            val res = runCatching {
                val url = "$host/v5/market/open-interest?category=linear&symbol=$symbol&intervalTime=$interval&limit=200"
                val req = Request.Builder().url(url).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val root = JSONObject(resp.body?.string() ?: return@use emptyList())
                    if (root.optInt("retCode", -1) != 0) return@use emptyList()
                    val list = root.getJSONObject("result").getJSONArray("list")
                    val out = ArrayList<OpenInterestPoint>(list.length())
                    for (i in list.length() - 1 downTo 0) {
                        val obj = list.getJSONObject(i)
                        out.add(
                            OpenInterestPoint(
                                timestampEpochMs = obj.getString("timestamp").toLong(),
                                openInterest = obj.getString("openInterest").toDouble()
                            )
                        )
                    }
                    out
                }
            }.getOrDefault(emptyList())
            if (res.isNotEmpty()) return res
        }
        return emptyList()
    }

    private fun fetchBinanceKlines(symbol: String, tf: Timeframe, limit: Int): List<Candle> {
        val interval = when (tf) {
            Timeframe.M15 -> "15m"
            Timeframe.H1 -> "1h"
            Timeframe.H4 -> "4h"
            Timeframe.D1 -> "1d"
        }
        val url = "https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=$interval&limit=${limit.coerceIn(50, 1500)}"
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Binance HTTP ${resp.code}")
            val arr = JSONArray(resp.body?.string() ?: error("Empty Binance body"))
            return parseBinanceArrayCandles(arr)
        }
    }

    private fun fetchBinanceSpotKlines(symbol: String, tf: Timeframe, limit: Int): List<Candle> {
        val interval = when (tf) {
            Timeframe.M15 -> "15m"
            Timeframe.H1 -> "1h"
            Timeframe.H4 -> "4h"
            Timeframe.D1 -> "1d"
        }
        val url = "https://api.binance.com/api/v3/klines?symbol=$symbol&interval=$interval&limit=${limit.coerceIn(50, 1000)}"
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("Binance Spot HTTP ${resp.code}")
            val arr = JSONArray(resp.body?.string() ?: error("Empty Binance Spot body"))
            return parseBinanceArrayCandles(arr)
        }
    }

    private fun fetchBinanceFunding(symbol: String): List<FundingPoint> {
        val url = "https://fapi.binance.com/fapi/v1/fundingRate?symbol=$symbol&limit=250"
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val arr = JSONArray(resp.body?.string() ?: return emptyList())
            return (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                FundingPoint(obj.getLong("fundingTime"), obj.getString("fundingRate").toDouble())
            }
        }
    }

    private fun fetchBinanceOpenInterest(symbol: String, tf: Timeframe): List<OpenInterestPoint> {
        val period = when (tf) {
            Timeframe.M15 -> "15m"
            Timeframe.H1 -> "1h"
            Timeframe.H4 -> "4h"
            Timeframe.D1 -> "1d"
        }
        val url = "https://fapi.binance.com/futures/data/openInterestHist?symbol=$symbol&period=$period&limit=200"
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val arr = JSONArray(resp.body?.string() ?: return emptyList())
            return (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                OpenInterestPoint(obj.getLong("timestamp"), obj.getString("sumOpenInterest").toDouble())
            }
        }
    }

    private fun fetchYahooFxCandles(yahooTicker: String, tf: Timeframe): List<Candle> {
        val (interval, range) = when (tf) {
            Timeframe.M15 -> "15m" to "30d"
            Timeframe.H1 -> "1h" to "90d"
            Timeframe.H4 -> "1h" to "180d"
            Timeframe.D1 -> "1d" to "2y"
        }
        val hosts = listOf("https://query1.finance.yahoo.com", "https://query2.finance.yahoo.com")
        var lastErr: Throwable? = null
        for (host in hosts) {
            try {
                val url = "$host/v8/finance/chart/$yahooTicker?interval=$interval&range=$range"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36")
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("Yahoo HTTP ${resp.code}")
                    val root = JSONObject(resp.body?.string() ?: error("Empty Yahoo body"))
                    val resultArr = root.getJSONObject("chart").getJSONArray("result")
                    if (resultArr.length() == 0) return emptyList()
                    val res = resultArr.getJSONObject(0)
                    val timestamps = res.getJSONArray("timestamp")
                    val quote = res.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
                    val opens = quote.getJSONArray("open")
                    val highs = quote.getJSONArray("high")
                    val lows = quote.getJSONArray("low")
                    val closes = quote.getJSONArray("close")
                    val vols = quote.optJSONArray("volume")

                    val baseStepMs = if (tf == Timeframe.H4) 3600_000L else tf.durationMinutes * 60_000L
                    val parsed = ArrayList<Candle>(timestamps.length())
                    for (i in 0 until timestamps.length()) {
                        if (opens.isNull(i) || highs.isNull(i) || lows.isNull(i) || closes.isNull(i)) continue
                        val openMs = timestamps.getLong(i) * 1000L
                        val vol = if (vols != null && !vols.isNull(i)) vols.optDouble(i, 0.0) else 0.0
                        parsed.add(
                            Candle(
                                openTimeEpochMs = openMs,
                                closeTimeEpochMs = openMs + baseStepMs,
                                open = opens.getDouble(i),
                                high = highs.getDouble(i),
                                low = lows.getDouble(i),
                                close = closes.getDouble(i),
                                volume = vol,
                                isClosed = true
                            )
                        )
                    }
                    return if (tf == Timeframe.H4) resampleTo4H(parsed) else parsed
                }
            } catch (t: Throwable) {
                lastErr = t
            }
        }
        throw (lastErr ?: IllegalStateException("Yahoo chart endpoint unreachable"))
    }

    private fun resampleTo4H(hourly: List<Candle>): List<Candle> {
        if (hourly.isEmpty()) return emptyList()
        val bucketMs = 4 * 3600_000L
        return hourly.groupBy { (it.openTimeEpochMs / bucketMs) * bucketMs }
            .toSortedMap()
            .map { (bucketStart, group) ->
                val sorted = group.sortedBy { it.openTimeEpochMs }
                Candle(
                    openTimeEpochMs = bucketStart,
                    closeTimeEpochMs = bucketStart + bucketMs,
                    open = sorted.first().open,
                    high = sorted.maxOf { it.high },
                    low = sorted.minOf { it.low },
                    close = sorted.last().close,
                    volume = sorted.sumOf { it.volume },
                    isClosed = true
                )
            }
    }

    private fun fetchFrankfurterDailyCandles(instrument: Instrument): List<Candle> {
        val end = LocalDate.now(ZoneOffset.UTC)
        val start = end.minusYears(2)
        val base = instrument.baseCurrency
        val quote = instrument.quoteCurrency
        val url = "https://api.frankfurter.dev/v1/$start..$end?base=$base&symbols=$quote"
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val root = JSONObject(resp.body?.string() ?: return emptyList())
            val ratesObj = root.getJSONObject("rates")
            val keys = ratesObj.keys().asSequence().toList().sorted()
            val out = ArrayList<Candle>(keys.size)
            var prevRate: Double? = null
            for (dateStr in keys) {
                val r = ratesObj.getJSONObject(dateStr).optDouble(quote, Double.NaN)
                if (r.isNaN() || r <= 0.0) continue
                val d = LocalDate.parse(dateStr)
                val openMs = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                val closeMs = openMs + 86_400_000L
                val open = prevRate ?: r
                val high = maxOf(open, r) * 1.0012
                val low = minOf(open, r) * 0.9988
                out.add(Candle(openMs, closeMs, open, high, low, r, 0.0, isClosed = true))
                prevRate = r
            }
            return out
        }
    }

    fun parseCsvCandles(inputStream: InputStream, timeframe: Timeframe): List<Candle> {
        val tfMs = timeframe.durationMinutes * 60_000L
        val reader = BufferedReader(InputStreamReader(inputStream))
        val out = mutableListOf<Candle>()
        reader.useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.lowercase().startsWith("time") || line.lowercase().startsWith("date")) {
                    return@forEach
                }
                val parts = line.split(',', ';', '\t').map { it.trim() }
                if (parts.size < 5) return@forEach
                val tsMs = parseTimestampToEpochMs(parts[0]) ?: return@forEach
                val o = parts[1].toDoubleOrNull() ?: return@forEach
                val h = parts[2].toDoubleOrNull() ?: return@forEach
                val l = parts[3].toDoubleOrNull() ?: return@forEach
                val c = parts[4].toDoubleOrNull() ?: return@forEach
                val v = parts.getOrNull(5)?.toDoubleOrNull() ?: 0.0
                out.add(Candle(tsMs, tsMs + tfMs, o, h, l, c, v, isClosed = true))
            }
        }
        return out.distinctBy { it.openTimeEpochMs }.sortedBy { it.openTimeEpochMs }
    }

    private fun parseTimestampToEpochMs(raw: String): Long? {
        raw.toLongOrNull()?.let { num ->
            return if (num < 10_000_000_000L) num * 1000L else num
        }
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(raw).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()
    }

    /**
     * Generates scheduled recurring high-impact macro calendar windows (US NFP, CPI, FOMC)
     * in UTC with explicit release timestamps (never period dates).
     */
    fun upcomingAndRecentMacroEvents(nowEpochMs: Long): List<EconomicEvent> {
        val nowZ = Instant.ofEpochMilli(nowEpochMs).atZone(ZoneOffset.UTC)
        val events = mutableListOf<EconomicEvent>()
        for (monthOffset in -1..1) {
            val ref = nowZ.plusMonths(monthOffset.toLong())
            val firstDay = ref.withDayOfMonth(1).withHour(13).withMinute(30).withSecond(0).withNano(0)
            // First Friday of month -> US NFP 13:30 UTC
            var nfp = firstDay
            while (nfp.dayOfWeek != java.time.DayOfWeek.FRIDAY) {
                nfp = nfp.plusDays(1)
            }
            events.add(
                EconomicEvent(
                    id = "NFP_${nfp.year}_${nfp.monthValue}",
                    title = "US Non-Farm Payrolls (NFP)",
                    currency = "USD",
                    impact = "HIGH",
                    periodDateIso = nfp.minusMonths(1).toLocalDate().toString(),
                    releaseEpochMs = nfp.toInstant().toEpochMilli()
                )
            )
            // 13th of month -> US CPI 13:30 UTC
            val cpi = ref.withDayOfMonth(13).withHour(13).withMinute(30).withSecond(0).withNano(0)
            events.add(
                EconomicEvent(
                    id = "CPI_${cpi.year}_${cpi.monthValue}",
                    title = "US CPI Release",
                    currency = "USD",
                    impact = "HIGH",
                    periodDateIso = cpi.minusMonths(1).toLocalDate().toString(),
                    releaseEpochMs = cpi.toInstant().toEpochMilli()
                )
            )
        }
        return events.sortedBy { it.releaseEpochMs }
    }

    companion object {
        private val FX_CURRENCIES = setOf(
            "USD", "EUR", "GBP", "JPY", "AUD", "CAD", "CHF", "NZD",
            "SGD", "HKD", "NOK", "SEK", "MXN", "ZAR", "TRY", "CNH", "INR", "PKR"
        )
    }
}
