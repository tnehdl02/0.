package com.example.binancephonescanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences(ScannerService.PREFS, MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var progressBar: ProgressBar

    // One section on screen per timeframe (15-minute candle, 1-hour candle).
    private class Section(val tf: ScanTimeframe) {
        lateinit var stateText: TextView
        lateinit var toggleButton: Button
        lateinit var summaryText: TextView
        lateinit var matchContainer: LinearLayout
        var renderedMatchSignature = "__NOT_RENDERED__"
    }

    private val sections = ScanTimeframe.values().map { Section(it) }

    private val refreshTask = object : Runnable {
        override fun run() {
            safeRefreshData()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            requestNotificationPermission()
            buildUi()
            safeRefreshData()
        } catch (e: Throwable) {
            showStartupError(e)
        }
    }

    private fun showStartupError(error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            setBackgroundColor(Color.rgb(7, 13, 28))
        }
        val title = TextView(this).apply {
            text = "앱 화면 초기화 실패"
            textSize = 22f
            setTextColor(Color.rgb(255, 120, 120))
            typeface = Typeface.DEFAULT_BOLD
        }
        val details = TextView(this).apply {
            text = "화면을 만드는 중 오류가 발생했습니다.\n\n오류: $message\n\n앱을 종료하지 말고 이 오류 내용을 캡처해 주세요."
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(0, dp(16), 0, dp(20))
        }
        val retry = Button(this).apply {
            text = "화면 다시 열기"
            setOnClickListener {
                try {
                    val intent = packageManager.getLaunchIntentForPackage(packageName)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (intent != null) startActivity(intent)
                    finish()
                } catch (_: Throwable) {
                    recreate()
                }
            }
        }
        screen.addView(title)
        screen.addView(details)
        screen.addView(retry, LinearLayout.LayoutParams(-1, dp(48)))
        setContentView(screen)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun buildUi() {
        val scroll = ScrollView(this).apply {
            setBackground(GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(7, 13, 28), Color.rgb(15, 31, 49), Color.rgb(18, 20, 38))
            ))
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(30))
        }
        scroll.addView(root)
        // Attach the root early: if a later view fails to initialize, the activity won't stay blank.
        setContentView(scroll)

        val title = TextView(this).apply {
            text = "BINANCE\nPHONE SCANNER"
            textSize = 27f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.rgb(236, 249, 255))
            letterSpacing = 0.04f
        }
        root.addView(title)
        root.addView(text("USDT PERPETUAL  ·  15M + 1H MOMENTUM", 12f, Color.rgb(78, 232, 193), true), margin(0, 5, 0, 18))

        val statusCard = card()
        statusText = text("상태 확인 중…", 16f, Color.WHITE, true)
        statusCard.addView(statusText)
        statusCard.addView(text("검사 시각 (한국 시간)\n• 15분봉: 매시 13 · 28 · 43 · 58분\n• 1시간봉: 매시 50분", 12f, Color.rgb(174, 194, 211), false), margin(0, 8, 0, 0))
        progressText = text("검사 진행률 0 / 0 · 0%", 14f, Color.rgb(236, 249, 255), true)
        statusCard.addView(progressText, margin(0, 14, 0, 7))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(34, 197, 145))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(48, 65, 83))
            minimumHeight = dp(10)
        }
        statusCard.addView(progressBar, LinearLayout.LayoutParams(-1, dp(10)))
        root.addView(statusCard, margin(0, 0, 0, 12))

        val conditionCard = card()
        conditionCard.addView(text("감시 조건", 17f, Color.WHITE, true))
        val conditionText = ScanTimeframe.values().joinToString("\n\n") { tf ->
            "[${tf.label}]\n" +
            "• 현재 봉의 고가가 시가 대비 +${fmtPct(tf.highPct)}% 이상\n" +
            "• 현재가가 시가 대비 +${fmtPct(tf.livePct)}% 이상\n" +
            "• 현재봉 누적 거래량이 직전 완료봉의 ${fmtPct(tf.volumeMultiple)}배 이상"
        } + "\n\n※ 미완성 봉 기준이며, 봉 마감 전 조건은 바뀔 수 있습니다."
        conditionCard.addView(text(conditionText, 13f, Color.rgb(197, 214, 229), false), margin(0, 8, 0, 0))
        root.addView(conditionCard, margin(0, 0, 0, 12))

        val batteryButton = button("백그라운드 유지 설정", Color.rgb(38, 116, 180))
        batteryButton.setOnClickListener {
            try {
                val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    !powerManager.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } else {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, "설정 > 앱 > Binance Phone Scanner > 배터리에서 제한 없음을 선택하세요.", Toast.LENGTH_LONG).show()
                }
            }
        }
        root.addView(batteryButton, margin(0, 0, 0, 16))

        for (section in sections) {
            val tf = section.tf
            val sectionCard = card()
            sectionCard.addView(text("${tf.label} 감시", 18f, Color.WHITE, true))
            section.stateText = text("○ 꺼짐", 14f, Color.rgb(255, 190, 110), true)
            sectionCard.addView(section.stateText, margin(0, 6, 0, 10))

            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            section.toggleButton = button("${tf.label} 감시 시작", Color.rgb(0, 200, 137))
            section.toggleButton.setOnClickListener { toggleTimeframe(section) }
            val history = button("기록 확인", Color.rgb(38, 116, 180))
            history.setOnClickListener {
                startActivity(Intent(this, HistoryActivity::class.java).apply {
                    putExtra(ScannerService.EXTRA_TIMEFRAME, tf.id)
                })
            }
            buttons.addView(section.toggleButton, LinearLayout.LayoutParams(0, dp(46), 3f))
            buttons.addView(history, LinearLayout.LayoutParams(0, dp(46), 2f).apply { marginStart = dp(8) })
            sectionCard.addView(buttons, margin(0, 0, 0, 12))

            section.summaryText = text("아직 검사 기록이 없습니다.", 13f, Color.rgb(197, 214, 229), false)
            sectionCard.addView(section.summaryText, margin(0, 0, 0, 14))
            sectionCard.addView(text("감지 종목", 15f, Color.WHITE, true))
            sectionCard.addView(text("종목명 복사를 누른 뒤 바이낸스 앱 검색창에 붙여넣으세요.", 12f, Color.rgb(174, 194, 211), false), margin(0, 4, 0, 10))
            section.matchContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            sectionCard.addView(section.matchContainer)
            root.addView(sectionCard, margin(0, 0, 0, 12))
        }
    }

    /** Switches one timeframe on or off without touching the other one. */
    private fun toggleTimeframe(section: Section) {
        val tf = section.tf
        val enabled = prefs.getBoolean(tf.key("enabled"), false)
        try {
            val serviceIntent = Intent(this, ScannerService::class.java).apply {
                action = if (enabled) ScannerService.ACTION_STOP else ScannerService.ACTION_START
                putExtra(ScannerService.EXTRA_TIMEFRAME, tf.id)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            section.stateText.text = if (enabled) "◷ 중지 요청 중…" else "◷ 시작 요청 중…"
            section.stateText.setTextColor(Color.rgb(255, 214, 102))
            handler.postDelayed({ safeRefreshData() }, 1000)
            handler.postDelayed({ safeRefreshData() }, 4000)
        } catch (e: Exception) {
            section.stateText.text = "요청 실패: ${e.message ?: e.javaClass.simpleName}"
            section.stateText.setTextColor(Color.RED)
            Toast.makeText(this, "${tf.label} 요청 실패: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun safeRefreshData() {
        try {
            refreshData()
        } catch (e: Exception) {
            if (::statusText.isInitialized) {
                statusText.text = "화면 갱신 오류: ${e.message ?: e.javaClass.simpleName}"
                statusText.setTextColor(Color.RED)
            }
        }
    }

    private fun refreshData() {
        val activeLabels = ScanTimeframe.values().filter { prefs.getBoolean(it.key("enabled"), false) }
        val active = activeLabels.isNotEmpty()
        statusText.text = if (active) "● 감시 실행 중 (${activeLabels.joinToString(" · ") { it.label }})" else "○ 감시 꺼짐"
        statusText.setTextColor(if (active) Color.rgb(80, 238, 180) else Color.rgb(255, 190, 110))

        val progressDone = prefs.getInt("scan_progress_done", 0)
        val progressTotal = prefs.getInt("scan_progress_total", 0)
        val progressPercent = prefs.getInt("scan_progress_percent", 0).coerceIn(0, 100)
        val scanning = prefs.getBoolean("scan_in_progress", false)
        val scanLabel = prefs.getString("scan_label", "") ?: ""
        val labelPrefix = if (scanLabel.isBlank()) "" else "[$scanLabel] "
        progressText.text = if (progressTotal > 0) {
            "$labelPrefix${if (scanning) "검사 중" else "검사 진행률"}  $progressDone / $progressTotal · $progressPercent%"
        } else {
            "검사 진행률 대기 중"
        }
        progressBar.progress = progressPercent

        for (section in sections) refreshSection(section)
    }

    private fun refreshSection(section: Section) {
        val tf = section.tf
        val last = prefs.getString(tf.key("last_scan_time"), "없음") ?: "없음"
        val scanned = prefs.getInt(tf.key("last_scanned"), 0)
        val matchesCount = prefs.getInt(tf.key("last_match_count"), 0)
        val failures = prefs.getInt(tf.key("last_failures"), 0)
        val next = prefs.getString(tf.key("next_scan"), "감시 시작 후 표시") ?: "감시 시작 후 표시"
        val outcome = prefs.getString(tf.key("last_outcome"), "대기 중") ?: "대기 중"
        section.summaryText.text = "최근 검사: $last\n검사 종목: ${scanned}개\n조건 충족: ${matchesCount}개\n요청 실패/시간 초과: ${failures}개\n결과: $outcome\n다음 검사: $next"

        val enabled = prefs.getBoolean(tf.key("enabled"), false)
        section.stateText.text = if (enabled) "● 실행 중" else "○ 꺼짐"
        section.stateText.setTextColor(if (enabled) Color.rgb(80, 238, 180) else Color.rgb(255, 190, 110))
        section.toggleButton.text = if (enabled) "${tf.label} 감시 중지" else "${tf.label} 감시 시작"
        section.toggleButton.background = rounded(if (enabled) Color.rgb(68, 82, 105) else Color.rgb(0, 200, 137), dp(13))

        val matches = try { JSONArray(prefs.getString(tf.key("last_matches"), "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val signature = (0 until matches.length()).joinToString("|") { i ->
            val m = matches.optJSONObject(i) ?: JSONObject()
            "${m.optString("symbol")}:${m.optDouble("changePct")}:${m.optDouble("volumeMultiple")}"
        }
        if (signature == section.renderedMatchSignature) return
        section.renderedMatchSignature = signature
        section.matchContainer.removeAllViews()
        if (matches.length() == 0) {
            section.matchContainer.addView(text("조건 충족 종목 없음 (최근 검사 기준)", 13f, Color.rgb(174, 194, 211), false))
            return
        }
        for (i in 0 until matches.length()) {
            val item = matches.optJSONObject(i) ?: continue
            val symbol = item.optString("symbol")
            val price = item.optDouble("price")
            val highPct = item.optDouble("highPct")
            val changePct = item.optDouble("changePct")
            val volumeMultiple = item.optDouble("volumeMultiple")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(Color.argb(110, 30, 52, 73), dp(14))
            }
            row.addView(text(symbol, 17f, Color.rgb(91, 235, 194), true))
            row.addView(text(
                "현재가 ${formatPrice(price)}\n고가/시가 +${"%.2f".format(Locale.US, highPct)}%  ·  현재가/시가 +${"%.2f".format(Locale.US, changePct)}%\n거래량 ${"%.2f".format(Locale.US, volumeMultiple)}배",
                12f, Color.rgb(213, 227, 239), false
            ), margin(0, 5, 0, 8))
            val copy = button("종목명 복사", Color.rgb(0, 142, 116))
            copy.setOnClickListener { copySymbol(symbol) }
            row.addView(copy, LinearLayout.LayoutParams(-1, dp(42)))
            section.matchContainer.addView(row, margin(0, 0, 0, 8))
        }
    }

    private fun fmtPct(value: Double): String =
        if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

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

    private fun formatPrice(value: Double): String =
        if (value >= 1000) "%,.3f".format(Locale.US, value)
        else "%.8f".format(Locale.US, value).trimEnd('0').trimEnd('.')

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
    }
}
