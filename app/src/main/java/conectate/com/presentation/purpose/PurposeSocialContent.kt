package conectate.com.presentation.purpose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PurposeSocialContent(data: PostOutcomeCatalog, state: PurposeState, model: PurposeViewModel, onSource: (PurposeSource) -> Unit) {
    val result = data.outcome
    val editing = state.draft?.containsKey("conclusion") == true
    var confirmWithdrawal by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer,shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(18.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("La publicación de origen",style = MaterialTheme.typography.titleMedium)
            Text(data.postText.ifBlank { "Publicación multimedia" },maxLines = 5,overflow = TextOverflow.Ellipsis)
        }
    }
    Text(when(result?.status) { "concluded" -> "La conclusión de su autor"; "open" -> "Esta petición sigue abierta"; "withdrawn" -> "El autor retiró su conclusión"; else -> "Sigue la petición hasta su resultado" },style = MaterialTheme.typography.headlineSmall)
    if(result?.status == "concluded") {
        Text(result.conclusion,style = MaterialTheme.typography.bodyLarge)
        Text("Es la valoración de quien publicó la petición.",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant)
        data.contributions.forEach { contribution ->
            if(contribution.available) TextButton(enabled = !state.busy,onClick = { onSource(PurposeSource("comment",contribution.id,data.postId)) }) {
                Column(Modifier.fillMaxWidth()) { Text(contribution.authorName,style = MaterialTheme.typography.titleSmall); Text(contribution.content,maxLines = 3,overflow = TextOverflow.Ellipsis) }
            } else Text("Una aportación citada ya no está disponible.",style = MaterialTheme.typography.bodySmall)
        }
    }
    result?.let { Text("Revisión ${it.revision} · ${purposeDate(it.updatedAt)}",style = MaterialTheme.typography.bodySmall,color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if(!data.canEdit) {
        if(result == null) Text("Su autor todavía no ha añadido un seguimiento. Puedes aportar desde los comentarios.")
        TextButton(onClick = { onSource(PurposeSource("post",data.postId)) },enabled = !state.busy) { Text("Ir a los comentarios") }
        return
    }
    if(editing) {
        val draft = state.draft ?: buildJsonObject {}
        val conclusion = state.draftText("conclusion")
        val selected = draft["contributionIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
        OutlinedTextField(value = conclusion,onValueChange = { text -> model.draft(buildJsonObject { draft.forEach { (key,value) -> put(key,value) }; put("conclusion",purposeLimit(text,800)) }) },
            modifier = Modifier.fillMaxWidth(),enabled = state.canAct,label = { Text("Qué te ayudó y cómo terminó") },minLines = 3,maxLines = 7,
            supportingText = { Text("${conclusion.codePointCount(0,conclusion.length)}/800 caracteres") })
        if(data.eligible.isNotEmpty()) {
            Text("Aportaciones que ayudaron",style = MaterialTheme.typography.titleMedium)
            Text("Puedes citar hasta cinco. Se comprobará de nuevo que sigan disponibles.",style = MaterialTheme.typography.bodySmall)
            data.eligible.forEach { contribution ->
                val checked = contribution.id in selected
                Row(Modifier.fillMaxWidth().clickable(enabled = state.canAct && (checked || selected.size < 5)) {
                    val ids = if(checked) selected - contribution.id else selected + contribution.id
                    model.draft(buildJsonObject { draft.forEach { (key,value) -> put(key,value) }; put("contributionIds",JsonArray(ids.map(::JsonPrimitive))) })
                }.padding(vertical = 6.dp)) {
                    Checkbox(checked,onCheckedChange = null)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) { Text(contribution.authorName,style = MaterialTheme.typography.titleSmall); Text(contribution.content,maxLines = 3,overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = state.canAct && conclusion.isNotBlank(),onClick = { model.act("conclude",buildJsonObject { put("conclusion",conclusion); put("contributionIds",JsonArray(selected.map(::JsonPrimitive))) }) }) { Text("Publicar conclusión") }
            TextButton(enabled = state.canAct,onClick = { model.draft(null) }) { Text("Descartar borrador") }
        }
    } else if(result == null) {
        Text("Si esta publicación pide ayuda, puedes mantenerla abierta y contar después qué sirvió.")
        Button(enabled = state.canAct,onClick = { model.act("start") }) { Text("Empezar seguimiento") }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = state.canAct,onClick = { model.draft(buildJsonObject { put("conclusion",result.conclusion); put("contributionIds",JsonArray(result.contributionIds.map(::JsonPrimitive))) }) }) { Text(if(result.status == "concluded") "Revisar conclusión" else "Escribir conclusión") }
            if(result.status == "concluded") TextButton(enabled = state.canAct,onClick = { confirmWithdrawal = true }) { Text("Retirar conclusión") }
            if(result.status == "withdrawn") TextButton(enabled = state.canAct,onClick = { model.act("reopen") }) { Text("Volver a abrir petición") }
        }
    }
    if(confirmWithdrawal) AlertDialog(onDismissRequest = { confirmWithdrawal = false },title = { Text("¿Retirar tu conclusión?") },text = { Text("Dejará de mostrarse. La publicación y sus comentarios se conservarán.") },
        confirmButton = { TextButton(onClick = { confirmWithdrawal = false; model.act("retract") }) { Text("Retirar conclusión") } },dismissButton = { TextButton(onClick = { confirmWithdrawal = false }) { Text("Conservar") } })
}
