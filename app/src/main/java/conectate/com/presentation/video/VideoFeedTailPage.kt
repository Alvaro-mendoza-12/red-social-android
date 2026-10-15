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

@Composable
internal fun VideoFeedTailPage(exhausted: Boolean, loading: Boolean, error: String?,
    onContinue: () -> Unit, onRefresh: () -> Unit, onSearch: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black).padding(top=80.dp,bottom=88.dp),contentAlignment=Alignment.Center) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if (loading) CircularProgressIndicator(color=Color.White)
            Text(when { loading -> "Cargando más videos"; error != null -> "No pudimos cargar más videos";
                exhausted -> "Cargando tu próximo video"; else -> "Sigamos descubriendo" },
                color=Color.White,fontWeight=FontWeight.Bold,style=MaterialTheme.typography.titleLarge,textAlign=TextAlign.Center)
            Text(when { loading -> "Un momento…"; error != null -> "Revisa tu conexión y vuelve a intentarlo.";
                exhausted -> "Seguimos buscando videos para ti.";
                else -> "Hay más videos por cargar." },color=Color.White.copy(alpha=.75f),textAlign=TextAlign.Center)
            if (!loading) {
                Button(onClick=if(exhausted && error == null) onRefresh else onContinue) {
                    Text(if(error != null) "Reintentar" else if(exhausted) "Continuar" else "Continuar")
                }
                TextButton(onClick=onSearch) { Text("Buscar videos",color=Color.White) }
            }
        }
    }
}
