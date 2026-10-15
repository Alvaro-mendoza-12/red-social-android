package conectate.com.presentation.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** A successful empty ranking has different recovery actions from a network failure. */
@Composable
internal fun VideoFeedNoMatchesPage(onReviewPreferences: () -> Unit, onRefresh: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).padding(top = 80.dp, bottom = 88.dp),
        contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("No hay videos que coincidan", color = Color.White,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center)
            Text("Revisa tus preferencias en Mi algoritmo o vuelve a buscar novedades.",
                color = Color.White.copy(alpha = .75f), textAlign = TextAlign.Center)
            Button(onClick = onReviewPreferences) { Text("Revisar Mi algoritmo") }
            TextButton(onClick = onRefresh) { Text("Volver a buscar", color = Color.White) }
        }
    }
}
