package conectate.com.presentation.purpose

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import java.time.*

internal fun purposeScheduledInstant(date: LocalDate,time: LocalTime,zone: ZoneId): Instant {
    val local = LocalDateTime.of(date,time)
    val offsets = zone.rules.getValidOffsets(local)
    require(offsets.size == 1) { "Esa hora cambia por el ajuste horario. Elige otra hora." }
    return local.toInstant(offsets.single())
}
internal fun gamingStatusText(status: String) = when(status) {
    "agreed" -> "Ambos aceptaron"; "ready" -> "Ambos reconfirmaron"; "finished" -> "Terminó el tiempo acordado"
    "expired" -> "Faltó reconfirmar a tiempo"; "cancelled" -> "Partida cancelada"; else -> "Falta aceptar la propuesta"
}
@Composable
internal fun PurposeGamingCatalog(data: GamingPlanCatalog,state: PurposeState,model: PurposeViewModel) {
    Text("De encontrar compañero a jugar juntos",style = MaterialTheme.typography.headlineSmall)
    Text("Tus ${data.recentLimit} propuestas más recientes. Puedes proponer desde el perfil de una conexión aceptada de PlayMatch.")
    if(data.plans.isEmpty()) Text("Aún no tienes propuestas. Abre un jugador con quien ya conectaste y elige «Proponer partida».")
    data.plans.forEach { plan ->
        Column(Modifier.fillMaxWidth().padding(vertical = 10.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(plan.gameTitle,style = MaterialTheme.typography.titleLarge)
            Text(purposeDate(plan.startsAt),style = MaterialTheme.typography.titleMedium)
            Text(plan.objective)
            Text(gamingStatusText(plan.status),style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { model.open(PurposeTarget("plan",plan.id)) },enabled = !state.busy) { Text("Ver propuesta y responder") }
        }
        HorizontalDivider()
    }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PurposeGamingPlan(data: GamingPlanReceipt,state: PurposeState,model: PurposeViewModel,onSource: (PurposeSource) -> Unit) {
    val plan = data.plan
    var confirmCancellation by remember { mutableStateOf(false) }
    if(state.draft?.containsKey("objective") == true && plan.authorId == state.accountId) {
        PurposeGamingEditor(data.games,state,plan,model)
        return
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer,shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(20.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(plan.gameTitle,style = MaterialTheme.typography.titleMedium)
            Text(purposeDate(plan.startsAt),style = MaterialTheme.typography.headlineSmall)
            Text("${plan.durationMinutes} minutos · Hora de tu dispositivo")
        }
    }
    Text(plan.objective,style = MaterialTheme.typography.headlineSmall)
    Text(gamingStatusText(plan.status),style = MaterialTheme.typography.titleMedium)
    plan.participants.forEach { member ->
        val confirmed = plan.confirmed[member.id] == plan.planRevision
        val accepted = plan.accepted[member.id] == plan.planRevision
        Surface(color = if(confirmed) PurposeMint else if(accepted) MaterialTheme.colorScheme.surfaceContainer else PurposePeach,
            contentColor = if(confirmed || !accepted) PurposeInk else MaterialTheme.colorScheme.onSurface,shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(if(member.id == state.accountId) "Tú" else member.name,style = MaterialTheme.typography.titleMedium)
                Text(if(confirmed) "Reconfirmó su asistencia" else if(accepted) "Aceptó esta revisión" else "Por confirmar",style = MaterialTheme.typography.bodySmall)
                if(member.id != state.accountId) TextButton(enabled = !state.busy,onClick = { onSource(PurposeSource("player",member.id)) }) { Text("Ver perfil") }
            }
        }
    }
    val now = runCatching { Instant.parse(plan.serverTime) }.getOrDefault(Instant.EPOCH)
    val start = Instant.parse(plan.startsAt)
    val active = plan.status !in setOf("cancelled","expired","finished")
    val accepted = plan.accepted[state.accountId] == plan.planRevision
    val confirmed = plan.confirmed[state.accountId] == plan.planRevision
    val cancelling = accepted || plan.authorId == state.accountId
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if(active && !accepted) Button(enabled = state.canAct,onClick = { model.act("accept") }) { Text("Aceptar propuesta") }
        if(active && plan.accepted.size == 2 && !confirmed && !now.isBefore(start.minusSeconds(3600))) Button(enabled = state.canAct,onClick = { model.act("confirm") }) { Text("Reconfirmar asistencia") }
        if(plan.authorId == state.accountId) OutlinedButton(enabled = state.canAct,onClick = { model.draft(buildJsonObject {
            put("objective",plan.objective); put("gameId",plan.gameId); put("startsAt",plan.startsAt); put("durationMinutes",plan.durationMinutes)
        }) }) { Text("Replanificar") }
        if(active) TextButton(enabled = state.canAct,onClick = { confirmCancellation = true }) { Text(if(cancelling) "Cancelar partida" else "Rechazar propuesta") }
    }
    if(active && plan.accepted.size == 2 && confirmed.not() && now.isBefore(start.minusSeconds(3600))) Text("Podrás reconfirmar cuando falte una hora o menos. Actualiza la propuesta en ese momento.",style = MaterialTheme.typography.bodySmall)
    if(!now.isBefore(start)) {
        HorizontalDivider()
        Text("¿Cómo terminó?",style = MaterialTheme.typography.titleLarge)
        Text("Responder es voluntario. Tu elección es privada y no crea una clasificación pública.")
        plan.myOutcome?.let { Text(if(it == "played") "Registraste: jugamos." else "Registraste: no se realizó.") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = state.canAct,onClick = { model.act("feedback",buildJsonObject { put("outcome","played") }) }) { Text("Jugamos") }
            OutlinedButton(enabled = state.canAct,onClick = { model.act("feedback",buildJsonObject { put("outcome","not_played") }) }) { Text("No se realizó") }
        }
    }
    Text("Plan ${plan.planRevision} · Revisión ${plan.revision}",style = MaterialTheme.typography.bodySmall)
    if(confirmCancellation) AlertDialog(onDismissRequest = { confirmCancellation = false },title = { Text("¿Cancelar esta propuesta?") },text = { Text("La otra persona podrá ver que esta partida quedó cancelada.") },
        confirmButton = { TextButton(onClick = { confirmCancellation = false; model.act(if(cancelling) "cancel" else "decline") }) { Text("Cancelar propuesta") } },dismissButton = { TextButton(onClick = { confirmCancellation = false }) { Text("Conservar") } })
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PurposeGamingEditor(games: List<PurposeGame>,state: PurposeState,previous: GamingPlan?,model: PurposeViewModel) {
    val context = LocalContext.current
    val draft = state.draft ?: buildJsonObject {}
    val objective = state.draftText("objective",previous?.objective.orEmpty())
    val game = state.draftText("gameId",games.firstOrNull()?.id.orEmpty())
    val initial = remember { Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).plusSeconds(3600).toString() }
    val starts = state.draftText("startsAt",previous?.startsAt ?: initial)
    val duration = state.draft?.get("durationMinutes")?.jsonPrimitive?.intOrNull ?: previous?.durationMinutes ?: 45
    var dateError by remember { mutableStateOf<String?>(null) }
    fun fields() = buildJsonObject { put("objective",objective); put("gameId",game); put("startsAt",starts); put("durationMinutes",duration) }
    fun change(name: String,value: JsonPrimitive) = model.draft(buildJsonObject { fields().forEach { (key,item) -> put(key,item) }; put(name,value) })
    Text(if(previous == null) "Pongan fecha a la próxima partida" else "Una nueva propuesta para ambos",style = MaterialTheme.typography.headlineSmall)
    if(previous != null) Text("Cambiar la propuesta reinicia las aceptaciones y reconfirmaciones. Nadie acepta por la otra persona.")
    Text("Juego compatible",style = MaterialTheme.typography.titleMedium)
    if(games.isEmpty()) Text("No hay juegos compatibles disponibles. Revisen sus juegos y plataformas en PlayMatch.")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { games.forEach { item -> FilterChip(selected = game == item.id,onClick = { change("gameId",JsonPrimitive(item.id)) },enabled = state.canAct,label = { Text(item.title) }) } }
    OutlinedTextField(value = objective,onValueChange = { change("objective",JsonPrimitive(purposeLimit(it,180))) },enabled = !state.busy,readOnly = !state.canAct,modifier = Modifier.fillMaxWidth(),
        label = { Text("Qué quieren hacer en esta partida") },minLines = 2,maxLines = 4,supportingText = { Text("Por ejemplo: practicar juntos sin jugar clasificatorias.") })
    OutlinedButton(enabled = state.canAct,onClick = {
        val selected = Instant.parse(starts).atZone(ZoneId.systemDefault())
        DatePickerDialog(context,{ _,year,month,day ->
            TimePickerDialog(context,{ _,hour,minute ->
                runCatching { purposeScheduledInstant(LocalDate.of(year,month+1,day),LocalTime.of(hour,minute),ZoneId.systemDefault()) }
                    .onSuccess { dateError = null; change("startsAt",JsonPrimitive(it.toString())) }
                    .onFailure { dateError = it.message }
            },selected.hour,selected.minute,android.text.format.DateFormat.is24HourFormat(context)).show()
        },selected.year,selected.monthValue-1,selected.dayOfMonth).apply {
            datePicker.minDate = System.currentTimeMillis(); datePicker.maxDate = System.currentTimeMillis()+30L*86400000
        }.show()
    }) { Icon(Icons.Default.Event,null); Spacer(Modifier.width(8.dp)); Text(purposeDate(starts)) }
    dateError?.let { Text(it,color = MaterialTheme.colorScheme.error) }
    Text("Hora de tu dispositivo (${ZoneId.systemDefault().id}). Elige entre quince minutos y treinta días a partir de ahora.",style = MaterialTheme.typography.bodySmall)
    Text("Duración prevista",style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(15,30,45,60,90,120,180,240).forEach { minutes -> FilterChip(selected = minutes == duration,onClick = { change("durationMinutes",JsonPrimitive(minutes)) },enabled = state.canAct,label = { Text("$minutes min") }) } }
    Button(enabled = state.canAct && !state.draftStale && objective.isNotBlank() && games.any { it.id == game },onClick = { model.act(if(previous == null) "propose" else "replan",fields()) }) { Text(if(previous == null) "Proponer partida" else "Guardar nueva propuesta") }
    if(previous != null) TextButton(enabled = state.canAct,onClick = { model.draft(null) }) { Text("Conservar propuesta anterior") }
}
