package conectate.com.presentation.components

import androidx.compose.runtime.Composable

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import conectate.com.data.model.Badge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription

@Composable
fun CreatorBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.padding(horizontal = 4.dp)
    ) {
        Text(
            text = "Creador",
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

@Composable
fun AdminBadge(
    modifier: Modifier = Modifier,
    showVerifiedLine: Boolean = true
) {
    // Compose observes the system animation scale and propagates it to this scope.
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    val animationsEnabled = (motionScale?.scaleFactor ?: 1f) > 0f
    val shimmerProgress: State<Float> = if (animationsEnabled) {
        rememberInfiniteTransition(label = "adminBadgeShimmer").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(3200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "diagonalShimmerProgress"
        )
    } else {
        // Keep a static diagonal highlight without creating an infinite animation.
        remember { mutableFloatStateOf(0.5f) }
    }
    val purpleBrush = remember {
        Brush.linearGradient(listOf(Color(0xFF4C1D95), Color(0xFF5B21B6)))
    }
    val shimmerColors = remember {
        // Limit the white sheen so white text retains contrast at every position.
        listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent)
    }

    Column(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = "Administrador verificado"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .drawBehind {
                    drawRect(purpleBrush)
                    val stripeWidth = size.height * 1.5f
                    // Start and finish outside the chip so restarting causes no flash.
                    val offset = -size.height - stripeWidth * 2f +
                        (size.width + size.height + stripeWidth * 2f) * shimmerProgress.value
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = shimmerColors,
                            start = Offset(offset, 0f),
                            end = Offset(offset + stripeWidth, -stripeWidth)
                        )
                    )
                }
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Verified,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(10.dp)
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = "ADMIN",
                color = Color.White,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                softWrap = false,
                letterSpacing = 0.25.sp
            )
        }
        if (showVerifiedLine) {
            Box(
                modifier = Modifier
                    .width(30.dp)
                    .height(1.dp)
                    .background(purpleBrush, RoundedCornerShape(1.dp))
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("ModifierParameter")
fun BadgeRow(
    isAdmin: Boolean = false,
    badges: List<Badge> = emptyList(),
    includeAdmin: Boolean = true,
    modifier: Modifier = Modifier
) {
    val adminFromCatalog = badges.any(::isAdminBadge)
    val showAdmin = includeAdmin && (isAdmin || adminFromCatalog)
    val otherBadges = badges.filterNot(::isAdminBadge)

    if (showAdmin || otherBadges.isNotEmpty()) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showAdmin) {
                AdminBadge(showVerifiedLine = false)
            }
            otherBadges.forEach { badge ->
                GenericBadgeChip(badge)
            }
        }
    }
}

fun isAdminBadge(badge: Badge): Boolean {
    val normalizedName = badge.name.trim().lowercase()
    return badge.isAdminBadge || normalizedName == "admin" || normalizedName == "administrador"
}

@Composable
fun GenericBadgeChip(badge: Badge) {
    val badgeColor = remember(badge.color) {
        try {
            Color(android.graphics.Color.parseColor(badge.color))
        } catch (e: Exception) {
            Color(0xFF6200EE) // Default purple
        }
    }

    Surface(
        color = badgeColor.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.3f)),
        modifier = Modifier.padding(vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = badge.icon, // Emoji or Icon char
                fontSize = 12.sp,
                modifier = Modifier.padding(end = 4.dp)
            )
            Text(
                text = badge.name,
                color = badgeColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
