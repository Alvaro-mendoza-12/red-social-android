package conectate.com.presentation.messages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import conectate.com.data.model.BirthdayCard
import conectate.com.presentation.components.BirthdayLetter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BirthdayGreetingSheet(initial: BirthdayCard, recipientName: String, busy: Boolean,
    onChange: (BirthdayCard) -> Unit, onDismiss: () -> Unit, onSend: (BirthdayCard) -> Unit) {
    var message by rememberSaveable { mutableStateOf(initial.message) }
    var theme by rememberSaveable { mutableStateOf(initial.theme) }
    val card = BirthdayCard(message = message, theme = theme)
    LaunchedEffect(message, theme) { onChange(card) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Felicítalo ahora", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(if (recipientName.isBlank()) "Prepara una carta de cumpleaños" else "Una carta de cumpleaños para $recipientName",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BirthdayCard.themes.zip(listOf("Confeti", "Amanecer", "Jardín")).forEach { (id, label) ->
                    FilterChip(selected = theme == id, onClick = { theme = id }, label = { Text(label) })
                }
            }
            BirthdayLetter(card.copy(message = message.ifBlank { "Tu felicitación aparecerá aquí" }), expanded = true)
            OutlinedTextField(value = message, onValueChange = { value ->
                message = if (value.codePointCount(0, value.length) > 600) value.substring(0, value.offsetByCodePoints(0, 600)) else value
            }, label = { Text("Tu felicitación") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
                supportingText = { Text("${message.codePointCount(0, message.length)}/600 · Se guarda como borrador") })
            Button(onClick = { onSend(card) }, enabled = card.isValid && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "Preparando envío…" else "Enviar carta de cumpleaños")
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text("Ahora no") }
            Spacer(Modifier.height(12.dp))
        }
    }
}
