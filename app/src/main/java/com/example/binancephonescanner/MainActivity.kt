package com.example.binancephonescanner

import android.Manifest
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Main screen: one page per watch, swiped left and right. Each page shows the watch's
 * conditions, its start/stop button, its progress and its latest detections.
 */
class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences(ScannerService.PREFS, MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var indicatorText: TextView
    private lateinit var pager: ViewPager

    /** The views of one page (one watch). */
    private class Page(val watchId: String) {
        lateinit var root: ScrollView
        lateinit var titleText: TextView
        lateinit var stateText: TextView
        lateinit var conditionText: TextView
        lateinit var toggleButton: Button
        lateinit var progressText: TextView
        lateinit var progressBar: ProgressBar
        lateinit var summaryText: TextView
        lateinit var matchContainer: LinearLayout
        var renderedMatchSignature = "__NOT_RENDERED__"
    }

    private var pages: List<Page> = emptyList()
    private var structureSignature = ""
    private var pendingSelectId: String? = null

    private val refreshTask = object : Runnable {
        override fun run() {
            safeRefreshData()
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            WatchStore.ensureLoaded(this)
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

    // ---- Screen ---------------------------------------------------------------------------

    private fun buildUi() {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackground(GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(7, 13, 28), Color.rgb(15, 31, 49), Color.rgb(18, 20, 38))
            ))
        }
        // Attach early: if a later view fails to initialize, the activity won't stay blank.
        setContentView(screen)

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(6))
        }
        screen.addView(header, LinearLayout.LayoutParams(-1, -2))

        header.addView(text("BINANCE PHONE SCANNER", 21f, Color.rgb(236, 249, 255), true))
        header.addView(text("USDT PERPETUAL  ·  CUSTOM MOMENTUM SCAN", 11f, Color.rgb(78, 232, 193), true), margin(0, 3, 0, 8))
        statusText = text("상태 확인 중…", 14f, Color.WHITE, true)
        header.addView(statusText, margin(0, 0, 0, 8))

        val buttonRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val addButton = button("+ 감시 추가", Color.rgb(0, 150, 120))
        addButton.setOnClickListener { addWatch() }
        val batteryButton = button("백그라운드 유지 설정", Color.rgb(38, 116, 180))
        batteryButton.setOnClickListener { openBatterySettings() }
        buttonRow.addView(addButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        buttonRow.addView(batteryButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) })
        header.addView(buttonRow, margin(0, 0, 0, 8))

        indicatorText = text("", 13f, Color.rgb(174, 194, 211), true).apply { gravity = Gravity.CENTER }
        header.addView(indicatorText, margin(0, 0, 0, 4))

        pager = ViewPager(this).apply {
            id = View.generateViewId()
            addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(position: Int) {
                    updateIndicator()
                }
            })
        }
        screen.addView(pager, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private inner class PagesAdapter : PagerAdapter() {
        override fun getCount(): Int = pages.size

        override fun isViewFromObject(view: View, obj: Any): Boolean = view === obj

        override fun instantiateItem(container: ViewGroup, position: Int): Any {
            val view = pages[position].root
            container.addView(view)
            return view
        }

        override fun destroyItem(container: ViewGroup, position: Int, obj: Any) {
            container.removeView(obj as View)
        }
    }

    private fun watchOf(id: String): Watch? = Watch.values().firstOrNull { it.id == id }

    private fun currentWatchId(): String? = pages.getOrNull(pager.currentItem)?.watchId

    private fun rebuildPages(selectId: String?) {
        val watches = Watch.values()
        pages = watches.map { buildPage(it.id) }
        pager.adapter = PagesAdapter()
        val index = watches.indexOfFirst { it.id == selectId }
        pager.setCurrentItem(if (index >= 0) index else 0, false)
        updateIndicator()
    }

    private fun updateIndicator() {
        if (!::indicatorText.isInitialized || pages.isEmpty()) return
        val current = pager.currentItem.coerceIn(0, pages.size - 1)
        val dots = pages.indices.joinToString(" ") { if (it == current) "●" else "○" }
        val name = watchOf(pages[current].watchId)?.name ?: ""
        indicatorText.text = if (pages.size > 1) "$dots   $name  (${current + 1}/${pages.size})\n← 좌우로 넘겨서 다른 감시 보기 →" else name
    }

    /** Builds the page of one watch. */
    private fun buildPage(watchId: String): Page {
        val page = Page(watchId)
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(40))
        }
        scroll.addView(column)
        page.root = scroll

        // Title row: name, rename, delete
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        page.titleText = text("감시", 20f, Color.WHITE, true)
        titleRow.addView(page.titleText, LinearLayout.LayoutParams(0, -2, 1f))
        val rename = button("이름 변경", Color.rgb(52, 84, 112))
        rename.textSize = 12f
        rename.setOnClickListener { showRenameDialog(watchId) }
        val delete = button("삭제", Color.rgb(150, 60, 70))
        delete.textSize = 12f
        delete.setOnClickListener { confirmDelete(watchId) }
        titleRow.addView(rename, LinearLayout.LayoutParams(dp(92), dp(38)))
        titleRow.addView(delete, LinearLayout.LayoutParams(dp(64), dp(38)).apply { marginStart = dp(6) })
        column.addView(titleRow, margin(0, 0, 0, 10))

        // Status card: state, conditions, progress
        val statusCard = card()
        page.stateText = text("○ 꺼짐", 15f, Color.rgb(255, 190, 110), true)
        statusCard.addView(page.stateText)
        page.conditionText = text("", 12f, Color.rgb(197, 214, 229), false)
        statusCard.addView(page.conditionText, margin(0, 8, 0, 12))
        page.progressText = text("검사 진행률 대기 중", 14f, Color.rgb(236, 249, 255), true)
        statusCard.addView(page.progressText, margin(0, 0, 0, 7))
        page.progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(34, 197, 145))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(48, 65, 83))
            minimumHeight = dp(10)
        }
        statusCard.addView(page.progressBar, LinearLayout.LayoutParams(-1, dp(10)))
        column.addView(statusCard, margin(0, 0, 0, 12))

        // Buttons: start/stop, history, settings
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        page.toggleButton = button("감시 시작", Color.rgb(0, 200, 137))
        page.toggleButton.setOnClickListener { toggleWatch(watchId) }
        val history = button("기록 확인", Color.rgb(38, 116, 180))
        history.setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java).apply {
                putExtra(ScannerService.EXTRA_WATCH_ID, watchId)
            })
        }
        buttons.addView(page.toggleButton, LinearLayout.LayoutParams(0, dp(46), 3f))
        buttons.addView(history, LinearLayout.LayoutParams(0, dp(46), 2f).apply { marginStart = dp(8) })
        column.addView(buttons, margin(0, 0, 0, 8))

        val settings = button("조건 · 시간 설정", Color.rgb(96, 82, 160))
        settings.setOnClickListener { showSettingsDialog(watchId) }
        column.addView(settings, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(12) })

        // Latest result
        val resultCard = card()
        resultCard.addView(text("최근 검사 결과", 16f, Color.WHITE, true))
        page.summaryText = text("아직 검사 기록이 없습니다.", 13f, Color.rgb(197, 214, 229), false)
        resultCard.addView(page.summaryText, margin(0, 8, 0, 14))
        resultCard.addView(text("감지 종목", 15f, Color.WHITE, true))
        resultCard.addView(text("종목명 복사를 누른 뒤 바이낸스 앱 검색창에 붙여넣으세요.", 12f, Color.rgb(174, 194, 211), false), margin(0, 4, 0, 10))
        page.matchContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        resultCard.addView(page.matchContainer)
        column.addView(resultCard)
        return page
    }

    // ---- Refresh --------------------------------------------------------------------------

    private fun safeRefreshData() {
        try {
            refreshData()
        } catch (e: Throwable) {
            if (::statusText.isInitialized) {
                statusText.text = "화면 갱신 오류: ${e.message ?: e.javaClass.simpleName}"
                statusText.setTextColor(Color.rgb(255, 120, 120))
            }
        }
    }

    private fun refreshData() {
        val watches = Watch.values()
        val signature = watches.joinToString("|") { "${it.id}:${it.name}" }
        if (signature != structureSignature) {
            val keep = pendingSelectId ?: currentWatchId()
            pendingSelectId = null
            structureSignature = signature
            rebuildPages(keep)
        }

        val active = watches.filter { prefs.getBoolean(it.key("enabled"), false) }
        statusText.text = if (active.isNotEmpty()) "● 감시 실행 중 (${active.joinToString(" · ") { it.name }})" else "○ 감시 꺼짐"
        statusText.setTextColor(if (active.isNotEmpty()) Color.rgb(80, 238, 180) else Color.rgb(255, 190, 110))
        updateIndicator()

        for (page in pages) refreshPage(page)
    }

    private fun refreshPage(page: Page) {
        val watch = watchOf(page.watchId) ?: return
        val config = watch.config
        val enabled = prefs.getBoolean(watch.key("enabled"), false)

        page.titleText.text = watch.name
        page.stateText.text = if (enabled) "● 실행 중 · ${config.label}" else "○ 꺼짐 · ${config.label}"
        page.stateText.setTextColor(if (enabled) Color.rgb(80, 238, 180) else Color.rgb(255, 190, 110))
        page.conditionText.text = config.conditionText() +
            "\n• 검사 시각: ${config.scheduleText()} (한국 시간)\n※ 아직 마감되지 않은 봉 기준입니다."
        page.toggleButton.text = if (enabled) "감시 중지" else "감시 시작"
        page.toggleButton.background = rounded(if (enabled) Color.rgb(68, 82, 105) else Color.rgb(0, 200, 137), dp(13))

        val done = prefs.getInt(watch.key("scan_progress_done"), 0)
        val total = prefs.getInt(watch.key("scan_progress_total"), 0)
        val percent = prefs.getInt(watch.key("scan_progress_percent"), 0).coerceIn(0, 100)
        val scanning = prefs.getBoolean(watch.key("scan_in_progress"), false)
        page.progressText.text = if (total > 0) {
            "${if (scanning) "검사 중" else "검사 진행률"}  $done / $total · $percent%"
        } else {
            "검사 진행률 대기 중"
        }
        page.progressBar.progress = percent

        val last = prefs.getString(watch.key("last_scan_time"), "없음") ?: "없음"
        val scanned = prefs.getInt(watch.key("last_scanned"), 0)
        val matchesCount = prefs.getInt(watch.key("last_match_count"), 0)
        val failures = prefs.getInt(watch.key("last_failures"), 0)
        val next = prefs.getString(watch.key("next_scan"), "감시 시작 후 표시") ?: "감시 시작 후 표시"
        val outcome = prefs.getString(watch.key("last_outcome"), "대기 중") ?: "대기 중"
        page.summaryText.text = "최근 검사: $last\n검사 종목: ${scanned}개\n조건 충족: ${matchesCount}개\n요청 실패/시간 초과: ${failures}개\n결과: $outcome\n다음 검사: $next"

        val matches = try { JSONArray(prefs.getString(watch.key("last_matches"), "[]") ?: "[]") } catch (_: Exception) { JSONArray() }
        val signature = (0 until matches.length()).joinToString("|") { i ->
            val m = matches.optJSONObject(i) ?: JSONObject()
            "${m.optString("symbol")}:${m.optDouble("changePct")}:${m.optDouble("volumeMultiple")}"
        }
        if (signature == page.renderedMatchSignature) return
        page.renderedMatchSignature = signature
        page.matchContainer.removeAllViews()
        if (matches.length() == 0) {
            page.matchContainer.addView(text("조건 충족 종목 없음 (최근 검사 기준)", 13f, Color.rgb(174, 194, 211), false))
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
            page.matchContainer.addView(row, margin(0, 0, 0, 8))
        }
    }

    // ---- Actions --------------------------------------------------------------------------

    private fun serviceIntent(action: String, watchId: String): Intent =
        Intent(this, ScannerService::class.java).apply {
            this.action = action
            putExtra(ScannerService.EXTRA_WATCH_ID, watchId)
        }

    /** Switches one watch on or off without touching the others. */
    private fun toggleWatch(watchId: String) {
        val watch = watchOf(watchId) ?: return
        val page = pages.firstOrNull { it.watchId == watchId }
        val enabled = prefs.getBoolean(watch.key("enabled"), false)
        try {
            ContextCompat.startForegroundService(
                this, serviceIntent(if (enabled) ScannerService.ACTION_STOP else ScannerService.ACTION_START, watchId)
            )
            page?.stateText?.text = if (enabled) "◷ 중지 요청 중…" else "◷ 시작 요청 중…"
            page?.stateText?.setTextColor(Color.rgb(255, 214, 102))
            handler.postDelayed({ safeRefreshData() }, 1000)
            handler.postDelayed({ safeRefreshData() }, 4000)
        } catch (e: Exception) {
            page?.stateText?.text = "요청 실패: ${e.message ?: e.javaClass.simpleName}"
            page?.stateText?.setTextColor(Color.RED)
            Toast.makeText(this, "${watch.name} 요청 실패: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    private fun addWatch() {
        val added = WatchStore.add(this)
        if (added == null) {
            Toast.makeText(this, "감시는 최대 ${WatchStore.MAX_WATCHES}개까지 만들 수 있습니다.", Toast.LENGTH_LONG).show()
            return
        }
        pendingSelectId = added.id
        safeRefreshData()
        Toast.makeText(this, "'${added.name}'을(를) 추가했습니다. 조건 · 시간 설정을 확인하세요.", Toast.LENGTH_LONG).show()
    }

    private fun showRenameDialog(watchId: String) {
        val watch = watchOf(watchId) ?: return
        val input = EditText(this).apply {
            setText(watch.name)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            setSelection(text.length)
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(22), dp(12), dp(22), dp(4))
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        AlertDialog.Builder(this)
            .setTitle("감시 이름 변경")
            .setView(container)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isEmpty()) {
                    Toast.makeText(this, "이름을 입력하세요.", Toast.LENGTH_SHORT).show()
                } else {
                    WatchStore.rename(this, watchId, newName.take(20))
                    safeRefreshData()
                }
            }
            .show()
    }

    private fun confirmDelete(watchId: String) {
        val watch = watchOf(watchId) ?: return
        if (Watch.values().size <= 1) {
            Toast.makeText(this, "마지막 감시는 삭제할 수 없습니다. 먼저 새 감시를 추가하세요.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("감시 삭제")
            .setMessage("'${watch.name}'을(를) 삭제할까요?\n이 감시의 설정과 저장된 기록이 모두 사라집니다.")
            .setNegativeButton("취소", null)
            .setPositiveButton("삭제") { _, _ -> deleteWatch(watch) }
            .show()
    }

    private fun deleteWatch(watch: Watch) {
        // Cancel the alarm here, so nothing can wake this watch up after it is gone.
        try {
            val alarmIntent = Intent(this, ScannerService::class.java).apply {
                action = ScannerService.ACTION_SCAN
                putExtra(ScannerService.EXTRA_WATCH_ID, watch.id)
            }
            val pending = PendingIntent.getForegroundService(
                this, ScannerService.ALARM_REQUEST_BASE + watch.num, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            getSystemService(AlarmManager::class.java).cancel(pending)
        } catch (_: Exception) {
        }

        // Remove everything saved for this watch (its keys all start with "<id>_").
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("${watch.id}_") }.forEach { editor.remove(it) }
        editor.putBoolean("running", Watch.values().any { it.id != watch.id && prefs.getBoolean(it.key("enabled"), false) })
        editor.apply()

        ScanHistory.deleteAll(this, watch.id)
        WatchStore.delete(this, watch.id)

        // Let the service re-check whether anything is still running.
        try {
            ContextCompat.startForegroundService(this, serviceIntent(ScannerService.ACTION_STOP, watch.id))
        } catch (_: Exception) {
        }
        safeRefreshData()
        Toast.makeText(this, "'${watch.name}'을(를) 삭제했습니다.", Toast.LENGTH_SHORT).show()
    }

    private fun openBatterySettings() {
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

    private fun copySymbol(symbol: String) {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("symbol", symbol))
            Toast.makeText(this, "$symbol 복사됨", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "복사하지 못했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Lets the user choose, for one watch: the candle size, the two price-change thresholds,
     * the volume multiple and the minutes of every hour at which the scan runs.
     */
    private fun showSettingsDialog(watchId: String) {
        val watch = watchOf(watchId) ?: return
        val current = watch.config

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(10), dp(22), dp(4))
        }
        fun caption(value: String) = TextView(this).apply {
            text = value
            textSize = 13f
            setPadding(0, dp(12), 0, dp(2))
        }
        fun numberField(initial: String, hintText: String) = EditText(this).apply {
            setText(initial)
            hint = hintText
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSingleLine(true)
        }

        val intervalKeys = ScanOptions.INTERVALS.keys.toList()
        val intervalSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                ScanOptions.INTERVALS.values.toList()
            )
            setSelection(intervalKeys.indexOf(current.interval).coerceAtLeast(0))
        }
        column.addView(caption("봉 종류 (타임프레임)"))
        column.addView(intervalSpinner)

        val highField = numberField(formatNumber(current.highPct), "예: 5")
        column.addView(caption("고가가 시가 대비 몇 % 이상 오른 적이 있는가 (%)"))
        column.addView(highField)

        val liveField = numberField(formatNumber(current.livePct), "예: 4")
        column.addView(caption("현재가가 시가 대비 몇 % 이상 올라 있는가 (%)"))
        column.addView(liveField)

        val volumeField = numberField(formatNumber(current.volumeMultiple), "예: 4.5")
        column.addView(caption("거래량이 직전 완료봉의 몇 배 이상인가 (배)"))
        column.addView(volumeField)

        val minutesField = EditText(this).apply {
            setText(current.minutes.joinToString(", "))
            hint = "예: 50  또는  0, 15, 30, 45"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        column.addView(caption("검사할 시각: 매시 몇 분에 검사할까요 (0~59, 쉼표로 여러 개, 한국 시간)"))
        column.addView(minutesField)

        val presets = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun preset(label: String, minutes: String) {
            val chip = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                setOnClickListener { minutesField.setText(minutes) }
            }
            presets.addView(chip, LinearLayout.LayoutParams(0, dp(40), 1f))
        }
        preset("정각", "0")
        preset("50분", "50")
        preset("59분", "59")
        preset("15분마다", "0, 15, 30, 45")
        column.addView(presets)
        column.addView(TextView(this).apply {
            text = "매시 지정한 분의 5초에 검사합니다. 봉이 마감되기 전 시각으로 정하면 진행 중인 봉을 검사합니다. 변경한 값은 다음 검사부터 적용됩니다."
            textSize = 12f
            setPadding(0, dp(10), 0, dp(6))
        })

        val scroll = ScrollView(this).apply { addView(column) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("${watch.name} 조건 · 시간 설정")
            .setView(scroll)
            .setNegativeButton("취소", null)
            .setPositiveButton("저장", null)
            .create()
        dialog.show()
        // Overridden after show() so that a wrong value keeps the dialog open.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val high = highField.text.toString().trim().toDoubleOrNull()
            val live = liveField.text.toString().trim().toDoubleOrNull()
            val volume = volumeField.text.toString().trim().toDoubleOrNull()
            val minutes = ScanOptions.parseMinutes(minutesField.text.toString())
            val problem = when {
                high == null || high < 0.0 -> "고가 상승률은 0 이상의 숫자로 입력하세요."
                live == null || live < 0.0 -> "현재가 상승률은 0 이상의 숫자로 입력하세요."
                volume == null || volume <= 0.0 -> "거래량 배수는 0보다 큰 숫자로 입력하세요."
                minutes == null -> "검사 시각은 0~59 사이의 숫자를 쉼표로 구분해 입력하세요."
                else -> null
            }
            if (problem != null || high == null || live == null || volume == null || minutes == null) {
                Toast.makeText(this, problem ?: "입력값을 확인하세요.", Toast.LENGTH_LONG).show()
            } else {
                val interval = intervalKeys[intervalSpinner.selectedItemPosition.coerceIn(0, intervalKeys.size - 1)]
                WatchStore.updateConfig(this, watch.id, ScanConfig(interval, minutes, high, live, volume))
                // If this monitor is running, re-arm its alarm with the new scan minutes.
                if (prefs.getBoolean(watch.key("enabled"), false)) {
                    try {
                        ContextCompat.startForegroundService(
                            this,
                            Intent(this, ScannerService::class.java).apply {
                                action = ScannerService.ACTION_START
                                putExtra(ScannerService.EXTRA_WATCH_ID, watch.id)
                            }
                        )
                    } catch (_: Exception) {
                    }
                }
                Toast.makeText(this, "저장했습니다. 다음 검사부터 적용됩니다.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                handler.postDelayed({ safeRefreshData() }, 800)
            }
        }
    }


    // ---- View helpers ---------------------------------------------------------------------

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
