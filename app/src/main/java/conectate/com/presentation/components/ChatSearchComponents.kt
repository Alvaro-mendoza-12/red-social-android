package conectate.com.presentation.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import conectate.com.data.model.Message
import conectate.com.util.TimeUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color

data class ChatSearchResult(
    val message: Message,
    val highlightRanges: List<IntRange> // Ranges of matching text
)

@Composable
fun ChatSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    resultCount: Int,
    currentIndex: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }
    
    LaunchedEffect(Unit) {
        delay(100)
        focusRequester.requestFocus()
    }
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Close button
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Cerrar búsqueda",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                // Search field
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Search,
                        keyboardType = KeyboardType.Text
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = { onSearch() }
                    ),
                    decorationBox = { innerTextField ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    text = "Buscar en el chat...",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                
                // Clear button
                AnimatedVisibility(
                    visible = query.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Limpiar",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Results navigation
            AnimatedVisibility(
                visible = resultCount > 0,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${currentIndex + 1} de $resultCount",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Row {
                        IconButton(
                            onClick = onPrevious,
                            enabled = currentIndex > 0
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "Anterior"
                            )
                        }
                        
                        IconButton(
                            onClick = onNext,
                            enabled = currentIndex < resultCount - 1
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Siguiente"
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatSearchResultsDialog(
    results: List<ChatSearchResult>,
    query: String,
    onResultClick: (ChatSearchResult) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${results.size} resultados",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar"
                        )
                    }
                }
                
                Divider()
                
                // Results list
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(results) { result ->
                        SearchResultItem(
                            result = result,
                            query = query,
                            onClick = { onResultClick(result) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    result: ChatSearchResult,
    query: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Sender name and time
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = result.message.sender_name ?: "Usuario",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Text(
                text = TimeUtils.formatTimestamp(result.message.created_at),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // Message content with highlight
        HighlightedText(
            text = result.message.content,
            highlightRanges = result.highlightRanges,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
    
    Divider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
fun HighlightedText(
    text: String,
    highlightRanges: List<IntRange>,
    style: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val annotatedString = remember(text, highlightRanges) {
        buildAnnotatedString {
            append(text)
            
            highlightRanges.forEach { range ->
                if (range.first >= 0 && range.last < text.length) {
                    addStyle(
                        style = SpanStyle(
                            background = Color(0xFFFFEB3B).copy(alpha = 0.6f),
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        ),
                        start = range.first,
                        end = range.last + 1
                    )
                }
            }
        }
    }
    
    Text(
        text = annotatedString,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = overflow
    )
}

// Extension function for search highlighting
fun String.findHighlightRanges(query: String): List<IntRange> {
    if (query.isBlank()) return emptyList()
    
    val ranges = mutableListOf<IntRange>()
    val lowerQuery = query.lowercase()
    val lowerText = this.lowercase()
    
    var startIndex = 0
    while (true) {
        val index = lowerText.indexOf(lowerQuery, startIndex)
        if (index == -1) break
        ranges.add(index until index + query.length)
        startIndex = index + 1
    }
    
    return ranges
}

// Search logic for ViewModels
object ChatSearchHelper {
    fun searchMessages(
        messages: List<Message>,
        query: String,
        maxResults: Int = 50
    ): List<ChatSearchResult> {
        if (query.isBlank()) return emptyList()
        
        val lowerQuery = query.lowercase()
        
        return messages
            .filter { message ->
                message.content.lowercase().contains(lowerQuery) ||
                message.sender_name?.lowercase()?.contains(lowerQuery) == true
            }
            .take(maxResults)
            .map { message ->
                ChatSearchResult(
                    message = message,
                    highlightRanges = message.content.findHighlightRanges(query)
                )
            }
    }
    
    fun findNextMatch(
        messages: List<Message>,
        query: String,
        currentMessageId: String?
    ): Message? {
        if (query.isBlank()) return null
        
        val lowerQuery = query.lowercase()
        val matchingMessages = messages.filter {
            it.content.lowercase().contains(lowerQuery)
        }
        
        if (matchingMessages.isEmpty()) return null
        
        val currentIndex = matchingMessages.indexOfFirst { it.id == currentMessageId }
        return if (currentIndex == -1 || currentIndex >= matchingMessages.size - 1) {
            matchingMessages.firstOrNull()
        } else {
            matchingMessages[currentIndex + 1]
        }
    }
    
    fun findPreviousMatch(
        messages: List<Message>,
        query: String,
        currentMessageId: String?
    ): Message? {
        if (query.isBlank()) return null
        
        val lowerQuery = query.lowercase()
        val matchingMessages = messages.filter {
            it.content.lowercase().contains(lowerQuery)
        }
        
        if (matchingMessages.isEmpty()) return null
        
        val currentIndex = matchingMessages.indexOfFirst { it.id == currentMessageId }
        return if (currentIndex <= 0) {
            matchingMessages.lastOrNull()
        } else {
            matchingMessages[currentIndex - 1]
        }
    }
}
