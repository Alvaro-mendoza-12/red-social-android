package conectate.com.presentation.social

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import conectate.com.core.media.MediaImageRequestFactory
import conectate.com.data.community.SocialPromotions
import conectate.com.presentation.messages.rememberCommunityEngagementRepository
import kotlinx.coroutines.launch

@Composable
fun SocialPromotionCard(accountId: String?, onOpen: (String) -> Unit) {
    key(accountId) {
        val repository=rememberCommunityEngagementRepository();val scope=rememberCoroutineScope();val context=LocalContext.current
        var catalog by remember{mutableStateOf<SocialPromotions?>(null)};var hidden by remember{mutableStateOf<Set<String>>(emptySet())};var manage by remember{mutableStateOf(false)}
        var title by remember{mutableStateOf("")};var description by remember{mutableStateOf("")};var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
        LaunchedEffect(accountId){if(!accountId.isNullOrBlank())repository.promotions().onSuccess{catalog=it}}
        val ad=catalog?.items?.firstOrNull{it.active&&it.id !in hidden}
        if(ad!=null) Card(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=10.dp)) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row {Column(Modifier.weight(1f)){Text("Novvin",style=MaterialTheme.typography.titleSmall);Text("Publicidad · De la aplicación",style=MaterialTheme.typography.labelSmall)};TextButton(onClick={hidden=hidden+ad.id}){Text("Ocultar")}}
                if(ad.imageUrl.isNotBlank())AsyncImage(model=MediaImageRequestFactory.preview(context,ad.imageUrl),contentDescription=ad.title,modifier=Modifier.fillMaxWidth().heightIn(max=320.dp),contentScale=ContentScale.Crop)
                Text(ad.title,style=MaterialTheme.typography.titleMedium);Text(ad.description)
                Button(onClick={onOpen(ad.destination)}){Text(ad.cta)}
                if(catalog?.canManage==true)TextButton(onClick={manage=true}){Text("Crear anuncio propio")}
            }
        }
        if(manage)AlertDialog(onDismissRequest={manage=false},title={Text("Anuncio de Novvin")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)){
            OutlinedTextField(value=title,onValueChange={title=it.take(100)},label={Text("Título")});OutlinedTextField(value=description,onValueChange={description=it.take(500)},label={Text("Descripción")});Text("El anuncio abrirá Mensajes. Puedes añadir imágenes y otros destinos desde la web.",style=MaterialTheme.typography.bodySmall);error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }},confirmButton={TextButton(enabled=!busy&&title.isNotBlank(),onClick={scope.launch{busy=true;repository.createPromotion(title,description).onSuccess{repository.promotions().onSuccess{catalog=it};manage=false;title="";description=""}.onFailure{error=it.message};busy=false}}){Text("Publicar")}},dismissButton={TextButton(onClick={manage=false}){Text("Cancelar")}})
    }
}
