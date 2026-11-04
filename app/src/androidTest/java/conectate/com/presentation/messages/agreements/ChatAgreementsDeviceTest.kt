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
        compose.onNodeWithText("Descartar borrador").performScrollTo().performClick()
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
        screenshot("agreements108-recovered-editor.png")
    }
    @Test fun returningFromASavedDraftClosesWithoutDiscardingIt() {
        var closed = false; var discarded = false
        compose.setContent { RedSocialTheme { Content(state().copy(sourceId = "source", draft = "Conservar", draftSaved = true),
            onClose = { closed = true }, onDismissEditor = { discarded = true }) } }
        compose.onNodeWithContentDescription("Volver al chat").performClick()
        compose.runOnIdle { assertTrue(closed); assertFalse(discarded) }
        compose.onNodeWithText("¿Descartar este borrador?").assertDoesNotExist()
    }
    @Test fun offlineDraftsRemainEditableWithReconnectAndNoNetworkWriteAction() {
        var refreshed = false
        compose.setContent { RedSocialTheme { Content(state().copy(sourceId = "source", draft = "Sin conexión", draftSaved = true,
            serverAvailable = false, error = "No se pudo conectar."), onRefresh = { refreshed = true }) } }
        compose.onNodeWithText("Propuesta").assertIsEnabled()
        compose.onNodeWithText("Enviar propuesta").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Volver a conectar").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(refreshed) }
    }
    @Test fun archivingExplainsThatItAffectsBothPeopleAndDispatchesOnlyAfterConfirmation() {
        var action: String? = null
        compose.setContent { RedSocialTheme { Content(state().copy(archivingSupported = true, editorVisible = false), onRespond = { _, value -> action = value }) } }
        compose.onNodeWithText("Archivar en el chat").performScrollTo().performClick()
        compose.runOnIdle { assertNull(action) }
        compose.onNodeWithText("El acuerdo pasará a Archivo para las dos personas. Su texto y sus respuestas se conservarán.").assertIsDisplayed()
        compose.onNodeWithText("Archivar acuerdo").performClick()
        compose.runOnIdle { assertEquals("archive", action) }
    }
    @Test fun anArchivedAgreementKeepsWithdrawalAndOffersRestoreWithoutAcceptOrEdit() {
        var action: String? = null
        val archived = original.copy(archived = true, status = "accepted", participants = original.participants.map { it.copy(response = "accepted") })
        compose.setContent { RedSocialTheme(darkTheme = true) { Content(state().copy(archivingSupported = true, editorVisible = false,
            showArchived = true, agreements = listOf(archived)), onRespond = { _, value -> action = value }) } }
        compose.onNodeWithText("Archivo del chat").assertIsDisplayed()
        compose.onNodeWithText("Confirmar").assertDoesNotExist(); compose.onNodeWithText("Editar propuesta").assertDoesNotExist()
        compose.onNodeWithText("Retirar confirmación").performScrollTo().assertIsEnabled()
        screenshot("agreements109-archive-dark.png")
        compose.onNodeWithText("Restaurar en Activos").performScrollTo().performClick()
        compose.onNodeWithText("¿Restaurar en Activos?").assertIsDisplayed()
        compose.onNodeWithText("Restaurar acuerdo").performClick()
        compose.runOnIdle { assertEquals("restore", action) }
    }
    @Test fun aParkedDraftStaysSeparateFromTheCatalogAndItsFilters() {
        var resumed = false; var filter: Boolean? = null
        val saved = state().copy(archivingSupported = true, editorVisible = false, sourceId = "source", draft = "Continúo después", draftSaved = true)
        compose.setContent { RedSocialTheme { Content(saved, onSelectArchive = { filter = it }, onResumeDraft = { resumed = true }) } }
        compose.onNodeWithText("Tu borrador").assertIsDisplayed(); compose.onNodeWithText("Propuesta").assertDoesNotExist()
        compose.onNodeWithText("Continuar borrador").performClick(); compose.runOnIdle { assertTrue(resumed) }
        compose.onNodeWithText("Archivo").performClick(); compose.runOnIdle { assertEquals(true, filter) }
        screenshot("agreements109-draft-light.png")
    }
    @Test fun pageControlsUseTheExactServerPositionsAndDisableThemDuringALoad() {
        var cursor: String? = null
        val state = mutableStateOf(state().copy(editorVisible = false, nextCursor = "older", previousCursor = "newer"))
        compose.setContent { RedSocialTheme { Content(state.value, onPage = { cursor = it }) } }
        compose.onNodeWithText("Más antiguos").performScrollTo().performClick(); compose.runOnIdle { assertEquals("older", cursor) }
        compose.onNodeWithText("Más recientes").performScrollTo().performClick(); compose.runOnIdle { assertEquals("newer", cursor); state.value = state.value.copy(loading = true) }
        compose.onNodeWithText("Más antiguos").performScrollTo().assertIsNotEnabled()
    }
    @Test fun largeTextCanReachArchiveAndContinueWithoutOpeningAFormInTheList() {
        var resumed = false
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 1.6f)) {
                RedSocialTheme { Content(state().copy(editorVisible = false, archivingSupported = true, sourceId = "source", draft = "Mi borrador", draftSaved = true),
                    onResumeDraft = { resumed = true }) }
            }
        }
        compose.onNodeWithText("Continuar borrador").performScrollTo().performClick(); compose.runOnIdle { assertTrue(resumed) }
        compose.onNodeWithText("Archivar en el chat").performScrollTo().assertIsDisplayed()
        screenshot("agreements109-active-large.png")
    }

    @Composable private fun Content(state: ChatAgreementsState, onRespond: (ChatAgreement, String) -> Unit = { _, _ -> },
        onRetry: () -> Unit = {}, onKeepDraft: () -> Unit = {}, onSource: () -> Unit = {}, onDismissSource: () -> Unit = {}, onDismissEditor: () -> Unit = {},
        onRecover: () -> Unit = {}, onClose: () -> Unit = {}, onRefresh: () -> Unit = {},
        onSelectArchive: (Boolean) -> Unit = {}, onPage: (String) -> Unit = {}, onResumeDraft: () -> Unit = {}, onParkDraft: () -> Unit = {}) {
        ChatAgreementsDialog(state, onClose = onClose, onRefresh = onRefresh, onDraft = {}, onSave = {}, onDismissEditor = onDismissEditor,
            onRetry = onRetry, onKeepDraft = onKeepDraft, onRespond = onRespond, onEdit = {}, onSource = { onSource() }, onDismissSource = onDismissSource,
            onRecoverSavedState = onRecover, onSelectArchive = onSelectArchive, onPage = onPage,
            onResumeDraft = onResumeDraft, onParkDraft = onParkDraft, onMakeNewProposal = {})
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
