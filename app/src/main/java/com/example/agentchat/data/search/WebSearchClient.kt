package com.example.agentchat.data.search

import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class WebSearchContext(
    val query: String,
    val summary: String,
    val sources: List<String>,
) {
    fun asSystemPrompt(): String = buildString {
        appendLine("你可以使用下面的联网查询结果回答用户。")
        appendLine("这些内容可能不完整，请不要编造查询结果中没有的信息。")
        appendLine()
        appendLine("查询：$query")
        appendLine(summary)
        if (sources.isNotEmpty()) {
            appendLine()
            appendLine("来源：")
            sources.forEach { appendLine("- $it") }
        }
    }
}

class WebSearchClient(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun search(query: String): WebSearchContext? = withContext(Dispatchers.IO) {
        if (!shouldSearch(query)) return@withContext null
        if (isWeatherQuery(query)) {
            searchWeather(query) ?: apiKey.takeIf { it.isNotBlank() }?.let { searchTavily(query) }
        } else if (apiKey.isNotBlank()) {
            searchTavily(query)
        } else {
            null
        }
    }

    private fun searchTavily(query: String): WebSearchContext? {
        val requestJson = buildJsonObject {
            put("api_key", apiKey)
            put("query", query)
            put("search_depth", "advanced")
            put("topic", "general")
            put("max_results", 5)
            put("include_answer", true)
            put("include_raw_content", false)
        }
        val request = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Accept", "application/json")
            .post(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), requestJson).toRequestBody("application/json".toMediaType()))
            .build()
        val root = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                response.body?.string()?.let { json.parseToJsonElement(it).jsonObject }
            }
        }.getOrNull() ?: return null
        val answer = root["answer"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val results = root["results"]?.jsonArray.orEmpty().mapNotNull { item ->
            val result = item.jsonObject
            val title = result["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val content = result["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val url = result["url"]?.jsonPrimitive?.contentOrNull
            if (title.isBlank() && content.isBlank()) null else Triple(title, content, url)
        }
        if (answer.isBlank() && results.isEmpty()) return null
        val summary = buildString {
            if (answer.isNotBlank()) appendLine("Tavily 摘要：$answer")
            results.forEachIndexed { index, result ->
                appendLine("${index + 1}. ${result.first}")
                if (result.second.isNotBlank()) appendLine(result.second)
            }
        }.trim()
        return WebSearchContext(query, summary, results.mapNotNull { it.third })
    }

    private fun searchWeather(query: String): WebSearchContext? {
        val city = extractCity(query) ?: return null
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${encode(city)}&count=1&language=zh&format=json"
        val geo = getJson(geoUrl)?.get("results")?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val latitude = geo["latitude"]?.jsonPrimitive?.contentOrNull ?: return null
        val longitude = geo["longitude"]?.jsonPrimitive?.contentOrNull ?: return null
        val location = geo["name"]?.jsonPrimitive?.contentOrNull ?: city
        val weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude&current=temperature_2m,apparent_temperature,weather_code,relative_humidity_2m,wind_speed_10m&timezone=auto"
        val current = getJson(weatherUrl)?.get("current")?.jsonObject ?: return null
        val temperature = current["temperature_2m"]?.jsonPrimitive?.contentOrNull ?: return null
        val apparent = current["apparent_temperature"]?.jsonPrimitive?.contentOrNull
        val humidity = current["relative_humidity_2m"]?.jsonPrimitive?.contentOrNull
        val wind = current["wind_speed_10m"]?.jsonPrimitive?.contentOrNull
        val code = current["weather_code"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        val summary = buildString {
            append("$location 当前天气：${weatherCode(code)}，气温 ${temperature}°C")
            apparent?.let { append("，体感 ${it}°C") }
            humidity?.let { append("，湿度 ${it}%") }
            wind?.let { append("，风速 ${it} km/h") }
        }
        return WebSearchContext(query, summary, listOf("https://open-meteo.com/"))
    }

    private fun getJson(url: String) = runCatching {
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()?.let { json.parseToJsonElement(it).jsonObject }
        }
    }.getOrNull()

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun extractCity(query: String): String? = listOf(
        Regex("([\\p{IsHan}]{2,12})(?:今天|明天|当前|现在)?(?:的)?(?:天气|气温|温度)"),
        Regex("(?:今天|明天|当前|现在)([\\p{IsHan}]{2,12})(?:的)?(?:天气|气温|温度)"),
    ).firstNotNullOfOrNull { it.find(query)?.groupValues?.getOrNull(1) }

    private fun isWeatherQuery(query: String) = listOf("天气", "气温", "温度", "下雨", "weather", "temperature", "forecast")
        .any { query.contains(it, ignoreCase = true) }

    private fun shouldSearch(query: String) = listOf(
        "联网", "搜索", "查一下", "查询", "最新", "实时", "今天", "现在", "天气", "新闻", "价格", "股价",
        "weather", "latest", "search", "current", "news",
    ).any { query.contains(it, ignoreCase = true) }

    private fun weatherCode(code: Int?) = when (code) {
        0 -> "晴"
        1, 2 -> "少云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55, 56, 57 -> "毛毛雨"
        61, 63, 65, 66, 67, 80, 81, 82 -> "降雨"
        71, 73, 75, 77, 85, 86 -> "降雪"
        95, 96, 99 -> "雷雨"
        else -> "天气状况未知"
    }
}
