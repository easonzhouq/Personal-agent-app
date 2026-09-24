package com.example.agentchat.data.search

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebSearchClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun retriesTransientHttpFailureAndAddsUserAgent() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"Heading\":\"Test\",\"AbstractText\":\"A result\",\"AbstractURL\":\"https://example.com\"}"),
        )

        val result = WebSearchClient(
            client = testClient(),
            duckDuckGoUrl = server.url("/search").toString(),
        ).search("latest news")

        assertNotNull(result.context)
        assertNull(result.failure)
        assertEquals(2, server.requestCount)
        assertTrue(server.takeRequest().getHeader("User-Agent").orEmpty().isNotBlank())
    }

    @Test
    fun doesNotRetryUnauthorizedResponse() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = WebSearchClient(
            client = testClient(),
            duckDuckGoUrl = server.url("/search").toString(),
        ).search("latest news")

        assertNull(result.context)
        assertEquals("联网服务返回 HTTP 401", result.failure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun fallsBackToWikipediaWhenDuckDuckGoHasNoAnswer() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"AbstractText\":\"\"}"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"query\":{\"search\":[{\"title\":\"测试条目\",\"snippet\":\"这是百科摘要\"}]}}"),
        )

        val result = WebSearchClient(
            client = testClient(),
            duckDuckGoUrl = server.url("/ddg").toString(),
            wikipediaUrl = server.url("/wikipedia").toString(),
        ).search("latest news")

        assertTrue(result.context?.summary.orEmpty().contains("测试条目"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun returnsLatestRssItemsForGenericNewsQuery() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/rss+xml")
                .setBody("<rss><channel><item><title>今日新闻</title><description>新闻摘要</description></item></channel></rss>"),
        )

        val result = WebSearchClient(
            client = testClient(),
            rssFeedUrls = listOf(server.url("/rss").toString()),
        ).search("latest news")

        assertTrue(result.context?.summary.orEmpty().contains("今日新闻"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun apiAndServiceQuestionsTriggerNetworkSearch() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"Heading\":\"Open-Meteo API\",\"AbstractText\":\"API documentation result\"}"),
        )

        val result = WebSearchClient(
            client = testClient(),
            duckDuckGoUrl = server.url("/ddg").toString(),
        ).search("关于 Open-Meteo API 调用")

        assertTrue(result.context?.summary.orEmpty().contains("Open-Meteo API"))
        assertEquals(1, server.requestCount)
    }

    private fun testClient() = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(1, TimeUnit.SECONDS)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()
}
