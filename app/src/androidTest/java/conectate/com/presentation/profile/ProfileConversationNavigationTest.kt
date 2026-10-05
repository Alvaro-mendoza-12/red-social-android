package conectate.com.presentation.profile

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import conectate.com.presentation.navigation.Screen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProfileConversationNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nestedProfileUsesRootAndPreservesAnEncodedIdentity() {
        val identity = "imported/person?name=Álvaro#chat"
        var received: String? = null
        var errors = 0
        compose.setContent {
            MaterialTheme {
                val root = rememberNavController()
                NavHost(root, startDestination = "home") {
                    composable("home") {
                        val nested = rememberNavController()
                        NavHost(nested, startDestination = "profile") {
                            composable("profile") {
                                ProfileMessageButton(onClick = { openProfileConversation(root, identity) { errors++ } })
                            }
                        }
                    }
                    composable(Screen.Chat.route) { entry ->
                        received = entry.arguments?.getString("userId")
                        Text("Conversación abierta")
                    }
                }
            }
        }
        compose.onNodeWithTag("profile_message").performClick()
        compose.onNodeWithText("Conversación abierta").assertIsDisplayed()
        assertEquals(identity, received)
        assertEquals(0, errors)
    }

    @Test fun staleGraphAndInvalidIdentityReturnErrorWithoutClosingActivity() {
        var errors by mutableIntStateOf(0)
        compose.setContent {
            MaterialTheme {
                val controller = rememberNavController()
                NavHost(controller, startDestination = "profile") {
                    composable("profile") {
                        ProfileMessageButton(onClick = {
                            assertFalse(openProfileConversation(controller, "user-without-chat-route") { errors++ })
                            assertFalse(openProfileConversation(controller, " ") { errors++ })
                        })
                        Text("Errores: $errors")
                    }
                }
            }
        }
        compose.onNodeWithTag("profile_message").performClick()
        compose.onNodeWithText("Errores: 2").assertIsDisplayed()
        compose.onNodeWithTag("profile_message").assertIsEnabled()
    }
}
