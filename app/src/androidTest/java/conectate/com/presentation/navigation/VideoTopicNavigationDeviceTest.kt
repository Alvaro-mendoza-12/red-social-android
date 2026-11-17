package conectate.com.presentation.navigation

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.junit.Rule
import org.junit.Test

class VideoTopicNavigationDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun encodedSearchOpensOnRootAndReturnsToTheNestedVideo() {
        val query = "Kirito y Asuna #2"
        compose.setContent {
            val root = rememberNavController()
            NavHost(root, startDestination = "home-test") {
                composable("home-test") {
                    val tabs = rememberNavController()
                    NavHost(tabs, startDestination = "videos-test") {
                        composable("videos-test") {
                            androidx.compose.material3.TextButton(onClick = {
                                root.navigate(Screen.SearchUsers.createRoute(query))
                            }) { Text("Buscar: $query") }
                        }
                    }
                }
                composable(
                    Screen.SearchUsers.routeWithQuery,
                    arguments = listOf(navArgument("query") {
                        type = NavType.StringType
                        defaultValue = ""
                    }),
                ) { entry ->
                    androidx.compose.material3.TextButton(onClick = { root.popBackStack() }) {
                        Text(entry.arguments?.getString("query").orEmpty())
                    }
                }
            }
        }
        compose.onNodeWithText("Buscar: $query").performClick()
        compose.onNodeWithText(query).assertIsDisplayed().performClick()
        compose.onNodeWithText("Buscar: $query").assertIsDisplayed()
    }
}
