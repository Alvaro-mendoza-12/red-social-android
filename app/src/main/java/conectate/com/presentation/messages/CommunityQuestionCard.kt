package conectate.com.presentation.messages

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.core.*
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
import androidx.compose.ui.MotionDurationScale

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
@OptIn(ExperimentalLayoutApi::class)
fun CommunityQuestionCard(kind: String, roomId: String, content: String, accountId: String?, reactions: Map<String,List<String>> = emptyMap(), onReact: () -> Unit = {}, onReaction: (String) -> Unit = {}, onLongClick: () -> Unit = {}) {
    val reference=remember(content){runCatching{JSONObject(content)}.getOrNull()}
    val id=reference?.optString("questionId").orEmpty()
    if(!id.matches(Regex("[a-fA-F0-9-]{36}"))) { Text("Pregunta no disponible");return }
    key(kind,roomId,id,accountId) {
        val repository=rememberCommunityEngagementRepository();val scope=rememberCoroutineScope();val lifecycle=LocalLifecycleOwner.current
        var question by remember {mutableStateOf<CommunityQuestion?>(null)};var error by remember {mutableStateOf<String?>(null)}
        var open by remember {mutableStateOf(false)};var respond by remember {mutableStateOf(false)};var busy by remember {mutableStateOf(false)};var rotation by remember {mutableIntStateOf((0..100000).random())}
        val (motionChoice, setMotionChoice) = rememberQuestionCloudMotion(accountId)
        val systemMotionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
        val motionRequested = cloudMotionRequested(motionChoice, (systemMotionScale?.scaleFactor ?: 1f) > 0f)
        val lifecycleState by lifecycle.lifecycle.currentStateFlow.collectAsState()
        val cloudSpace = (64 * androidx.compose.ui.platform.LocalDensity.current.fontScale + 48).dp
        suspend fun refresh() { repository.question(kind,roomId,id).onSuccess{fresh->
            val old=question
            question=if(old!=null && old.answers.size>50)fresh.copy(answers=(fresh.answers+old.answers).distinctBy{it.id},nextOffset=old.nextOffset)else fresh
            error=null
        }.onFailure{error="No se pudieron cargar las respuestas. Inténtalo de nuevo."} }
        LaunchedEffect(kind,roomId,id,accountId){lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){while(true){refresh();delay(15000)}}}
        LaunchedEffect(id, motionRequested, open){if (motionRequested && !open) lifecycle.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){while(true){delay(12000);rotation++}}}
        val answers=question?.answers.orEmpty()
        val sample=remember(answers,rotation){answers.sortedBy { it.id }.shuffled(kotlin.random.Random(rotation)).take(3)}
        val prompt=question?.prompt ?: reference?.optString("prompt").orEmpty()
        Column(Modifier.widthIn(max=360.dp).fillMaxWidth().padding(vertical=8.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth().combinedClickable(onClick={respond=false;open=true},onLongClick=onLongClick)) {
                val cloudWidth = ((maxWidth - 80.dp) / 2).coerceAtLeast(64.dp).coerceAtMost(140.dp)
                Surface(color=MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.large,modifier=Modifier.fillMaxWidth().padding(top=if(sample.isEmpty())0.dp else cloudSpace,bottom=if(sample.size<2)0.dp else cloudSpace)) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        Text("Pregunta a la comunidad",style=MaterialTheme.typography.labelMedium)
                        Text(prompt,style=MaterialTheme.typography.titleLarge)
                        Text("${question?.total ?: 0} respuestas · Ver respuestas",style=MaterialTheme.typography.labelLarge)
                    }
                }
                sample.forEachIndexed { index, answer ->
                    val alignment = when {
                        sample.size == 1 -> Alignment.TopCenter
                        sample.size == 2 && index == 1 -> Alignment.BottomEnd
                        index == 0 -> Alignment.TopStart
                        index == 1 -> Alignment.TopEnd
                        else -> Alignment.BottomCenter
                    }
                    CloudAnswerBubble(answer.content, index, moving = shouldMoveQuestionClouds(motionRequested, lifecycleState == Lifecycle.State.RESUMED, open),
                        modifier = Modifier.align(alignment).padding(horizontal=18.dp).widthIn(min=64.dp,max=cloudWidth),
                        onClick = { respond=false;open=true })
                }
            }
            FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                TextButton(onClick={respond=true;open=true},enabled=question?.canRespond==true&&question?.closed==false){Text("Responder")}
                TextButton(onClick=onReact){Text("♡ Reaccionar")}
                if (sample.isNotEmpty()) TextButton(onClick = { setMotionChoice(if (motionRequested) CloudMotionChoice.OFF else CloudMotionChoice.ON) }) { Text(if (motionRequested) "Pausar nubes" else "Animar nubes", style = MaterialTheme.typography.labelSmall) }
            }
            if(reactions.isNotEmpty())Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){reactions.entries.take(6).forEach{(emoji,users)->FilterChip(selected=accountId in users,onClick={onReaction(emoji)},label={Text("$emoji ${users.size}")})}}
            if(error!=null&&!open)TextButton(onClick={scope.launch{refresh()}}){Text("Reintentar respuestas")}
        }
        if(open)QuestionAnswerSheet(question,prompt,respond,accountId,busy,error,onClose={open=false},onAnswer={draft,done->scope.launch{busy=true;repository.answer(kind,roomId,id,draft).onSuccess{refresh();done()}.onFailure{error=it.message};busy=false}},onVote={answerId,value->scope.launch{busy=true;repository.vote(kind,roomId,id,answerId,value).onFailure{error=it.message};refresh();busy=false}},onMore={question?.nextOffset?.let{offset->scope.launch{busy=true;repository.question(kind,roomId,id,offset).onSuccess{next->question=next.copy(answers=(question?.answers.orEmpty()+next.answers).distinctBy{it.id})}.onFailure{error="No se pudieron cargar más respuestas."};busy=false}}})
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
            Text("Los participantes reaccionan, crean encuestas, votan y responden. Solo el creador crea sorteos.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(value=prompt,onValueChange={prompt=it.take(300)},label={Text("Pregunta")})
            Text("Máximo por respuesta: $limit caracteres")
            Slider(value=limit.toFloat(),onValueChange={limit=it.toInt()},valueRange=20f..40f,steps=19)
        } else Text("Solo el creador publica preguntas y sorteos. Puedes crear encuestas, reaccionar y responder desde cada pregunta.")
        error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
    }},confirmButton={if(access?.isOwner==true)TextButton(enabled=!busy&&prompt.isNotBlank(),onClick={scope.launch{busy=true;repository.create(kind,roomId,prompt,limit).onSuccess{prompt="";onClose()}.onFailure{error=it.message};busy=false}}){Text("Publicar pregunta")}},dismissButton={TextButton(onClick=onClose){Text("Cerrar")}})
}
