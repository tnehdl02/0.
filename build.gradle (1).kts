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
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Main screen: one page per watch, swiped left and right.
 *
 * Each page has, from top to bottom: the conditions (editable until the scanner is started,
 * then locked), the start/stop button, and a short live log (time + symbol; a red dot marks the
 * symbols of the latest detection). Details and the scan log are in the history screen.
 * The gear button (top right) holds: add watch, rename, delete, background settings.
 */
class MainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences(ScannerService.PREFS, MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var indicatorText: TextView
    private lateinit var pager: ViewPager

    private val kst: TimeZone = TimeZone.getTimeZone("Asia/Seoul")
    private val clockFormat by lazy { SimpleDateFormat("HH:mm:ss", Locale.KOREA).apply { timeZone = kst } }
    private val dateTimeFormat by lazy { SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).apply { timeZone = kst } }
    private val dayFormat by lazy { SimpleDateFormat("yyyyMMdd", Locale.KOREA).apply { timeZone = kst } }

    private val green = Color.rgb(120, 230, 90)
    private val redDot = Color.rgb(255, 69, 69)
    private val muted = Color.rgb(174, 194, 211)

    /** The views of one page (one watch). */
    private class Page(val watchId: String) {
        lateinit var root: ScrollView
        lateinit var stateText: TextView
        val chips = LinkedHashMap<String, TextView>()
        var interval = "15m"
        lateinit var volumeField: EditText
        lateinit var highField: EditText
        lateinit var liveField: EditText
        lateinit var minutesField: EditText
        lateinit var hoursField: EditText
        lateinit var hoursCard: LinearLayout
        lateinit var presetRow: LinearLayout
        lateinit var fieldsContainer: LinearLayout
        lateinit var toggleButton: Button
        lateinit var infoText: TextView
        lateinit var progressBar: ProgressBar
        lateinit var logContainer: LinearLayout
        var locked = false
        var renderedLogSignature = "__NOT_RENDERED__"
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

        // Top bar: title + status on the left, gear button on the right.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(28), dp(16), dp(4))
        }
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleColumn.addView(text("BINANCE SCANNER", 20f, Color.rgb(236, 249, 255), true))
        statusText = text("상태 확인 중…", 13f, Color.WHITE, true)
        titleColumn.addView(statusText, margin(0, 2, 0, 0))
        top.addView(titleColumn, LinearLayout.LayoutParams(0, -2, 1f))

        val gear = TextView(this).apply {
            text = "⚙"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(220, 236, 248))
            background = rounded(Color.argb(110, 40, 64, 90), dp(14), Color.argb(110, 111, 161, 184))
            setOnClickListener { showSettingsMenu() }
        }
        top.addView(gear, LinearLayout.LayoutParams(dp(46), dp(46)))
        screen.addView(top, LinearLayout.LayoutParams(-1, -2))

        indicatorText = text("", 12f, muted, true).apply { gravity = Gravity.CENTER }
        screen.addView(indicatorText, margin(16, 4, 16, 2))

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
        indicatorText.text = if (pages.size > 1) "$dots   $name  (${current + 1}/${pages.size})" else name
    }

    // ---- Page -----------------------------------------------------------------------------

    private fun buildPage(watchId: String): Page {
        val page = Page(watchId)
        val config = watchOf(watchId)?.config
        val scroll = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(40))
        }
        scroll.addView(column)
        page.root = scroll

        // ---- Conditions card (top of the screen) ----
        val settings = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(text("SETTINGS: CONDITIONS", 17f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        page.stateText = text("○ 대기", 12f, Color.rgb(255, 190, 110), true)
        head.addView(page.stateText)
        settings.addView(head, margin(0, 0, 0, 10))

        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.fieldsContainer = fields
        settings.addView(fields)

        // Timeframe chips
        val timeframeBox = miniBox()
        timeframeBox.addView(text("TIMEFRAME", 11f, muted, true))
        val chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((key, _) in ScanOptions.INTERVALS) {
            val chip = chip(key)
            chip.setOnClickListener {
                if (page.locked) {
                    lockedToast()
                } else {
                    page.interval = key
                    styleChips(page)
                    applyIntervalDefaults(page)
                }
            }
            page.chips[key] = chip
            chipRow.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        chipScroll.addView(chipRow)
        timeframeBox.addView(chipScroll, margin(0, 6, 0, 0))
        fields.addView(timeframeBox, margin(0, 0, 0, 8))

        // Volume + high %
        page.volumeField = inputBox(decimal = true, hintText = "4.5")
        page.highField = inputBox(decimal = true, hintText = "5")
        page.liveField = inputBox(decimal = true, hintText = "4")
        page.minutesField = inputBox(decimal = false, hintText = "50")
        page.hoursField = inputBox(decimal = false, hintText = "4, 8, 12")

        val rowA = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowA.addView(miniCard("VOLUME SPIKE (직전 봉 대비)", page.volumeField, "배"), LinearLayout.LayoutParams(0, -2, 1f))
        rowA.addView(miniCard("고가 / 시가", page.highField, "%"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        fields.addView(rowA, margin(0, 0, 0, 8))

        val rowB = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rowB.addView(miniCard("현재가 / 시가", page.liveField, "%"), LinearLayout.LayoutParams(0, -2, 1f))
        rowB.addView(miniCard("검사 시각 (매시 N분)", page.minutesField, "분"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        fields.addView(rowB, margin(0, 0, 0, 8))

        // Hours: only for 4h and longer candles
        page.hoursCard = miniCard("검사 시각 (시, 한국 시간 · 비우면 매시간)", page.hoursField, "시")
        fields.addView(page.hoursCard, margin(0, 0, 0, 6))
        page.presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val closePreset = chip("마감 10분 전")
        closePreset.background = rounded(Color.argb(80, 255, 255, 255), dp(20))
        closePreset.setOnClickListener {
            if (page.locked) {
                lockedToast()
            } else {
                val preset = ScanOptions.closePreset(page.interval)
                page.hoursField.setText(preset.first.joinToString(", "))
                page.minutesField.setText(preset.second.toString())
            }
        }
        val everyHour = chip("매시간")
        everyHour.background = rounded(Color.argb(80, 255, 255, 255), dp(20))
        everyHour.setOnClickListener {
            if (page.locked) lockedToast() else page.hoursField.setText("")
        }
        page.presetRow.addView(closePreset, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        page.presetRow.addView(everyHour)
        fields.addView(page.presetRow, margin(0, 0, 0, 8))

        if (config != null) fillFields(page, config)

        // Start / stop
        page.toggleButton = Button(this).apply {
            text = "▶  START SCANNER"
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            stateListAnimator = null
            setOnClickListener { onToggle(page) }
        }
        settings.addView(page.toggleButton, LinearLayout.LayoutParams(-1, dp(54)).apply { topMargin = dp(4) })

        page.progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(34, 197, 145))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(48, 65, 83))
        }
        settings.addView(page.progressBar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(10) })
        page.infoText = text("", 11.5f, muted, false)
        settings.addView(page.infoText, margin(0, 6, 0, 0))
        column.addView(settings, margin(0, 0, 0, 12))

        // ---- Live scan log (short: time + symbol) ----
        val logCard = card()
        val logHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logHead.addView(text("LIVE SCAN LOG", 17f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        val historyButton = Button(this).apply {
            text = "기록 확인"
            textSize = 12f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(Color.argb(130, 38, 116, 180), dp(12), Color.argb(120, 150, 200, 230))
            stateListAnimator = null
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, HistoryActivity::class.java).apply {
                    putExtra(ScannerService.EXTRA_WATCH_ID, watchId)
                })
            }
        }
        logHead.addView(historyButton, LinearLayout.LayoutParams(-2, dp(38)))
        logCard.addView(logHead, margin(0, 0, 0, 8))
        page.logContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logCard.addView(page.logContainer)
        column.addView(logCard)
        return page
    }

    private fun chip(label: String): TextView = TextView(this).apply {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(14), dp(7), dp(14), dp(7))
    }

    private fun styleChips(page: Page) {
        for ((key, view) in page.chips) {
            val selected = key == page.interval
            view.background = rounded(
                if (selected) Color.argb(210, 110, 160, 195) else Color.argb(60, 255, 255, 255), dp(20)
            )
            view.setTextColor(if (selected) Color.rgb(8, 20, 34) else Color.WHITE)
            view.typeface = Typeface.create("sans-serif", if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun updateHoursVisibility(page: Page) {
        val hourly = ScanOptions.isHourly(page.interval)
        page.hoursCard.visibility = if (hourly) View.VISIBLE else View.GONE
        page.presetRow.visibility = if (hourly) View.VISIBLE else View.GONE
    }

    /** When the candle size changes: 4h or longer gets the "10 minutes before close" schedule. */
    private fun applyIntervalDefaults(page: Page) {
        updateHoursVisibility(page)
        if (ScanOptions.isHourly(page.interval)) {
            val preset = ScanOptions.closePreset(page.interval)
            page.hoursField.setText(preset.first.joinToString(", "))
            page.minutesField.setText(preset.second.toString())
        } else {
            page.hoursField.setText("")
        }
    }

    private fun fillFields(page: Page, config: ScanConfig) {
        page.interval = config.interval
        page.volumeField.setText(formatNumber(config.volumeMultiple))
        page.highField.setText(formatNumber(config.highPct))
        page.liveField.setText(formatNumber(config.livePct))
        page.minutesField.setText(config.minutes.joinToString(", "))
        page.hoursField.setText(config.hours.joinToString(", "))
        styleChips(page)
        updateHoursVisibility(page)
    }

    /** Locks (or unlocks) the condition fields: while the scanner runs they cannot change. */
    private fun setLocked(page: Page, locked: Boolean) {
        page.locked = locked
        listOf(page.volumeField, page.highField, page.liveField, page.minutesField, page.hoursField)
            .forEach { it.isEnabled = !locked }
        page.fieldsContainer.alpha = if (locked) 0.6f else 1f
    }

    private fun lockedToast() {
        Toast.makeText(this, "감시 중에는 조건이 고정됩니다. 바꾸려면 먼저 중지하세요.", Toast.LENGTH_SHORT).show()
    }

    /** Reads and checks the fields. Null (with a message) if something is wrong. */
    private fun readConfig(page: Page): ScanConfig? {
        val high = page.highField.text.toString().trim().toDoubleOrNull()
        val live = page.liveField.text.toString().trim().toDoubleOrNull()
        val volume = page.volumeField.text.toString().trim().toDoubleOrNull()
        val minutes = ScanOptions.parseMinutes(page.minutesField.text.toString())
        val hourly = ScanOptions.isHourly(page.interval)
        val hours = if (hourly) ScanOptions.parseHours(page.hoursField.text.toString()) else emptyList()
        val problem = when {
            volume == null || volume <= 0.0 -> "거래량 배수는 0보다 큰 숫자로 입력하세요."
            high == null || high < 0.0 -> "고가 상승률은 0 이상의 숫자로 입력하세요."
            live == null || live < 0.0 -> "현재가 상승률은 0 이상의 숫자로 입력하세요."
            minutes == null -> "검사 분은 0~59 사이의 숫자를 쉼표로 구분해 입력하세요."
            hours == null -> "검사 시는 0~23 사이의 숫자를 쉼표로 구분해 입력하세요."
            else -> null
        }
        if (problem != null || high == null || live == null || volume == null || minutes == null || hours == null) {
            Toast.makeText(this, problem ?: "입력값을 확인하세요.", Toast.LENGTH_LONG).show()
            return null
        }
        return ScanConfig(page.interval, minutes, high, live, volume, hours)
    }

    /** START: saves the conditions (they are then fixed) and starts. STOP: stops and unlocks. */
    private fun onToggle(page: Page) {
        val watch = watchOf(page.watchId) ?: return
        val enabled = prefs.getBoolean(watch.key("enabled"), false)
        if (!enabled) {
            val config = readConfig(page) ?: return
            WatchStore.updateConfig(this, watch.id, config)
            setLocked(page, true)
        }
        toggleWatch(watch.id)
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

    private fun shortTime(value: String): String =
        if (value.length >= 19 && value[4] == '-') value.substring(5, 19) else value

    private fun refreshPage(page: Page) {
        val watch = watchOf(page.watchId) ?: return
        val config = watch.config
        val enabled = prefs.getBoolean(watch.key("enabled"), false)

        // While running, the fields show the fixed conditions of the running scanner.
        if (enabled) fillFields(page, config)
        if (enabled != page.locked) setLocked(page, enabled)

        page.stateText.text = if (enabled) "● 실행 중 · 조건 고정" else "○ 대기 중 · 수정 가능"
        page.stateText.setTextColor(if (enabled) Color.rgb(80, 238, 180) else Color.rgb(255, 190, 110))
        page.toggleButton.text = if (enabled) "■  STOP SCANNER" else "▶  START SCANNER"
        page.toggleButton.background = if (enabled) {
            rounded(Color.argb(190, 150, 60, 70), dp(16), Color.argb(150, 255, 150, 160))
        } else {
            rounded(Color.argb(150, 110, 130, 150), dp(16), Color.argb(150, 200, 225, 245))
        }

        val done = prefs.getInt(watch.key("scan_progress_done"), 0)
        val total = prefs.getInt(watch.key("scan_progress_total"), 0)
        val percent = prefs.getInt(watch.key("scan_progress_percent"), 0).coerceIn(0, 100)
        val scanning = prefs.getBoolean(watch.key("scan_in_progress"), false)
        page.progressBar.progress = if (enabled) percent else 0

        val last = prefs.getString(watch.key("last_scan_time"), "") ?: ""
        val matchesCount = prefs.getInt(watch.key("last_match_count"), 0)
        val next = prefs.getString(watch.key("next_scan"), "") ?: ""
        page.infoText.text = when {
            !enabled -> "시작하면 위 조건으로 고정되어 감시합니다.\n검사 시각: ${config.scheduleText()} (한국 시간) · 마감 전 봉 기준"
            scanning && total > 0 -> "검사 중  $done / $total · $percent%"
            else -> "검사 시각: ${config.scheduleText()}\n최근 ${if (last.isBlank()) "없음" else shortTime(last)} · 감지 ${matchesCount}개\n다음 ${shortTime(next)}"
        }

        refreshLog(page, watch)
    }

    private fun timeLabel(ms: Long): String {
        val date = Date(ms)
        return if (dayFormat.format(date) == dayFormat.format(Date())) clockFormat.format(date) else dateTimeFormat.format(date)
    }

    /** The short log: detection time and symbol. A red dot marks the latest detection. */
    private fun refreshLog(page: Page, watch: Watch) {
        val records = ScanHistory.load(this, watch)
        val latest = records.lastOrNull { it.matches.isNotEmpty() }
        val latestKeys = latest?.matches?.map { "${it.symbol}|${it.observedAt}" }?.toSet() ?: emptySet()
        val rows = records.flatMap { it.matches }.sortedByDescending { it.observedAt }.take(30)
        val signature = rows.joinToString("|") { "${it.symbol}:${it.observedAt}" } + "#" + latestKeys.size

        if (signature == page.renderedLogSignature) return
        page.renderedLogSignature = signature
        page.logContainer.removeAllViews()
        if (rows.isEmpty()) {
            page.logContainer.addView(text("아직 감지된 종목이 없습니다.", 13f, muted, false))
            return
        }
        for (m in rows) {
            val isLatest = "${m.symbol}|${m.observedAt}" in latestKeys
            page.logContainer.addView(logRow(m, isLatest))
        }
    }

    private fun logRow(m: ScanHistory.MatchRecord, isLatest: Boolean): View {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(11), dp(4), dp(11))
            setOnClickListener { copySymbol(m.symbol) }
        }
        val dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(redDot)
            }
            visibility = if (isLatest) View.VISIBLE else View.INVISIBLE
        }
        row.addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(8) })
        row.addView(mono(timeLabel(m.observedAt), 13f, Color.rgb(150, 168, 184), false), LinearLayout.LayoutParams(dp(92), -2))
        val name = m.symbol.removeSuffix("USDT") + "/USDT"
        row.addView(mono(name, 14f, Color.WHITE, true), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(mono("+${"%.1f".format(Locale.US, m.changePct)}% ×${"%.1f".format(Locale.US, m.volumeMultiple)}", 13f, green, true))
        wrapper.addView(row)
        wrapper.addView(View(this).apply { setBackgroundColor(Color.argb(40, 255, 255, 255)) }, LinearLayout.LayoutParams(-1, 1))
        return wrapper
    }

    private fun mono(value: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create("monospace", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setSingleLine(true)
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
            page?.let { setLocked(it, false) }
            page?.stateText?.text = "요청 실패: ${e.message ?: e.javaClass.simpleName}"
            page?.stateText?.setTextColor(Color.RED)
            Toast.makeText(this, "${watch.name} 요청 실패: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        }
    }

    /** The gear button: add watch, rename, delete, background settings. */
    private fun showSettingsMenu() {
        val items = arrayOf("＋ 감시 추가", "현재 감시 이름 변경", "현재 감시 삭제", "백그라운드 유지 설정")
        AlertDialog.Builder(this)
            .setTitle("설정")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> addWatch()
                    1 -> currentWatchId()?.let { showRenameDialog(it) }
                    2 -> currentWatchId()?.let { confirmDelete(it) }
                    3 -> openBatterySettings()
                }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun addWatch() {
        val added = WatchStore.add(this)
        if (added == null) {
            Toast.makeText(this, "감시는 최대 ${WatchStore.MAX_WATCHES}개까지 만들 수 있습니다.", Toast.LENGTH_LONG).show()
            return
        }
        pendingSelectId = added.id
        safeRefreshData()
        Toast.makeText(this, "'${added.name}'을(를) 추가했습니다. 조건을 정하고 START SCANNER를 누르세요.", Toast.LENGTH_LONG).show()
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

    // ---- View helpers ---------------------------------------------------------------------

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.argb(125, 25, 42, 62), dp(20), Color.argb(110, 111, 161, 184))
    }

    private fun miniBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(Color.argb(70, 20, 36, 56), dp(14), Color.argb(90, 111, 161, 184))
    }

    private fun inputBox(decimal: Boolean, hintText: String): EditText = EditText(this).apply {
        hint = hintText
        inputType = if (decimal) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL else InputType.TYPE_CLASS_TEXT
        setSingleLine(true)
        textSize = 17f
        setTextColor(Color.WHITE)
        setHintTextColor(Color.argb(110, 255, 255, 255))
        background = null
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun miniCard(caption: String, edit: EditText, suffix: String): LinearLayout {
        val box = miniBox()
        box.addView(text(caption, 11f, muted, true))
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        line.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(text(suffix, 14f, muted, false))
        box.addView(line)
        return box
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setLineSpacing(dp(2).toFloat(), 1.02f)
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

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
        }
    }
}
