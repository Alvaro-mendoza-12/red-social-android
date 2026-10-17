package conectate.com.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import conectate.com.data.model.BirthdayCard

internal fun birthdayColors(theme: String): List<Color> = when (theme) {
    "sunrise" -> listOf(Color(0xFFFFE1CA), Color(0xFFFFF5E8))
    "garden" -> listOf(Color(0xFFCEEADC), Color(0xFFF1FAF3))
    else -> listOf(Color(0xFFE5DBFF), Color(0xFFFFF0F6))
}

@Composable
fun BirthdayLetter(card: BirthdayCard, expanded: Boolean = false, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    val ink = Color(0xFF31243F)
    Card(onClick = { if (!expanded) open = true }, modifier = modifier.widthIn(max = 320.dp),
        shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(birthdayColors(card.theme)))
            .padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(if (expanded) Icons.Default.Cake else Icons.Default.Email, null, tint = ink,
                modifier = Modifier.size(if (expanded) 36.dp else 42.dp))
            Text("¡Feliz cumpleaños!", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = ink)
            HorizontalDivider(color = ink.copy(alpha = .15f))
            if (expanded) Text(card.message, color = ink, style = MaterialTheme.typography.bodyLarge)
            else {
                Text("Una carta para celebrar tu día", color = ink, textAlign = TextAlign.Center)
                Text("Toca para abrir", color = ink.copy(alpha = .75f), style = MaterialTheme.typography.labelMedium)
            }
            Text("Con cariño · Novvin", color = ink.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
        }
    }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { Text("Tu carta de cumpleaños") },
        text = { Box {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                BirthdayLetter(card, expanded = true)
            }
            BirthdayConfetti(Modifier.matchParentSize())
        } }, confirmButton = { TextButton(onClick = { open = false }) { Text("Cerrar carta") } })
}

/** Brief, decorative animation. It never captures a tap or repeats indefinitely. */
@Composable
private fun BirthdayConfetti(modifier: Modifier) {
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, androidx.compose.animation.core.tween(2600, easing = androidx.compose.animation.core.LinearEasing)) }
    if (progress.value < 1f) androidx.compose.foundation.Canvas(modifier) {
        val colors = listOf(Color(0xFFE75BA5), Color(0xFFFFBE3B), Color(0xFF6486FF), Color(0xFF37BA91))
        val t = progress.value
        repeat(44) { i ->
            val x = ((i * 73 % 101) / 100f * size.width + kotlin.math.sin(t * 7 + i) * 18.dp.toPx()).coerceIn(0f, size.width)
            val y = (-.3f + t * 1.5f + (i % 7) * .04f) * size.height
            drawRect(colors[i % colors.size].copy(alpha = (1f - t) * .9f),
                topLeft = androidx.compose.ui.geometry.Offset(x, y),
                size = androidx.compose.ui.geometry.Size(5.dp.toPx(), (if (i % 2 == 0) 9 else 5).dp.toPx()))
        }
    }
}
