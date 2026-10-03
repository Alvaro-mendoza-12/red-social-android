package conectate.com.presentation.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import conectate.com.data.model.User
import conectate.com.data.repository.ProfileViewer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileVisitorsContentTest {
    @get:Rule val compose = createComposeRule()
    @Test fun loadingDoesNotSayThereAreNoVisits() {
        compose.setContent { MaterialTheme { ProfileVisitorsContent(ProfileVisitorsState(), {}, {}) } }
        compose.onNodeWithText("Cargando visitas…").assertIsDisplayed()
        compose.onNodeWithText("Todavía no hay visitas visibles.", substring = true).assertDoesNotExist()
    }
    @Test fun failureOffersARetry() {
        var retries = 0
        compose.setContent { MaterialTheme { ProfileVisitorsContent(
            ProfileVisitorsState(loading = false, error = "No se pudieron cargar las visitas."), { retries++ }, {}) } }
        compose.onNodeWithText("Reintentar").performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("Todavía no hay visitas visibles.", substring = true).assertDoesNotExist()
    }
    @Test fun longListsScrollAndOpenTheCorrectVisitor() {
        val visitors = (1..50).map { ProfileViewer(User(id = "visitor-$it", username = "visitante_$it",
            display_name = "Visitante $it"), "2026-10-03T12:00:00Z") }
        var opened: String? = null
        compose.setContent { MaterialTheme { ProfileVisitorsContent(
            ProfileVisitorsState(visitors, loading = false), {}, { opened = it }) } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Visitante 50"))
        compose.onNodeWithText("Visitante 50").assertIsDisplayed().performClick()
        assertEquals("visitor-50", opened)
    }
    @Test fun privacyOptOutExplainsTheEmptyList() {
        compose.setContent { MaterialTheme { ProfileVisitorsContent(
            ProfileVisitorsState(loading = false, enabled = false), {}, {}) } }
        compose.onNodeWithText("El historial de visitas está desactivado en tu privacidad.").assertIsDisplayed()
    }
}
