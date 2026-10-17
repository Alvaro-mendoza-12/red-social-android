package conectate.com.presentation.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import conectate.com.data.community.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@EntryPoint @InstallIn(SingletonComponent::class)
interface CommunityEngagementEntryPoint { fun repository(): CommunityEngagementRepository }
@Composable fun rememberCommunityEngagementRepository(): CommunityEngagementRepository {
    val context=LocalContext.current.applicationContext
    return remember(context) { EntryPointAccessors.fromApplication(context,CommunityEngagementEntryPoint::class.java).repository() }
}
fun limitedQuestionAnswer(text: String, maxLength: Int): String {
    val count=text.codePointCount(0,text.length)
    return if(count<=maxLength)text else text.substring(0,text.offsetByCodePoints(0,maxLength))
}
@Composable
fun CommunityQuestionCard(kind: String, roomId: String, content: String, accountId: String?) {
    val reference=remember(content){runCatching{JSONObject(content)}.getOrNull()}
    val id=reference?.optString("questionId").orEmpty()
    if(!id.matches(Regex("[a-fA-F0-9-]{36}"))) { Text("Pregunta no disponible");return }
    key(kind,roomId,id,accountId) {
        val repository=rememberCommunityEngagementRepository();val coroutineScope=rememberCoroutineScope();val lifecycle=LocalLifecycleOwner.current
        var question by remember {mutableStateOf<CommunityQuestion?>(null)};var error by remember {mutableStateOf<String?>(null)}
        var open by remember {mutableStateOf(false)};var draft by remember {mutableStateOf("")};var busy by remember {mutableStateOf(false)};var rotation by remember {mutableIntStateOf(0)}
        suspend fun refresh() { repository.question(kind,roomId,id).onSuccess{question=it;error=null}.onFailure{error=it.message} }
        LaunchedEffect(kind,roomId,id,accountId){lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){while(true){refresh();delay(15000)}}}
        LaunchedEffect(question?.answers?.size){while(true){delay(6000);rotation+=(1..7).random()}}
        val answers=question?.answers.orEmpty()
        Card(Modifier.widthIn(max=360.dp).fillMaxWidth().padding(vertical=6.dp)) {
            Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth().clickable{open=true}) {
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Pregunta",style=MaterialTheme.typography.labelMedium)
                        Text(question?.prompt ?: reference?.optString("prompt").orEmpty(),style=MaterialTheme.typography.titleMedium)
                        Text("${question?.total ?: 0} respuestas · Ver todas",style=MaterialTheme.typography.labelMedium)
                    }
                }
                if(answers.isNotEmpty()) Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                    repeat(minOf(3,answers.size)) { index ->
                        Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.secondaryContainer,modifier=Modifier.align(if(index%2==0)Alignment.Start else Alignment.End).clickable{open=true}) {Text(answers[(rotation+index)%answers.size].content,Modifier.padding(horizontal=12.dp,vertical=6.dp),style=MaterialTheme.typography.bodySmall)}
                    }
                }
                if(error!=null) TextButton(onClick={coroutineScope.launch{refresh()}}){Text("Reintentar respuestas")}
            }
        }
        if(open) Dialog(onDismissRequest={open=false}) {
            Surface(shape=MaterialTheme.shapes.large) { Column(Modifier.fillMaxWidth().heightIn(max=600.dp).padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically){Text(question?.prompt ?: "Respuestas",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);TextButton(onClick={open=false}){Text("Cerrar")}}
                Text("${question?.total ?: 0} respuestas")
                LazyColumn(Modifier.weight(1f,false),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    items(answers,key={it.id}) { answer -> Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(answer.name + if(answer.username.isNotBlank()) " · @${answer.username}" else "",style=MaterialTheme.typography.labelLarge)
                        Text(answer.content)
                        Text(runCatching { DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(answer.createdAt)) }.getOrDefault(answer.createdAt),style=MaterialTheme.typography.labelSmall)
                        Row { listOf(1,-1).forEach { value -> TextButton(enabled=!busy&&question?.canRespond==true,onClick={coroutineScope.launch{busy=true;repository.vote(kind,roomId,id,answer.id,if(answer.myVote==value)0 else value).onFailure{error=it.message};refresh();busy=false}}){Text("${if(value==1)"👍" else "👎"} ${if(value==1)answer.likes else answer.dislikes}${if(answer.myVote==value)" ✓" else ""}")}} }
                        HorizontalDivider()
                    } }
                    question?.nextOffset?.let { offset -> item { TextButton(enabled=!busy,onClick={coroutineScope.launch{busy=true;repository.question(kind,roomId,id,offset).onSuccess{question=it.copy(answers=answers+it.answers)}.onFailure{error=it.message};busy=false}}){Text("Ver más respuestas")} } }
                    if(answers.isEmpty())item{Text("Sé la primera persona en responder.")}
                }
                error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
                if(question?.canRespond==true && question?.closed==false) {
                    val max=question?.maxLength ?: 40
                    OutlinedTextField(value=draft,onValueChange={draft=limitedQuestionAnswer(it,max)},label={Text("Tu respuesta")},supportingText={Text("${draft.codePointCount(0,draft.length)}/$max caracteres · Una respuesta por persona")},modifier=Modifier.fillMaxWidth())
                    Button(enabled=!busy&&draft.isNotBlank(),onClick={coroutineScope.launch{busy=true;repository.answer(kind,roomId,id,draft).onSuccess{draft=""}.onFailure{error=it.message};refresh();busy=false}},modifier=Modifier.align(Alignment.End)){Text("Responder")}
                }
            } }
        }
    }
}

