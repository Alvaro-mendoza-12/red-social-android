package conectate.com.presentation.social

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.background
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import conectate.com.core.media.MediaImageRequestFactory
import conectate.com.data.community.*
import kotlinx.coroutines.launch

@Composable
fun SocialPromotionCard(state: PromotionState, onOpen: (String) -> Unit) {
    val ad=state.catalog?.items?.firstOrNull{it.active&&it.id !in state.hidden} ?: return
    Card(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=10.dp)) {
        Column(Modifier.padding(vertical=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Novvin",style=MaterialTheme.typography.titleSmall);Text("Publicidad · De la aplicación",style=MaterialTheme.typography.labelSmall)};TextButton(onClick={state.hidden=state.hidden+ad.id}){Text("Ocultar")}}
            key(ad.id){PromotionCreative(ad,onOpen)}
        }
    }
}
@Composable
internal fun PromotionCreative(ad: SocialPromotion, onOpen: (String)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val cards=ad.cards.ifEmpty{listOf(PromotionCard(ad.title,ad.description,ad.imageUrl,ad.destination,ad.cta))}
    val pager=rememberPagerState(pageCount={cards.size})
    Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(ad.title,Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.titleMedium)
        if(ad.description.isNotBlank())Text(ad.description,Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodyMedium)
        HorizontalPager(state=pager,contentPadding=PaddingValues(horizontal=16.dp),pageSpacing=10.dp,modifier=Modifier.fillMaxWidth()) { index ->
            val card=cards[index]
            OutlinedCard(Modifier.fillMaxWidth()) {Column {
                if(card.imageUrl.contains("/images/promotion-")&&card.imageUrl.endsWith(".svg")) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Brush.linearGradient(listOf(Color(0xff087f8c),Color(0xff272967))))) {
                        Column(Modifier.align(Alignment.Center).padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)) {
                            Text("Novvin",color=Color.White,style=MaterialTheme.typography.headlineLarge)
                            Text(card.title,color=Color.White,style=MaterialTheme.typography.titleLarge)
                        }
                    }
                }
                else if(card.imageUrl.isNotBlank()) {
                    var imageFailed by remember(card.imageUrl) { mutableStateOf(false) }
                    if (imageFailed) Box(Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment=Alignment.Center) { Text(card.title,Modifier.padding(24.dp),style=MaterialTheme.typography.titleLarge) }
                    else AsyncImage(model=MediaImageRequestFactory.preview(context,card.imageUrl),contentDescription=card.title,modifier=Modifier.fillMaxWidth().aspectRatio(1f),contentScale=ContentScale.Crop,onError={imageFailed=true})
                }
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(card.title,style=MaterialTheme.typography.titleSmall);if(card.description.isNotBlank())Text(card.description,style=MaterialTheme.typography.bodySmall);FilledTonalButton(onClick={onOpen(card.destination)},modifier=Modifier.fillMaxWidth()){Text(card.cta)}}
            }}
        }
        if(cards.size>1)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){TextButton(enabled=pager.currentPage>0,onClick={scope.launch{pager.animateScrollToPage(pager.currentPage-1)}}){Text("‹")};Text("${pager.currentPage+1} / ${cards.size} · Desliza",style=MaterialTheme.typography.labelMedium);TextButton(enabled=pager.currentPage<cards.lastIndex,onClick={scope.launch{pager.animateScrollToPage(pager.currentPage+1)}}){Text("›")}}
    }
}
