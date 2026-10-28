package com.example.binancephonescanner

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * One watch ("감시"). It only holds the id; its name and settings are looked up in [WatchStore],
 * so they are always current. `num` is a unique number used for the alarm request code.
 */
data class Watch(val id: String, val num: Int) {
    val name: String get() = WatchStore.nameOf(id)
    val config: ScanConfig get() = WatchStore.configOf(id)
    val interval: String get() = config.interval
    val label: String get() = config.label
    val scanMinutes: List<Int> get() = config.minutes

    /** SharedPreferences key that belongs to this watch. */
    fun key(suffix: String): String = "${id}_$suffix"

    companion object {
        /** All watches, in display order. */
        fun values(): List<Watch> = WatchStore.watches()
    }
}

/**
 * The list of watches with their names and settings, saved on the phone. The app, the scan
 * service and the history screen all run in one process and share the in-memory copy; every
 * entry point calls [ensureLoaded] first.
 *
 * The first two watches keep the ids "m15" and "h1" of the earlier versions, so their saved
 * state, settings and history carry over.
 */
object WatchStore {
    const val MAX_WATCHES = 10

    private const val FILE = "binance_scanner_config"
    private const val KEY_WATCHES = "watches_v2"
    private const val KEY_NEXT_NUM = "next_num"

    private class Entry(val id: String, val num: Int, val name: String, val config: ScanConfig)

    private var entries: List<Entry> = emptyList()
    private var loaded = false

    private val FALLBACK = ScanConfig("1h", listOf(50), 5.0, 4.0, 4.5)
    private val DEFAULT_15M = ScanConfig("15m", listOf(13, 28, 43, 58), 4.0, 3.0, 4.5)
    private val DEFAULT_1H = ScanConfig("1h", listOf(50), 5.0, 4.0, 4.5)

    private fun prefsOf(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val prefs = prefsOf(context)
        val raw = prefs.getString(KEY_WATCHES, null)
        var parsed: List<Entry> = emptyList()
        if (raw != null) {
            try {
                parsed = parse(raw)
            } catch (_: Exception) {
                parsed = emptyList()
            }
        }
        if (parsed.isEmpty()) {
            // First start of this version: carry over the two fixed monitors of v2.0 and earlier.
            parsed = listOf(
                Entry("m15", 1, "감시 1", legacyConfig(prefs, "m15", DEFAULT_15M)),
                Entry("h1", 2, "감시 2", legacyConfig(prefs, "h1", DEFAULT_1H))
            )
            prefs.edit().putInt(KEY_NEXT_NUM, 3).apply()
        }
        entries = parsed
        loaded = true
        persist(context)
    }

    @Synchronized
    fun watches(): List<Watch> = entries.map { Watch(it.id, it.num) }

    @Synchronized
    fun nameOf(id: String): String = entries.firstOrNull { it.id == id }?.name ?: id

    @Synchronized
    fun configOf(id: String): ScanConfig = entries.firstOrNull { it.id == id }?.config ?: FALLBACK

    @Synchronized
    fun updateConfig(context: Context, id: String, config: ScanConfig) {
        ensureLoaded(context)
        entries = entries.map { if (it.id == id) Entry(it.id, it.num, it.name, config) else it }
        persist(context)
    }

    @Synchronized
    fun rename(context: Context, id: String, newName: String) {
        ensureLoaded(context)
        val clean = newName.trim()
        if (clean.isEmpty()) return
        entries = entries.map { if (it.id == id) Entry(it.id, it.num, clean, it.config) else it }
        persist(context)
    }

    /** Adds a watch with default settings. Returns null when the maximum is reached. */
    @Synchronized
    fun add(context: Context): Watch? {
        ensureLoaded(context)
        if (entries.size >= MAX_WATCHES) return null
        val prefs = prefsOf(context)
        val num = maxOf(prefs.getInt(KEY_NEXT_NUM, 3), (entries.maxOfOrNull { it.num } ?: 0) + 1)
        prefs.edit().putInt(KEY_NEXT_NUM, num + 1).apply()
        val entry = Entry("w$num", num, "감시 $num", DEFAULT_15M)
        entries = entries + entry
        persist(context)
        return Watch(entry.id, entry.num)
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        ensureLoaded(context)
        entries = entries.filter { it.id != id }
        persist(context)
    }

    private fun persist(context: Context) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(JSONObject().apply {
                put("id", e.id)
                put("num", e.num)
                put("name", e.name)
                put("interval", e.config.interval)
                put("minutes", e.config.minutes.joinToString(","))
                put("high", e.config.highPct)
                put("live", e.config.livePct)
                put("volume", e.config.volumeMultiple)
                put("hours", e.config.hours.joinToString(","))
            })
        }
        prefsOf(context).edit().putString(KEY_WATCHES, array.toString()).apply()
    }

    private fun parse(raw: String): List<Entry> {
        val array = JSONArray(raw)
        val list = ArrayList<Entry>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val interval = o.optString("interval", FALLBACK.interval)
            list.add(
                Entry(
                    id = o.getString("id"),
                    num = o.optInt("num", i + 1),
                    name = o.optString("name", "감시"),
                    config = ScanConfig(
                        interval = if (ScanOptions.INTERVALS.containsKey(interval)) interval else FALLBACK.interval,
                        minutes = ScanOptions.parseMinutes(o.optString("minutes")) ?: FALLBACK.minutes,
                        highPct = o.optDouble("high", FALLBACK.highPct),
                        livePct = o.optDouble("live", FALLBACK.livePct),
                        volumeMultiple = o.optDouble("volume", FALLBACK.volumeMultiple),
                        hours = ScanOptions.parseHours(o.optString("hours", "")) ?: emptyList()
                    )
                )
            )
        }
        return list
    }

    /** Reads the settings that v2.0 stored per fixed monitor. */
    private fun legacyConfig(prefs: SharedPreferences, id: String, default: ScanConfig): ScanConfig {
        val interval = prefs.getString("${id}_cfg_interval", null)
        return ScanConfig(
            interval = if (interval != null && ScanOptions.INTERVALS.containsKey(interval)) interval else default.interval,
            minutes = ScanOptions.parseMinutes(prefs.getString("${id}_cfg_minutes", null)) ?: default.minutes,
            highPct = prefs.getString("${id}_cfg_high", null)?.toDoubleOrNull() ?: default.highPct,
            livePct = prefs.getString("${id}_cfg_live", null)?.toDoubleOrNull() ?: default.livePct,
            volumeMultiple = prefs.getString("${id}_cfg_volume", null)?.toDoubleOrNull() ?: default.volumeMultiple
        )
    }
}
