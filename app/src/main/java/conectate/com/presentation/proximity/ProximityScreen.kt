package conectate.com.presentation.proximity

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import conectate.com.data.localfallback.AnonymousChatMessage
import conectate.com.data.localfallback.AnonymousChatSession
import conectate.com.data.localfallback.NearbyProfile
import conectate.com.presentation.components.CircularProfileImage
import conectate.com.presentation.messages.getBestLastKnownLocation
import conectate.com.presentation.messages.hasLocationPermission
import conectate.com.presentation.navigation.Screen

private enum class LocationAction { ENABLE_NEARBY, START_RANDOM }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProximityScreen(
    navController: NavController,
    viewModel: ProximityViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val profiles by viewModel.nearbyProfiles.collectAsState()
    val nearbyEnabled by viewModel.nearbyEnabled.collectAsState()
    val radiusKm by viewModel.radiusKm.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val randomStatus by viewModel.randomStatus.collectAsState()
    val session by viewModel.anonymousSession.collectAsState()
    val messages by viewModel.anonymousMessages.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    var radiusSlider by remember(radiusKm) { mutableFloatStateOf(radiusKm.toFloat()) }
    var gender by remember { mutableStateOf("unspecified") }
    var lookingFor by remember { mutableStateOf("any") }
    var localError by remember { mutableStateOf<String?>(null) }
    var pendingLocationAction by remember { mutableStateOf<LocationAction?>(null) }

    fun performLocationAction(action: LocationAction) {
        val location = getBestLastKnownLocation(context)
        if (location == null) {
            localError = "No se pudo obtener una ubicación reciente. Activa la ubicación del dispositivo e inténtalo de nuevo."
            return
        }
        viewModel.setRadius(radiusSlider.toDouble())
        when (action) {
            LocationAction.ENABLE_NEARBY -> viewModel.enableNearby(location.latitude, location.longitude)
            LocationAction.START_RANDOM -> viewModel.startAnonymousMatch(
                latitude = location.latitude,
                longitude = location.longitude,
                gender = gender,
                lookingFor = lookingFor
            )
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val action = pendingLocationAction
        pendingLocationAction = null
        if (granted && action != null) performLocationAction(action)
        else if (!granted) localError = "Se necesita permiso de ubicación para usar proximidad."
    }

    fun requestLocation(action: LocationAction) {
        if (hasLocationPermission(context)) {
            performLocationAction(action)
        } else {
            pendingLocationAction = action
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    val visibleError = localError ?: errorMessage
    LaunchedEffect(visibleError) {
        if (!visibleError.isNullOrBlank()) {
            kotlinx.coroutines.delay(4_000)
            localError = null
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Personas cerca") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Atrás")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Radar") },
                    icon = { Icon(Icons.Default.MyLocation, contentDescription = null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Aleatorio") },
                    icon = { Icon(Icons.Default.Shuffle, contentDescription = null) }
                )
            }
            if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (!visibleError.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        visibleError,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            when (selectedTab) {
                0 -> NearbyTab(
                    profiles = profiles,
                    enabled = nearbyEnabled,
                    radius = radiusSlider,
                    onRadiusChange = {
                        radiusSlider = it
                        viewModel.setRadius(it.toDouble())
                    },
                    onEnable = { requestLocation(LocationAction.ENABLE_NEARBY) },
                    onRefresh = viewModel::refreshNearby,
                    onDisable = viewModel::disableNearby,
                    onOpenProfile = { navController.navigate(Screen.UserProfile.createRoute(it)) }
                )
                else -> AnonymousTab(
                    status = randomStatus,
                    session = session,
                    messages = messages,
                    radius = radiusSlider,
                    gender = gender,
                    lookingFor = lookingFor,
                    onRadiusChange = {
                        radiusSlider = it
                        viewModel.setRadius(it.toDouble())
                    },
                    onGenderChange = { gender = it },
                    onLookingForChange = { lookingFor = it },
                    onStart = { requestLocation(LocationAction.START_RANDOM) },
                    onCancel = viewModel::cancelAnonymousMatch,
                    onSend = viewModel::sendAnonymousMessage,
                    onReveal = viewModel::revealNextLevel,
                    onEnd = viewModel::endAnonymousSession,
                    onOpenProfile = { userId -> navController.navigate(Screen.UserProfile.createRoute(userId)) }
                )
            }
        }
    }
}

@Composable
private fun NearbyTab(
    profiles: List<NearbyProfile>,
    enabled: Boolean,
    radius: Float,
    onRadiusChange: (Float) -> Unit,
    onEnable: () -> Unit,
    onRefresh: () -> Unit,
    onDisable: () -> Unit,
    onOpenProfile: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ProximityPrivacyNotice()
            Spacer(Modifier.height(12.dp))
            RadiusControl(radius, onRadiusChange)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onEnable,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(if (enabled) Icons.Default.LocationOn else Icons.Default.MyLocation, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (enabled) "Actualizar" else "Activar radar")
                }
                if (enabled) {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Actualizar personas")
                    }
                    IconButton(onClick = onDisable) {
                        Icon(Icons.Default.LocationOff, contentDescription = "Desactivar radar")
                    }
                }
            }
        }

        if (enabled && profiles.isEmpty()) {
            item {
                Text(
                    "No hay personas disponibles dentro de este radio.",
                    modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(profiles, key = { it.userId }) { profile ->
            NearbyProfileRow(profile = profile, onClick = { onOpenProfile(profile.userId) })
            Divider()
        }
    }
}

@Composable
private fun ProximityPrivacyNotice() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LocationOn, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(
                "Tu ubicación se redondea, caduca en 2 horas y nunca se muestra a otras personas.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun RadiusControl(radius: Float, onRadiusChange: (Float) -> Unit) {
    Text("Radio: ${radius.toInt()} km", fontWeight = FontWeight.SemiBold)
    androidx.compose.material3.Slider(
        value = radius,
        onValueChange = onRadiusChange,
        valueRange = 1f..20f,
        steps = 18,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun NearbyProfileRow(profile: NearbyProfile, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CircularProfileImage(imageUrl = profile.avatarUrl, size = 52.dp, showBorder = true)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.displayName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val location = listOf(profile.city, profile.country).filter { it.isNotBlank() }.joinToString(", ")
                if (location.isNotBlank()) Text(location, style = MaterialTheme.typography.bodySmall)
                if (profile.interests.isNotEmpty()) {
                    Text(profile.interests.take(3).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                if (profile.distanceKm < 1) "< 1 km" else "${profile.distanceKm} km",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnonymousTab(
    status: String,
    session: AnonymousChatSession?,
    messages: List<AnonymousChatMessage>,
    radius: Float,
    gender: String,
    lookingFor: String,
    onRadiusChange: (Float) -> Unit,
    onGenderChange: (String) -> Unit,
    onLookingForChange: (String) -> Unit,
    onStart: () -> Unit,
    onCancel: () -> Unit,
    onSend: (String) -> Unit,
    onReveal: () -> Unit,
    onEnd: () -> Unit,
    onOpenProfile: (String) -> Unit
) {
    if (session != null) {
        AnonymousConversation(
            session = session,
            messages = messages,
            onSend = onSend,
            onReveal = onReveal,
            onEnd = onEnd,
            onOpenProfile = onOpenProfile
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ProximityPrivacyNotice()
        RadiusControl(radius, onRadiusChange)
        PreferenceSelector(
            label = "Me identifico como",
            value = gender,
            options = listOf(
                "unspecified" to "Prefiero no decir",
                "woman" to "Mujer",
                "man" to "Hombre",
                "nonbinary" to "No binario"
            ),
            onValueChange = onGenderChange
        )
        PreferenceSelector(
            label = "Quiero conversar con",
            value = lookingFor,
            options = listOf(
                "any" to "Cualquier persona",
                "woman" to "Mujeres",
                "man" to "Hombres",
                "nonbinary" to "Personas no binarias"
            ),
            onValueChange = onLookingForChange
        )
        when (status) {
            "waiting" -> {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Buscando una persona compatible...")
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(8.dp)) {
                            Icon(Icons.Default.Cancel, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Cancelar")
                        }
                    }
                }
            }
            else -> Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.PersonSearch, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Buscar chat aleatorio")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreferenceSelector(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == value }?.second ?: options.first().second
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.second) },
                    onClick = {
                        onValueChange(option.first)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AnonymousConversation(
    session: AnonymousChatSession,
    messages: List<AnonymousChatMessage>,
    onSend: (String) -> Unit,
    onReveal: () -> Unit,
    onEnd: () -> Unit,
    onOpenProfile: (String) -> Unit
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (session.peer.avatarUrl != null) {
                CircularProfileImage(imageUrl = session.peer.avatarUrl, size = 44.dp, showBorder = true)
            } else {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.padding(10.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(session.peer.displayName, fontWeight = FontWeight.Bold)
                Text("Identidad compartida: ${session.peerRevealLevel}/3", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onEnd) {
                Icon(Icons.Default.Cancel, contentDescription = "Finalizar chat")
            }
        }
        if (session.peer.interests.isNotEmpty() || session.peer.hobbies.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                (session.peer.interests + session.peer.hobbies).take(3).forEach { item ->
                    AssistChip(onClick = {}, label = { Text(item, maxLines = 1) })
                }
            }
        }
        session.peer.userId?.let { userId ->
            OutlinedButton(
                onClick = { onOpenProfile(userId) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Ver perfil compartido")
            }
        }
        if (session.ownRevealLevel < 3) {
            OutlinedButton(
                onClick = onReveal,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    when (session.ownRevealLevel) {
                        0 -> "Compartir mis intereses"
                        1 -> "Compartir mi nombre y foto"
                        else -> "Compartir acceso a mi perfil"
                    }
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id.ifBlank { it.clientMessageId } }) { message ->
                AnonymousMessageBubble(message)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(1000) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Mensaje") },
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                enabled = draft.isNotBlank(),
                onClick = {
                    val content = draft.trim()
                    draft = ""
                    onSend(content)
                }
            ) {
                Icon(Icons.Default.Send, contentDescription = "Enviar")
            }
        }
    }
}

@Composable
private fun AnonymousMessageBubble(message: AnonymousChatMessage) {
    val mine = message.sender == "me"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(0.82f)
        ) {
            Text(message.content, modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp))
        }
    }
}
