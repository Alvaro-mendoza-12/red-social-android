package conectate.com.presentation.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import conectate.com.BuildConfig
import conectate.com.data.localfallback.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class SystemAnnouncementViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: LocalFallbackApiClient,
) : ViewModel() {
    val notices = MutableStateFlow<List<SystemAnnouncement>>(emptyList())
    private val json = Json { ignoreUnknownKeys = true }
    private var refreshJob: Job? = null
    private var visibleNoticeJob: Job? = null
    @Volatile private var activeOwner = "guest"
    private val dismissed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val preferenceMutex = Mutex()
    fun refresh(owner: String?) {
        refreshJob?.cancel()
        val newOwner = owner?.trim()?.lowercase().orEmpty().ifBlank { "guest" }
        if (newOwner != activeOwner) notices.value = emptyList()
        activeOwner = newOwner
        val capturedOwner = activeOwner
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("system_announcements", Context.MODE_PRIVATE)
            fun show(items: List<SystemAnnouncement>) {
                if (capturedOwner != activeOwner) return
                val seen = prefs.getStringSet("seen:$capturedOwner", emptySet()).orEmpty()
                notices.value = orderedSystemAnnouncements(items, BuildConfig.VERSION_CODE)
                    .filter { it.id !in seen && "$capturedOwner:${it.id}" !in dismissed }
            }
            val cached = runCatching { json.decodeFromString(SystemAnnouncementEnvelope.serializer(), prefs.getString("cache", "{}").orEmpty()).items }.getOrDefault(emptyList())
            show(cached)
            api.loadSystemAnnouncements().onSuccess { fresh ->
                if (capturedOwner != activeOwner) return@onSuccess
                prefs.edit().putString("cache", json.encodeToString(SystemAnnouncementEnvelope.serializer(), SystemAnnouncementEnvelope(fresh))).apply()
                show(fresh)
            }
        }
    }
    fun watchVisibleNotices(owner: String?) {
        visibleNoticeJob?.cancel()
        refresh(owner)
        visibleNoticeJob = viewModelScope.launch {
            // Revalidate a displayed announcement so revocation can remove it.
            // No polling when no announcement is visible or the app is paused.
            notices.collectLatest { visible ->
                if (visible.isEmpty()) return@collectLatest
                while (isActive) { delay(15_000); refresh(owner) }
            }
        }
    }
    fun stopWatching(owner: String?) {
        if (owner?.trim()?.lowercase().orEmpty().ifBlank { "guest" } != activeOwner) return
        visibleNoticeJob?.cancel()
        visibleNoticeJob = null
        refreshJob?.cancel()
    }
    fun dismiss(id: String) {
        val owner = activeOwner
        dismissed += "$owner:$id"
        notices.update { items -> items.filterNot { it.id == id } }
        viewModelScope.launch(Dispatchers.IO) {
            preferenceMutex.withLock {
            val prefs = context.getSharedPreferences("system_announcements", Context.MODE_PRIVATE)
            val seen = prefs.getStringSet("seen:$owner", emptySet()).orEmpty() + id
            prefs.edit().putStringSet("seen:$owner", seen.toList().takeLast(200).toSet()).apply()
            }
        }
    }
}

internal fun noticeSchedule(notice: SystemAnnouncement, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (notice.kind != "maintenance") return null
    return runCatching {
        val format = DateTimeFormatter.ofPattern("dd MMM · HH:mm", Locale("es"))
        "${format.format(Instant.parse(notice.startsAt).atZone(zone))} — ${format.format(Instant.parse(notice.endsAt).atZone(zone))}\nHora de ${zone.id}"
    }.getOrNull()
}

@Composable
fun SystemAnnouncementHost(owner: String?) {
    val viewModel: SystemAnnouncementViewModel = hiltViewModel()
    val notices by viewModel.notices.collectAsStateWithLifecycle()
    LifecycleResumeEffect(owner) {
        viewModel.watchVisibleNotices(owner)
        onPauseOrDispose { viewModel.stopWatching(owner) }
    }
    notices.firstOrNull()?.let { notice ->
        SystemAnnouncementDialog(notice, onDismiss = { viewModel.dismiss(notice.id) }, remaining = notices.size)
    }
}

@Composable
fun SystemAnnouncementDialog(notice: SystemAnnouncement, onDismiss: () -> Unit, remaining: Int = 1) {
    val context = LocalContext.current
    AlertDialog(
        modifier = Modifier.testTag("system_announcement"),
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when (notice.kind) {
                        "maintenance" -> "Mantenimiento de Novvin"
                        "update" -> "Actualización de Novvin"
                        else -> "Novedades de Novvin"
                    }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (remaining > 1) Text("Quedan $remaining avisos", style = MaterialTheme.typography.labelMedium)
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, "Cerrar aviso")
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(notice.title, style = MaterialTheme.typography.titleLarge)
                noticeSchedule(notice)?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                Text(notice.body)
                if (notice.kind == "update") Text("¿Deseas actualizar Novvin?", style = MaterialTheme.typography.titleSmall)
            }
        },
        confirmButton = {
            Button(onClick = {
                if (notice.kind == "update") {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=conectate.com"))
                    val opened = runCatching { context.startActivity(intent) }.isSuccess
                    if (!opened) {
                        Toast.makeText(context, "No se pudo abrir Google Play. Inténtalo de nuevo.", Toast.LENGTH_LONG).show()
                        return@Button
                    }
                }
                onDismiss()
            }) { Text(if (notice.kind == "update") "Actualizar" else "Entendido") }
        },
        dismissButton = { if (notice.kind == "update") TextButton(onClick = onDismiss) { Text("Más tarde") } },
    )
}
