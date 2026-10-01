package conectate.com.presentation.profile

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import conectate.com.data.localfallback.*
import conectate.com.data.supabase.AuthService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class SystemAnnouncementAdminViewModel @Inject constructor(
    private val api: LocalFallbackApiClient, private val auth: AuthService,
) : ViewModel() {
    val notices = MutableStateFlow<List<SystemAnnouncement>>(emptyList())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    private val owner = auth.getCurrentUserId()
    fun refresh() = action { token -> load(token) }
    fun publish(notice: PublishSystemAnnouncement) = action { token ->
        api.publishSystemAnnouncement(token, notice).getOrThrow()
        message.value = "Aviso publicado. La entrega a las cuentas se procesa en segundo plano."
        load(token)
    }
    fun revoke(id: String) = action { token ->
        api.revokeSystemAnnouncement(token, id).getOrThrow()
        message.value = "El aviso se retiró. Las notificaciones que ya llegaron pueden seguir visibles."
        load(token)
    }
    private suspend fun load(token: String) {
        val fresh = api.loadSystemAnnouncements(token).getOrThrow()
        if (auth.getCurrentUserId() == owner) notices.value = fresh
    }
    private fun action(block: suspend (String) -> Unit) {
        if (busy.value || auth.getCurrentUserId() != owner) return
        busy.value = true
        viewModelScope.launch {
            try {
                val token = auth.ensureValidAccessToken().getOrThrow()
                if (auth.getCurrentUserId() == owner) block(token)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (auth.getCurrentUserId() == owner) message.value = "No se pudo completar la acción. Revisa tu conexión y tus permisos." }
            finally { busy.value = false }
        }
    }
}

@Composable
fun SystemAnnouncementAdminSection(access: AdminAccessInfo) {
    val model: SystemAnnouncementAdminViewModel = hiltViewModel()
    val notices by model.notices.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val result by model.message.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { model.refresh() }
    SystemAnnouncementAdminForm(
        canManage = "admin.system.manage" in access.capabilities,
        busy = busy, onPublish = model::publish,
    )
    result?.let { Text(it, modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
    OutlinedButton(onClick = { model.refresh() }, enabled = !busy) { Text("Actualizar avisos") }
    notices.forEach { notice ->
        Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(notice.title, style = MaterialTheme.typography.titleMedium)
                Text(notice.body)
                conectate.com.presentation.common.noticeSchedule(notice)?.let { Text(it) }
                Text(if (notice.isVisible(Int.MIN_VALUE)) "Publicado" else "Retirado o finalizado", style = MaterialTheme.typography.labelMedium)
                if (notice.revokedAt == null && "admin.system.manage" in access.capabilities) {
                    TextButton(onClick = { model.revoke(notice.id) }, enabled = !busy) { Text("Retirar aviso") }
                }
            }
        }
    }
}

