package com.example.binancephonescanner

import java.util.Locale

/** 1 -> "1", 4.5 -> "4.5" */
fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString()
    else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

/**
 * The settings of one watch, chosen by the user:
 *  - interval       : candle size ("1m", "5m", "15m", "1h", "4h" or "1d")
 *  - minutes        : the minutes of every hour at which the scan runs (Korea time)
 *  - highPct        : the candle high must be at least this many % above the candle open
 *  - livePct        : the current price must be at least this many % above the candle open
 *  - volumeMultiple : the candle volume must be at least this many times the previous candle
 */
data class ScanConfig(
    val interval: String,
    val minutes: List<Int>,
    val highPct: Double,
    val livePct: Double,
    val volumeMultiple: Double
) {
    val label: String get() = ScanOptions.INTERVALS[interval] ?: interval

    /** "매시 정각", "매시 50분", "매시 13분 · 28분 · 43분 · 58분" */
    fun scheduleText(): String {
        val sorted = minutes.sorted()
        if (sorted.isEmpty()) return "시간 미설정"
        if (sorted.size == 1) return if (sorted[0] == 0) "매시 정각" else "매시 ${sorted[0]}분"
        return "매시 " + sorted.joinToString(" · ") { if (it == 0) "정각" else "${it}분" }
    }

    fun conditionText(): String =
        "• 현재 ${label}의 고가가 시가 대비 +${formatNumber(highPct)}% 이상\n" +
            "• 현재가가 시가 대비 +${formatNumber(livePct)}% 이상\n" +
            "• 거래량이 직전 완료봉의 ${formatNumber(volumeMultiple)}배 이상"
}

/** Choices and input parsing for the settings dialog. */
object ScanOptions {
    val INTERVALS: LinkedHashMap<String, String> = linkedMapOf(
        "1m" to "1분봉",
        "5m" to "5분봉",
        "15m" to "15분봉",
        "1h" to "1시간봉",
        "4h" to "4시간봉",
        "1d" to "1일봉"
    )

    /**
     * "0, 15 30;45" -> [0, 15, 30, 45]. Returns null if the text is empty or has anything that
     * is not a number from 0 to 59.
     */
    fun parseMinutes(text: String?): List<Int>? {
        if (text.isNullOrBlank()) return null
        val parts = text.split(',', ' ', ';', '，', '·').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val result = ArrayList<Int>()
        for (part in parts) {
            val minute = part.trim().toIntOrNull() ?: return null
            if (minute !in 0..59) return null
            result.add(minute)
        }
        return result.distinct().sorted()
    }
}
