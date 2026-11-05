package conectate.com.presentation.purpose

import android.graphics.Bitmap
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.community.CommunityQuestion
import conectate.com.data.local.AgreementRecoveryPreferences
import conectate.com.data.model.CommunityEvent
import conectate.com.data.model.CommunityEventCatalog
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.presentation.messages.CommunityEventCard
import conectate.com.presentation.messages.QuestionAnswerSheet
import conectate.com.presentation.theme.RedSocialTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Actual candidate image and PostgreSQL, localhost TLS and explicitly fictitious identities. */
class PurposeHttpDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val a = "44444444-4444-4444-8444-444444444444"
    private val b = "55555555-5555-4555-8555-555555555555"
    private val post = "66666666-6666-4666-8666-666666666666"
    private val comment = "77777777-7777-4777-8777-777777777777"
    private val community = "88888888-8888-4888-8888-888888888888"
    private val questionId = "99999999-9999-4999-8999-999999999999"
    private val messageId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val eventId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
    private val models = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var client: HttpClient
    private lateinit var api: PurposeApi
    private var dark = false
    private var failure: Throwable? = null
    private val base = "https://127.0.0.1:18107"
    private fun token(actor: String) = if(actor == a) "qa-a" else "qa-b"
    private fun lease(actor: String) = AuthSessionMutationGuard().captureAccountLease { actor }
    @Before fun before() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowPurposeFixture") == "true")
        dark = args.getString("purposeDark") == "true"
        val certificate = checkNotNull(args.getString("fixtureCertificate"))
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null,null)
            setCertificateEntry("qa-only",CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(android.util.Base64.decode(certificate,android.util.Base64.DEFAULT))))
        }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }.trustManagers.filterIsInstance<X509TrustManager>().single()
        val tls = SSLContext.getInstance("TLS").apply { init(null,arrayOf(manager),null) }
        client = HttpClient(OkHttp) { engine { config { sslSocketFactory(tls.socketFactory,manager);retryOnConnectionFailure(false) } };install(HttpTimeout) }
        api = PurposeApi(client,{token(checkNotNull(it.accountId))},{base})
        runBlocking(Dispatchers.IO) {
            val reset = client.post(base+"/__qa/reset") { header(HttpHeaders.Authorization,"Bearer qa-reset-only") }
            assertEquals(200,reset.status.value);reset.bodyAsText()
        }
        clearOwnedRecovery()
    }
    @After fun after() {
        if(!::client.isInitialized) return
        InstrumentationRegistry.getInstrumentation().runOnMainSync { models.clear();scope.cancel() }
        client.close();clearOwnedRecovery()
    }
    private fun clearOwnedRecovery() {
        val preferences = AgreementRecoveryPreferences.open(InstrumentationRegistry.getInstrumentation().targetContext)
        val prefixes = listOf(a,b).map { AgreementRecoveryPreferences.accountPrefix(it)+"purpose:" }
        synchronized(AgreementRecoveryPreferences.lock) { assertTrue(preferences.edit().apply {
            preferences.all.keys.filter { key -> prefixes.any(key::startsWith) }.forEach(::remove)
        }.commit()) }
    }
    private fun model(actor: String): PurposeViewModel {
        val guard = AuthSessionMutationGuard();val current = guard.captureAccountLease { actor }
        val persistence = PurposeRecoveryStore({AgreementRecoveryPreferences.open(InstrumentationRegistry.getInstrumentation().targetContext)},guard::commitForLease)
        lateinit var value: PurposeViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            value = PurposeViewModel({current},MutableStateFlow(0),emptyFlow(),api::available,api::read,api::send,persistence::read,persistence::replace)
            models.put(UUID.randomUUID().toString(),value)
        }
        return value
    }
    private fun await(model: PurposeViewModel,predicate: (PurposeState)->Boolean = {true}) {
        compose.waitUntil(20_000) { failure?.let { throw AssertionError("Fixture failed",it) }
            val state = model.state.value; state.supported && state.payload != null && !state.loading && !state.busy && predicate(state) }
        assertNull(model.state.value.error)
    }
    private fun click(text: String) {
        val node = compose.onNode(hasText(text) and hasClickAction())
        if (!node.isDisplayed()) node.performScrollTo()
        node.assertIsDisplayed().performClick()
    }
    private fun snapshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val run = InstrumentationRegistry.getArguments().getString("purposeRunId") ?: "owned-qa"
        val target = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir("purpose111")),"$run-$name.png")
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) };bitmap.recycle()
    }
    private suspend fun direct(path: String,actor: String,body: JsonObject? = null,method: HttpMethod = if(body == null) HttpMethod.Get else HttpMethod.Post): JsonObject {
        val response = client.request(base+path) { this.method = method;header(HttpHeaders.Authorization,"Bearer ${token(actor)}")
            if(body != null) { contentType(ContentType.Application.Json);setBody(body.toString()) } }
        val text = response.bodyAsText();assertEquals(text,200,response.status.value)
        return purposeJson.parseToJsonElement(text).jsonObject
    }
    private fun async(action: suspend ()->Unit) { scope.launch { try { action() } catch(error: Exception) { failure = error } } }
    private fun command(action: String,revision: Int,fields: JsonObject = buildJsonObject {}) = buildJsonObject {
        fields.forEach { (key,value) -> put(key,value) };put("action",action);put("expectedRevision",revision);put("operationId",UUID.randomUUID().toString())
    }
    @Test fun socialConclusionCitesTheRealCommentAndCanBeWithdrawn() {
        val model = model(a);val visible = mutableStateOf(true);var source: PurposeSource? = null
        compose.setContent { RedSocialTheme(darkTheme = dark) { if(visible.value) PurposeExperienceDialog(PurposeTarget("social",post),{visible.value = false},{source = it},model) } }
        await(model);click("Empezar seguimiento");await(model) { it.payload?.get("outcome") is JsonObject }
        click("Escribir conclusión")
        compose.onNode(hasSetTextAction()).performTextInput("La alternativa de Bruno nos ayudó a resolverlo.")
        click("Probar esta alternativa")
        click("Publicar conclusión");await(model) { it.payload?.get("outcome")?.jsonObject?.get("status")?.jsonPrimitive?.content == "concluded" }
        snapshot("social-conclusion")
        val result = purposeJson.decodeFromJsonElement<PostOutcomeCatalog>(checkNotNull(model.state.value.payload))
        assertEquals(listOf(comment),result.outcome?.contributionIds)
        click("Bruno");compose.waitUntil(10_000) { source != null }
        assertEquals(PurposeSource("comment",comment,post),source)
        compose.runOnIdle { visible.value = true };await(model)
        click("Retirar conclusión")
        compose.onAllNodes(hasText("Retirar conclusión") and hasClickAction()).onLast().performClick()
        await(model) { it.payload?.get("outcome")?.jsonObject?.get("status")?.jsonPrimitive?.content == "withdrawn" }
        assertEquals("",runBlocking(Dispatchers.IO) { purposeJson.decodeFromJsonElement<PostOutcomeCatalog>(api.read(lease(b),PurposeTarget("social",post))).outcome?.conclusion })
    }
    @Test fun communityOpensTheActualQuestionAndEventAndTheExistingWidgetsPersistParticipation() {
        val model = model(b);val visible = mutableStateOf(true)
        val question = mutableStateOf<CommunityQuestion?>(null);val event = mutableStateOf<CommunityEvent?>(null)
        var source: PurposeSource? = null
        suspend fun loadQuestion() { question.value = purposeJson.decodeFromJsonElement(direct("/v1/data/questions/community/$community/$questionId",b)) }
        suspend fun loadEvent() { event.value = purposeJson.decodeFromJsonElement<CommunityEventCatalog>(direct("/v1/data/messages/communities/$community/events",b)).events.single { it.id == eventId } }
        compose.setContent { RedSocialTheme(darkTheme = dark) {
            if(visible.value) PurposeExperienceDialog(PurposeTarget("community",community),{visible.value = false},{ destination ->
                source = destination
                async { when(destination.kind) { "question" -> loadQuestion(); "event" -> loadEvent(); else -> Unit } }
            },model)
            question.value?.let { current -> QuestionAnswerSheet(current,current.prompt,true,b,false,null,{question.value = null},
                onAnswer = { text,saved -> async { direct("/v1/data/questions/community/$community/$questionId/answers",b,buildJsonObject { put("content",text) });loadQuestion();saved() } },
                onVote = { id,value -> async { direct("/v1/data/questions/community/$community/$questionId/votes",b,buildJsonObject { put("answerId",id);put("value",value) });loadQuestion() } },
                onMore = { throw AssertionError("One-answer fixture must not offer another page") }) }
            event.value?.let { current -> AlertDialog(onDismissRequest = {event.value = null},title = {Text("Encuentro")},text = {
                androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) { CommunityEventCard(current,
                    onAttendanceChange = { attending,reminder -> async { direct("/v1/data/messages/communities/$community/events/$eventId/attendance",b,buildJsonObject { put("status",if(attending) "going" else "not_going");put("reminderMinutes",reminder?.let(::JsonPrimitive) ?: JsonNull) },HttpMethod.Put);loadEvent() } },
                    onCancel = {throw AssertionError("A member cannot cancel the community's event")}) }
            },confirmButton = {TextButton(onClick = {event.value = null}) {Text("Cerrar encuentro")}}) }
        } }
        await(model);click("Elegir esta acción");await(model) { it.payload?.get("journey") is JsonObject }
        snapshot("community-step")
        click("Abrir pregunta");compose.waitUntil(15_000) { question.value != null }
        assertEquals(PurposeSource("question",messageId,community),source)
        compose.onNode(hasSetTextAction()).performTextReplacement("Una primera aportación útil")
        click(if(question.value?.myAnswer == null) "Enviar respuesta" else "Actualizar respuesta")
        compose.waitUntil(15_000) { question.value?.myAnswer?.content == "Una primera aportación útil" }
        snapshot("community-answer")
        compose.runOnIdle { question.value = null; visible.value = true };await(model)
        click("Participar");click("Elegir esta acción");await(model) { it.payload?.get("journey")?.jsonObject?.get("targetKind")?.jsonPrimitive?.content == "event" }
        click("Ver encuentro");compose.waitUntil(15_000) { event.value != null }
        assertEquals(PurposeSource("event",eventId,community),source)
        if(event.value?.currentAttendance == "going") { click("Cancelar mi asistencia");compose.waitUntil(15_000) { event.value?.currentAttendance != "going" } }
        click("Confirmar asistencia");compose.waitUntil(15_000) { event.value?.currentAttendance == "going" }
        snapshot("community-event")
        val offer = runBlocking(Dispatchers.IO) { api.send(lease(a),PurposePending("$PURPOSE_BASE/communities/$community/guide",command("offer",0,buildJsonObject { put("hours",2) }))) }
        assertTrue(purposeJson.decodeFromJsonElement<CommunityPurposeCatalog>(offer).guide?.active == true)
        compose.runOnIdle { event.value = null;visible.value = true };await(model)
        click("Pedir orientación");click("Elegir esta acción");await(model) { it.payload?.get("journey")?.jsonObject?.get("targetKind")?.jsonPrimitive?.content == "guide" }
        click("Ver perfil del guía");compose.waitUntil(10_000) { source?.kind == "guide" }
        assertEquals(PurposeSource("guide",a,community),source)
    }
    @Test fun gamingProposalBothAcceptancesReconfirmationAndReplanningUseTheRealApi() {
        val first = model(a);val other = model(b);val shown = mutableStateOf(first)
        val id = UUID.randomUUID().toString();val target = mutableStateOf(PurposeTarget("proposal",b,id))
        compose.setContent { RedSocialTheme(darkTheme = dark) { key(shown.value) { PurposeExperienceDialog(target.value,{}, { throw AssertionError("No profile was requested") },shown.value) } } }
        await(first)
        compose.onNode(hasSetTextAction()).performTextInput("Practicar juntos una ronda sin clasificación")
        click("Proponer partida");await(first) { it.target?.kind == "plan" }
        snapshot("gaming-proposal")
        click("Aceptar propuesta");await(first)
        compose.runOnIdle { target.value = PurposeTarget("plan",id);shown.value = other };await(other)
        click("Aceptar propuesta");await(other) { it.payload?.get("plan")?.jsonObject?.get("status")?.jsonPrimitive?.content == "agreed" }
        click("Reconfirmar asistencia");await(other)
        compose.runOnIdle { shown.value = first;first.refresh() };await(first)
        click("Reconfirmar asistencia");await(first) { it.payload?.get("plan")?.jsonObject?.get("status")?.jsonPrimitive?.content == "ready" }
        snapshot("gaming-ready")
        click("Replanificar")
        compose.onNode(hasSetTextAction()).performTextReplacement("Una ronda para aprender juntos")
        click("60 min");click("Guardar nueva propuesta");await(first) { it.draft == null && it.payload?.get("plan")?.jsonObject?.get("planRevision")?.jsonPrimitive?.int == 2 }
        val changed = purposeJson.decodeFromJsonElement<GamingPlanReceipt>(checkNotNull(first.state.value.payload)).plan
        assertTrue(changed.accepted.isEmpty());assertTrue(changed.confirmed.isEmpty())
        snapshot("gaming-replanned")
        click("Cancelar partida");compose.onNode(hasText("Cancelar propuesta") and hasClickAction()).performClick()
        await(first) { it.payload?.get("plan")?.jsonObject?.get("status")?.jsonPrimitive?.content == "cancelled" }
    }
}
