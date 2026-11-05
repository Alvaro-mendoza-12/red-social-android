package conectate.com.presentation.purpose

import conectate.com.data.supabase.AuthSessionMutationGuard
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PurposeApiTest {
    private val actor = "11111111-1111-4111-8111-111111111111"
    private val post = "22222222-2222-4222-8222-222222222222"
    private fun lease() = AuthSessionMutationGuard().captureAccountLease { actor }
    private class LocalApi(handler: (HttpExchange) -> Unit) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/", handler)
            start()
        }
        val endpoint = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }
    private fun HttpExchange.reply(status: Int, body: String) {
        responseHeaders.set("Content-Type", "application/json")
        val bytes = body.toByteArray(Charsets.UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }
    private data class Request(val path: String, val method: String, val authorization: String?, val body: String)
    @Test fun capabilitiesRespectOlderServersAndWritesKeepTheExactOperationAndAuthorization() = runBlocking {
        val requests = java.util.Collections.synchronizedList(mutableListOf<Request>())
        val server = LocalApi { request ->
            requests += Request(request.requestURI.path, request.requestMethod, request.requestHeaders.getFirst("Authorization"), request.requestBody.use { it.readBytes().decodeToString() })
            if(request.requestURI.path.endsWith("capabilities")) request.reply(404,"missing")
            else request.reply(200,"{\"postId\":\"$post\"}")
        }
        val client = HttpClient(OkHttp) { install(HttpTimeout) }
        try {
            val api = PurposeApi(client,{"qa-token"},{server.endpoint})
            assertFalse(api.available(lease()))
            val body = buildJsonObject { put("operationId",post); put("action","start"); put("expectedRevision",0) }
            val path = PurposeTarget("social",post).writePath(null)
            api.send(lease(),PurposePending(path,body))
            assertEquals("Bearer qa-token",requests.last().authorization)
            assertEquals("POST",requests.last().method)
            assertEquals(path,requests.last().path)
            assertEquals(body,Json.parseToJsonElement(requests.last().body))
        } finally { client.close();server.close() }
    }
    @Test fun accountChangeDuringTokenLookupPreventsAnyHttpRequest() = runBlocking {
        var current: String? = actor; val requests = AtomicInteger()
        val guard = AuthSessionMutationGuard(); val captured = guard.captureAccountLease { current }
        val server = LocalApi { requests.incrementAndGet();it.reply(200,"{}") }
        val client = HttpClient(OkHttp) { install(HttpTimeout) }
        try {
            val api = PurposeApi(client,{ current = post; "old-token" },{server.endpoint})
            assertThrows(CancellationException::class.java) { runBlocking { api.read(captured,PurposeTarget("social",post)) } }
            assertEquals(0,requests.get())
        } finally { client.close();server.close() }
    }
    @Test fun errorsAndMalformedSuccessBodiesAreReportedWithoutManufacturingAnEmptyCatalog() = runBlocking {
        val calls = AtomicInteger()
        val server = LocalApi {
            if(calls.incrementAndGet() == 1) it.reply(409,"{\"error\":\"PURPOSE_REVISION_CONFLICT\"}")
            else it.reply(200,"<html>not a catalog</html>")
        }
        val client = HttpClient(OkHttp) { install(HttpTimeout) }
        try {
            val api = PurposeApi(client,{"qa"},{server.endpoint})
            val conflict = assertThrows(PurposeApiException::class.java) { runBlocking { api.read(lease(),PurposeTarget("social",post)) } }
            assertEquals(409,conflict.status);assertEquals("PURPOSE_REVISION_CONFLICT",conflict.code)
            assertThrows(java.io.IOException::class.java) { runBlocking { api.read(lease(),PurposeTarget("social",post)) } }
            assertEquals(2,calls.get())
        } finally { client.close();server.close() }
    }
}
