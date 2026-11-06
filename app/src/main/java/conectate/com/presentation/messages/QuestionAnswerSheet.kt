package conectate.com.presentation.messages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import conectate.com.data.community.CommunityQuestion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuestionAnswerSheet(question: CommunityQuestion?, prompt: String, initialAnswer: Boolean, accountId: String?, busy: Boolean, error: String?,
    onClose: () -> Unit, onAnswer: (String, () -> Unit) -> Unit, onVote: (String, Int) -> Unit, onMore: () -> Unit) {
    var tab by remember { mutableIntStateOf(if(initialAnswer) 0 else 1) }
    val mine=question?.myAnswer ?: question?.answers?.find{it.userId==accountId}
    var draft by remember { mutableStateOf(mine?.content.orEmpty()) }
    var edited by remember {mutableStateOf(false)}
    LaunchedEffect(mine?.content){if(!edited)draft=mine?.content.orEmpty()}
    val limit=question?.maxLength ?: 40
    ModalBottomSheet(onDismissRequest=onClose, sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        LazyColumn(Modifier.fillMaxWidth().height(with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * .75f).imePadding(),
            contentPadding=PaddingValues(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item(key="question-heading") { Row { Text(prompt,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=3,overflow=TextOverflow.Ellipsis);TextButton(onClick=onClose){Text("Cerrar")}} }
            item(key="question-tabs") { PrimaryTabRow(selectedTabIndex=tab) {
                Tab(selected=tab==0,onClick={tab=0},text={Text("Tu respuesta")})
                Tab(selected=tab==1,onClick={tab=1},text={Text("Respuestas (${question?.total ?: 0})")})
            } }
            if(tab==0) {
                item(key="question-editor") { Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Text("Comparte una respuesta breve. Puedes actualizarla cuando quieras.",style=MaterialTheme.typography.bodyMedium)
                    if(question?.canRespond==true && !question.closed) {
                        OutlinedTextField(value=draft,onValueChange={edited=true;draft=limitedQuestionAnswer(it,limit)},label={Text("Tu respuesta")},minLines=2,maxLines=4,modifier=Modifier.fillMaxWidth(),supportingText={Text("${draft.codePointCount(0,draft.length)}/$limit caracteres · Una por persona")})
                        Button(enabled=!busy&&draft.isNotBlank(),modifier=Modifier.fillMaxWidth(),onClick={onAnswer(draft){tab=1}}){Text(if(busy)"Guardando…" else if(mine!=null)"Actualizar respuesta" else "Enviar respuesta")}
                    } else Text(if(question?.closed==true)"Esta pregunta está cerrada." else "Únete para responder.")
                } }
            } else {
                    if(question?.answers.isNullOrEmpty()) item { Text("Todavía no hay respuestas. Puedes ser la primera persona.") }
                    items(question?.answers.orEmpty(),key={it.id}) { answer ->
                        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(answer.name,style=MaterialTheme.typography.titleSmall)
                            if(answer.username.isNotBlank())Text("@${answer.username}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(answer.content,style=MaterialTheme.typography.bodyLarge)
                            Text(runCatching{DateTimeFormatter.ofPattern("dd/MM/yyyy · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(answer.createdAt))}.getOrDefault(""),style=MaterialTheme.typography.labelSmall)
                            Row { listOf(1,-1).forEach { value -> TextButton(enabled=!busy&&question?.canRespond==true,onClick={onVote(answer.id,if(answer.myVote==value)0 else value)}) { Text("${if(value==1)"👍" else "👎"} ${if(value==1)answer.likes else answer.dislikes}${if(answer.myVote==value)" ✓" else ""}") } } }
                        } }
                    }
                    if(question?.nextOffset!=null)item{TextButton(enabled=!busy,onClick=onMore,modifier=Modifier.fillMaxWidth()){Text(if(busy)"Cargando…" else "Ver más respuestas")}}
            }
            error?.let{ item(key="question-error") { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) } }
            item(key="question-bottom") { Spacer(Modifier.height(8.dp)) }
        }
    }
}
