package com.example.quant

import com.example.domain.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class BollingerResult(
    val middle: Double,
    val upper: Double,
    val lower: Double,
    val bandwidth: Double
)

object Indicators {

    fun sma(values: List<Double>, period: Int): List<Double?> {
        if (period <= 0 || values.isEmpty()) return List(values.size) { null }
        val result = MutableList<Double?>(values.size) { null }
        var sum = 0.0
        for (i in values.indices) {
            sum += values[i]
            if (i >= period) sum -= values[i - period]
            if (i >= period - 1) result[i] = sum / period
        }
        return result
    }

    fun ema(values: List<Double>, period: Int): List<Double?> {
        if (period <= 0 || values.size < period) return List(values.size) { null }
        val result = MutableList<Double?>(values.size) { null }
        val k = 2.0 / (period + 1.0)
        var seed = 0.0
        for (i in 0 until period) seed += values[i]
        var prev = seed / period
        result[period - 1] = prev
        for (i in period until values.size) {
            prev = values[i] * k + prev * (1.0 - k)
            result[i] = prev
        }
        return result
    }

    fun trueRange(candles: List<Candle>): List<Double> {
        if (candles.isEmpty()) return emptyList()
        val tr = MutableList(candles.size) { 0.0 }
        tr[0] = candles[0].high - candles[0].low
        for (i in 1 until candles.size) {
            val prevClose = candles[i - 1].close
            val c = candles[i]
            tr[i] = max(
                c.high - c.low,
                max(abs(c.high - prevClose), abs(c.low - prevClose))
            )
        }
        return tr
    }

    fun atr(candles: List<Candle>, period: Int = 14): List<Double?> {
        if (period <= 0 || candles.size < period) return List(candles.size) { null }
        val tr = trueRange(candles)
        val result = MutableList<Double?>(candles.size) { null }
        var sum = 0.0
        for (i in 0 until period) sum += tr[i]
        var prev = sum / period
        result[period - 1] = prev
        for (i in period until candles.size) {
            prev = ((prev * (period - 1)) + tr[i]) / period
            result[i] = prev
        }
        return result
    }

    fun adx(candles: List<Candle>, period: Int = 14): List<Double?> {
        if (period <= 1 || candles.size < period * 2) return List(candles.size) { null }
        val n = candles.size
        val tr = trueRange(candles)
        val plusDm = DoubleArray(n)
        val minusDm = DoubleArray(n)

        for (i in 1 until n) {
            val upMove = candles[i].high - candles[i - 1].high
            val downMove = candles[i - 1].low - candles[i].low
            plusDm[i] = if (upMove > downMove && upMove > 0.0) upMove else 0.0
            minusDm[i] = if (downMove > upMove && downMove > 0.0) downMove else 0.0
        }

        var smoothTr = 0.0
        var smoothPlusDm = 0.0
        var smoothMinusDm = 0.0
        for (i in 1..period) {
            smoothTr += tr[i]
            smoothPlusDm += plusDm[i]
            smoothMinusDm += minusDm[i]
        }

        val dx = DoubleArray(n)
        for (i in period until n) {
            if (i > period) {
                smoothTr = smoothTr - (smoothTr / period) + tr[i]
                smoothPlusDm = smoothPlusDm - (smoothPlusDm / period) + plusDm[i]
                smoothMinusDm = smoothMinusDm - (smoothMinusDm / period) + minusDm[i]
            }
            val plusDi = if (smoothTr > 0.0) 100.0 * (smoothPlusDm / smoothTr) else 0.0
            val minusDi = if (smoothTr > 0.0) 100.0 * (smoothMinusDm / smoothTr) else 0.0
            val diSum = plusDi + minusDi
            dx[i] = if (diSum > 0.0) 100.0 * abs(plusDi - minusDi) / diSum else 0.0
        }

        val result = MutableList<Double?>(n) { null }
        var adxSum = 0.0
        val startAdxIdx = period * 2 - 1
        for (i in period..startAdxIdx) {
            adxSum += dx[i]
        }
        var prevAdx = adxSum / period
        result[startAdxIdx] = prevAdx
        for (i in (startAdxIdx + 1) until n) {
            prevAdx = ((prevAdx * (period - 1)) + dx[i]) / period
            result[i] = prevAdx
        }
        return result
    }

