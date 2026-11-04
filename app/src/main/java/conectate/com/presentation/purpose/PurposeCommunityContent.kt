package conectate.com.presentation.purpose

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import java.time.Instant

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PurposeCommunityContent(data: CommunityPurposeCatalog, state: PurposeState, model: PurposeViewModel, onSource: (PurposeSource) -> Unit) {
    var choice by remember { mutableStateOf(data.journey?.targetKind ?: "question") }
    val selected = data.journey?.let { step -> data.targets.find { it.kind == step.targetKind && it.id == step.targetId } }
    Text("Empieza con algo que sí te interese",style = MaterialTheme.typography.headlineSmall)
    Text("Elige una acción disponible. Puedes omitir este recorrido y usar la comunidad como siempre.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("question" to "Responder","event" to "Participar","guide" to "Pedir orientación").forEach { (kind,label) ->
            FilterChip(selected = choice == kind,onClick = { choice = kind },label = { Text(label) })
        }
    }
    data.journey?.takeIf { it.targetId != null }?.let { step ->
        Surface(color = if(step.outcome != null) PurposeMint else MaterialTheme.colorScheme.surfaceContainer,contentColor = if(step.outcome != null) PurposeInk else MaterialTheme.colorScheme.onSurface,shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(18.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if(step.outcome != null) "Gracias por contarnos cómo te fue" else "Tu siguiente paso",style = MaterialTheme.typography.titleLarge,color = if(step.outcome != null) PurposeInk else MaterialTheme.colorScheme.onSurface)
                if(step.outcome != null) Text("Registraste voluntariamente tu participación.",color = PurposeInk)
                else Text(selected?.title ?: "Esta actividad ya no está disponible. Puedes elegir otra.")
                if(step.opened && step.outcome == null) Text("Abriste el destino. Tú decides si quieres contar después cómo te fue.",style = MaterialTheme.typography.bodySmall)
                if(selected != null) Button(enabled = state.canAct,onClick = { model.act("opened",part = "community_start",onConfirmed = {
                    onSource(PurposeSource(selected.kind,if(selected.kind == "question") checkNotNull(selected.messageId) else selected.id,data.communityId))
                }) }) { Text(when(selected.kind) { "question" -> "Abrir pregunta"; "event" -> "Ver encuentro"; else -> "Ver perfil del guía" }) }
                if(step.outcome == null) OutlinedButton(enabled = state.canAct,onClick = { model.act("report",buildJsonObject { put("outcome",when(step.targetKind) { "question" -> "helped"; "event" -> "participated"; else -> "received_help" }) },"community_start") }) { Text(when(step.targetKind) { "question" -> "Ya aporté"; "event" -> "Ya participé"; else -> "Recibí orientación" }) }
                TextButton(enabled = state.canAct,onClick = { model.act("reset",part = "community_start") }) { Text("Elegir otra acción") }
            }
        }
    }
    val targets = data.targets.filter { it.kind == choice }
    if(targets.isEmpty()) Text(when(choice) { "question" -> "No hay preguntas abiertas disponibles ahora."; "event" -> "Todavía no hay próximos encuentros."; else -> "Nadie se ha ofrecido a acompañar en este momento." })
    targets.forEach { item ->
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.title,style = MaterialTheme.typography.titleMedium)
            item.startsAt?.let { Text(purposeDate(it),style = MaterialTheme.typography.bodySmall) }
            item.expiresAt?.let { Text("Disponible hasta ${purposeDate(it)}",style = MaterialTheme.typography.bodySmall) }
            TextButton(enabled = state.canAct,onClick = { model.act("select",buildJsonObject { put("targetKind",item.kind); put("targetId",item.id) },"community_start") }) { Text("Elegir esta acción") }
        }
        HorizontalDivider()
    }
    Text("Puedes acompañar a alguien",style = MaterialTheme.typography.titleLarge)
    Text("Ofrecerte es voluntario. Tu disponibilidad vence y puedes retirarla antes. No se envían mensajes automáticamente.")
    val active = data.guide?.let { it.active && runCatching { Instant.parse(it.expiresAt).isAfter(Instant.parse(data.serverTime)) }.getOrDefault(false) } == true
    if(active) {
        Text("Te ofreciste hasta ${purposeDate(checkNotNull(data.guide).expiresAt)}")
        OutlinedButton(enabled = state.canAct,onClick = { model.act("withdraw",part = "guide") }) { Text("Retirar mi disponibilidad") }
    } else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(2,8,24).forEach { hours -> OutlinedButton(enabled = state.canAct,onClick = { model.act("offer",buildJsonObject { put("hours",hours) },"guide") }) { Text("Ofrecerme $hours h") } }
    }
}
