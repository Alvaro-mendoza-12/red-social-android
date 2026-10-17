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
import androidx.compose.ui.platform.LocalConfiguration
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
    val scope=rememberCoroutineScope();val context=LocalContext.current
    var editing by remember {mutableStateOf<String?>(null)}
    var title by remember {mutableStateOf("")};var description by remember {mutableStateOf("")}
    var cards by remember {mutableStateOf(listOf(PromotionCard()))};var selected by remember{mutableIntStateOf(0)}
    var busy by remember {mutableStateOf(false)};var preview by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)};var notice by remember{mutableStateOf<String?>(null)}
    fun change(index: Int, transform: (PromotionCard)->PromotionCard){cards=cards.mapIndexed{i,card->if(i==index)transform(card)else card}}
    val imagePicker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null&&selected in cards.indices)change(selected){it.copy(imageUrl=uri.toString())}}
    LaunchedEffect(state){state.refresh(true).onFailure{error="No se pudieron cargar los anuncios."}}
    ModalBottomSheet(onDismissRequest={if(!busy)onClose()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp*.85f).dp).imePadding().padding(horizontal=16.dp)) {
            Row {Text("Publicidad",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall);TextButton(enabled=!busy,onClick=onClose){Text("Cerrar")}}
            if(state.catalog?.canManage!=true) { Text("Tu cuenta no tiene permisos para gestionar publicidad.");return@Column }
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(14.dp),contentPadding=PaddingValues(bottom=24.dp)) {
                item{Text(if(editing==null)"Crear anuncio" else "Editar anuncio",style=MaterialTheme.typography.titleMedium);Text("Añade hasta 10 tarjetas. Cada una puede abrir su propio producto o sección.",style=MaterialTheme.typography.bodySmall)}
                item{OutlinedTextField(value=title,onValueChange={title=it.take(100)},enabled=!busy,label={Text("Título del anuncio")},modifier=Modifier.fillMaxWidth())}
                item{OutlinedTextField(value=description,onValueChange={description=it.take(500)},enabled=!busy,label={Text("Texto principal")},modifier=Modifier.fillMaxWidth(),maxLines=4)}
                itemsIndexed(cards){index,card->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text("Tarjeta ${index+1}",style=MaterialTheme.typography.titleSmall)
                    if(card.imageUrl.isNotBlank())AsyncImage(model=MediaImageRequestFactory.preview(context,card.imageUrl),contentDescription="Vista previa de tarjeta ${index+1}",modifier=Modifier.fillMaxWidth().height(150.dp))
                    OutlinedButton(enabled=!busy,onClick={selected=index;imagePicker.launch("image/*")}){Text(if(card.imageUrl.isBlank())"Añadir imagen" else "Cambiar imagen")}
                    OutlinedTextField(value=card.title,onValueChange={value->change(index){it.copy(title=value.take(100))}},enabled=!busy,label={Text("Título de tarjeta")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(value=card.description,onValueChange={value->change(index){it.copy(description=value.take(500))}},enabled=!busy,label={Text("Descripción breve")},modifier=Modifier.fillMaxWidth(),maxLines=3)
                    OutlinedTextField(value=card.destination,onValueChange={value->change(index){it.copy(destination=value.take(2000))}},enabled=!busy,label={Text("Enlace https:// o destino /app/…")},modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(value=card.cta,onValueChange={value->change(index){it.copy(cta=value.take(30))}},enabled=!busy,label={Text("Texto del botón")},modifier=Modifier.fillMaxWidth())
                    Row{TextButton(enabled=!busy&&index>0,onClick={cards=cards.toMutableList().apply{add(index-1,removeAt(index))}}){Text("Subir")};TextButton(enabled=!busy&&index<cards.lastIndex,onClick={cards=cards.toMutableList().apply{add(index+1,removeAt(index))}}){Text("Bajar")};TextButton(enabled=!busy&&cards.size>1,onClick={cards=cards.filterIndexed{i,_->i!=index}}){Text("Quitar")}}
                }}}
                item{OutlinedButton(enabled=!busy&&cards.size<10,onClick={cards=cards+PromotionCard()},modifier=Modifier.fillMaxWidth()){Text("Añadir tarjeta (${cards.size}/10)")}}
                item{TextButton(onClick={preview=!preview}){Text(if(preview)"Ocultar vista previa" else "Ver vista previa")};if(preview)PromotionCreative(SocialPromotion("preview",title,description,destination="/app/messages",cards=cards),onOpen={})}
                error?.let{item{Text(it,color=MaterialTheme.colorScheme.error)}}
                notice?.let{item{Text(it,color=MaterialTheme.colorScheme.primary)}}
                item{Button(enabled=!busy&&title.isNotBlank()&&cards.all{it.title.isNotBlank()&&it.imageUrl.isNotBlank()&&it.cta.isNotBlank()},modifier=Modifier.fillMaxWidth(),onClick={scope.launch{
                    busy=true;error=null;notice=null
                    try{val uploaded=cards.map{card->if(card.imageUrl.startsWith("content:"))card.copy(imageUrl=state.repository.uploadPromotionImage(Uri.parse(card.imageUrl),state.accountId.orEmpty()).getOrThrow())else card};state.repository.savePromotion(editing,title,description,uploaded).getOrThrow();state.refresh(true).getOrThrow();title="";description="";cards=listOf(PromotionCard());editing=null;notice="Anuncio guardado. Ya está disponible en Social."}catch(e: kotlinx.coroutines.CancellationException){throw e}catch(e: Exception){error=e.message ?: "No se pudo guardar el anuncio."}finally{busy=false}
                }}){Text(if(busy)"Subiendo imágenes y guardando…" else if(editing==null)"Publicar anuncio" else "Guardar cambios")}}
                item{Text("Mis anuncios",style=MaterialTheme.typography.titleMedium)}
                itemsIndexed(state.catalog?.items.orEmpty().filter{it.id!="novvin-welcome"}){_,ad->OutlinedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(14.dp)){
                    Text(ad.title,style=MaterialTheme.typography.titleSmall);Text(if(ad.active)"Activo" else "Pausado",style=MaterialTheme.typography.bodySmall)
                    Row{TextButton(enabled=!busy,onClick={editing=ad.id;title=ad.title;description=ad.description;cards=ad.cards.ifEmpty{listOf(PromotionCard(ad.title,ad.description,ad.imageUrl,ad.destination,ad.cta))}}){Text("Editar")};TextButton(enabled=!busy,onClick={scope.launch{busy=true;state.repository.activatePromotion(ad.id,!ad.active).onSuccess{state.refresh(true)}.onFailure{error="No se pudo cambiar el anuncio."};busy=false}}){Text(if(ad.active)"Pausar" else "Activar")}}
                }}}
            }
        }
    }
}
