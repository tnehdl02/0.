package com.example.binancephonescanner

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Keeps the results of every successful scan for the last [RETENTION_DAYS] days, one file per
 * timeframe (history_m15.jsonl / history_h1.jsonl in the app's private storage). Each line is
 * one scan: when it ran, how many symbols were checked and which symbols matched.
 *
 * Every matched symbol also carries tracking data (see [HistoryTracker]): the stop-loss level
 * (the low of the detected candle), whether and when the price fell below it, and the highest
 * price reached within 5 days after the detection.
 *
 * Scans that matched nothing are stored too, so the history screen can say "N detections out
 * of M scans".
 */
object ScanHistory {
    const val RETENTION_DAYS = 5L

    /**
     * One detected symbol.
     *
     * price            : price at the moment of detection (used as the entry price)
     * lowPrice         : low of the detected candle = stop-loss level (0 = unknown, not tracked)
     * highAtDetection  : candle high at detection time
     * observedAt       : when the symbol was checked (epoch ms)
     * trackedUntil     : open time of the last 1-minute candle already processed
     * stopTime         : open time of the first 1-minute candle that traded below lowPrice (0 = none)
     * peakPrice/Time   : highest price within 5 days after detection, ignoring the stop-loss
     * peakBeforeStop   : highest price reached before the stop-loss was hit (= peakPrice if never hit)
     * done             : the 5-day tracking window is over
     */
    data class MatchRecord(
        val symbol: String,
        val price: Double,
        val highPct: Double,
        val changePct: Double,
        val volumeMultiple: Double,
        val lowPrice: Double = 0.0,
        val highAtDetection: Double = 0.0,
        val observedAt: Long = 0L,
        val trackedUntil: Long = 0L,
        val stopTime: Long = 0L,
        val peakPrice: Double = 0.0,
        val peakTime: Long = 0L,
        val peakBeforeStop: Double = 0.0,
        val done: Boolean = false
    ) {
        val isTrackable: Boolean get() = lowPrice > 0.0 && observedAt > 0L && price > 0.0
        val stopPct: Double get() = (lowPrice - price) / price * 100.0
        val peakPct: Double get() = (maxOf(peakPrice, price) - price) / price * 100.0
        val peakBeforeStopPct: Double get() = (maxOf(peakBeforeStop, price) - price) / price * 100.0
    }

    data class ScanRecord(
        val timeMillis: Long,
        val scanned: Int,
        val failures: Int,
        val matches: List<MatchRecord>
    )

    private fun file(context: Context, tf: ScanTimeframe) =
        File(context.filesDir, "history_${tf.id}.jsonl")

    private fun cutoffMillis(): Long =
        System.currentTimeMillis() - TimeUnit.DAYS.toMillis(RETENTION_DAYS)

    private fun keyOf(m: MatchRecord) = "${m.symbol}|${m.observedAt}"

    /** Adds one scan and drops everything older than the retention period. */
    @Synchronized
    fun append(context: Context, tf: ScanTimeframe, record: ScanRecord) {
        val cutoff = cutoffMillis()
        val target = file(context, tf)
        val kept = ArrayList<String>()
        if (target.exists()) {
            target.readLines().forEach { line ->
                if (line.isBlank()) return@forEach
                val time = try { JSONObject(line).optLong("t", 0L) } catch (_: Exception) { 0L }
                if (time >= cutoff) kept.add(line)
            }
        }
        kept.add(toJson(record).toString())
        target.writeText(kept.joinToString("\n") + "\n")
    }

    /** All stored scans of the last [RETENTION_DAYS] days, oldest first. */
    @Synchronized
    fun load(context: Context, tf: ScanTimeframe): List<ScanRecord> {
        val source = file(context, tf)
        if (!source.exists()) return emptyList()
        val cutoff = cutoffMillis()
        val result = ArrayList<ScanRecord>()
        source.readLines().forEach { line ->
            if (line.isBlank()) return@forEach
            try {
                val record = fromJson(JSONObject(line))
                if (record.timeMillis >= cutoff) result.add(record)
            } catch (_: Exception) {
                // Skip a damaged line instead of losing the whole history.
            }
        }
        return result
    }

    /**
     * Writes tracking results back. The file is read again under the lock and the new values are
     * merged into what is stored (stop-loss time = earliest, peak = highest, done = either), so a
     * scan saved in the meantime, or two updates running at once, never lose data.
     */
    @Synchronized
    fun applyTracking(context: Context, tf: ScanTimeframe, updates: List<MatchRecord>) {
        if (updates.isEmpty()) return
        val byKey = updates.associateBy { keyOf(it) }
        val target = file(context, tf)
        if (!target.exists()) return
        val cutoff = cutoffMillis()
        val out = ArrayList<String>()
        target.readLines().forEach { line ->
            if (line.isBlank()) return@forEach
            try {
                val record = fromJson(JSONObject(line))
                if (record.timeMillis < cutoff) return@forEach
                val merged = record.matches.map { old ->
                    val update = byKey[keyOf(old)]
                    if (update == null) old else merge(old, update)
                }
                out.add(toJson(record.copy(matches = merged)).toString())
            } catch (_: Exception) {
                out.add(line)
            }
        }
        target.writeText(out.joinToString("\n") + "\n")
    }

    private fun merge(old: MatchRecord, new: MatchRecord): MatchRecord {
        val newerPeak = new.peakPrice > old.peakPrice
        val stops = listOf(old.stopTime, new.stopTime).filter { it > 0L }
        return old.copy(
            trackedUntil = maxOf(old.trackedUntil, new.trackedUntil),
            stopTime = stops.minOrNull() ?: 0L,
            peakPrice = if (newerPeak) new.peakPrice else old.peakPrice,
            peakTime = if (newerPeak) new.peakTime else old.peakTime,
            peakBeforeStop = maxOf(old.peakBeforeStop, new.peakBeforeStop),
            done = old.done || new.done
        )
    }

    private fun toJson(record: ScanRecord): JSONObject {
        val matches = JSONArray()
        record.matches.forEach { m ->
            matches.put(JSONObject().apply {
                put("s", m.symbol)
                put("p", m.price)
                put("h", m.highPct)
                put("c", m.changePct)
                put("v", m.volumeMultiple)
                put("l", m.lowPrice)
                put("ha", m.highAtDetection)
                put("o", m.observedAt)
                put("tu", m.trackedUntil)
                put("st", m.stopTime)
                put("pk", m.peakPrice)
                put("pt", m.peakTime)
                put("pb", m.peakBeforeStop)
                put("d", m.done)
            })
        }
        return JSONObject().apply {
            put("t", record.timeMillis)
            put("n", record.scanned)
            put("f", record.failures)
            put("m", matches)
        }
    }

    private fun fromJson(json: JSONObject): ScanRecord {
        val array = json.optJSONArray("m") ?: JSONArray()
        val matches = ArrayList<MatchRecord>()
        for (i in 0 until array.length()) {
            val m = array.getJSONObject(i)
            matches.add(
                MatchRecord(
                    symbol = m.getString("s"),
                    price = m.optDouble("p"),
                    highPct = m.optDouble("h"),
                    changePct = m.optDouble("c"),
                    volumeMultiple = m.optDouble("v"),
                    lowPrice = m.optDouble("l", 0.0),
                    highAtDetection = m.optDouble("ha", 0.0),
                    observedAt = m.optLong("o", 0L),
                    trackedUntil = m.optLong("tu", 0L),
                    stopTime = m.optLong("st", 0L),
                    peakPrice = m.optDouble("pk", 0.0),
                    peakTime = m.optLong("pt", 0L),
                    peakBeforeStop = m.optDouble("pb", 0.0),
                    done = m.optBoolean("d", false)
                )
            )
        }
        return ScanRecord(json.getLong("t"), json.optInt("n"), json.optInt("f"), matches)
    }
}
