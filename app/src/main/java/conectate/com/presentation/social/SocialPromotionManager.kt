package conectate.com.presentation.social

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import conectate.com.core.media.MediaImageRequestFactory
import conectate.com.data.community.*
import conectate.com.presentation.messages.rememberCommunityEngagementRepository
import kotlinx.coroutines.launch

@Stable
class PromotionState(val accountId: String?, val repository: CommunityEngagementRepository) {
    var catalog by mutableStateOf<SocialPromotions?>(null)
    var hidden by mutableStateOf<Set<String>>(emptySet())
    suspend fun refresh(manage: Boolean=false) = repository.promotions(manage).onSuccess{catalog=it}
}
@Composable
fun rememberPromotionState(accountId: String?): PromotionState {
    val repository=rememberCommunityEngagementRepository()
    val state=remember(accountId){PromotionState(accountId,repository)}
    LaunchedEffect(state){if(!accountId.isNullOrBlank())state.refresh()}
    return state
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SocialPromotionManager(state: PromotionState, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var cards by remember { mutableStateOf(listOf(PromotionCard())) }
    var selected by remember { mutableIntStateOf(0) }
    var step by remember { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var uploadStatus by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<SocialPromotion?>(null) }
    fun change(transform: (PromotionCard) -> PromotionCard) { cards = cards.mapIndexed { i, c -> if (i == selected) transform(c) else c } }
    fun begin(ad: SocialPromotion? = null) {
        editing = ad?.id; title = ad?.title.orEmpty(); description = ad?.description.orEmpty()
        cards = ad?.cards?.takeIf { it.isNotEmpty() } ?: listOf(PromotionCard())
        selected = 0; step = 0; creating = true; error = null; notice = null
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && selected in cards.indices) change { it.copy(imageUrl = uri.toString()) }
    }
    LaunchedEffect(state) { state.refresh(true).onFailure { error = "No se pudieron cargar los anuncios. Cierra y vuelve a intentarlo." }; loaded = true }
    val card = cards[selected.coerceIn(cards.indices)]
    val validCard = card.title.isNotBlank() && card.imageUrl.isNotBlank() && card.cta.isNotBlank() &&
        (card.destination.startsWith("https://") || card.destination in listOf("/app/messages", "/app/videos", "/app/social", "/app/games"))
    val ready = title.isNotBlank() && cards.all { it.title.isNotBlank() && it.imageUrl.isNotBlank() && it.cta.isNotBlank() &&
        (it.destination.startsWith("https://") || it.destination in listOf("/app/messages", "/app/videos", "/app/social", "/app/games")) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onClose() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().height(with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * .88f).imePadding().padding(horizontal = 20.dp)) {
            Row { Text(if (creating) "Crear anuncio" else "Publicidad", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(enabled = !busy, onClick = onClose) { Text("Cerrar") } }
            if (!loaded) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Cargando tus anuncios…"); return@Column }
            if (state.catalog?.canManage != true) { Text(error ?: "Tu cuenta no tiene permisos para gestionar publicidad."); return@Column }
            if (creating) {
                Text("Paso ${step + 1} de 3 · ${listOf("Contenido", "Tarjetas", "Revisar")[step]}", style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(progress = { (step + 1) / 3f }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
                error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                notice?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
                if (!creating) {
                    item { Text("Muestra lo que ofrece Novvin con imágenes y un botón claro.", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { begin() }, modifier = Modifier.fillMaxWidth()) { Text("Crear anuncio") } }
                    item { Text("Mis anuncios", style = MaterialTheme.typography.titleMedium) }
                    val ads = state.catalog?.items.orEmpty().filter { it.id != "novvin-welcome" }
                    if (ads.isEmpty()) item { Text("Todavía no hay anuncios. Crea el primero en tres pasos.") }
                    itemsIndexed(ads) { _, ad -> OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                        Text(ad.title, style = MaterialTheme.typography.titleMedium); Text(if (ad.active) "Activo" else "Pausado")
                        Row { TextButton(enabled = !busy, onClick = { begin(ad) }) { Text("Editar") }
                            TextButton(enabled = !busy, onClick = { scope.launch { busy = true
                                try { state.repository.activatePromotion(ad.id, !ad.active).getOrThrow(); state.refresh(true).getOrThrow() }
                                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                catch (_: Exception) { error = "No se pudo cambiar el anuncio. Inténtalo otra vez." }
                                finally { busy = false }
                            } }) { Text(if (ad.active) "Pausar" else "Activar") }
                            TextButton(enabled = !busy, onClick = { error = null; deleting = ad }) {
                                Text("Eliminar", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } } }
                } else when (step) {
                    0 -> {
                        item { Text("¿Qué quieres anunciar?", style = MaterialTheme.typography.titleLarge)
                            Text("Usa un título corto y cuenta lo esencial. Podrás revisarlo antes de publicar.") }
                        item { OutlinedTextField(title, { title = it.take(100) }, label = { Text("Título del anuncio") }, placeholder = { Text("Descubre las comunidades de Novvin") }, supportingText = { Text("${title.length}/100") }, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
                        item { OutlinedTextField(description, { description = it.take(500) }, label = { Text("Texto principal · opcional") }, minLines = 2, maxLines = 4, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
                    }
                    1 -> {
                        item { Text("Imágenes y destino", style = MaterialTheme.typography.titleLarge); Text("Una tarjeta para cada producto o idea. Desliza para verlas en el anuncio.") }
                        item { androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(cards.size) { index -> FilterChip(selected = index == selected, onClick = { selected = index }, label = { Text("Tarjeta ${index + 1}") }) }
                            item { AssistChip(enabled = !busy && cards.size < 10, onClick = { cards = cards + PromotionCard(); selected = cards.lastIndex }, label = { Text("+ Añadir") }) }
                        } }
                        item { OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Tarjeta ${selected + 1} de ${cards.size}", style = MaterialTheme.typography.titleMedium)
                            if (card.imageUrl.isNotBlank()) AsyncImage(model = MediaImageRequestFactory.preview(context, card.imageUrl), contentDescription = "Vista previa", modifier = Modifier.fillMaxWidth().height(170.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                            OutlinedButton(enabled = !busy, onClick = { imagePicker.launch("image/*") }, modifier = Modifier.fillMaxWidth()) { Text(if (card.imageUrl.isBlank()) "Añadir imagen" else "Cambiar imagen") }
                            Text("Imagen cuadrada recomendada. JPG, PNG o WebP.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(card.title, { value -> change { it.copy(title = value.take(100)) } }, label = { Text("Título de tarjeta") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(card.description, { value -> change { it.copy(description = value.take(500)) } }, label = { Text("Descripción · opcional") }, maxLines = 3, modifier = Modifier.fillMaxWidth())
                            Text("Al tocar el botón", style = MaterialTheme.typography.labelLarge)
                            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(4) { i -> val destinations = listOf("/app/messages", "/app/videos", "/app/social", "https://")
                                    FilterChip(selected = if (i == 3) card.destination.startsWith("https://") else card.destination == destinations[i], onClick = { change { it.copy(destination = destinations[i], cta = listOf("Abrir mensajes", "Ver videos", "Descubrir", "Más información")[i]) } }, label = { Text(listOf("Mensajes", "Videos", "Social", "Sitio web")[i]) }) }
                            }
                            if (card.destination.startsWith("https://")) OutlinedTextField(card.destination, { value -> change { it.copy(destination = value.take(2000)) } }, label = { Text("Enlace del sitio web") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(card.cta, { value -> change { it.copy(cta = value.take(30)) } }, label = { Text("Texto del botón") }, modifier = Modifier.fillMaxWidth())
                            Row { TextButton(enabled = selected > 0 && !busy, onClick = { cards = cards.toMutableList().apply { add(selected - 1, removeAt(selected)) }; selected-- }) { Text("Subir") }
                                TextButton(enabled = selected < cards.lastIndex && !busy, onClick = { cards = cards.toMutableList().apply { add(selected + 1, removeAt(selected)) }; selected++ }) { Text("Bajar") }
                                TextButton(enabled = cards.size > 1 && !busy, onClick = { cards = cards.filterIndexed { i, _ -> i != selected }; selected = selected.coerceAtMost(cards.lastIndex) }) { Text("Quitar") } }
                        } } }
                    }
                    2 -> {
                        item { Text("Así se verá tu anuncio", style = MaterialTheme.typography.titleLarge); Text("Publicidad identificada entre las publicaciones de Social. Revisa las imágenes y el botón.") }
                        item { PromotionCreative(SocialPromotion("preview", title, description, destination = "/app/messages", cards = cards), onOpen = {}) }
                    }
                }
            }
            if (creating) Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = !busy, modifier = Modifier.weight(1f), onClick = { if (step > 0) step-- else creating = false }) { Text(if (step == 0) "Mis anuncios" else "Atrás") }
                Button(enabled = !busy && when (step) { 0 -> title.isNotBlank(); 1 -> ready && validCard; else -> ready }, modifier = Modifier.weight(1f), onClick = {
                    if (step < 2) { step++; error = null } else scope.launch {
                        busy = true; error = null
                        try {
                            val uploaded = cards.mapIndexed { i, c -> uploadStatus = "Imagen ${i + 1} de ${cards.size}"
                                if (c.imageUrl.startsWith("content:")) c.copy(imageUrl = state.repository.uploadPromotionImage(Uri.parse(c.imageUrl), state.accountId.orEmpty()).getOrThrow()) else c }
                            uploadStatus = "Publicando anuncio…"; state.repository.savePromotion(editing, title, description, uploaded).getOrThrow()
                            state.refresh(true).getOrThrow(); creating = false; notice = "Anuncio publicado. Puedes pausarlo o editarlo aquí."
                        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: Exception) { error = "No se pudo publicar. Conservamos tus cambios; revisa la conexión y vuelve a intentarlo." }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "Publicando…" else if (step < 2) "Continuar" else "Publicar anuncio") }
            }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(uploadStatus, style = MaterialTheme.typography.bodySmall) }
        }
    }
    deleting?.let { ad ->
        AlertDialog(
            onDismissRequest = { if (!busy) deleting = null },
            title = { Text("¿Eliminar anuncio?") },
            text = { Text("«${ad.title}» dejará de aparecer en Social y en tus anuncios. Esta acción no se puede deshacer.") },
            dismissButton = { TextButton(enabled = !busy, onClick = { deleting = null }) { Text("Cancelar") } },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true; error = null; uploadStatus = "Eliminando anuncio…"
                        try {
                            state.repository.deletePromotion(ad.id).getOrThrow()
                            state.catalog = state.catalog?.let { it.copy(items = it.items.filterNot { item -> item.id == ad.id }) }
                            deleting = null; notice = "Anuncio eliminado."
                            state.refresh(true)
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { deleting = null; error = "No se pudo eliminar el anuncio. Inténtalo otra vez." }
                        finally { busy = false }
                    }
                }) { Text(if (busy) "Eliminando…" else "Eliminar", color = MaterialTheme.colorScheme.error) }
            }
        )
    }
}
