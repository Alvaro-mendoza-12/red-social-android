package conectate.com.presentation.live

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.supabase.AuthSessionMutationGuard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production dialog and account guard, with synthetic state and no external requests. */
@RunWith(AndroidJUnit4::class)
class LiveChannelPointsDialogDeviceTest {
    @get:Rule val compose = createComposeRule()
    private class Fixture {
        val guard = AuthSessionMutationGuard()
        var account = "a"
        fun visit(live: String = "live") = LiveToolAccountVisit(
            LiveToolVisitKey(live, account, guard.accountVisitChanges.value),
            guard.captureAccountLease { account }, guard::commitForLease,
        )
        fun aba() { guard.invalidate { account = "b" }; guard.invalidate { account = "a" } }
    }

    private fun field(label: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(label))
        return compose.onNodeWithText(label)
    }

    private fun assertValue(label: String, value: String) {
        assertEquals(value, field(label).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    @Test fun aSameFrameAccountReturnClearsTheWholeRewardDraft() {
        val f = Fixture()
        val visit = mutableStateOf(f.visit())
        compose.setContent {
            MaterialTheme { LiveChannelPointsDialog(visit.value, LiveChannelPointsUiState(), true, {}, {}, { _, _, _, _ -> }, { _, _, _, _, _ -> }, { _, _ -> }) }
        }
        field("Título").performTextInput("Privado de A")
        field("Descripción").performTextInput("Descripción de A")
        field("Costo en puntos").performTextInput("51")
        field("Cupos (vacío = ilimitado)").performTextInput("4")
        field("Espera entre canjes (min)").performTextReplacement("12")
        compose.runOnIdle { f.aba(); visit.value = f.visit() }
        assertValue("Título", "")
        assertValue("Descripción", "")
        assertValue("Costo en puntos", "")
        assertValue("Cupos (vacío = ilimitado)", "")
        assertValue("Espera entre canjes (min)", "0")
    }

    @Test fun anotherLiveDoesNotInheritTheRewardDraft() {
        val f = Fixture()
        val visit = mutableStateOf(f.visit())
        compose.setContent {
            MaterialTheme { LiveChannelPointsDialog(visit.value, LiveChannelPointsUiState(), true, {}, {}, { _, _, _, _ -> }, { _, _, _, _, _ -> }, { _, _ -> }) }
        }
        field("Título").performTextInput("Solo para el primer canal")
        compose.runOnIdle { visit.value = f.visit("other-live") }
        assertValue("Título", "")
    }

    @Test fun tokenRenewalAndRecompositionKeepTheDraft() {
        val f = Fixture()
        val revision = mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme {
                LiveChannelPointsDialog(f.visit(), LiveChannelPointsUiState(balance = revision.intValue), true, {}, {}, { _, _, _, _ -> }, { _, _, _, _, _ -> }, { _, _ -> })
            }
        }
        field("Título").performTextInput("Se conserva")
        field("Costo en puntos").performTextInput("51")
        compose.runOnIdle { f.guard.commitIfCurrent(f.guard.snapshot()) { }; revision.intValue++ }
        assertValue("Título", "Se conserva")
        assertValue("Costo en puntos", "51")
    }

    @Test fun aRetainedButtonActionCannotDispatchAfterAnAccountReturn() {
        val f = Fixture()
        val visit = mutableStateOf(f.visit())
        var submitted = 0
        compose.setContent {
            MaterialTheme { LiveChannelPointsDialog(visit.value, LiveChannelPointsUiState(), true, {}, {}, { _, _, _, _ -> submitted++ }, { _, _, _, _, _ -> }, { _, _ -> }) }
        }
        field("Guardar reglas")
        val retained = compose.onNodeWithText("Guardar reglas").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { f.aba(); retained(); assertEquals(0, submitted); visit.value = f.visit() }
        field("Guardar reglas").performClick()
        compose.runOnIdle { assertEquals(1, submitted) }
    }

    @Test fun aFailedCreateKeepsTheDraftUntilCreationIsConfirmed() {
        val f = Fixture()
        val visit = f.visit()
        val state = mutableStateOf(LiveChannelPointsUiState())
        val requests = mutableListOf<String>()
        compose.setContent {
            MaterialTheme { LiveChannelPointsDialog(visit, state.value, true, {}, {}, { _, _, _, _ -> }, { title, _, _, _, _ -> requests.add(title) }, { _, _ -> }) }
        }
        field("Título").performTextInput("Mi recompensa")
        field("Descripción").performTextInput("Mi descripción")
        field("Costo en puntos").performTextInput("51")
        field("Crear recompensa").performClick()
        compose.runOnIdle { state.value = state.value.copy(message = "Error de conexión") }
        assertValue("Título", "Mi recompensa")
        assertValue("Descripción", "Mi descripción")
        assertValue("Costo en puntos", "51")
        field("Crear recompensa").performClick()
        compose.runOnIdle {
            assertEquals(listOf("Mi recompensa", "Mi recompensa"), requests)
            state.value = state.value.copy(rewardCreationVersion = 1)
        }
        assertValue("Título", "")
        assertValue("Descripción", "")
        assertValue("Costo en puntos", "")
    }
}
