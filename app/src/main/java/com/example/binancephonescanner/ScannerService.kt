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

/**
 * One monitoring timeframe. Both timeframes use the same conditions, but each has its own
 * candle interval, its own scan times (Korea time) and its own saved results.
 *
 * scanMinutes: the minute of each hour at which the scan runs (at 5 seconds past the minute).
 *  - 1h  : :50          (10 minutes before the hourly candle closes)
 *  - 15m : :13 :28 :43 :58 (2 minutes before each 15-minute candle closes)
 */
enum class ScanTimeframe(
    val id: String,
    val interval: String,
    val label: String,
    val scanMinutes: List<Int>
) {
    M15("m15", "15m", "15분봉", listOf(13, 28, 43, 58)),
    H1("h1", "1h", "1시간봉", listOf(50));

    /** SharedPreferences key that belongs to this timeframe. */
    fun key(name: String): String = "${id}_$name"
}

class ScannerService : Service() {
    companion object {
        const val ACTION_START = "com.example.binancephonescanner.START"
        const val ACTION_STOP = "com.example.binancephonescanner.STOP"
        const val ACTION_SCAN = "com.example.binancephonescanner.SCAN"
        private const val EXTRA_TIMEFRAME = "timeframe"
        private const val ALARM_REQUEST_BASE = 2000
        const val PREFS = "binance_scanner_state"
        private const val CHANNEL_SERVICE = "scanner_status"
        private const val CHANNEL_ALERTS = "scanner_alerts"
        private const val SERVICE_NOTIFICATION_ID = 1001
        private const val VOLUME_MULTIPLE_THRESHOLD = 4.5
        private const val HIGH_CHANGE_THRESHOLD = 5.0
        private const val LIVE_CHANGE_THRESHOLD = 4.0
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val DISPLAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
        private val SHORT_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA)
    }

    private val running = AtomicBoolean(false)
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
        createChannels()
        startForeground(SERVICE_NOTIFICATION_ID, serviceNotification("감시 서비스 준비 중"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SCAN -> {
                val tf = ScanTimeframe.values().firstOrNull { it.id == intent?.getStringExtra(EXTRA_TIMEFRAME) }
                if (tf == null || !handleScanAlarm(tf)) {
                    // Monitoring was stopped by the user, so a leftover alarm must not restart it.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_START, null -> startMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (!running.compareAndSet(false, true)) {
            scheduleAllAlarms(afterFire = false)
            updateServiceNotification(idleText())
            return
        }
        prefs.edit().putBoolean("running", true).apply()
        // The hourly/15-minute scans are triggered by AlarmManager alarms, which wake the phone
        // even in Doze mode. (Thread.sleep() stops counting while the CPU sleeps.)
        scheduleAllAlarms(afterFire = false)
        refreshNextScanPrefs()
        updateServiceNotification(idleText())
        val wakeLock = acquireWakeLock()
        try {
            scheduler.execute {
                try {
                    // Run each timeframe once immediately so the user can verify that scanning works.
                    for (tf in ScanTimeframe.values()) {
                        if (!running.get()) break
                        appendLog(tf, "${nowText()} | 감시 시작 | 즉시 검사 시작")
                        try {
                            performScan(tf)
                        } catch (e: Exception) {
                            val message = "첫 검사 오류: ${e.message ?: e.javaClass.simpleName}"
                            appendLog(tf, "${nowText()} | 검사 실패 | $message")
                            prefs.edit().putString(tf.key("last_outcome"), "검사 실패").apply()
                            updateServiceNotification("검사 오류 · 인터넷 연결을 확인하세요")
                        }
                    }
                } finally {
                    releaseWakeLock(wakeLock)
                    if (running.get()) updateServiceNotification(idleText())
                }
            }
        } catch (e: Exception) {
            releaseWakeLock(wakeLock)
            running.set(false)
            cancelAlarms()
            prefs.edit().putBoolean("running", false).putString("scan_label", "").apply()
            ScanTimeframe.values().forEach { tf ->
                prefs.edit().putString(tf.key("last_outcome"), "감시 시작 실패: ${e.message ?: e.javaClass.simpleName}").apply()
                appendLog(tf, "${nowText()} | 감시 시작 실패 | ${e.message ?: e.javaClass.simpleName}")
            }
            updateServiceNotification("감시 시작 실패 · 앱을 다시 실행하세요")
        }
    }

    /**
     * Called when an alarm fires. Returns false if monitoring was stopped (nothing to do).
     * The next alarm for this timeframe is scheduled first, so the chain never breaks even if
     * the scan itself fails.
     */
    private fun handleScanAlarm(tf: ScanTimeframe): Boolean {
        if (!prefs.getBoolean("running", false)) return false
        running.set(true)
        scheduleAlarm(tf, afterFire = true)
        refreshNextScanPrefs()
        val wakeLock = acquireWakeLock()
        try {
            scheduler.execute {
                try {
                    performScan(tf)
                } catch (e: Exception) {
                    val message = "검사 오류: ${e.message ?: e.javaClass.simpleName}"
                    appendLog(tf, "${nowText()} | 검사 실패 | $message")
                    prefs.edit().putString(tf.key("last_outcome"), "검사 실패").apply()
                    updateServiceNotification("[${tf.label}] 검사 오류 · 인터넷 연결을 확인하세요")
                } finally {
                    releaseWakeLock(wakeLock)
                    if (running.get()) updateServiceNotification(idleText())
                }
            }
        } catch (e: Exception) {
            releaseWakeLock(wakeLock)
            appendLog(tf, "${nowText()} | 실패 | 검사 예약 실행 오류: ${e.message ?: e.javaClass.simpleName}")
        }
        return true
    }

    // ---- Alarms & wake lock ---------------------------------------------------------------

    private fun alarmPendingIntent(tf: ScanTimeframe): PendingIntent {
        val intent = Intent(this, ScannerService::class.java).apply {
            action = ACTION_SCAN
            putExtra(EXTRA_TIMEFRAME, tf.id)
        }
        return PendingIntent.getForegroundService(
            this, ALARM_REQUEST_BASE + tf.ordinal, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun scheduleAllAlarms(afterFire: Boolean) {
        ScanTimeframe.values().forEach { scheduleAlarm(it, afterFire) }
    }

    private fun scheduleAlarm(tf: ScanTimeframe, afterFire: Boolean) {
        // After an alarm fires, look slightly ahead so an alarm that fires a moment early
        // cannot schedule the same scan time again.
        val base = ZonedDateTime.now(KST).plusSeconds(if (afterFire) 30 else 0)
        val triggerAt = nextTarget(tf, base).toInstant().toEpochMilli()
        val alarmManager = getSystemService(AlarmManager::class.java)
        val pending = alarmPendingIntent(tf)
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

    private fun cancelAlarms() {
        try {
            val alarmManager = getSystemService(AlarmManager::class.java)
            ScanTimeframe.values().forEach { alarmManager.cancel(alarmPendingIntent(it)) }
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

    private fun stopMonitoring() {
        running.set(false)
        cancelAlarms()
        val editor = prefs.edit().putBoolean("running", false)
        ScanTimeframe.values().forEach { editor.putString(it.key("next_scan"), "감시 중지") }
        editor.apply()
        scheduler.shutdownNow()
    }

    // ---- Schedule -------------------------------------------------------------------------

    private fun nextTarget(tf: ScanTimeframe, now: ZonedDateTime = ZonedDateTime.now(KST)): ZonedDateTime {
        val hourStart = now.truncatedTo(ChronoUnit.HOURS)
        for (hourOffset in 0L..1L) {
            for (minute in tf.scanMinutes) {
                val target = hourStart.plusHours(hourOffset).withMinute(minute).withSecond(5).withNano(0)
                if (now.isBefore(target)) return target
            }
        }
        return hourStart.plusHours(2).withMinute(tf.scanMinutes.first()).withSecond(5).withNano(0)
    }

    /** The timeframe whose scan time comes first, and when. */
    private fun nextScheduled(): Pair<ScanTimeframe, ZonedDateTime> {
        val now = ZonedDateTime.now(KST)
        return ScanTimeframe.values()
            .map { it to nextTarget(it, now) }
            .minByOrNull { it.second.toInstant().toEpochMilli() }!!
    }

    private fun nextScanText(tf: ScanTimeframe): String = nextTarget(tf).format(DISPLAY_TIME) + " KST"

    private fun refreshNextScanPrefs() {
        val editor = prefs.edit()
        ScanTimeframe.values().forEach { editor.putString(it.key("next_scan"), nextScanText(it)) }
        editor.apply()
    }

    /** Text for the ongoing notification while waiting for the next scan. */
    private fun idleText(): String {
        val (tf, target) = nextScheduled()
        val next = "다음 ${tf.label} ${target.format(SHORT_TIME)}"
        return if (lastStatusLine.isBlank()) "감시 실행 중 · $next" else "$lastStatusLine · $next"
    }

    private fun nowText(): String = ZonedDateTime.now(KST).format(DISPLAY_TIME)

    private data class Match(
        val symbol: String,
        val price: Double,
        val highPct: Double,
        val changePct: Double,
        val volumeMultiple: Double
    )

    // ---- Scan -----------------------------------------------------------------------------

    private fun performScan(tf: ScanTimeframe) {
        val startText = nowText()
        val tag = "[${tf.label}]"
        appendLog(tf, "$startText | 검사 시작 (v1.6) | 바이낸스 USDT 무기한 종목 목록 조회 중")
        updateServiceNotification("$tag 종목 목록 조회 중…")
        prefs.edit()
            .putString(tf.key("last_outcome"), "검사 진행 중")
            .putString("scan_label", tf.label)
            .apply()

        val symbols = try {
            getSymbols()
        } catch (e: Exception) {
            val endText = nowText()
            prefs.edit()
                .putString(tf.key("last_scan_time"), endText)
                .putInt(tf.key("last_scanned"), 0)
                .putInt(tf.key("last_match_count"), 0)
                .putInt(tf.key("last_failures"), 1)
                .putString(tf.key("last_matches"), "[]")
                .putString(tf.key("last_outcome"), "검사 실패")
                .putInt("scan_progress_done", 0)
                .putInt("scan_progress_total", 0)
                .putInt("scan_progress_percent", 0)
                .putBoolean("scan_in_progress", false)
                .apply()
            appendLog(tf, "$startText | 검사 실패 | 종목 목록을 가져오지 못함: ${e.message ?: e.javaClass.simpleName}")
            lastStatusLine = "$tag 검사 실패"
            updateServiceNotification("$tag 검사 실패 · 종목 목록 조회 오류")
            return
        }

        if (symbols.isEmpty()) {
            val endText = nowText()
            prefs.edit()
                .putString(tf.key("last_scan_time"), endText)
                .putInt(tf.key("last_scanned"), 0)
                .putInt(tf.key("last_match_count"), 0)
                .putInt(tf.key("last_failures"), 1)
                .putString(tf.key("last_matches"), "[]")
                .putString(tf.key("last_outcome"), "검사 실패: 종목 0개")
                .putInt("scan_progress_done", 0)
                .putInt("scan_progress_total", 0)
                .putInt("scan_progress_percent", 0)
                .putBoolean("scan_in_progress", false)
                .apply()
            appendLog(tf, "$startText | 검사 실패 | 조회된 USDT 무기한 종목이 0개")
            lastStatusLine = "$tag 검사 실패"
            updateServiceNotification("$tag 검사 실패 · 종목 0개")
            return
        }

        val failures = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val progressStep = max(1, symbols.size / 100)
        prefs.edit()
            .putInt("scan_progress_done", 0)
            .putInt("scan_progress_total", symbols.size)
            .putInt("scan_progress_percent", 0)
            .putBoolean("scan_in_progress", true)
            .putString("scan_label", tf.label)
            .apply()
        synchronized(progressLock) {
            lastProgressDone = 0
            lastNotifyAt = SystemClock.elapsedRealtime()
        }
        updateServiceNotification("$tag 검사 중 · 0/${symbols.size} · 0%")

        val tasks = symbols.map { symbol ->
            Callable {
                try {
                    inspectSymbol(symbol, tf, failures)
                } finally {
                    val done = completed.incrementAndGet()
                    if (done % progressStep == 0 || done == symbols.size) {
                        publishProgress(tf, done, symbols.size)
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
            .putString(tf.key("last_scan_time"), endText)
            .putInt(tf.key("last_scanned"), symbols.size)
            .putInt(tf.key("last_match_count"), matches.size)
            .putInt(tf.key("last_failures"), failureCount)
            .putString(tf.key("last_matches"), matchJson.toString())
            .putString(tf.key("last_outcome"), outcome)
            .putInt("scan_progress_done", symbols.size)
            .putInt("scan_progress_total", symbols.size)
            .putInt("scan_progress_percent", 100)
            .putBoolean("scan_in_progress", false)
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
        appendLog(tf, logLine)

        // One separate notification per detected coin.
        if (failureCount < symbols.size) matches.forEach { sendMatchNotification(tf, it) }
        try { Thread.sleep(1000) } catch (_: InterruptedException) {}
        lastStatusLine = "$tag ${ZonedDateTime.now(KST).format(SHORT_TIME)} 검사 · 감지 ${matches.size}개"
        updateServiceNotification(idleText())
    }

    // Serialize progress updates from the worker threads. Without this, updates can arrive
    // out of order (e.g. 85/524 posted after 524/524) and Android may drop notification
    // updates that are posted too quickly, leaving a stale count in the notification bar.
    private fun publishProgress(tf: ScanTimeframe, done: Int, total: Int) {
        synchronized(progressLock) {
            if (done < lastProgressDone) return
            lastProgressDone = done
            val percent = (done * 100 / total).coerceIn(0, 100)
            prefs.edit()
                .putInt("scan_progress_done", done)
                .putInt("scan_progress_total", total)
                .putInt("scan_progress_percent", percent)
                .apply()
            val now = SystemClock.elapsedRealtime()
            if (now - lastNotifyAt >= 800L && done < total) {
                lastNotifyAt = now
                updateServiceNotification("[${tf.label}] 검사 중 · $done/$total · $percent%")
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

    private fun inspectSymbol(symbol: String, tf: ScanTimeframe, failures: AtomicInteger): Match? {
        return try {
            val url = "https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=${tf.interval}&limit=3"
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

            if (highPct >= HIGH_CHANGE_THRESHOLD &&
                changePct >= LIVE_CHANGE_THRESHOLD &&
                volumeMultiple >= VOLUME_MULTIPLE_THRESHOLD) {
                Match(symbol, livePrice, highPct, changePct, volumeMultiple)
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

    private fun appendLog(tf: ScanTimeframe, line: String) {
        val logKey = tf.key("scan_logs")
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
        getSystemService(NotificationManager::class.java)
            .notify(SERVICE_NOTIFICATION_ID, serviceNotification(message))
    }

    /**
     * Posts one standalone notification for a detected coin. Each alert has a unique id and
     * independent title ("15분봉 급등 종목 알림" / "1시간봉 급등 종목 알림"), so Android does not bundle them.
     */
    private fun sendMatchNotification(tf: ScanTimeframe, match: Match) {
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
        
        // 요청하신 대로 "15분봉 급등 종목 알림" 또는 "1시간봉 급등 종목 알림" 형태로 개별 제목 지정
        val alertTitle = "${tf.label} 급등 종목 알림: ${match.symbol}"

        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(alertTitle)
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
        running.set(false)
        // Do not clear persisted running state on unexpected teardown; explicit STOP clears it.
        scheduler.shutdownNow()
        scannerPool.shutdownNow()
        super.onDestroy()
    }
}
