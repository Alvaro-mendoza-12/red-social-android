package conectate.com.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import conectate.com.data.local.prefs.SensitiveContentMode
import conectate.com.presentation.theme.LocalSensitiveContentMode

@Composable
fun SensitiveContentGate(
    isSensitive: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val mode = LocalSensitiveContentMode.current
    var revealed by remember(isSensitive, mode) { mutableStateOf(false) }
    val concealed = isSensitive && mode != SensitiveContentMode.SHOW && !revealed
    Box(modifier = modifier.fillMaxWidth()) {
        Box(modifier = if (concealed) Modifier.clearAndSetSemantics { } else Modifier) {
            content()
        }
        if (concealed) {
            SensitiveContentCover(
                mode = mode,
                onReveal = { revealed = true },
                modifier = Modifier.matchParentSize().heightIn(min = 116.dp)
            )
        }
    }
}

@Composable
fun SensitiveContentCover(
    onReveal: () -> Unit,
    modifier: Modifier = Modifier,
    mode: SensitiveContentMode = LocalSensitiveContentMode.current,
) {
    val canReveal = mode == SensitiveContentMode.BLUR
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = if (canReveal) onReveal else ({ }))
            .padding(horizontal = 24.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.VisibilityOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Contenido sensible",
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = if (canReveal) "Toca para ver" else "Oculto por tus preferencias",
            modifier = Modifier.padding(top = 2.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
    }
}
