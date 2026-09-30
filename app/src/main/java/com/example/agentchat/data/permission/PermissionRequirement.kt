package com.example.agentchat.data.permission

/** Pure query classification used before starting protected device actions. */
object PermissionRequirement {
    private val weatherTerms = listOf("天气", "气温", "温度", "下雨", "weather", "temperature", "forecast")
    private val calendarTerms = listOf("日历", "日程", "会议", "安排", "行程")
    private val currentLocationTerms = listOf("当前位置", "当前所在地", "我这里", "附近", "本地")
    private val explicitCityPattern = Regex(
        "([\\p{IsHan}]{2,12})(?:今天|明天|当前|现在)?(?:的)?(?:天气|气温|温度)",
    )

    fun needsCurrentLocation(query: String): Boolean {
        if (!weatherTerms.any { query.contains(it, ignoreCase = true) }) return false
        if (currentLocationTerms.any { query.contains(it) }) return true
        return explicitCityPattern.find(query) == null
    }

    fun needsCalendarRead(query: String): Boolean = calendarTerms.any { query.contains(it) }
}
