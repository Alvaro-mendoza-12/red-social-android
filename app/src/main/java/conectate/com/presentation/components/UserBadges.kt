package conectate.com.presentation.components

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

@Composable
fun CreatorBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
        shape = RoundedCornerShape(4.dp),
        modifier = modifier.padding(horizontal = 4.dp)
    ) {
        Text(
            text = "Creador",
            color = Color.White,
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
    val infiniteTransition = rememberInfiniteTransition(label = "badgeShimmer")
    
    // Fast cycle (2s) but with a shorter travel distance to feel "slow and smooth"
    val offset by infiniteTransition.animateFloat(
        initialValue = -100f,
        targetValue = 400f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "offset"
    )
    
    val colors = listOf(
        Color(0xFFFFD700), // Gold
        Color(0xFFFFFFFF), // Pure White (Destello)
        Color(0xFFFF00FF), // Magenta (Llamativo)
        Color(0xFFFFD700), // Gold
        Color(0xFFFF8C00), // Orange
        Color(0xFFFFD700), // Gold
    )

    val brush = Brush.linearGradient(
        colors = colors,
        start = androidx.compose.ui.geometry.Offset(offset, offset),
        end = androidx.compose.ui.geometry.Offset(offset + 200f, offset + 200f)
    )

    // Ensure it doesn't wrap vertically
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(brush)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .width(IntrinsicSize.Min) // Prevent over-stretching
    ) {
        Icon(
            imageVector = Icons.Default.Verified,
            contentDescription = "Verificado",
            tint = Color.White,
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "ADMIN",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            softWrap = false,
            letterSpacing = 1.sp
        )
    }
    
    if (showVerifiedLine) {
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .width(40.dp)
                .height(2.dp)
                .background(brush, RoundedCornerShape(1.dp))
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
@Suppress("ModifierParameter")
fun BadgeRow(
    userName: String?,
    userEmail: String? = null,
    badges: List<Badge> = emptyList(),
    modifier: Modifier = Modifier
) {
    // Collect all badges to display
    val displayBadges = remember(userName, userEmail, badges) {
        val list = mutableListOf<@Composable () -> Unit>()
        
        // 1. Admin Badge (if applicable according to identifiers)
        if (isUserAdmin(userName, userEmail)) {
            list.add { AdminBadge(showVerifiedLine = false) }
        }
        
        // 2. Map other badges from the list
        badges.forEach { badge ->
            // Skip if it's an admin badge and we already added it OR if name is Admin
            if (badge.name.lowercase() != "admin" || !isUserAdmin(userName, userEmail)) {
                list.add { GenericBadgeChip(badge) }
            }
        }
        
        list
    }

    if (displayBadges.isNotEmpty()) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            displayBadges.forEach { badgeComposable ->
                badgeComposable()
            }
        }
    }
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

/**
 * Checks if a user is an admin based on specific identifiers from SettingsViewModel
 */
fun isUserAdmin(userName: String?, userEmail: String? = null): Boolean {
    val normalizedName = userName?.lowercase()?.trim() ?: ""
    val normalizedEmail = userEmail?.lowercase()?.trim() ?: ""
    
    return normalizedName == "conectate" || 
           normalizedName == "alvaro" || 
           normalizedEmail == "alvaromendozamarin004@gmail.com" ||
           normalizedEmail == "gamergamerff017@gmail.com"
}
