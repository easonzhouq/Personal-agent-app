package com.example.agentchat.data.provider

import com.example.agentchat.domain.model.Attachment
import com.example.agentchat.domain.model.ChatEvent
import com.example.agentchat.data.attachment.AttachmentReadError
import com.example.agentchat.domain.model.ChatMessage
import com.example.agentchat.domain.model.MessageStatus
import com.example.agentchat.domain.model.ModelConfig
import com.example.agentchat.domain.model.ProviderProtocol
import com.example.agentchat.domain.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.ResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException
import okio.buffer
import okio.Buffer
import okio.Source
import okio.Timeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiCompatibleProviderTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun config() = ModelConfig("id", "Test", server.url("/v1/").toString(), "model-x", ProviderProtocol.OPENAI_COMPATIBLE)
    private fun message(text: String, role: Role) = ChatMessage("$role-$text", "c", role, text, status = MessageStatus.COMPLETED)

    @Test
    fun postsNormalizedPathAuthModelStreamAndOrderedRoles() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\ndata: [DONE]\n"))

        val events = OpenAiCompatibleProvider().stream(config(), "secret-key", listOf(message("one", Role.USER), message("two", Role.ASSISTANT))).toList()
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer secret-key", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type").orEmpty().startsWith("application/json"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("model-x", body["model"]?.jsonPrimitive?.content)
        assertEquals(true, body["stream"]?.jsonPrimitive?.boolean)
        assertTrue(!body.toString().contains("secret-key"))
        val messages = body["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("one", messages[0].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("assistant", messages[1].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("two", messages[1].jsonObject["content"]?.jsonPrimitive?.content)
        assertTrue(events.contains(ChatEvent.Delta("ok")))
    }

    @Test
    fun mapsUnauthorizedAndRateLimitWithoutExposingResponseBody() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("secret response body"))
        val unauthorized = OpenAiCompatibleProvider().stream(config(), "key", emptyList()).toList().last() as ChatEvent.Failed
        assertEquals("unauthorized", unauthorized.error.code)
        assertTrue(!unauthorized.error.message.contains("secret response body"))

        server.enqueue(MockResponse().setResponseCode(429).setBody("private details"))
        val limited = OpenAiCompatibleProvider().stream(config(), "key", emptyList()).toList().last() as ChatEvent.Failed
        assertEquals("rate_limited", limited.error.code)
        assertTrue(limited.error.retryable)
    }

    @Test
    fun encodesAttachmentsOrReturnsUnsupportedAttachment() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("data: [DONE]\n"))
        val attachment = Attachment("a", "x.txt", "text/plain", 1, "content://x")
        val encoder = AttachmentEncoder {
            buildJsonObject {
                put("type", "input_file")
                put("file_id", "encoded:${it.name}")
            }
        }
        OpenAiCompatibleProvider(attachmentEncoder = encoder).stream(config(), "key", listOf(message("hi", Role.USER).copy(attachments = listOf(attachment)))).toList()
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val content = body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("hi", content[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("input_file", content[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("encoded:x.txt", content[1].jsonObject["file_id"]?.jsonPrimitive?.content)

        val unsupported = OpenAiCompatibleProvider().stream(config(), "key", listOf(message("hi", Role.USER).copy(attachments = listOf(attachment)))).toList().last() as ChatEvent.Failed
        assertEquals("unsupported_attachment", unsupported.error.code)
    }

    @Test
    fun encoderReadFailureIsTypedAndDoesNotSendSecretsOrFileData() = runTest {
        val apiKey = "api-key-private"
        val fileData = "file-content-private"
        val encoder = AttachmentEncoder { throw AttachmentReadError() }
        val attachment = Attachment("a", "private.txt", "text/plain", 1, "content://private")
        val events = OpenAiCompatibleProvider(attachmentEncoder = encoder)
            .stream(config(), apiKey, listOf(message("hi", Role.USER).copy(attachments = listOf(attachment))))
            .toList()
        val failure = events.last() as ChatEvent.Failed
        assertEquals("attachment_read_error", failure.error.code)
        assertTrue(!failure.error.message.contains(apiKey))
        assertTrue(!failure.error.message.contains(fileData))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancellationCancelsRequest() = runBlocking {
        val requestReceived = CountDownLatch(1)
        val responseGate = CountDownLatch(1)
        val callCancelled = CountDownLatch(1)
        val started = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                requestReceived.countDown()
                responseGate.await()
                return MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"late\"}}]}\n\ndata: [DONE]\n")
            }
        }
        val events = mutableListOf<ChatEvent>()
        val client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) { callCancelled.countDown() }
        }).build()
        val job: Job = launch(Dispatchers.IO) {
            OpenAiCompatibleProvider(client = client).stream(config(), "key", emptyList()).collect {
                events += it
                if (it == ChatEvent.Started) started.countDown()
            }
        }
        yield()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        assertTrue(requestReceived.await(5, TimeUnit.SECONDS))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        job.cancelAndJoin()
        assertTrue(callCancelled.await(5, TimeUnit.SECONDS))
        responseGate.countDown()
        assertEquals(listOf(ChatEvent.Started), events)
    }

    @Test
    fun cancellationAfterFirstDeltaCancelsOpenResponseWithoutLateCompletion() = runBlocking {
        val callFactory = GatedCallFactory()
        val events = CopyOnWriteArrayList<ChatEvent>()
        val job = launch(Dispatchers.IO) {
            OpenAiCompatibleProvider(callFactory = callFactory).stream(config(), "key", emptyList()).collect {
                events += it
                if (it == ChatEvent.Delta("first")) callFactory.deltaConsumed.countDown()
            }
        }

        yield()
        assertTrue(callFactory.deltaConsumed.await(5, TimeUnit.SECONDS))
        job.cancel()
        callFactory.releaseBody()
        job.join()
        assertTrue(callFactory.cancelled.await(5, TimeUnit.SECONDS))
        assertTrue(callFactory.closed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(ChatEvent.Started, ChatEvent.Delta("first")), events.toList())
        assertTrue(events.none { it is ChatEvent.Completed })
    }
}

