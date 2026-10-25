package com.example.binancescanner.data

enum class ConditionType {
    VOLUME_INCREASE,   // 거래량 증가
    MA_ALIGNMENT,      // 이동평균선 배열
    MA_CROSS,          // 이동평균선 비교
    PRICE_CHANGE       // 가격 등락률
}

data class MonitorCondition(
    val id: String = java.util.UUID.randomUUID().toString(),
    val conditionType: ConditionType,
    val timeframe: String, // 1m, 5m, 15m, 1h, 4h, 1d
    val param1: Double = 0.0,
    val param2: Double = 0.0,
    val maType: String = "SMA"
)
