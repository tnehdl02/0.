package com.example.binancephonescanner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
        const val PREFS = "binance_scanner_state"
        private const val CHANNEL_SERVICE = "scanner_status"
        private const val CHANNEL_ALERTS = "scanner_alerts"
        private const val SERVICE_NOTIFICATION_ID = 1001
        private const val VOLUME_MULTIPLE_THRESHOLD = 4.5
        private const val HIGH_CHANGE_THRESHOLD = 5.0
        private const val LIVE_CHANGE_THRESHOLD = 4.0
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val DISPLAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.KOREA)
    }

    private val running = AtomicBoolean(false)
    private val scheduler = Executors.newSingleThreadExecutor()
    private val scannerPool = Executors.newFixedThreadPool(8)

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
            ACTION_START, null -> startMonitoring()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (!running.compareAndSet(false, true)) {
            updateServiceNotification("감시 실행 중 · 다음 검사 ${nextScanText()}")
            return
        }
        prefs.edit().putBoolean("running", true).apply()
        updateServiceNotification("감시 실행 중 · 다음 검사 ${nextScanText()}")
        try {
            scheduler.execute {
            // Run once immediately so the user can verify that scanning really works.
            // After this first pass, continue at each hour's :50 mark (Korea time).
            appendLog("${nowText()} | 감시 시작 | 즉시 검사 시작")
            try {
                performScan()
            } catch (e: Exception) {
                val message = "첫 검사 오류: ${e.message ?: e.javaClass.simpleName}"
                appendLog("${nowText()} | 검사 실패 | $message")
                prefs.edit().putString("last_outcome", "검사 실패").apply()
                updateServiceNotification("검사 오류 · 인터넷 연결을 확인하세요")
            }

            while (running.get()) {
                try {
                    val waitMs = millisUntilNextCheck()
                    val nextText = nextScanText()
                    prefs.edit().putString("next_scan", nextText).apply()
                    updateServiceNotification("감시 실행 중 · 다음 검사 $nextText")
                    Thread.sleep(waitMs)
                    if (!running.get()) break
                    performScan()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                } catch (e: Exception) {
                    val message = "검사 루프 오류: ${e.message ?: e.javaClass.simpleName}"
                    appendLog("${nowText()} | 실패 | $message")
                    prefs.edit().putString("last_outcome", "검사 실패").apply()
                    updateServiceNotification("검사 오류 · 인터넷 연결을 확인하세요")
                    try { Thread.sleep(30_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
                }
            }
            }
        } catch (e: Exception) {
            running.set(false)
            prefs.edit().putBoolean("running", false)
                .putString("last_outcome", "감시 시작 실패: ${e.message ?: e.javaClass.simpleName}").apply()
            appendLog("${nowText()} | 감시 시작 실패 | ${e.message ?: e.javaClass.simpleName}")
            updateServiceNotification("감시 시작 실패 · 앱을 다시 실행하세요")
        }
    }

    private fun stopMonitoring() {
        running.set(false)
        prefs.edit().putBoolean("running", false).putString("next_scan", "감시 중지").apply()
        scheduler.shutdownNow()
    }

    private fun millisUntilNextCheck(): Long {
        val now = ZonedDateTime.now(KST)
        var target = now.withMinute(50).withSecond(5).withNano(0)
        if (!now.isBefore(target)) target = target.plusHours(1)
        return max(1_000L, Duration.between(now, target).toMillis())
    }

    private fun nextScanText(): String {
        val now = ZonedDateTime.now(KST)
        var target = now.withMinute(50).withSecond(5).withNano(0)
        if (!now.isBefore(target)) target = target.plusHours(1)
        return target.format(DISPLAY_TIME) + " KST"
    }

    private fun nowText(): String = ZonedDateTime.now(KST).format(DISPLAY_TIME)

    private data class Match(
        val symbol: String,
        val price: Double,
        val highPct: Double,
        val changePct: Double,
        val volumeMultiple: Double
    )

    private fun performScan() {
        val startText = nowText()
        appendLog("$startText | 검사 시작 (v1.3) | 바이낸스 USDT 무기한 종목 목록 조회 중")
        updateServiceNotification("바이낸스 종목 목록 조회 중…")
        prefs.edit().putString("last_outcome", "검사 진행 중").apply()

        val symbols = try {
            getSymbols()
        } catch (e: Exception) {
            val endText = nowText()
            val detail = "종목 목록 조회 실패: ${e.message ?: e.javaClass.simpleName}"
            prefs.edit()
                .putString("last_scan_time", endText)
                .putInt("last_scanned", 0)
                .putInt("last_match_count", 0)
                .putInt("last_failures", 1)
                .putString("last_matches", "[]")
                .putString("last_outcome", "검사 실패")
                .putInt("scan_progress_done", 0)
                .putInt("scan_progress_total", 0)
                .putInt("scan_progress_percent", 0)
                .putBoolean("scan_in_progress", false)
                .apply()
            appendLog("$startText | 검사 실패 | 종목 목록을 가져오지 못함: ${e.message ?: e.javaClass.simpleName}")
            updateServiceNotification("검사 실패 · 종목 목록 조회 오류")
            return
        }

        if (symbols.isEmpty()) {
            val endText = nowText()
            prefs.edit()
                .putString("last_scan_time", endText)
                .putInt("last_scanned", 0)
                .putInt("last_match_count", 0)
                .putInt("last_failures", 1)
                .putString("last_matches", "[]")
                .putString("last_outcome", "검사 실패: 종목 0개")
                .putInt("scan_progress_done", 0)
                .putInt("scan_progress_total", 0)
                .putInt("scan_progress_percent", 0)
                .putBoolean("scan_in_progress", false)
                .apply()
            appendLog("$startText | 검사 실패 | 조회된 USDT 무기한 종목이 0개")
            updateServiceNotification("검사 실패 · 종목 0개")
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
            .putString("last_outcome", "검사 진행 중")
            .apply()
        updateServiceNotification("검사 중 · 0/${symbols.size} · 0%")

        val tasks = symbols.map { symbol ->
            Callable {
                try {
                    inspectSymbol(symbol, failures)
                } finally {
                    val done = completed.incrementAndGet()
                    if (done % progressStep == 0 || done == symbols.size) {
                        val percent = (done * 100 / symbols.size).coerceIn(0, 100)
                        prefs.edit()
                            .putInt("scan_progress_done", done)
                            .putInt("scan_progress_total", symbols.size)
                            .putInt("scan_progress_percent", percent)
                            .apply()
                        updateServiceNotification("검사 중 · $done/${symbols.size} · $percent%")
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

        prefs.edit()
            .putString("last_scan_time", endText)
            .putInt("last_scanned", symbols.size)
            .putInt("last_match_count", matches.size)
            .putInt("last_failures", failureCount)
            .putString("last_matches", matchJson.toString())
            .putString("last_outcome", outcome)
            .putString("next_scan", nextScanText())
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
        appendLog(logLine)

        if (failureCount < symbols.size) matches.forEach { sendMatchNotification(it) }
        updateServiceNotification("마지막 검사 $endText · ${symbols.size}종목 · 감지 ${matches.size}개 · 실패 $failureCount")
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

    private fun inspectSymbol(symbol: String, failures: AtomicInteger): Match? {
        return try {
            val url = "https://fapi.binance.com/fapi/v1/klines?symbol=$symbol&interval=1h&limit=60"
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

    private fun appendLog(line: String) {
        val old = prefs.getString("scan_logs", "") ?: ""
        val lines = (old.lines().filter { it.isNotBlank() } + line.take(1200)).takeLast(5)
        prefs.edit().putString("scan_logs", lines.joinToString("\n")).apply()
    }

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
            .setContentIntent(openPending)
            .setOngoing(true)
            .addAction(0, "감시 중지", stopPending)
            .build()
    }

    private fun updateServiceNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(SERVICE_NOTIFICATION_ID, serviceNotification(message))
    }

    private fun sendMatchNotification(match: Match) {
        val manager = getSystemService(NotificationManager::class.java)
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, match.symbol.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = "고가/시가 +%.2f%% · 현재/시가 +%.2f%% · 거래량 %.2fx".format(
            Locale.US, match.highPct, match.changePct, match.volumeMultiple
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("급등 감지: ${match.symbol}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body\n현재가 ${match.price}"))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
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
