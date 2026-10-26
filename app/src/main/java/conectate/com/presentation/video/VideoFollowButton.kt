package conectate.com.presentation.video

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Pending follows remain visible; confirmation appears only after the real server acknowledgement. */
@Composable
internal fun VideoFollowButton(
    userId: String,
    name: String,
    following: Boolean,
    pending: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    onFollow: () -> Unit,
) {
    var previouslyPending by remember(userId) { mutableStateOf(false) }
    var confirming by remember(userId) { mutableStateOf(false) }
    LaunchedEffect(userId, pending, following) {
        val succeeded = previouslyPending && !pending && following
        previouslyPending = pending
        confirming = succeeded
        if (succeeded) {
            delay(700)
            confirming = false
        }
    }
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    val animate = !reduceMotion && (motionScale?.scaleFactor ?: 1f) > 0f
    val scale by animateFloatAsState(
        targetValue = if (pending) 1.12f else 1f,
        animationSpec = if (animate) tween(160) else snap(), label = "video-follow-confirmation",
    )
    if (!following || pending || confirming) {
        Surface(
            modifier = modifier.size(28.dp).scale(scale)
                .semantics {
                    contentDescription = if (following && !pending) "Siguiendo a $name" else "Seguir a $name"
                    stateDescription = when { pending -> "Guardando seguimiento"; following -> "Seguimiento confirmado"; else -> "Sin seguir" }
                }
                .clickable(enabled = !pending && !following, role = Role.Button, onClick = onFollow),
            shape = CircleShape,
            color = if (following && !pending) Color(0xFF287BC1) else Color(0xFFFF2D55),
            border = BorderStroke(2.dp, Color.White),
            shadowElevation = 4.dp,
        ) {
            Icon(if (following && !pending) Icons.Default.Check else Icons.Default.Add,
                contentDescription = null, tint = Color.White, modifier = Modifier.padding(4.dp))
        }
    }
}
