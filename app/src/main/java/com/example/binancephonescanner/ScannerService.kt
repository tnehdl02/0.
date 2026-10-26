package com.example.binancephonescanner

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

class ScannerService : Service() {
    companion object {
        const val ACTION_START = "com.example.binancephonescanner.START"
        const val ACTION_STOP = "com.example.binancephonescanner.STOP"
        const val ACTION_SCAN = "com.example.binancephonescanner.SCAN"
        const val EXTRA_WATCH_ID = "watch_id"
        const val ALARM_REQUEST_BASE = 2000
        const val PREFS = "binance_scanner_state"
        private const val CHANNEL_SERVICE = "scanner_status"
        private const val CHANNEL_ALERTS = "scanner_alerts"
        private const val SERVICE_NOTIFICATION_ID = 1001
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val DISPLAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
        private val SHORT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA)
    }

    private val scheduler = Executors.newSingleThreadExecutor()
    private val scannerPool = Executors.newFixedThreadPool(8)
    private val progressLock = Any()
    private var lastProgressDone = 0
    private var lastNotifyAt = 0L
    private var lastStatusLine = ""

    // Every surge alert gets its own notification id (never reused), so alerts never replace each other.
    private val alertIdCounter = AtomicInteger(10_000 + ((System.currentTimeMillis() / 1000) % 1_000_000).toInt())

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        WatchStore.ensureLoaded(this)
        createChannels()
        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification("감시 서비스 준비 중"))
    }

    private fun isEnabled(watch: Watch): Boolean = prefs.getBoolean(watch.key("enabled"), false)

    private fun enabledWatches(): List<Watch> = Watch.values().filter { isEnabled(it) }

    /** "running" stays true while at least one timeframe is switched on (used by RestartReceiver). */
    private fun syncRunningFlag() {
        prefs.edit().putBoolean("running", enabledWatches().isNotEmpty()).apply()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedId = intent?.getStringExtra(EXTRA_WATCH_ID)
        val requested = Watch.values().firstOrNull { it.id == requestedId }
        when (intent?.action) {
            ACTION_STOP -> {
                // With a watch id: switch only that one off (it may already be deleted).
                // Without one: switch everything off (the notification's stop button).
                if (requestedId == null) Watch.values().forEach { disableWatch(it) }
                else if (requested != null) disableWatch(requested)
            }
            ACTION_SCAN -> if (requested != null) handleScanAlarm(requested)
            ACTION_START -> {
                if (requested != null) enableWatch(requested)
                else if (requestedId == null) resumeMonitoring()
            }
            // No action: the system restarted the service (or boot/update receiver).
            else -> resumeMonitoring()
        }
        if (enabledWatches().isEmpty()) {
            syncRunningFlag()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    /** Switches one watch on: schedules its alarms and runs one scan right away. */
    private fun enableWatch(watch: Watch) {
        val alreadyEnabled = isEnabled(watch)
        prefs.edit().putBoolean(watch.key("enabled"), true).apply()
        syncRunningFlag()
        scheduleAlarm(watch, afterFire = false)
        refreshNextScanPrefs()
        updateServiceNotification(idleText())
        if (alreadyEnabled) return

        val wakeLock = acquireWakeLock()
        try {
            scheduler.execute {
                try {
                    appendLog(watch, "${nowText()} | 감시 시작 | 즉시 검사 시작")
                    performScan(watch)
                } catch (e: Exception) {
                    val message = "첫 검사 오류: ${e.message ?: e.javaClass.simpleName}"
                    appendLog(watch, "${nowText()} | 검사 실패 | $message")
                    prefs.edit()
                        .putString(watch.key("last_outcome"), "검사 실패")
                        .putBoolean(watch.key("scan_in_progress"), false)
                        .apply()
                    updateServiceNotification("[${watch.name}] 검사 오류 · 인터넷 연결을 확인하세요")
                } finally {
                    releaseWakeLock(wakeLock)
                    updateServiceNotification(idleText())
                }
            }
        } catch (e: Exception) {
            releaseWakeLock(wakeLock)
            cancelAlarm(watch)
            val reason = e.message ?: e.javaClass.simpleName
            prefs.edit()
                .putBoolean(watch.key("enabled"), false)
                .putString(watch.key("last_outcome"), "감시 시작 실패: $reason")
                .apply()
            syncRunningFlag()
            appendLog(watch, "${nowText()} | 감시 시작 실패 | $reason")
            refreshNextScanPrefs()
        }
    }

    /** Switches one watch off. The service itself stops when no watch is left. */
    private fun disableWatch(watch: Watch) {
        cancelAlarm(watch)
        val editor = prefs.edit()
            .putBoolean(watch.key("enabled"), false)
            .putString(watch.key("next_scan"), "감시 중지")
        editor.putBoolean(watch.key("scan_in_progress"), false)
        editor.apply()
        syncRunningFlag()
        updateServiceNotification(idleText())
    }

    /** After a system restart, re-arm the alarms of every watch that was switched on. */
    private fun resumeMonitoring() {
        enabledWatches().forEach { scheduleAlarm(it, afterFire = false) }
        syncRunningFlag()
        refreshNextScanPrefs()
        updateServiceNotification(idleText())
    }

    /**
     * Called when an alarm fires. The next alarm for this timeframe is scheduled first, so the
     * chain never breaks even if the scan itself fails. A stale alarm of a switched-off
     * timeframe is ignored.
     */
    private fun handleScanAlarm(watch: Watch) {
        if (!isEnabled(watch)) return
        scheduleAlarm(watch, afterFire = true)
        refreshNextScanPrefs()
        val wakeLock = acquireWakeLock()
        try {
            scheduler.execute {
                try {
                    performScan(watch)
                } catch (e: Exception) {
                    val message = "검사 오류: ${e.message ?: e.javaClass.simpleName}"
                    appendLog(watch, "${nowText()} | 검사 실패 | $message")
                    prefs.edit()
                        .putString(watch.key("last_outcome"), "검사 실패")
                        .putBoolean(watch.key("scan_in_progress"), false)
                        .apply()
                    updateServiceNotification("[${watch.name}] 검사 오류 · 인터넷 연결을 확인하세요")
                } finally {
                    releaseWakeLock(wakeLock)
                    updateServiceNotification(idleText())
                }
            }
        } catch (e: Exception) {
            releaseWakeLock(wakeLock)
            appendLog(watch, "${nowText()} | 실패 | 검사 예약 실행 오류: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---- Alarms & wake lock ---------------------------------------------------------------

    private fun alarmPendingIntent(watch: Watch): PendingIntent {
        val intent = Intent(this, ScannerService::class.java).apply {
            action = ACTION_SCAN
            putExtra(EXTRA_WATCH_ID, watch.id)
        }
        return PendingIntent.getForegroundService(
            this, ALARM_REQUEST_BASE + watch.num, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleAlarm(watch: Watch, afterFire: Boolean) {
        // After an alarm fires, look slightly ahead so an alarm that fires a moment early
        // cannot schedule the same scan time again.
        val base = ZonedDateTime.now(KST).plusSeconds(if (afterFire) 30 else 0)
        val triggerAt = nextTarget(watch, base).toInstant().toEpochMilli()
        val alarmManager = getSystemService(AlarmManager::class.java)
        val pending = alarmPendingIntent(watch)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    private fun cancelAlarm(watch: Watch) {
        try {
            getSystemService(AlarmManager::class.java).cancel(alarmPendingIntent(watch))
        } catch (_: Exception) {
        }
    }

    private fun acquireWakeLock(): PowerManager.WakeLock? = try {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "binancescanner:scan")
            .apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 1000L)
            }
    } catch (_: Exception) {
        null
    }

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock?) {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
    }

    // ---- Schedule -------------------------------------------------------------------------

    private fun nextTarget(watch: Watch, now: ZonedDateTime = ZonedDateTime.now(KST)): ZonedDateTime {
        val hourStart = now.truncatedTo(ChronoUnit.HOURS)
        for (hourOffset in 0L..1L) {
            for (minute in watch.scanMinutes) {
                val target = hourStart.plusHours(hourOffset).withMinute(minute).withSecond(5).withNano(0)
                if (now.isBefore(target)) return target
            }
        }
        return hourStart.plusHours(2).withMinute(watch.scanMinutes.first()).withSecond(5).withNano(0)
    }

    /** Among the timeframes that are switched on: which scan comes first, and when. Null if none. */
    private fun nextScheduled(): Pair<Watch, ZonedDateTime>? {
        val now = ZonedDateTime.now(KST)
        return enabledWatches()
            .map { it to nextTarget(it, now) }
            .minByOrNull { it.second.toInstant().toEpochMilli() }
    }

    private fun nextScanText(watch: Watch): String = nextTarget(watch).format(DISPLAY_TIME) + " KST"

    private fun refreshNextScanPrefs() {
        val editor = prefs.edit()
        Watch.values().forEach {
            editor.putString(it.key("next_scan"), if (isEnabled(it)) nextScanText(it) else "감시 중지")
        }
        editor.apply()
    }

    /** Text for the ongoing notification while waiting for the next scan. */
    private fun idleText(): String {
        val (watch, target) = nextScheduled() ?: return "감시 꺼짐"
        val next = "다음 ${watch.name} ${target.format(SHORT_TIME)}"
        val labels = enabledWatches().joinToString("·") { it.name }
        return if (lastStatusLine.isBlank()) "감시 실행 중($labels) · $next" else "$lastStatusLine · $next"
    }

    private fun nowText(): String = ZonedDateTime.now(KST).format(DISPLAY_TIME)

    private data class Match(
        val symbol: String,
        val price: Double,
        val highPct: Double,
        val changePct: Double,
        val volumeMultiple: Double,
        val lowPrice: Double,
        val highPrice: Double,
        val observedAt: Long,
        val openPrice: Double,
        val peakStart: Long,
        val candleLabel: String
    )

    // ---- Scan -----------------------------------------------------------------------------

    private fun performScan(watch: Watch) {
        val startText = nowText()
        val tag = "[${watch.name}]"
        appendLog(watch, "$startText | 검사 시작 (v2.1) | 바이낸스 USDT 무기한 종목 목록 조회 중")
        updateServiceNotification("$tag 종목 목록 조회 중…")
        prefs.edit()
            .putString(watch.key("last_outcome"), "검사 진행 중")
            .apply()

        val symbols = try {
            getSymbols()
        } catch (e: Exception) {
            val endText = nowText()
            prefs.edit()
                .putString(watch.key("last_scan_time"), endText)
                .putInt(watch.key("last_scanned"), 0)
                .putInt(watch.key("last_match_count"), 0)
                .putInt(watch.key("last_failures"), 1)
                .putString(watch.key("last_matches"), "[]")
                .putString(watch.key("last_outcome"), "검사 실패")
                .putInt(watch.key("scan_progress_done"), 0)
                .putInt(watch.key("scan_progress_total"), 0)
                .putInt(watch.key("scan_progress_percent"), 0)
                .putBoolean(watch.key("scan_in_progress"), false)
                .apply()
            appendLog(watch, "$startText | 검사 실패 | 종목 목록을 가져오지 못함: ${e.message ?: e.javaClass.simpleName}")
            lastStatusLine = "$tag 검사 실패"
            updateServiceNotification("$tag 검사 실패 · 종목 목록 조회 오류")
            return
        }

        if (symbols.isEmpty()) {
            val endText = nowText()
            prefs.edit()
                .putString(watch.key("last_scan_time"), endText)
                .putInt(watch.key("last_scanned"), 0)
                .putInt(watch.key("last_match_count"), 0)
                .putInt(watch.key("last_failures"), 1)
                .putString(watch.key("last_matches"), "[]")
                .putString(watch.key("last_outcome"), "검사 실패: 종목 0개")
                .putInt(watch.key("scan_progress_done"), 0)
                .putInt(watch.key("scan_progress_total"), 0)
                .putInt(watch.key("scan_progress_percent"), 0)
                .putBoolean(watch.key("scan_in_progress"), false)
                .apply()
            appendLog(watch, "$startText | 검사 실패 | 조회된 USDT 무기한 종목이 0개")
            lastStatusLine = "$tag 검사 실패"
            updateServiceNotification("$tag 검사 실패 · 종목 0개")
            return
        }

        val failures = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val progressStep = max(1, symbols.size / 100)
        prefs.edit()
            .putInt(watch.key("scan_progress_done"), 0)
            .putInt(watch.key("scan_progress_total"), symbols.size)
            .putInt(watch.key("scan_progress_percent"), 0)
            .putBoolean(watch.key("scan_in_progress"), true)
            .apply()
        synchronized(progressLock) {
            lastProgressDone = 0
            lastNotifyAt = SystemClock.elapsedRealtime()
        }
        updateServiceNotification("$tag 검사 중 · 0/${symbols.size} · 0%")

        val cfg = watch.config
        val tasks = symbols.map { symbol ->
            Callable {
                try {
                    inspectSymbol(symbol, cfg, failures)
                } finally {
                    val done = completed.incrementAndGet()
                    if (done % progressStep == 0 || done == symbols.size) {
                        publishProgress(watch, done, symbols.size)
                    }
                }
            }
        }
        val matches = mutableListOf<Match>()
        try {
            val results = scannerPool.invokeAll(tasks, 180, TimeUnit.SECONDS)
            results.forEach { future ->
                try {
                    if (future.isCancelled) {
                        failures.incrementAndGet()
                    } else {
                        future.get()?.let { matches.add(it) }
                    }
                } catch (_: Exception) {
                    failures.incrementAndGet()
                }
            }
        } catch (e: Exception) {
            failures.incrementAndGet()
        }

        if (!isEnabled(watch)) {
            // The user switched this timeframe off while the scan was running: drop the results.
            prefs.edit()
                .putBoolean(watch.key("scan_in_progress"), false)
                .putString(watch.key("last_outcome"), "감시 중지됨")
                .apply()
            return
        }

        matches.sortByDescending { it.changePct }
        val endText = nowText()
        val matchJson = JSONArray()
        matches.forEach { match ->
            matchJson.put(JSONObject().apply {
                put("symbol", match.symbol)
                put("price", match.price)
                put("highPct", match.highPct)
                put("changePct", match.changePct)
                put("volumeMultiple", match.volumeMultiple)
            })
        }

        val failureCount = failures.get()
        val outcome = when {
            failureCount >= symbols.size -> "검사 실패"
            matches.isEmpty() -> if (failureCount > 0) "조건 충족 종목 없음 (일부 검사 실패)" else "조건 충족 종목 없음"
            failureCount > 0 -> "검사 완료 (일부 종목 실패)"
            else -> "검사 완료"
        }

        refreshNextScanPrefs()
        prefs.edit()
            .putString(watch.key("last_scan_time"), endText)
            .putInt(watch.key("last_scanned"), symbols.size)
            .putInt(watch.key("last_match_count"), matches.size)
            .putInt(watch.key("last_failures"), failureCount)
            .putString(watch.key("last_matches"), matchJson.toString())
            .putString(watch.key("last_outcome"), outcome)
            .putInt(watch.key("scan_progress_done"), symbols.size)
            .putInt(watch.key("scan_progress_total"), symbols.size)
            .putInt(watch.key("scan_progress_percent"), 100)
            .putBoolean(watch.key("scan_in_progress"), false)
            .apply()

        val logLine = buildString {
            append("$startText | ")
            append(if (failureCount >= symbols.size) "검사 실패" else "검사 완료")
            append(" | 검사 ${symbols.size}종목 · 실패 ${failureCount}개 · 감지 ${matches.size}개")
            if (matches.isEmpty()) {
                append(" | 조건 충족 종목 없음")
            } else {
                matches.take(30).forEach { match ->
                    append(" | ${match.symbol} 고가 +${"%.2f".format(Locale.US, match.highPct)}%")
                    append(" / 현재 +${"%.2f".format(Locale.US, match.changePct)}%")
                    append(" / 거래량 ${"%.2f".format(Locale.US, match.volumeMultiple)}배")
                }
                if (matches.size > 30) append(" | 외 ${matches.size - 30}개")
            }
        }
        appendLog(watch, logLine)

        // One separate notification per detected coin. Always sent, even when the symbol is
        // already in the history (a repeated detection is not recorded twice, but still alerts).
        if (failureCount < symbols.size) matches.forEach { sendMatchNotification(watch, it) }

        // Keep the result for 5 days so the history screen can compare symbols over time.
        // A symbol that already has a record still being followed (stop-loss not hit, 5-day
        // window not over) is not recorded again: the earlier record stays. If the earlier
        // record already hit its stop-loss, the new detection is saved as a new record.
        if (failureCount < symbols.size) {
            try {
                var recordable: List<Match> = matches
                if (matches.isNotEmpty()) {
                    var open = ScanHistory.openSymbols(this, watch)
                    if (matches.any { it.symbol in open }) {
                        // Bring the earlier records up to date first, so a stop-loss that
                        // happened since the last update is known before deciding.
                        try { HistoryTracker.update(this, watch) } catch (_: Exception) {}
                        open = ScanHistory.openSymbols(this, watch)
                    }
                    recordable = matches.filter { it.symbol !in open }
                }
                ScanHistory.append(
                    this, watch,
                    ScanHistory.ScanRecord(
                        System.currentTimeMillis(), symbols.size, failureCount,
                        recordable.map {
                            ScanHistory.MatchRecord(
                                symbol = it.symbol,
                                price = it.price,
                                highPct = it.highPct,
                                changePct = it.changePct,
                                volumeMultiple = it.volumeMultiple,
                                lowPrice = it.lowPrice,
                                highAtDetection = it.highPrice,
                                openPrice = it.openPrice,
                                candleLabel = it.candleLabel,
                                peakStart = it.peakStart,
                                lastPrice = it.price,
                                lastPriceTime = it.observedAt,
                                observedAt = it.observedAt,
                                peakPrice = it.price,
                                peakTime = it.observedAt,
                                peakBeforeStop = it.price
                            )
                        }
                    )
                )
            } catch (_: Exception) {
            }
        }

        // Follow up on earlier detections (stop-loss / 5-day peak). Alerts above go out first.
        updateServiceNotification("$tag 감지 종목 추적 결과 갱신 중…")
        try {
            HistoryTracker.update(this, watch)
        } catch (e: Exception) {
            appendLog(watch, "${nowText()} | 추적 갱신 실패 | ${e.message ?: e.javaClass.simpleName}")
        }
        try { Thread.sleep(1000) } catch (_: InterruptedException) {}
        lastStatusLine = "$tag ${ZonedDateTime.now(KST).format(SHORT_TIME)} 검사 · 감지 ${matches.size}개"
        updateServiceNotification(idleText())
    }

    // Serialize progress updates from the worker threads. Without this, updates can arrive
    // out of order (e.g. 85/524 posted after 524/524) and Android may drop notification
    // updates that are posted too quickly, leaving a stale count in the notification bar.
    private fun publishProgress(watch: Watch, done: Int, total: Int) {
        synchronized(progressLock) {
            if (done < lastProgressDone) return
            lastProgressDone = done
            val percent = (done * 100 / total).coerceIn(0, 100)
            prefs.edit()
                .putInt(watch.key("scan_progress_done"), done)
                .putInt(watch.key("scan_progress_total"), total)
                .putInt(watch.key("scan_progress_percent"), percent)
                .apply()
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotifyAt >= 800L && done < total) {
                lastNotifyAt = now
                updateServiceNotification("[${watch.name}] 검사 중 · $done/$total · $percent%")
            }
        }
    }

    private fun getSymbols(): List<String> {
        val root = JSONObject(httpGet("https://fapi.binance.com/fapi/v1/exchangeInfo"))
        val json = root.getJSONArray("symbols")
        val result = ArrayList<String>()
        for (i in 0 until json.length()) {
            val item = json.getJSONObject(i)
            if (item.optString("status") == "TRADING" &&
                item.optString("contractType") == "PERPETUAL" &&
                item.optString("quoteAsset") == "USDT") {
                result.add(item.getString("symbol"))
            }
        }
        return result
    }

    private fun inspectSymbol(symbol: String, cfg: ScanConfig, failures: AtomicInteger): Match? {
        return try {
            val url = "https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=${cfg.interval}&limit=3"
            val rows = JSONArray(httpGet(url))
            if (rows.length() < 3) {
                failures.incrementAndGet()
                return null
            }

            // Binance returns the unfinished current candle as the last row.
            val current = rows.getJSONArray(rows.length() - 1)
            val previousClosed = rows.getJSONArray(rows.length() - 2)
            val openPrice = current.getString(1).toDouble()
            val highPrice = current.getString(2).toDouble()
            val lowPrice = current.getString(3).toDouble()
            // The detected candle closes at this time; the 5-day peak is counted from the next candle.
            val candleCloseTime = current.getLong(6)
            val livePrice = current.getString(4).toDouble()
            val currentVolume = current.getString(5).toDouble()
            val previousVolume = previousClosed.getString(5).toDouble()

            if (openPrice <= 0.0 || previousVolume <= 0.0 || currentVolume < 0.0) {
                failures.incrementAndGet()
                return null
            }

            val highPct = (highPrice - openPrice) / openPrice * 100.0
            val changePct = (livePrice - openPrice) / openPrice * 100.0
            val volumeMultiple = currentVolume / previousVolume

            if (highPct >= cfg.highPct &&
                changePct >= cfg.livePct &&
                volumeMultiple >= cfg.volumeMultiple) {
                Match(
                    symbol, livePrice, highPct, changePct, volumeMultiple, lowPrice, highPrice,
                    System.currentTimeMillis(), openPrice, candleCloseTime + 1L, cfg.label
                )
            } else null
        } catch (_: Exception) {
            failures.incrementAndGet()
            null
        }
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

    private fun appendLog(watch: Watch, line: String) {
        val logKey = watch.key("scan_logs")
        synchronized(prefs) {
            val old = prefs.getString(logKey, "") ?: ""
            val lines = (old.lines().filter { it.isNotBlank() } + line.take(1200)).takeLast(5)
            prefs.edit().putString(logKey, lines.joinToString("\n")).apply()
        }
    }

    // ---- Notifications --------------------------------------------------------------------

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_SERVICE, "감시 상태", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "바이낸스 감시 서비스 실행 상태" }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ALERTS, "급등 감지 알림", NotificationManager.IMPORTANCE_HIGH)
                    .apply {
                        description = "급등 조건을 만족한 바이낸스 선물 종목"
                        setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, null)
                        enableVibration(true)
                    }
            )
        }
    }

    private fun serviceNotification(message: String): Notification {
        val stopIntent = Intent(this, ScannerService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 101, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 102, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Binance Phone Scanner")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(0, "감시 중지", stopPending)
            .build()
    }

    private fun updateServiceNotification(message: String) {
        if (enabledWatches().isEmpty()) return
        getSystemService(NotificationManager::class.java)
            .notify(SERVICE_NOTIFICATION_ID, serviceNotification(message))
    }

    /**
     * Posts one standalone notification for a detected coin. Each alert has a unique id and
     * independent title ("[감시 1] 15분봉 급등 종목 알림"), so Android does not bundle them.
     */
    private fun sendMatchNotification(watch: Watch, match: Match) {
        val manager = getSystemService(NotificationManager::class.java)
        val id = alertIdCounter.incrementAndGet()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openPending = PendingIntent.getActivity(this, id, Intent(this, MainActivity::class.java), flags)
        val copyIntent = Intent(this, CopyReceiver::class.java).apply {
            putExtra(CopyReceiver.EXTRA_SYMBOL, match.symbol)
        }
        val copyPending = PendingIntent.getBroadcast(this, id, copyIntent, flags)

        val body = "고가/시가 +%.2f%% · 현재/시가 +%.2f%% · 거래량 %.2fx".format(
            Locale.US, match.highPct, match.changePct, match.volumeMultiple
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("[${watch.name}] ${match.candleLabel} 급등 종목 알림: ${match.symbol}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body\n현재가 ${match.price}"))
            .setContentIntent(openPending)
            .addAction(0, "종목명 복사", copyPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(id, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // stopWithTask=false keeps the foreground service alive when the app task is swiped away.
        // START_STICKY asks Android to recreate the service if the process is reclaimed.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Do not clear the persisted on/off switches here; only an explicit STOP does that.
        scheduler.shutdownNow()
        scannerPool.shutdownNow()
        super.onDestroy()
    }
}
