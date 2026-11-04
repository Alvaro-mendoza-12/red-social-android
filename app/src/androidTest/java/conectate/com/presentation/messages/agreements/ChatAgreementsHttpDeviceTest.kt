package conectate.com.presentation.messages.agreements

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.data.supabase.AuthSessionMutationGuard
import conectate.com.presentation.theme.RedSocialTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Explicit opt-in to a localhost-only, temporary PostgreSQL/HTTPS QA fixture. */
class ChatAgreementsHttpDeviceTest {
    @get:Rule val compose = createComposeRule()
    @Test fun nativeProposalAndBothConsentsReachTheIsolatedServerAndEditsResetConsent() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("allowAgreementFixture") == "true")
        val certificate = checkNotNull(args.getString("fixtureCertificate"))
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("qa-only", CertificateFactory.getInstance("X.509").generateCertificate(
                ByteArrayInputStream(android.util.Base64.decode(certificate, android.util.Base64.DEFAULT))))
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trustStore) }
        val manager = trust.trustManagers.filterIsInstance<X509TrustManager>().single()
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        val client = HttpClient(OkHttp) {
            engine { config { sslSocketFactory(tls.socketFactory, manager); retryOnConnectionFailure(false) } }
            install(HttpTimeout)
        }
        val api = ChatAgreementApi(client, { checkNotNull(it.accountId) }, { "https://127.0.0.1:18107" })
        val alice = "11111111-1111-4111-8111-111111111111"
        val bob = "22222222-2222-4222-8222-222222222222"
        val chat = "33333333-3333-4333-8333-333333333333"
        val source = "44444444-4444-4444-8444-444444444444"
        val guard = AuthSessionMutationGuard()
        val aliceLease = guard.captureAccountLease { alice }
        val bobLease = AuthSessionMutationGuard().captureAccountLease { bob }
        val models = ViewModelStore()
        val journal = AgreementRecoveryJournal(encryptedAgreementRecoveryPreferences(InstrumentationRegistry.getInstrumentation().targetContext))
        val recovery = AgreementRecoveryStore({ journal }, guard::commitForLease)
        lateinit var vm: ChatAgreementsViewModel
        try {
            compose.runOnIdle {
                vm = ChatAgreementsViewModel({ aliceLease }, MutableStateFlow(0), emptyFlow(), api::supported, api::load, api::send, api::source, recovery)
                models.put("qa-agreements", vm); vm.bind(chat)
            }
            compose.waitUntil(15_000) { vm.state.value.supported }
            compose.setContent {
                val state by vm.state.collectAsState()
                RedSocialTheme {
                    if (state.open) ChatAgreementsDialog(state, vm::close, vm::refresh, vm::changeDraft, vm::saveDraft,
                        vm::dismissEditor, vm::retry, vm::keepDraftAgainstLatestRevision, vm::respond, vm::edit, vm::showSource, vm::dismissSource, vm::recoverSavedState)
                }
            }
            compose.runOnIdle { vm.propose(source) }
            compose.waitUntil(15_000) { !vm.state.value.loading }
            compose.onNodeWithText("Propuesta").performTextInput("Ensayamos el sábado a las cinco")
            compose.onNodeWithText("Enviar propuesta").performScrollTo().performClick()
            compose.waitUntil(15_000) { !vm.state.value.busy && vm.state.value.sourceId == null && vm.state.value.agreements.isNotEmpty() }
            compose.onNodeWithText("Confirmar").performScrollTo().performClick()
            compose.waitUntil(15_000) { !vm.state.value.busy && !vm.state.value.loading }
            val first = vm.state.value.agreements.first()
            assertEquals("pending", first.status)
            assertEquals("accepted", first.participants.single { it.id == alice }.response)
            val both = runBlocking(Dispatchers.IO) { api.send(bobLease, chat, first.id,
                AgreementCommand(UUID.randomUUID().toString(), expectedVersion = first.version, action = "accept")) }
            assertEquals("accepted", both.status)
            compose.runOnIdle { vm.refresh() }
            compose.waitUntil(15_000) { !vm.state.value.loading && vm.state.value.agreements.first().status == "accepted" }
            compose.onNodeWithText("Editar propuesta").performScrollTo().performClick()
            compose.onNodeWithText("Propuesta").performTextReplacement("Ensayamos el domingo a las cinco")
            compose.onNodeWithText("Guardar nueva revisión").performScrollTo().performClick()
            compose.waitUntil(15_000) { !vm.state.value.busy && vm.state.value.sourceId == null && !vm.state.value.loading }
            val changed = vm.state.value.agreements.first()
            assertEquals(2, changed.proposalRevision)
            assertTrue(changed.participants.all { it.response == "pending" })
            compose.onNodeWithText("Ver mensaje de origen").performScrollTo().performClick()
            compose.waitUntil(15_000) { vm.state.value.source != null }
            compose.onNodeWithText("Ensayamos este sábado").assertIsDisplayed()
            compose.onNodeWithText("Volver al acuerdo").performClick()
            compose.onNodeWithText("Cancelar acuerdo").performScrollTo().performClick()
            compose.onAllNodesWithText("Cancelar acuerdo").let { nodes -> nodes[nodes.fetchSemanticsNodes().lastIndex].performClick() }
            compose.waitUntil(15_000) { !vm.state.value.busy && vm.state.value.agreements.first().cancelled }
            assertEquals("cancelled", runBlocking(Dispatchers.IO) { api.load(bobLease, chat) }.first().status)
        } finally {
            compose.runOnIdle { models.clear() }
            client.close()
            runBlocking(Dispatchers.IO) { journal.eraseAccount(alice) }
        }
    }
}
