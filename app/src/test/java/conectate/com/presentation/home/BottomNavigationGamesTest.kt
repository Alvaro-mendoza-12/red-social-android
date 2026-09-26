package conectate.com.presentation.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.VideoLibrary
import conectate.com.presentation.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavigationGamesTest {
    @Test
    fun `preserves Novvin icons and inserts Juegos before Perfil`() {
        val items = novvinBottomNavItems()
        assertEquals(listOf("Mensajes", "Videos", "Social", "Juegos", "Perfil"), items.map { it.title })
        assertEquals(Icons.AutoMirrored.Filled.Message, items[0].icon)
        assertEquals(Icons.Default.VideoLibrary, items[1].icon)
        assertEquals(Icons.Default.Public, items[2].icon)
        assertEquals(Icons.Default.SportsEsports, items[3].icon)
        assertEquals(Screen.Profile.route, items[4].route)
    }
}
