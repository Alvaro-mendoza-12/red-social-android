package conectate.com.presentation.common

import android.content.Context
import androidx.annotation.MainThread
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import conectate.com.BuildConfig
import conectate.com.core.concurrency.DispatcherProvider
import conectate.com.data.localfallback.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

internal class SystemAnnouncementVisit(val owner: String, val playUpdateVersion: Int?)

internal data class SystemAnnouncementDisplay(
    val visit: SystemAnnouncementVisit? = null,
    val notices: List<SystemAnnouncement> = emptyList(),
)

@HiltViewModel
class SystemAnnouncementViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: LocalFallbackApiClient,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val display = MutableStateFlow(SystemAnnouncementDisplay())
    internal val state = display.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private var watchJob: Job? = null
    private val dismissed = mutableSetOf<Pair<String, String>>()
    private val preferenceMutex = Mutex()

    @MainThread
    internal fun watchVisibleNotices(owner: String?, playUpdateVersion: Int?): SystemAnnouncementVisit {
        watchJob?.cancel()
        val visit = SystemAnnouncementVisit(owner?.trim()?.lowercase().orEmpty().ifBlank { "guest" }, playUpdateVersion)
        display.value = SystemAnnouncementDisplay(visit)
        watchJob = viewModelScope.launch {
            val prefs = withContext(dispatchers.io) {
                context.getSharedPreferences("system_announcements", Context.MODE_PRIVATE)
            }
            val (cached, seen) = withContext(dispatchers.io) {
                val items = runCatching {
                    val stored = prefs.getString("cache", "{}").orEmpty()
                    json.decodeFromString(SystemAnnouncementEnvelope.serializer(), stored).items
                }.getOrDefault(emptyList())
                items to prefs.getStringSet("seen:${visit.owner}", emptySet()).orEmpty()
            }
            fun show(items: List<SystemAnnouncement>) {
                val eligible = orderedSystemAnnouncements(items, BuildConfig.VERSION_CODE).filter { notice ->
                    val matchesMode = if (visit.playUpdateVersion == null) notice.kind != "update"
                    else notice.kind == "update" && notice.versionCode == visit.playUpdateVersion
                    matchesMode && notice.id !in seen && (visit.owner to notice.id) !in dismissed
                }
                display.value = SystemAnnouncementDisplay(visit, eligible)
            }
            var items = cached
            show(items)
            do {
                val response = api.loadSystemAnnouncements()
                currentCoroutineContext().ensureActive()
                response.getOrNull()?.let { fresh ->
                    val encoded = withContext(dispatchers.io) {
                        json.encodeToString(SystemAnnouncementEnvelope.serializer(), SystemAnnouncementEnvelope(fresh))
                    }
                    // SharedPreferences applies this to memory immediately and writes to disk asynchronously.
                    prefs.edit().putString("cache", encoded).apply()
                    items = fresh
                }
                show(items)
                if (display.value.notices.isEmpty()) break
                delay(15_000)
            } while (display.value.notices.isNotEmpty())
        }
        return visit
    }

    @MainThread
    internal fun stopWatching(visit: SystemAnnouncementVisit) {
        if (display.value.visit !== visit) return
        watchJob?.cancel()
        watchJob = null
        display.value = SystemAnnouncementDisplay()
    }

    @MainThread
    internal fun dismiss(visit: SystemAnnouncementVisit, id: String) {
        val current = display.value
        if (current.visit !== visit || current.notices.none { it.id == id }) return
        dismissed += visit.owner to id
        display.value = current.copy(notices = current.notices.filterNot { it.id == id })
        viewModelScope.launch(dispatchers.io) {
            preferenceMutex.withLock {
            val prefs = context.getSharedPreferences("system_announcements", Context.MODE_PRIVATE)
                val key = "seen:${visit.owner}"
                val seen = prefs.getStringSet(key, emptySet()).orEmpty() + id
                prefs.edit().putStringSet(key, seen.toList().takeLast(200).toSet()).apply()
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
fun SystemAnnouncementHost(
    owner: String?,
    playUpdateVersion: Int? = null,
    onDismissPlayUpdate: () -> Unit = {},
) {
    val viewModel: SystemAnnouncementViewModel = hiltViewModel()
    val display by viewModel.state.collectAsStateWithLifecycle()
    var visit by remember(owner, playUpdateVersion) { mutableStateOf<SystemAnnouncementVisit?>(null) }
    LifecycleResumeEffect(owner, playUpdateVersion) {
        val current = viewModel.watchVisibleNotices(owner, playUpdateVersion)
        visit = current
        onPauseOrDispose {
            visit = null
            viewModel.stopWatching(current)
        }
    }
    val currentVisit = visit
    val notices = display.notices.takeIf { display.visit === currentVisit }.orEmpty()
    if (playUpdateVersion != null) {
        PlayStoreUpdateDialog(notices.firstOrNull(), onDismissPlayUpdate)
        return
    }
    if (currentVisit == null) return
    val notice = notices.firstOrNull() ?: return
    SystemAnnouncementDialog(notice, onDismiss = { viewModel.dismiss(currentVisit, notice.id) }, remaining = notices.size)
}

@Composable
fun SystemAnnouncementDialog(notice: SystemAnnouncement, onDismiss: () -> Unit, remaining: Int = 1) {
    AlertDialog(
        modifier = Modifier.testTag("system_announcement"),
        onDismissRequest = onDismiss,
        title = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (notice.kind == "maintenance") "Mantenimiento de Novvin" else "Novedades de Novvin",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Entendido") } },
    )
}
