package com.example.binancephonescanner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Shows the last 5 days of results for one timeframe (15-minute or 1-hour):
 * which symbols were detected how often, every detection in time order, and the recent scan log.
 */
class HistoryActivity : ComponentActivity() {
    private val timeFormat by lazy {
        SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }
    }

    private class SymbolStats(val symbol: String) {
        var count = 0
        var maxHighPct = 0.0
        var volumeSum = 0.0
        var lastTime = 0L
        var tracked = 0
        var stopped = 0
        var peakSum = 0.0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScanConfigStore.ensureLoaded(this)
        val tf = ScanTimeframe.values().firstOrNull { it.id == intent.getStringExtra(ScannerService.EXTRA_TIMEFRAME) }
        if (tf == null) {
            finish()
            return
        }
        try {
            buildScreen(tf)
        } catch (e: Throwable) {
            val message = TextView(this).apply {
                text = "기록 화면 오류: ${e.message ?: e.javaClass.simpleName}"
                setTextColor(Color.WHITE)
                setPadding(dp(20), dp(40), dp(20), dp(20))
            }
            setContentView(message)
        }
    }

    private fun buildScreen(tf: ScanTimeframe) {
        val scroll = ScrollView(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(7, 13, 28), Color.rgb(15, 31, 49), Color.rgb(18, 20, 38))
            )
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(30))
        }
        scroll.addView(root)
        setContentView(scroll)

        val back = button("← 돌아가기", Color.rgb(52, 84, 112))
        back.setOnClickListener { finish() }
        root.addView(back, LinearLayout.LayoutParams(dp(130), dp(42)).apply { bottomMargin = dp(14) })

        root.addView(text("감시 ${tf.ordinal + 1} · ${tf.label} 기록", 26f, Color.rgb(236, 249, 255), true))
        root.addView(
            text("최근 ${ScanHistory.RETENTION_DAYS}일 · 이 휴대폰에만 저장됩니다 (한국 시간)", 12f, Color.rgb(78, 232, 193), true),
            margin(0, 5, 0, 16)
        )

        val trackUpdated = getSharedPreferences(ScannerService.PREFS, MODE_PRIVATE).getLong(tf.key("track_updated"), 0L)
        val refresh = button("추적 결과 새로고침", Color.rgb(38, 116, 180))
        refresh.setOnClickListener {
            refresh.isEnabled = false
            refresh.text = "갱신 중… (최대 1분)"
            Thread {
                try {
                    HistoryTracker.update(this, tf)
                } catch (_: Exception) {
                }
                runOnUiThread { if (!isFinishing) buildScreen(tf) }
            }.start()
        }
        root.addView(refresh, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(4) })
        root.addView(
            text(
                if (trackUpdated > 0L) "마지막 추적 갱신: ${timeFormat.format(Date(trackUpdated))}" else "아직 추적 갱신 전입니다.",
                12f, Color.rgb(174, 194, 211), false
            ),
            margin(0, 0, 0, 14)
        )

        val records = ScanHistory.load(this, tf)
        val detections = records.filter { it.matches.isNotEmpty() }
        val allMatches = records.flatMap { rec -> rec.matches.map { rec.timeMillis to it } }

        // ---- Summary ----
        val summaryCard = card()
        summaryCard.addView(text("요약", 17f, Color.WHITE, true))
        val summary = if (records.isEmpty()) {
            "아직 저장된 기록이 없습니다.\n감시를 켜고 검사가 한 번 끝나면 여기에 쌓입니다."
        } else {
            "검사 ${records.size}회 중 감지가 있었던 검사 ${detections.size}회\n" +
                "감지 ${allMatches.size}건 · 서로 다른 종목 ${allMatches.map { it.second.symbol }.toSet().size}개\n" +
                "기록 범위: ${timeFormat.format(Date(records.first().timeMillis))} ~ ${timeFormat.format(Date(records.last().timeMillis))}"
        }
        summaryCard.addView(text(summary, 13f, Color.rgb(197, 214, 229), false), margin(0, 8, 0, 0))

        // Tracking result: stop-loss = low of the detected candle; peak = highest price within 5 days.
        val trackedMatches = allMatches.map { it.second }.filter { it.isTrackable }
        summaryCard.addView(text("추적 결과", 15f, Color.WHITE, true), margin(0, 14, 0, 4))
        val trackingSummary = if (trackedMatches.isEmpty()) {
            "아직 추적 중인 감지가 없습니다. (손절선 = 감지된 봉의 저가)"
        } else {
            val stopped = trackedMatches.count { it.stopTime > 0L }
            val notStopped = trackedMatches.filter { it.stopTime == 0L }
            val avgPeakAll = trackedMatches.map { it.peakPct }.average()
            val avgPeakNoStop = if (notStopped.isEmpty()) null else notStopped.map { it.peakPct }.average()
            val maxPeak = trackedMatches.maxOf { it.peakPct }
            "추적 ${trackedMatches.size}건 · 손절 발생 ${stopped}건 · 손절 없음 ${notStopped.size}건\n" +
                "평균 고점 +${"%.2f".format(Locale.US, avgPeakAll)}%" +
                (if (avgPeakNoStop != null) " (손절 없는 건만 +${"%.2f".format(Locale.US, avgPeakNoStop)}%)" else "") +
                " · 최대 고점 +${"%.2f".format(Locale.US, maxPeak)}%\n" +
                "손절선 = 감지된 봉의 저가 / 고점 = 감지 후 5일 내 최고가 (손절 무시)\n" +
                "고점은 진입가(감지 시 현재가) 대비 %입니다."
        }
        summaryCard.addView(text(trackingSummary, 13f, Color.rgb(197, 214, 229), false))
        root.addView(summaryCard, margin(0, 0, 0, 12))

        // ---- Per-symbol statistics ----
        val stats = HashMap<String, SymbolStats>()
        allMatches.forEach { (time, m) ->
            val st = stats.getOrPut(m.symbol) { SymbolStats(m.symbol) }
            st.count += 1
            st.volumeSum += m.volumeMultiple
            if (m.highPct > st.maxHighPct) st.maxHighPct = m.highPct
            if (time > st.lastTime) st.lastTime = time
            if (m.isTrackable) {
                st.tracked += 1
                if (m.stopTime > 0L) st.stopped += 1
                st.peakSum += m.peakPct
            }
        }
        val ranking = stats.values.sortedWith(compareByDescending<SymbolStats> { it.count }.thenByDescending { it.maxHighPct })

        val rankCard = card()
        rankCard.addView(text("종목별 감지 횟수", 17f, Color.WHITE, true))
        rankCard.addView(
            text("많이 감지된 종목이 먼저 나옵니다. 누르면 종목명이 복사됩니다.", 12f, Color.rgb(174, 194, 211), false),
            margin(0, 4, 0, 10)
        )
        if (ranking.isEmpty()) {
            rankCard.addView(text("감지된 종목이 없습니다.", 13f, Color.rgb(174, 194, 211), false))
        } else {
            ranking.take(30).forEachIndexed { index, st ->
                val avgVolume = st.volumeSum / st.count
                val trackLine = if (st.tracked > 0) {
                    "\n   손절 ${st.stopped}/${st.tracked}회 · 평균 고점 +${"%.2f".format(Locale.US, st.peakSum / st.tracked)}%"
                } else ""
                val row = text(
                    "${index + 1}. ${st.symbol}   ${st.count}회\n" +
                        "   최대 고가 +${"%.2f".format(Locale.US, st.maxHighPct)}% · 평균 거래량 ${"%.1f".format(Locale.US, avgVolume)}배 · 마지막 ${timeFormat.format(Date(st.lastTime))}" +
                        trackLine,
                    13f, Color.rgb(213, 227, 239), false
                )
                row.setOnClickListener { copySymbol(st.symbol) }
                rankCard.addView(row, margin(0, 0, 0, 8))
            }
            if (ranking.size > 30) {
                rankCard.addView(text("… 외 ${ranking.size - 30}개 종목", 12f, Color.rgb(174, 194, 211), false))
            }
        }
        root.addView(rankCard, margin(0, 0, 0, 12))

        // ---- Detections in time order (newest first) ----
        val listCard = card()
        listCard.addView(text("감지 기록 (최신순)", 17f, Color.WHITE, true))
        listCard.addView(
            text("검사 시각별로 묶어서 보여줍니다. 종목을 누르면 복사됩니다.", 12f, Color.rgb(174, 194, 211), false),
            margin(0, 4, 0, 10)
        )
        if (detections.isEmpty()) {
            listCard.addView(text("감지 기록이 없습니다.", 13f, Color.rgb(174, 194, 211), false))
        } else {
            val maxRows = 300
            var shown = 0
            for (record in detections.asReversed()) {
                if (shown >= maxRows) break
                listCard.addView(
                    text(
                        "${timeFormat.format(Date(record.timeMillis))} 검사 · 감지 ${record.matches.size}개 / ${record.scanned}종목",
                        13f, Color.rgb(91, 235, 194), true
                    ),
                    margin(0, 10, 0, 4)
                )
                for (m in record.matches) {
                    if (shown >= maxRows) break
                    val row = text(
                        "${m.symbol}\n고가 +${"%.2f".format(Locale.US, m.highPct)}% · 현재 +${"%.2f".format(Locale.US, m.changePct)}% · 거래량 ${"%.2f".format(Locale.US, m.volumeMultiple)}배" +
                            trackingDetail(m),
                        12f, Color.rgb(213, 227, 239), false
                    ).apply {
                        setPadding(dp(10), dp(8), dp(10), dp(8))
                        background = rounded(Color.argb(110, 30, 52, 73), dp(10))
                    }
                    row.setOnClickListener { copySymbol(m.symbol) }
                    listCard.addView(row, margin(0, 0, 0, 6))
                    shown += 1
                }
            }
            val total = detections.sumOf { it.matches.size }
            if (total > shown) {
                listCard.addView(
                    text("가장 최근 ${shown}건만 표시했습니다 (전체 ${total}건).", 12f, Color.rgb(174, 194, 211), false),
                    margin(0, 8, 0, 0)
                )
            }
        }
        root.addView(listCard, margin(0, 0, 0, 12))

        // ---- Recent scan log (last 5 lines) ----
        val logCard = card()
        logCard.addView(text("최근 검사 로그", 17f, Color.WHITE, true))
        val log = getSharedPreferences(ScannerService.PREFS, MODE_PRIVATE).getString(tf.key("scan_logs"), "") ?: ""
        val logText = if (log.isBlank()) "아직 검사 로그가 없습니다." else log
        logCard.addView(
            text(logText, 12f, Color.rgb(190, 208, 224), false).apply { typeface = Typeface.MONOSPACE },
            margin(0, 8, 0, 0)
        )
        root.addView(logCard)
    }

    /** Extra lines under a detection: stop-loss level, whether it was hit, and the 5-day peak. */
    private fun trackingDetail(m: ScanHistory.MatchRecord): String {
        if (!m.isTrackable) return "\n(이전 버전 기록 · 추적 정보 없음)"
        val stopped = m.stopTime > 0L
        val stopState = if (stopped) "손절 발생 ${timeFormat.format(Date(m.stopTime))}" else "손절 없음"
        val sb = StringBuilder()
        sb.append("\n진입 ${formatPrice(m.price)} · 손절선 ${formatPrice(m.lowPrice)} (${"%.2f".format(Locale.US, m.stopPct)}%) · $stopState")
        if (m.peakPrice > m.price) {
            sb.append("\n5일 고점 ${formatPrice(m.peakPrice)} (+${"%.2f".format(Locale.US, m.peakPct)}%, ${timeFormat.format(Date(m.peakTime))})")
        } else {
            sb.append("\n5일 고점: 진입가 위로 오르지 않음")
        }
        if (stopped && m.peakBeforeStop > m.price) {
            sb.append("\n손절 전 고점 +${"%.2f".format(Locale.US, m.peakBeforeStopPct)}%")
        }
        if (!m.done) sb.append("\n추적 중 (감지 후 5일까지)")
        return sb.toString()
    }

    private fun formatPrice(value: Double): String =
        if (value >= 1000) "%,.3f".format(Locale.US, value)
        else "%.8f".format(Locale.US, value).trimEnd('0').trimEnd('.')

    private fun copySymbol(symbol: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("symbol", symbol))
            Toast.makeText(this, "$symbol 복사됨", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "복사하지 못했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.argb(125, 25, 42, 62), dp(20), Color.argb(110, 111, 161, 184))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1.02f)
    }

    private fun button(label: String, color: Int): Button = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = rounded(color, dp(13))
        stateListAnimator = null
    }

    private fun rounded(color: Int, radius: Int, stroke: Int = Color.TRANSPARENT) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun margin(left: Int, top: Int, right: Int, bottom: Int) =
        LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(left), dp(top), dp(right), dp(bottom))
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
