package com.example.binancescanner.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class NetworkRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun getSymbols(marketType: String): List<String> = withContext(Dispatchers.IO) {
        val symbols = mutableListOf<String>()
        val url = if (marketType == "FUTURES") {
            "https://fapi.binance.com/fapi/v1/ticker/24hr"
        } else {
            "https://api.binance.com/api/v3/ticker/24hr"
        }

        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val jsonArray = JSONArray(response.body?.string() ?: "[]")

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val symbol = obj.getString("symbol")
                if (symbol.endsWith("USDT")) {
                    symbols.add(symbol)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext symbols
    }
}
