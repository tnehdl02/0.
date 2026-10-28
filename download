package com.example.binancephonescanner

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
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
 * History of one watch: every detected symbol of the last 5 days as its own card, with the
 * detection data, the current price, the stop-loss status and the 5-day peak.
 * Red = stop-loss was hit, green = in profit, white = in loss.
 */
class HistoryActivity : ComponentActivity() {
    private val timeFormat by lazy {
        SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).apply { timeZone = TimeZone.getTimeZone("Asia/Seoul") }
    }

    private val red = Color.rgb(255, 99, 99)
    private val green = Color.rgb(80, 238, 150)
    private val white = Color.rgb(240, 245, 250)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchStore.ensureLoaded(this)
        val watchId = intent.getStringExtra(ScannerService.EXTRA_WATCH_ID)
        val watch = Watch.values().firstOrNull { it.id == watchId }
        if (watch == null) {
            finish()
            return
        }
        try {
            buildScreen(watch)
        } catch (e: Throwable) {
            val message = TextView(this).apply {
                text = "기록 화면 오류: ${e.message ?: e.javaClass.simpleName}"
                setTextColor(Color.WHITE)
                setPadding(dp(20), dp(40), dp(20), dp(20))
            }
            setContentView(message)
        }
    }

    private fun buildScreen(watch: Watch) {
        val scroll = ScrollView(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(7, 13, 28), Color.rgb(15, 31, 49), Color.rgb(18, 20, 38))
            )
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(30))
        }
        scroll.addView(root)
        setContentView(scroll)

        val back = button("← 돌아가기", Color.rgb(52, 84, 112))
        back.setOnClickListener { finish() }
        root.addView(back, LinearLayout.LayoutParams(dp(130), dp(42)).apply { bottomMargin = dp(14) })

        root.addView(text("${watch.name} 기록", 26f, Color.rgb(236, 249, 255), true))
        root.addView(
            text("최근 ${ScanHistory.RETENTION_DAYS}일 · 이 휴대폰에만 저장됩니다 (한국 시간)", 12f, Color.rgb(78, 232, 193), true),
            margin(0, 5, 0, 14)
        )

        // Refresh: downloads the latest price of every symbol that is still tracked.
        val updatedAt = getSharedPreferences(ScannerService.PREFS, MODE_PRIVATE).getLong(watch.key("track_updated"), 0L)
        val refresh = button("새로고침 (현재가 갱신)", Color.rgb(38, 116, 180))
        refresh.setOnClickListener {
            refresh.isEnabled = false
            refresh.text = "현재가 불러오는 중… (최대 1분)"
            Thread {
                try {
                    HistoryTracker.update(this, watch)
                } catch (_: Exception) {
                }
                runOnUiThread { if (!isFinishing) buildScreen(watch) }
            }.start()
        }
        root.addView(refresh, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(4) })
        root.addView(
            text(
                if (updatedAt > 0L) "마지막 갱신: ${timeFormat.format(Date(updatedAt))}" else "아직 현재가를 갱신하지 않았습니다.",
                12f, Color.rgb(174, 194, 211), false
            ),
            margin(0, 0, 0, 12)
        )

        val matches = ScanHistory.load(this, watch)
            .flatMap { it.matches }
            .sortedByDescending { it.observedAt }

        // ---- Short summary ----
        val trackable = matches.filter { it.isTrackable }
        val trackingCount = trackable.count { !it.done }
        val stoppedCount = trackable.count { it.stopTime > 0L }
        val losingCount = trackable.count { it.stopTime == 0L && it.profitPct < 0.0 }
        val winningCount = trackable.count { it.stopTime == 0L && it.profitPct > 0.0 }

        val summaryCard = card()
        summaryCard.addView(text("현재 추적 중 ${trackingCount}개", 17f, Color.WHITE, true))
        val counts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        counts.addView(text("손절 발생 ${stoppedCount}개", 14f, red, true), LinearLayout.LayoutParams(0, -2, 1f))
        counts.addView(text("손실 중 ${losingCount}개", 14f, white, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        counts.addView(text("수익 중 ${winningCount}개", 14f, green, true).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        summaryCard.addView(counts, margin(0, 8, 0, 0))
        root.addView(summaryCard, margin(0, 0, 0, 14))

        // ---- One card per detected symbol (newest first) ----
        root.addView(text("감지된 종목", 18f, Color.WHITE, true), margin(0, 0, 0, 4))
        root.addView(
            text("빨강: 손절선 터치 · 초록: 수익 중 · 흰색: 손실 중 (감지 당시 가격 기준)", 12f, Color.rgb(174, 194, 211), false),
            margin(0, 0, 0, 10)
        )
        if (matches.isEmpty()) {
            val empty = card()
            empty.addView(text("감지된 종목이 없습니다.", 13f, Color.rgb(174, 194, 211), false))
            root.addView(empty, margin(0, 0, 0, 12))
        } else {
            val maxCards = 100
            for (m in matches.take(maxCards)) root.addView(matchCard(watch, m), margin(0, 0, 0, 10))
            if (matches.size > maxCards) {
                root.addView(
                    text("가장 최근 ${maxCards}건만 표시했습니다 (전체 ${matches.size}건).", 12f, Color.rgb(174, 194, 211), false),
                    margin(0, 0, 0, 10)
                )
            }
        }

        // ---- Recent scan log ----
        val logCard = card()
        logCard.addView(text("최근 검사 로그", 17f, Color.WHITE, true))
        val log = getSharedPreferences(ScannerService.PREFS, MODE_PRIVATE).getString(watch.key("scan_logs"), "") ?: ""
        logCard.addView(
            text(if (log.isBlank()) "아직 검사 로그가 없습니다." else log, 12f, Color.rgb(190, 208, 224), false)
                .apply { typeface = Typeface.MONOSPACE },
            margin(0, 8, 0, 0)
        )
        root.addView(logCard)
    }

    private fun pct(value: Double): String = String.format(Locale.US, "%+.2f", value) + "%"

    private fun matchCard(watch: Watch, m: ScanHistory.MatchRecord): LinearLayout {
        val stopped = m.isTrackable && m.stopTime > 0L
        val color = when {
            stopped -> red
            m.isTrackable && m.profitPct > 0.0 -> green
            else -> white
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(12))
            background = rounded(Color.argb(125, 25, 42, 62), dp(16), Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)))
        }

        // Name on top
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text(m.symbol, 19f, color, true), LinearLayout.LayoutParams(0, -2, 1f))
        val detectedLabel = (if (m.candleLabel.isBlank()) "" else m.candleLabel + " · ") + "감지 " + timeFormat.format(Date(m.observedAt))
        head.addView(text(detectedLabel, 11f, Color.rgb(174, 194, 211), false))
        card.addView(head)

        // 1. At the time of detection
        val open = m.candleOpen
        val high = if (m.highAtDetection > 0.0) m.highAtDetection else open * (1.0 + m.highPct / 100.0)
        val volumePct = (m.volumeMultiple - 1.0) * 100.0
        val lines = StringBuilder()
        lines.append("① 감지 당시\n")
        lines.append("   가격 ${formatPrice(m.price)} · 거래량 ${String.format(Locale.US, "%.2f", m.volumeMultiple)}배 (${pct(volumePct)})\n")
        lines.append("   시가 ${formatPrice(open)} · 고가 ${formatPrice(high)} (${pct(m.highPct)}) · 종가 ${formatPrice(m.price)} (${pct(m.changePct)})")

        if (!m.isTrackable) {
            lines.append("\n(이전 버전 기록 · 추적 정보 없음)")
        } else {
            // 2. Current price
            lines.append("\n② 현재 가격 ${formatPrice(m.currentPrice)} (감지 당시 대비 ${pct(m.profitPct)})")
            if (m.lastPriceTime > 0L) lines.append(" · ${timeFormat.format(Date(m.lastPriceTime))} 기준")

            // 3. Stop-loss
            lines.append("\n③ 손절선 ${formatPrice(m.lowPrice)} (감지 당시 대비 ${pct(m.stopPct)}) · ")
            if (stopped) lines.append("터치함 ${timeFormat.format(Date(m.stopTime))}") else lines.append("터치 안 함")

            // 4. Peak within 5 days, excluding the detected candle
            lines.append("\n④ 5일 고점 ")
            if (m.peakPrice > m.price) {
                lines.append("${formatPrice(m.peakPrice)} (진입가 대비 ${pct(m.peakPct)}, ${timeFormat.format(Date(m.peakTime))})")
            } else {
                lines.append("진입가 위로 오르지 않음")
            }
            if (stopped && m.peakBeforeStop > m.price) lines.append("\n   손절 전 고점 ${pct(m.peakBeforeStopPct)}")
            lines.append("\n   집계 종료 ${timeFormat.format(Date(m.windowEnd))}")
            lines.append(if (m.done) " (종료됨)" else " (추적 중)")
        }
        card.addView(text(lines.toString(), 12.5f, color, false), margin(0, 8, 0, 8))

        // Copy / delete
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val copy = button("종목명 복사", Color.rgb(0, 142, 116))
        copy.setOnClickListener { copySymbol(m.symbol) }
        val delete = button("삭제", Color.rgb(150, 60, 70))
        delete.setOnClickListener { confirmDelete(watch, m) }
        buttons.addView(copy, LinearLayout.LayoutParams(0, dp(40), 3f))
        buttons.addView(delete, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(8) })
        card.addView(buttons)
        return card
    }

    private fun confirmDelete(watch: Watch, m: ScanHistory.MatchRecord) {
        AlertDialog.Builder(this)
            .setTitle("기록 삭제")
            .setMessage("${m.symbol} (${timeFormat.format(Date(m.observedAt))} 감지) 기록을 삭제할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ ->
                ScanHistory.deleteMatch(this, watch, m.symbol, m.observedAt)
                buildScreen(watch)
            }
            .show()
    }

    private fun copySymbol(symbol: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("symbol", symbol))
            Toast.makeText(this, "$symbol 복사됨", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "복사하지 못했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatPrice(value: Double): String =
        if (value >= 1000) "%,.3f".format(Locale.US, value)
        else "%.8f".format(Locale.US, value).trimEnd('0').trimEnd('.')

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
