package conectate.com.presentation.messages.agreements

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import conectate.com.presentation.theme.RedSocialTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatAgreementsDeviceTest {
    @get:Rule val compose = createComposeRule()
    private val alice = "alice"
    private val original = ChatAgreement("agreement", "source", alice, "Ensayamos el sábado a las cinco. Cada uno lleva su instrumento.", 1, 1,
        participants = listOf(AgreementParticipant(alice, "Ana"), AgreementParticipant("bob", "Luis")), sourceAvailable = true)
    private fun state() = ChatAgreementsState(accountId = alice, chatId = "chat", open = true, supported = true, agreements = listOf(original))

    @Test fun bothPeopleHaveTheirOwnResponseAndTheConfirmationCallsTheSelectedAgreement() {
        var action: Pair<String, String>? = null
        compose.setContent { RedSocialTheme { Content(state(), onRespond = { agreement, value -> action = agreement.id to value }) } }
        compose.onNodeWithText("Tú").assertIsDisplayed()
        compose.onNodeWithText("Luis").assertIsDisplayed()
        compose.onAllNodesWithText("Por confirmar").assertCountEquals(2)
        screenshot("agreements107-light.png")
        compose.onNodeWithText("Confirmar").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("agreement" to "accept", action) }
    }
    @Test fun anUncertainSendPreservesTheEditorAndHasAnExplicitRetryAction() {
        val state = mutableStateOf(state().copy(sourceId = "source", draft = "Ensayar mañana", retryPending = true, error = "No se pudo completar la acción. Conservamos lo que escribiste para reintentar."))
        var retried = false
        compose.setContent { RedSocialTheme { Content(state.value, onRetry = { retried = true }) } }
        compose.onNodeWithText("Propuesta").assertIsNotEnabled()
        compose.onNodeWithText("Enviar propuesta").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Ensayar mañana").assertIsDisplayed()
        compose.onNodeWithText("Reintentar envío").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(retried) }
    }
    @Test fun anEditedProposalRequiresComparingTheCurrentTextWithTheDraft() {
        var kept = false
        val changed = state().copy(agreements = listOf(original.copy(version = 2, text = "El domingo")),
            sourceId = "source", editingId = original.id, editingVersion = 1, draft = "El sábado")
        compose.setContent { RedSocialTheme { Content(changed, onKeepDraft = { kept = true }) } }
        compose.onNodeWithText("Guardar nueva revisión").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Comparar revisiones").performScrollTo().performClick()
        compose.onNodeWithText("Texto actual").assertIsDisplayed()
        compose.onNodeWithText("El domingo").assertIsDisplayed()
        compose.onNodeWithText("Conservar mi borrador").performClick()
        compose.runOnIdle { assertTrue(kept) }
    }
    @Test fun cancellationRequiresAnExplicitChoiceAndNeverConfirmsForTheOtherPerson() {
        var action: String? = null
        compose.setContent { RedSocialTheme { Content(state(), onRespond = { _, value -> action = value }) } }
        compose.onNodeWithText("Cancelar acuerdo").performScrollTo().performClick()
        compose.runOnIdle { assertNull(action) }
        compose.onNodeWithText("¿Cancelar el acuerdo?").assertIsDisplayed()
        compose.onAllNodesWithText("Cancelar acuerdo").let { nodes -> nodes[nodes.fetchSemanticsNodes().lastIndex].performClick() }
        compose.runOnIdle { assertEquals("cancel", action) }
    }
    @Test fun largeTextInDarkThemeCanReachTheSourceAndWithdrawal() {
        if (InstrumentationRegistry.getArguments().getString("expectSystemLargeText") == "true") {
            assertTrue(InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale >= 1.59f)
        }
        var sourceRequested = false
        val confirmed = original.copy(version = 3, status = "accepted", participants = original.participants.map { it.copy(response = "accepted") })
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) {
                RedSocialTheme(darkTheme = true) { Content(state().copy(agreements = listOf(confirmed)), onSource = { sourceRequested = true }) }
            }
        }
        compose.onNodeWithText("Retirar confirmación").performScrollTo().assertIsDisplayed()
        screenshot("agreements107-dark-large.png")
        compose.onNodeWithText("Ver mensaje de origen").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(sourceRequested) }
    }
    @Test fun aSourcePreviewReturnsToTheAgreementAndAnUnsentDraftWarnsBeforeDiscarding() {
        val state = mutableStateOf(state().copy(source = AgreementSource("source", "El mensaje original")))
        var dismissed = false
        compose.setContent { RedSocialTheme { Content(state.value, onDismissSource = { state.value = state.value.copy(source = null) }, onDismissEditor = { dismissed = true }) } }
        compose.onNodeWithText("Mensaje de origen").assertIsDisplayed()
        compose.onNodeWithText("Volver al acuerdo").performClick()
        compose.runOnIdle { state.value = state.value.copy(sourceId = "source", draft = "Borrador importante") }
        compose.onNodeWithContentDescription("Volver al chat").performClick()
        compose.onNodeWithText("¿Descartar este borrador?").assertIsDisplayed()
        compose.onNodeWithText("Seguir escribiendo").performClick()
        compose.runOnIdle { assertFalse(dismissed) }
    }

    @Test fun restoringAnotherScreensDraftRequiresASeparateConfirmation() {
        var recovered = false
        val conflicted = state().copy(sourceId = "source", draft = "Texto sin guardar", recoveryConflict = true,
            recoveryUnavailable = true, error = "El borrador cambió en otra pantalla.")
        compose.setContent { RedSocialTheme { Content(conflicted, onRecover = { recovered = true }) } }
        compose.onNodeWithText("Enviar propuesta").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Recuperar borrador guardado").performScrollTo().performClick()
        compose.runOnIdle { assertFalse(recovered) }
        compose.onNodeWithText("¿Volver al borrador guardado?").assertIsDisplayed()
        compose.onNodeWithText("Recuperar estado guardado").performClick()
        compose.runOnIdle { assertTrue(recovered) }
    }
    @Test fun theEditorDistinguishesAnInProgressSaveFromADurableDraft() {
        val state = mutableStateOf(state().copy(sourceId = "source", draft = "Texto", savingDraft = true))
        compose.setContent { RedSocialTheme { Content(state.value) } }
        compose.onNodeWithText("Guardando borrador…").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(savingDraft = false, draftSaved = true) }
        compose.onNodeWithText("Borrador guardado en este dispositivo").performScrollTo().assertIsDisplayed()
    }

    @Composable private fun Content(state: ChatAgreementsState, onRespond: (ChatAgreement, String) -> Unit = { _, _ -> },
        onRetry: () -> Unit = {}, onKeepDraft: () -> Unit = {}, onSource: () -> Unit = {}, onDismissSource: () -> Unit = {}, onDismissEditor: () -> Unit = {},
        onRecover: () -> Unit = {}) {
        ChatAgreementsDialog(state, onClose = {}, onRefresh = {}, onDraft = {}, onSave = {}, onDismissEditor = onDismissEditor,
            onRetry = onRetry, onKeepDraft = onKeepDraft, onRespond = onRespond, onEdit = {}, onSource = { onSource() }, onDismissSource = onDismissSource,
            onRecoverSavedState = onRecover)
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), name).outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