@Composable
fun SystemAnnouncementAdminForm(canManage: Boolean, busy: Boolean, onPublish: (PublishSystemAnnouncement) -> Unit) {
    val context = LocalContext.current
    var kind by rememberSaveable { mutableStateOf("info") }
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    var version by rememberSaveable { mutableStateOf("") }
    var start by rememberSaveable { mutableStateOf<Long?>(null) }
    var end by rememberSaveable { mutableStateOf<Long?>(null) }
    var preview by remember { mutableStateOf<PublishSystemAnnouncement?>(null) }
    var draftId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var draftExpiry by rememberSaveable(draftId) { mutableStateOf<Long?>(null) }
    val zone = ZoneId.systemDefault()
    val format = remember { DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm", Locale("es")) }
    fun pick(current: Long?, selected: (Long) -> Unit) {
        val initial = Instant.ofEpochMilli(current ?: System.currentTimeMillis()).atZone(zone)
        DatePickerDialog(context, { _, year, month, day ->
            TimePickerDialog(context, { _, hour, minute ->
                selected(LocalDateTime.of(year, month + 1, day, hour, minute).atZone(zone).toInstant().toEpochMilli())
            }, initial.hour, initial.minute, true).show()
        }, initial.year, initial.monthValue - 1, initial.dayOfMonth).show()
    }
    val scheduleValid = kind != "maintenance" || (start != null && end != null &&
        end!! > start!! && end!! > System.currentTimeMillis() &&
        end!! + 86400000 < System.currentTimeMillis() + 90 * 86400000L)
    val versionValid = kind != "update" || (version.toIntOrNull()?.let { it in 1..2100000000 } == true)
    val valid = canManage && !busy && title.trim().length >= 3 && body.trim().length >= 5 && scheduleValid && versionValid
    Column(Modifier.fillMaxWidth().testTag("system_announcement_admin"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Avisos de Novvin", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Comunica una novedad, un mantenimiento o una actualización a todas las cuentas. También se mostrará al abrir la app.")
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("info" to "Novedad", "maintenance" to "Mantenimiento", "update" to "Actualización").forEach { (value, label) ->
                FilterChip(selected = kind == value, onClick = { kind = value; draftId = UUID.randomUUID().toString() },
                    label = { Text(label) }, enabled = canManage && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
            }
        }
        OutlinedTextField(title, { title = it.take(100); draftId = UUID.randomUUID().toString() }, label = { Text("Título") }, modifier = Modifier.fillMaxWidth(), enabled = canManage && !busy)
        OutlinedTextField(body, { body = it.take(1200); draftId = UUID.randomUUID().toString() }, label = { Text("Mensaje para los usuarios") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 6, enabled = canManage && !busy)
        if (kind == "maintenance") {
            Text("Horario de ${zone.id}", style = MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick = { pick(start) { start = it; draftId = UUID.randomUUID().toString() } }, enabled = canManage && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(start?.let { "Desde: ${format.format(Instant.ofEpochMilli(it).atZone(zone))}" } ?: "Elegir fecha y hora de inicio")
            }
            OutlinedButton(onClick = { pick(end) { end = it; draftId = UUID.randomUUID().toString() } }, enabled = canManage && !busy, modifier = Modifier.fillMaxWidth()) {
                Text(end?.let { "Hasta: ${format.format(Instant.ofEpochMilli(it).atZone(zone))}" } ?: "Elegir fecha y hora de fin")
            }
            if (start != null && end != null && !scheduleValid) Text("El fin debe ser posterior al inicio y estar dentro de los próximos 89 días.", color = MaterialTheme.colorScheme.error)
        }
        if (kind == "update") OutlinedTextField(version, { version = it.filter(Char::isDigit).take(10); draftId = UUID.randomUUID().toString() }, label = { Text("Código de versión publicado en Google Play") },
            supportingText = { Text("Avísalo cuando la versión ya esté disponible. Solo aparece a quienes tengan una versión anterior.") }, modifier = Modifier.fillMaxWidth(), enabled = canManage && !busy)
        Text("${if (kind == "maintenance") "Se mostrará hasta 24 h después del mantenimiento." else "El aviso estará disponible durante 7 días."}")
        Button(onClick = {
            val expires = Instant.ofEpochMilli(draftExpiry ?: (if (kind == "maintenance") end!! + 86400000 else System.currentTimeMillis() + 7 * 86400000L).also { draftExpiry = it })
            preview = PublishSystemAnnouncement(draftId, kind, title.trim(), body.trim(), expires.toString(),
                if (kind == "maintenance") Instant.ofEpochMilli(start!!).toString() else null,
                if (kind == "maintenance") Instant.ofEpochMilli(end!!).toString() else null, version.takeIf { kind == "update" }?.toIntOrNull())
        }, enabled = valid, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (busy) "Procesando…" else "Revisar antes de publicar") }
    }
    preview?.let { notice ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text("Publicar para todas las cuentas") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(notice.title, fontWeight = FontWeight.Bold); Text(notice.body); if (notice.kind == "maintenance") Text("Desde ${format.format(Instant.parse(notice.startsAt).atZone(zone))}\nHasta ${format.format(Instant.parse(notice.endsAt).atZone(zone))}\nHora de ${zone.id}"); Text("Las cuentas recibirán una notificación de Novvin.") } },
            confirmButton = { TextButton(onClick = { preview = null; onPublish(notice) }) { Text("Publicar aviso") } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Volver a editar") } })
    }
}