private class GatedCallFactory : Call.Factory {
    val deltaConsumed = CountDownLatch(1)
    val cancelled = CountDownLatch(1)
    val closed = CountDownLatch(1)
    private val body = GatedResponseBody(closed)

    fun releaseBody() = body.release()

    override fun newCall(request: okhttp3.Request): Call = object : Call {
        private val cancelledState = AtomicBoolean(false)
        private var executed = false
        private val response = okhttp3.Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()

        override fun request() = request
        override fun timeout() = Timeout.NONE
        override fun isExecuted() = executed
        override fun isCanceled() = cancelledState.get()
        override fun cancel() {
            if (cancelledState.compareAndSet(false, true)) {
                cancelled.countDown()
                body.close()
            }
        }
        override fun clone(): Call = this
        override fun execute(): okhttp3.Response = error("Synchronous execution is not used")
        override fun enqueue(responseCallback: okhttp3.Callback) {
            executed = true
            Thread {
                if (!cancelledState.get()) responseCallback.onResponse(this, response)
            }.start()
        }
    }
}

private class GatedResponseBody(private val closed: CountDownLatch) : ResponseBody() {
    private val source = GatedSource()
    private val buffered = source.buffer()
    private val closeState = AtomicBoolean(false)

    override fun contentType() = "text/event-stream".toMediaType()
    override fun contentLength() = -1L
    override fun source() = buffered
    override fun close() {
        if (closeState.compareAndSet(false, true)) {
            source.close()
            closed.countDown()
        }
    }

    fun release() = source.release()
}

private class GatedSource : Source {
    private val remaining = Buffer().writeUtf8("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n")
    private val release = CountDownLatch(1)
    private val closed = AtomicBoolean(false)

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (remaining.size > 0) {
            val count = minOf(byteCount, remaining.size)
            sink.write(remaining, count)
            return count
        }
        try {
            release.await()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("gated source interrupted", error)
        }
        return -1L
    }

    override fun timeout() = Timeout.NONE

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }

    fun release() = release.countDown()
}
