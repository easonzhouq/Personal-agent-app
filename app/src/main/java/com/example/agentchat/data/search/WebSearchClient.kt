package com.example.agentchat.data.search

import com.example.agentchat.data.location.DeviceCoordinates
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException

data class WebSearchResult(
    val context: WebSearchContext? = null,
    val failure: String? = null,
)

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
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val locationProvider: suspend () -> DeviceCoordinates? = { null },
) {
    suspend fun search(query: String): WebSearchResult = withContext(Dispatchers.IO) {
        if (!shouldSearch(query)) return@withContext WebSearchResult()
        try {
            val context = if (isWeatherQuery(query)) searchWeather(query) ?: searchDuckDuckGo(query)
            else searchDuckDuckGo(query)
            if (context == null) WebSearchResult(failure = "联网服务没有返回可用结果，请检查网络或改用更具体的城市/关键词")
            else WebSearchResult(context = context)
        } catch (error: SearchNetworkException) {
            WebSearchResult(failure = error.message ?: "联网请求失败")
        } catch (_: SocketTimeoutException) {
            WebSearchResult(failure = "联网请求超时，请检查手机网络")
        } catch (_: IOException) {
            WebSearchResult(failure = "网络连接失败，请检查 Wi-Fi、移动数据或 VPN")
        } catch (_: Exception) {
            WebSearchResult(failure = "联网数据解析失败，请稍后重试")
        }
    }

    private fun searchDuckDuckGo(query: String): WebSearchContext? {
        val root = getJson("https://api.duckduckgo.com/?q=${encode(query)}&format=json&no_html=1&skip_disambig=1") ?: return null
        val abstractText = root["AbstractText"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val abstractUrl = root["AbstractURL"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val heading = root["Heading"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val related = root["RelatedTopics"]?.jsonArray.orEmpty().firstNotNullOfOrNull { item ->
            item.jsonObject["Text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                text to item.jsonObject["FirstURL"]?.jsonPrimitive?.contentOrNull
            }
        }
        val summary = when {
            abstractText.isNotBlank() -> listOfNotNull(heading.takeIf { it.isNotBlank() }, abstractText).joinToString("：")
            related != null -> related.first
            else -> return null
        }
        val sources = listOfNotNull(abstractUrl.takeIf { it.isNotBlank() }, related?.second)
        return WebSearchContext(query, summary, sources)
    }

    private suspend fun searchWeather(query: String): WebSearchContext? {
        val city = extractCity(query)
        val coordinates = if (city == null) {
            locationProvider()?.let { it.latitude.toString() to it.longitude.toString() }
        } else {
            val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${encode(city)}&count=1&language=zh&format=json"
            val geo = getJson(geoUrl)?.get("results")?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val latitude = geo["latitude"]?.jsonPrimitive?.contentOrNull ?: return null
            val longitude = geo["longitude"]?.jsonPrimitive?.contentOrNull ?: return null
            latitude to longitude
        }
        if (coordinates == null && city == null) {
            return WebSearchContext(
                query = query,
                summary = "无法获取当前位置。请检查应用的大致位置权限和手机系统定位服务，或直接输入城市名称，例如“北京今天天气”。",
                sources = emptyList(),
            )
        }
        val (latitude, longitude) = coordinates ?: return null
        val location = city ?: "当前位置"
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
            if (!response.isSuccessful) throw SearchNetworkException("联网服务返回 HTTP ${response.code}")
            response.body?.string()?.let { json.parseToJsonElement(it).jsonObject }
                ?: throw SearchNetworkException("联网服务返回空数据")
        }
    }.getOrElse { error ->
        when (error) {
            is SearchNetworkException -> throw error
            is SocketTimeoutException -> throw error
            is IOException -> throw error
            else -> throw SearchNetworkException("联网服务响应格式异常")
        }
    }

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

private class SearchNetworkException(message: String) : IllegalStateException(message)