@Composable
fun CollectionQuestionTools(kind: String, roomId: String, accountId: String?, open: Boolean, onClose: () -> Unit, onCanPublish: (Boolean) -> Unit) {
    val repository=rememberCommunityEngagementRepository();val scope=rememberCoroutineScope();val lifecycle=LocalLifecycleOwner.current
    var access by remember(roomId,accountId){mutableStateOf<QuestionAccess?>(null)};var prompt by remember {mutableStateOf("")};var limit by remember{mutableIntStateOf(40)};var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(kind,roomId,accountId){lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){while(true){repository.access(kind,roomId).onSuccess{access=it;onCanPublish(it.isOwner||!it.onlyCreator)};delay(15000)}}}
    if(open) AlertDialog(onDismissRequest=onClose,title={Text("Preguntas y permisos")},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(access?.isOwner==true) {
            Row(verticalAlignment=Alignment.CenterVertically){Text("Solo el creador publica",Modifier.weight(1f));Switch(checked=access?.onlyCreator==true,enabled=!busy,onCheckedChange={value->scope.launch{busy=true;repository.policy(kind,roomId,value).onSuccess{access=access?.copy(onlyCreator=value);onCanPublish(true)}.onFailure{error=it.message};busy=false}})}
            Text("Los participantes reaccionan, votan y responden. Solo el creador crea sorteos.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(value=prompt,onValueChange={prompt=it.take(300)},label={Text("Pregunta")})
            Text("Máximo por respuesta: $limit caracteres")
            Slider(value=limit.toFloat(),onValueChange={limit=it.toInt()},valueRange=20f..40f,steps=19)
        } else Text("Solo el creador publica preguntas y sorteos. Puedes responder y votar desde cada pregunta.")
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={if(access?.isOwner==true)TextButton(enabled=!busy&&prompt.isNotBlank(),onClick={scope.launch{busy=true;repository.create(kind,roomId,prompt,limit).onSuccess{prompt="";onClose()}.onFailure{error=it.message};busy=false}}){Text("Publicar pregunta")}},dismissButton={TextButton(onClick=onClose){Text("Cerrar")}})
}
