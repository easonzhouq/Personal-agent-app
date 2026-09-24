package com.example.agentchat.data.search

import com.example.agentchat.data.location.DeviceCoordinates
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.util.concurrent.TimeUnit

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
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val locationProvider: suspend () -> DeviceCoordinates? = { null },
    private val duckDuckGoUrl: String = "https://api.duckduckgo.com/",
    private val wikipediaUrl: String = "https://zh.wikipedia.org/w/api.php",
    private val arxivUrl: String = "https://export.arxiv.org/api/query",
    private val rssFeedUrls: List<String> = emptyList(),
) {
    suspend fun search(query: String): WebSearchResult = withContext(Dispatchers.IO) {
        if (!shouldSearch(query)) return@withContext WebSearchResult()
        try {
            val context = if (isWeatherQuery(query)) searchWeather(query) ?: searchFallbacks(query)
            else searchFallbacks(query)
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

    private suspend fun searchDuckDuckGo(query: String): WebSearchContext? {
        val root = getJson("${duckDuckGoUrl.trimEnd('/')}/?q=${encode(query)}&format=json&no_html=1&skip_disambig=1") ?: return null
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

    private suspend fun searchFallbacks(query: String): WebSearchContext? {
        val candidates: List<suspend () -> WebSearchContext?> = when {
            isAcademicQuery(query) -> listOf(::searchArxiv, ::searchWikipedia, ::searchDuckDuckGo).map { candidate -> { candidate(query) } }
            isNewsQuery(query) -> listOf(::searchRss, ::searchDuckDuckGo, ::searchWikipedia).map { candidate -> { candidate(query) } }
            else -> listOf(::searchDuckDuckGo, ::searchWikipedia, ::searchArxiv).map { candidate -> { candidate(query) } }
        }
        for (candidate in candidates) {
            val result = try {
                candidate()
            } catch (error: SearchNetworkException) {
                if (!error.retryable) throw error
                null
            } catch (_: IOException) {
                null
            }
            if (result != null) return result
        }
        return null
    }

    private suspend fun searchWikipedia(query: String): WebSearchContext? {
        val root = getJson("${wikipediaUrl.trimEnd('/')}?action=query&list=search&srsearch=${encode(query)}&format=json&utf8=1")
        val results = root["query"]?.jsonObject?.get("search")?.jsonArray.orEmpty()
        if (results.isEmpty()) return null
        val items = results.take(3).mapNotNull { item ->
            val objectItem = item.jsonObject
            val title = objectItem["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val snippet = objectItem["snippet"]?.jsonPrimitive?.contentOrNull.orEmpty()
            title.takeIf { it.isNotBlank() }?.let { "$it：${cleanMarkup(snippet)}" }
        }
        if (items.isEmpty()) return null
        val sources = results.take(3).mapNotNull { item ->
            item.jsonObject["pageid"]?.jsonPrimitive?.contentOrNull?.let { "https://zh.wikipedia.org/?curid=$it" }
        }
        return WebSearchContext(query, items.joinToString("\n"), sources)
    }

    private suspend fun searchArxiv(query: String): WebSearchContext? {
        val body = getBody("${arxivUrl.trimEnd('/')}?search_query=all:${encode(query)}&start=0&max_results=3", "application/atom+xml")
        val entries = Regex("<entry>(.*?)</entry>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(body)
            .take(3)
            .mapNotNull { match ->
                val entry = match.groupValues[1]
                val title = firstTag(entry, "title")?.let(::cleanMarkup).orEmpty()
                val summary = firstTag(entry, "summary")?.let(::cleanMarkup).orEmpty()
                title.takeIf { it.isNotBlank() }?.let { "$it：$summary" }
            }
            .toList()
        if (entries.isEmpty()) return null
        return WebSearchContext(query, entries.joinToString("\n"), listOf("https://arxiv.org/"))
    }

    private suspend fun searchRss(query: String): WebSearchContext? {
        if (rssFeedUrls.isEmpty()) return null
        val entries = buildList {
            rssFeedUrls.forEach { feedUrl ->
                val body = runCatching { getBody(feedUrl, "application/rss+xml, application/atom+xml, text/xml") }.getOrNull() ?: return@forEach
                Regex("<(item|entry)(?:\\s[^>]*)?>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .findAll(body)
                    .forEach { match ->
                        val item = match.groupValues[2]
                        val title = firstTag(item, "title")?.let(::cleanMarkup).orEmpty()
                        val description = (firstTag(item, "description") ?: firstTag(item, "summary"))?.let(::cleanMarkup).orEmpty()
                        if (title.isNotBlank()) add("$title：$description")
                    }
            }
        }
        val terms = query.split(Regex("\\s+"))
            .filter { it.length > 1 && !it.equals("latest", true) && !it.equals("news", true) }
        val selected = if (terms.isEmpty()) entries else entries.filter { it.containsAny(terms) }.ifEmpty { entries }
        if (selected.isEmpty()) return null
        return WebSearchContext(query, selected.take(5).joinToString("\n"), rssFeedUrls)
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

    private suspend fun getJson(url: String): kotlinx.serialization.json.JsonObject {
        val body = getBody(url, "application/json")
        return runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { throw SearchNetworkException("联网服务响应格式异常") }
    }

    private suspend fun getBody(url: String, accept: String): String {
        var lastFailure: Throwable? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                return client.newCall(
                    Request.Builder()
                        .url(url)
                        .header("Accept", accept)
                        .header("User-Agent", USER_AGENT)
                        .build(),
                ).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw SearchNetworkException(
                            message = "联网服务返回 HTTP ${response.code}",
                            retryable = response.code == 408 || response.code == 429 || response.code in 500..599,
                        )
                    }
                    response.body?.string()?.takeIf { it.isNotBlank() }
                        ?: throw SearchNetworkException("联网服务返回空数据")
                }
            } catch (error: SearchNetworkException) {
                lastFailure = error
                if (!error.retryable || attempt == MAX_ATTEMPTS - 1) throw error
            } catch (error: IOException) {
                lastFailure = error
                if (attempt == MAX_ATTEMPTS - 1) throw error
            }
            delay(RETRY_DELAYS_MS[attempt])
        }
        throw lastFailure ?: SearchNetworkException("联网请求失败")
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
        "weather", "latest", "search", "current", "news", "api", "http://", "https://", ".com", ".org",
        "duckduckgo", "open-meteo", "wikipedia", "arxiv", "rss",
    ).any { query.contains(it, ignoreCase = true) }

    private fun isNewsQuery(query: String) = listOf("新闻", "资讯", "latest", "news").any { query.contains(it, ignoreCase = true) }

    private fun isAcademicQuery(query: String) = listOf("论文", "学术", "arxiv", "研究", "paper").any { query.contains(it, ignoreCase = true) }

    private fun firstTag(value: String, tag: String): String? =
        Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(value)?.groupValues?.getOrNull(1)

    private fun cleanMarkup(value: String) = value
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun String.containsAny(values: List<String>) = values.any { contains(it, ignoreCase = true) }

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

    companion object {
        const val MAX_ATTEMPTS = 3
        val RETRY_DELAYS_MS = longArrayOf(100L, 300L)
        const val USER_AGENT = "KapibaraAgent/1.2.1 (Android)"
        val DEFAULT_RSS_FEEDS = listOf("https://feeds.bbci.co.uk/news/world/asia/rss.xml")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

private class SearchNetworkException(
    message: String,
    val retryable: Boolean = false,
) : IllegalStateException(message)