    /**
     * Prior N-bar high excluding the current bar [index] to prevent self-inclusion look-ahead.
     */
    fun priorDonchianHigh(candles: List<Candle>, index: Int, period: Int): Double? {
        if (index < period || period <= 0) return null
        var maxHigh = Double.NEGATIVE_INFINITY
        for (i in (index - period) until index) {
            if (candles[i].high > maxHigh) maxHigh = candles[i].high
        }
        return if (maxHigh.isFinite()) maxHigh else null
    }

    /**
     * Prior N-bar low excluding the current bar [index].
     */
    fun priorDonchianLow(candles: List<Candle>, index: Int, period: Int): Double? {
        if (index < period || period <= 0) return null
        var minLow = Double.POSITIVE_INFINITY
        for (i in (index - period) until index) {
            if (candles[i].low < minLow) minLow = candles[i].low
        }
        return if (minLow.isFinite()) minLow else null
    }

    fun bollingerBands(values: List<Double>, period: Int = 20, stdMult: Double = 2.0): List<BollingerResult?> {
        if (period <= 1 || values.size < period) return List(values.size) { null }
        val result = MutableList<BollingerResult?>(values.size) { null }
        for (i in (period - 1) until values.size) {
            val window = values.subList(i - period + 1, i + 1)
            val mean = window.average()
            var variance = 0.0
            for (v in window) {
                val d = v - mean
                variance += d * d
            }
            val std = sqrt(variance / period)
            val upper = mean + stdMult * std
            val lower = mean - stdMult * std
            val bw = if (mean != 0.0) (upper - lower) / mean else 0.0
            result[i] = BollingerResult(mean, upper, lower, bw)
        }
        return result
    }

    fun rollingMedian(values: List<Double?>, index: Int, lookback: Int): Double? {
        val start = max(0, index - lookback + 1)
        val slice = values.subList(start, index + 1).filterNotNull().sorted()
        if (slice.isEmpty()) return null
        val mid = slice.size / 2
        return if (slice.size % 2 == 1) slice[mid] else (slice[mid - 1] + slice[mid]) / 2.0
    }

    fun atrPercentile(atrSeries: List<Double?>, index: Int, lookback: Int = 250): Double? {
        val current = atrSeries.getOrNull(index) ?: return null
        val start = max(0, index - lookback + 1)
        val window = atrSeries.subList(start, index + 1).filterNotNull()
        if (window.size < 20) return null
        val lessOrEqual = window.count { it <= current }
        return (lessOrEqual.toDouble() / window.size.toDouble()) * 100.0
    }

    fun zScore(values: List<Double>, index: Int, lookback: Int): Double? {
        if (index < 1 || lookback < 2) return null
        val start = max(0, index - lookback + 1)
        val window = values.subList(start, index + 1)
        if (window.size < 5) return null
        val mean = window.average()
        var varSum = 0.0
        for (v in window) {
            val d = v - mean
            varSum += d * d
        }
        val std = sqrt(varSum / window.size)
        if (std <= 1e-12) return 0.0
        return (values[index] - mean) / std
    }

    fun realizedVolatility(closes: List<Double>, index: Int, lookback: Int = 20): Double? {
        if (index < lookback) return null
        val returns = ArrayList<Double>(lookback)
        for (i in (index - lookback + 1)..index) {
            val prev = closes[i - 1]
            if (prev > 0.0 && closes[i] > 0.0) {
                returns.add(kotlin.math.ln(closes[i] / prev))
            }
        }
        if (returns.size < 2) return null
        val mean = returns.average()
        var sumSq = 0.0
        for (r in returns) {
            val d = r - mean
            sumSq += d * d
        }
        return sqrt(sumSq / (returns.size - 1)) * sqrt(252.0)
    }

    fun rollingBeta(assetReturns: List<Double>, benchmarkReturns: List<Double>): Double {
        val n = minOf(assetReturns.size, benchmarkReturns.size)
        if (n < 10) return 1.0
        val a = assetReturns.takeLast(n)
        val b = benchmarkReturns.takeLast(n)
        val meanA = a.average()
        val meanB = b.average()
        var cov = 0.0
        var varB = 0.0
        for (i in 0 until n) {
            cov += (a[i] - meanA) * (b[i] - meanB)
            val db = b[i] - meanB
            varB += db * db
        }
        return if (varB > 1e-12) cov / varB else 1.0
    }
}
