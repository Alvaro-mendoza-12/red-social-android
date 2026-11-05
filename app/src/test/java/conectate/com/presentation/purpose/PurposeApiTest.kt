package conectate.com.presentation.purpose

import conectate.com.data.supabase.AuthSessionMutationGuard
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class PurposeApiTest {
    private val actor = "11111111-1111-4111-8111-111111111111"
    private val post = "22222222-2222-4222-8222-222222222222"
    private fun lease() = AuthSessionMutationGuard().captureAccountLease { actor }
    @Test fun capabilitiesRespectOlderServersAndWritesKeepTheExactOperationAndAuthorization() = runBlocking {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = HttpClient(MockEngine { request -> requests += request
            if(request.url.encodedPath.endsWith("capabilities")) respond("missing",HttpStatusCode.NotFound)
            else respond("{\"postId\":\"$post\"}",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        }) { install(HttpTimeout) }
        try {
            val api = PurposeApi(client,{"qa-token"},{"https://example.invalid"})
            assertFalse(api.available(lease()))
            val body = buildJsonObject { put("operationId",post); put("action","start"); put("expectedRevision",0) }
            val path = PurposeTarget("social",post).writePath(null)
            api.send(lease(),PurposePending(path,body))
            assertEquals("Bearer qa-token",requests.last().headers[HttpHeaders.Authorization])
            assertEquals(HttpMethod.Post,requests.last().method)
            assertEquals(path,requests.last().url.encodedPath)
            assertEquals(body,Json.parseToJsonElement(requests.last().body.toByteArray().decodeToString()))
        } finally { client.close() }
    }
    @Test fun accountChangeDuringTokenLookupPreventsAnyHttpRequest() = runBlocking {
        var current: String? = actor; var requests = 0
        val guard = AuthSessionMutationGuard(); val captured = guard.captureAccountLease { current }
        val client = HttpClient(MockEngine { requests++;respond("{}") }) { install(HttpTimeout) }
        try {
            val api = PurposeApi(client,{ current = post; "old-token" },{"https://example.invalid"})
            assertThrows(CancellationException::class.java) { runBlocking { api.read(captured,PurposeTarget("social",post)) } }
            assertEquals(0,requests)
        } finally { client.close() }
    }
    @Test fun errorsAndMalformedSuccessBodiesAreReportedWithoutManufacturingAnEmptyCatalog() = runBlocking {
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            if(calls == 1) respond("{\"error\":\"PURPOSE_REVISION_CONFLICT\"}",HttpStatusCode.Conflict)
            else respond("<html>not a catalog</html>",HttpStatusCode.OK)
        }) { install(HttpTimeout) }
        try {
            val api = PurposeApi(client,{"qa"},{"https://example.invalid"})
            val conflict = assertThrows(PurposeApiException::class.java) { runBlocking { api.read(lease(),PurposeTarget("social",post)) } }
            assertEquals(409,conflict.status);assertEquals("PURPOSE_REVISION_CONFLICT",conflict.code)
            assertThrows(java.io.IOException::class.java) { runBlocking { api.read(lease(),PurposeTarget("social",post)) } }
            assertEquals(2,calls)
        } finally { client.close() }
    }
}
