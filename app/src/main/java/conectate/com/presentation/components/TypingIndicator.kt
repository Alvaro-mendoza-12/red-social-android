package conectate.com.presentation.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TypingIndicator(
    userName: String? = null,
    modifier: Modifier = Modifier,
    dotColor: Color = MaterialTheme.colorScheme.primary
) {
    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Animated dots
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(end = 8.dp)
        ) {
            repeat(3) { index ->
                AnimatedDot(
                    index = index,
                    color = dotColor
                )
            }
        }
        
        // Text
        Text(
            text = if (userName != null) {
                "$userName está escribiendo..."
            } else {
                "Escribiendo..."
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AnimatedDot(
    index: Int,
    color: Color
) {
    val transition = rememberInfiniteTransition(label = "dot_animation_$index")
    
    val scale by transition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, delayMillis = index * 150),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale_$index"
    )
    
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, delayMillis = index * 150),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha_$index"
    )
    
    Box(
        modifier = Modifier
            .size(8.dp)
            .scale(scale)
            .alpha(alpha)
            .background(color, CircleShape)
    )
}

@Composable
fun TypingIndicatorBubble(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            AnimatedDot(
                index = index,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun TypingIndicatorMinimal(
    modifier: Modifier = Modifier,
    dotSize: androidx.compose.ui.unit.Dp = 4.dp
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val transition = rememberInfiniteTransition(label = "minimal_dot_$index")
            
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = index * 100),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "minimal_alpha_$index"
            )
            
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .alpha(alpha)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        CircleShape
                    )
            )
        }
    }
}

@Composable
fun OnlineStatusIndicator(
    isOnline: Boolean,
    lastSeen: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    if (isOnline) Color(0xFF4CAF50) else Color(0xFF9E9E9E),
                    CircleShape
                )
        )
        
        Text(
            text = if (isOnline) "En línea" else "Últ. vez ${lastSeen ?: "recientemente"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
    }
}

@Composable
fun UserStatusBar(
    userName: String,
    isOnline: Boolean,
    isTyping: Boolean,
    lastSeen: String? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = userName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        
        AnimatedContent(
            targetState = isTyping,
            label = "status_transition"
        ) { typing ->
            if (typing) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypingIndicatorMinimal(modifier = Modifier.padding(end = 6.dp))
                    Text(
                        text = "escribiendo...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                OnlineStatusIndicator(
                    isOnline = isOnline,
                    lastSeen = lastSeen
                )
            }
        }
    }
}
