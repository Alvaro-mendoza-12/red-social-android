package conectate.com.presentation.surveys

import org.junit.Assert.assertEquals
import org.junit.Test

class SurveyApiErrorsTest {
    @Test fun missingBootstrapRouteDoesNotBlameTheAccount() {
        assertEquals(
            "El servicio de Encuestas no está disponible en este momento. Inténtalo más adelante.",
            surveyApiErrorMessage(404, "/v1/surveys/me", null)
        )
    }

    @Test fun missingLibraryRoutesAlsoIdentifyAnUnavailableService() {
        for (path in listOf("/v1/surveys/forms", "/v1/surveys/campaigns/?state=active")) {
            assertEquals(
                "El servicio de Encuestas no está disponible en este momento. Inténtalo más adelante.",
                surveyApiErrorMessage(404, path, " ")
            )
        }
    }

    @Test fun unavailableIndividualSurveyPreservesItsPrivacyMessage() {
        assertEquals("Encuesta no disponible para esta cuenta.",
            surveyApiErrorMessage(404, "/v1/surveys/campaigns/example", null))
    }

    @Test fun disabledModulePreservesTheServersExplanation() {
        assertEquals("Encuestas aún no está habilitado en este servidor.",
            surveyApiErrorMessage(503, "/v1/surveys/me",
                "Encuestas aún no está habilitado en este servidor."))
    }

    @Test fun unauthorizedSessionOffersLoginAgain() {
        assertEquals("Tu sesión venció. Inicia sesión de nuevo.",
            surveyApiErrorMessage(401, "/v1/surveys/me", null))
    }

    @Test fun forbiddenOperationExplainsThePermission() {
        assertEquals("No tienes permiso para realizar esta operación.",
            surveyApiErrorMessage(403, "/v1/surveys/admin/reports", null))
    }

    @Test fun busyServiceAndRateLimitOfferRetry() {
        assertEquals("El servicio no está disponible. Inténtalo de nuevo.",
            surveyApiErrorMessage(503, "/v1/surveys/me", ""))
        assertEquals("Demasiadas solicitudes. Espera un momento.",
            surveyApiErrorMessage(429, "/v1/surveys/me", null))
    }

    @Test fun unexpectedStatusIsStillVisibleForDiagnosis() {
        assertEquals("No se pudo completar la operación (500).",
            surveyApiErrorMessage(500, "/v1/surveys/me", null))
    }
}
