package com.example.binancephonescanner

import android.content.Context
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Follows every detected symbol for 5 days after the detection, using 1-minute candles:
 *
 *  - Stop-loss: the low of the detected candle. It counts as hit when any later 1-minute candle
 *    trades below that low. The time of the first such candle is saved.
 *  - Peak: the highest price within 5 days after the detection, as if the stop-loss had not
 *    existed. The highest price reached before the stop-loss was hit is saved separately.
 *
 * Only the new minutes since the last update are downloaded, so each update is small. Limits:
 * within a single 1-minute candle it is unknown whether the low or the high came first, so a
 * candle that breaks the stop-loss is counted as the stop (its high is not counted as
 * "before the stop").
 */
object HistoryTracker {
    private const val WINDOW_MS = 5L * 24 * 60 * 60 * 1000
    private const val MINUTE_MS = 60_000L

    /** Updates every unfinished detection of one watch (latest price, stop-loss, 5-day peak). Returns how many were updated. */
    fun update(context: Context, watch: Watch): Int {
        val now = System.currentTimeMillis()
        val pending = ScanHistory.load(context, watch)
            .flatMap { it.matches }
            .filter { it.isTrackable && !it.done }

        var updatedCount = 0
        if (pending.isNotEmpty()) {
            val pool = Executors.newFixedThreadPool(6)
            try {
                val tasks = pending.map { match ->
                    Callable<ScanHistory.MatchRecord?> {
                        try { track(match, now) } catch (_: Exception) { null }
                    }
                }
                val updated = pool.invokeAll(tasks, 90, TimeUnit.SECONDS).mapNotNull { future ->
                    try { if (future.isCancelled) null else future.get() } catch (_: Exception) { null }
                }
                ScanHistory.applyTracking(context, watch, updated)
                updatedCount = updated.size
            } finally {
                pool.shutdownNow()
            }
        }
        context.getSharedPreferences(ScannerService.PREFS, Context.MODE_PRIVATE)
            .edit().putLong(watch.key("track_updated"), now).apply()
        return updatedCount
    }

    private fun track(m: ScanHistory.MatchRecord, now: Long): ScanHistory.MatchRecord {
        val windowEnd = m.observedAt + WINDOW_MS
        val fetchEnd = minOf(now, windowEnd)

        var start = if (m.trackedUntil > 0L) m.trackedUntil else m.observedAt - (m.observedAt % MINUTE_MS)
        var stopTime = m.stopTime
        var peak = if (m.peakPrice > 0.0) m.peakPrice else m.price
        var peakTime = if (m.peakTime > 0L) m.peakTime else m.observedAt
        var peakBeforeStop = if (m.peakBeforeStop > 0.0) m.peakBeforeStop else m.price
        var lastOpen = start
        var lastClose = if (m.lastPrice > 0.0) m.lastPrice else m.price
        var lastCloseTime = m.lastPriceTime

        while (start <= fetchEnd) {
            val wanted = ((fetchEnd - start) / MINUTE_MS + 2).coerceIn(2L, 1500L).toInt()
            val url = "https://fapi.binance.com/fapi/v1/klines?symbol=${m.symbol}&interval=1m" +
                "&startTime=$start&endTime=$fetchEnd&limit=$wanted"
            val rows = JSONArray(httpGet(url))
            if (rows.length() == 0) break

            for (i in 0 until rows.length()) {
                val row = rows.getJSONArray(i)
                val openTime = row.getLong(0)
                val high = row.getString(2).toDouble()
                val low = row.getString(3).toDouble()

                // The peak is counted from the candle AFTER the detected candle (peakStart), so
                // the rest of the candle that met the conditions is excluded. Records saved by
                // older versions have no peakStart: in the detection minute a new high only
                // counts if it beats the high known at detection.
                val detectionMinute = m.observedAt >= openTime && m.observedAt < openTime + MINUTE_MS
                val highCounts = if (m.peakStart > 0L) openTime >= m.peakStart
                else (!detectionMinute || high > m.highAtDetection)
                // A low below the stop level can only have happened after the detection.
                val breaksStop = low < m.lowPrice

                if (highCounts && high > peak) {
                    peak = high
                    peakTime = openTime
                }
                if (stopTime == 0L) {
                    if (breaksStop) stopTime = openTime
                    else if (highCounts && high > peakBeforeStop) peakBeforeStop = high
                }
                lastOpen = openTime
                lastClose = row.getString(4).toDouble()
                lastCloseTime = now
            }
            if (rows.length() < wanted) break
            start = lastOpen + MINUTE_MS
        }

        return m.copy(
            trackedUntil = lastOpen,
            stopTime = stopTime,
            peakPrice = peak,
            peakTime = peakTime,
            peakBeforeStop = peakBeforeStop,
            done = now >= windowEnd + MINUTE_MS,
            lastPrice = lastClose,
            lastPriceTime = lastCloseTime
        )
    }

    private fun httpGet(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 12_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", "BinancePhoneScanner/1.1")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            body
        } finally {
            connection.disconnect()
        }
    }
}
