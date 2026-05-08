package conectate.com.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * Carousel de reacciones deslizables tipo Facebook
 */
enum class ReactionType(
    val emoji: String,
    val label: String,
    val color: Color,
    val icon: ImageVector? = null
) {
    LIKE("👍", "Me gusta", Color(0xFF2196F3), Icons.Default.ThumbUp),
    LOVE("❤️", "Me encanta", Color(0xFFE91E63)),
    HAHA("😂", "Me divierte", Color(0xFFFFC107)),
    WOW("😮", "Me asombra", Color(0xFFFF9800)),
    SAD("😢", "Me entristece", Color(0xFF2196F3)),
    ANGRY("😠", "Me enoja", Color(0xFFF44336)),
    CARE("🤗", "Me importa", Color(0xFFFF9800)),
    FIRE("🔥", "Fuego", Color(0xFFFF5722))
}

@Composable
fun ReactionCarouselButton(
    currentReaction: ReactionType?,
    onReactionSelected: (ReactionType) -> Unit,
    onReactionRemoved: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showCarousel by remember { mutableStateOf(false) }
    var isPressed by remember { mutableStateOf(false) }
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium)
    )
    
    val buttonColor = currentReaction?.color?.copy(alpha = 0.2f) 
        ?: MaterialTheme.colorScheme.surfaceVariant
    val contentColor = currentReaction?.color 
        ?: MaterialTheme.colorScheme.onSurfaceVariant
    
    Box(modifier = modifier) {
        // Main Button
        Surface(
            modifier = Modifier
                .scale(scale)
                .clickable { 
                    if (currentReaction != null) {
                        onReactionRemoved()
                    } else {
                        onReactionSelected(ReactionType.LIKE)
                    }
                },
            shape = RoundedCornerShape(20.dp),
            color = buttonColor
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (currentReaction != null) {
                    Text(
                        text = currentReaction.emoji,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = currentReaction.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = currentReaction.color,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.ThumbUp,
                        contentDescription = "Like",
                        modifier = Modifier.size(18.dp),
                        tint = contentColor
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Me gusta",
                        style = MaterialTheme.typography.labelMedium,
                        color = contentColor
                    )
                }
            }
        }
        
        // Long press to show carousel
        Box(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { isPressed = true },
                        onDragEnd = { 
                            isPressed = false
                            showCarousel = false 
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            if (!showCarousel && dragAmount > 20) {
                                showCarousel = true
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when {
                                event.changes.any { it.pressed } -> {
                                    isPressed = true
                                    // Long press detection
                                    val job = kotlinx.coroutines.launch {
                                        kotlinx.coroutines.delay(300)
                                        showCarousel = true
                                    }
                                    // Wait for release
                                    awaitPointerEvent()
                                    job.cancel()
                                    isPressed = false
                                }
                            }
                        }
                    }
                }
        )
        
        // Reaction Carousel Popup
        if (showCarousel) {
            ReactionCarouselPopup(
                onReactionSelected = { reaction ->
                    onReactionSelected(reaction)
                    showCarousel = false
                },
                onDismiss = { showCarousel = false }
            )
        }
    }
}

@Composable
private fun ReactionCarouselPopup(
    onReactionSelected: (ReactionType) -> Unit,
    onDismiss: () -> Unit
) {
    val reactions = ReactionType.values()
    var selectedIndex by remember { mutableStateOf(-1) }
    
    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false)
    ) {
        Surface(
            modifier = Modifier
                .padding(bottom = 8.dp)
                .wrapContentSize(),
            shape = RoundedCornerShape(30.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                reactions.forEachIndexed { index, reaction ->
                    val isSelected = selectedIndex == index
                    
                    AnimatedReactionItem(
                        reaction = reaction,
                        isSelected = isSelected,
                        onSelected = {
                            selectedIndex = index
                            onReactionSelected(reaction)
                        },
                        delayMillis = index * 30L
                    )
                }
            }
        }
    }
}

@Composable
private fun AnimatedReactionItem(
    reaction: ReactionType,
    isSelected: Boolean,
    onSelected: () -> Unit,
    delayMillis: Long
) {
    var isVisible by remember { mutableStateOf(false) }
    
    val scale by animateFloatAsState(
        targetValue = when {
            !isVisible -> 0f
            isSelected -> 1.4f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "reaction_scale"
    )
    
    val offsetY by animateFloatAsState(
        targetValue = when {
            !isVisible -> 50f
            isSelected -> -20f
            else -> 0f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "reaction_offset"
    )
    
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMillis)
        isVisible = true
    }
    
    Box(
        modifier = Modifier
            .size(44.dp)
            .scale(scale)
            .offset { IntOffset(0, offsetY.roundToInt()) }
            .clickable(onClick = onSelected),
        contentAlignment = Alignment.Center
    ) {
        // Shadow/background
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color.White, CircleShape)
        )
        
        // Emoji
        Text(
            text = reaction.emoji,
            fontSize = MaterialTheme.typography.headlineSmall.fontSize,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

@Composable
fun ReactionSummaryBar(
    reactionCounts: Map<ReactionType, Int>,
    totalCount: Int,
    topReactions: List<ReactionType>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (totalCount == 0) return
    
    Row(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Show top 3 reaction emojis
        Row(
            modifier = Modifier.offset(x = (topReactions.size * 4).dp),
            horizontalArrangement = Arrangement.spacedBy((-8).dp)
        ) {
            topReactions.take(3).forEach { reaction ->
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = reaction.emoji,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Text(
            text = formatReactionCount(totalCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ReactionBreakdownDialog(
    reactionCounts: Map<ReactionType, Int>,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Reacciones",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Sort by count
                val sortedReactions = reactionCounts.entries
                    .sortedByDescending { it.value }
                
                sortedReactions.forEach { (reaction, count) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = reaction.emoji,
                            fontSize = MaterialTheme.typography.headlineSmall.fontSize
                        )
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        Text(
                            text = reaction.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        
                        Text(
                            text = formatCount(count),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cerrar")
                }
            }
        }
    }
}

private fun formatReactionCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM reacciones", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK reacciones", count / 1_000.0)
        count == 1 -> "1 reacción"
        else -> "$count reacciones"
    }
}

private fun formatCount(count: Int): String {
    return when {
        count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}
