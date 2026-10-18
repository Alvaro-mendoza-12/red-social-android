package conectate.com.presentation.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileFollowCounts(followers: Int, following: Int, onFollowers: () -> Unit, onFollowing: () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FollowCount(followers, "Seguidores", onFollowers)
        FollowCount(following, "Siguiendo", onFollowing)
    }
}

@Composable
internal fun FollowCount(value: Int, label: String, onClick: () -> Unit) {
    Column(
        Modifier.widthIn(min = 56.dp).width(IntrinsicSize.Max).heightIn(min = 48.dp)
            .semantics { contentDescription = value.coerceAtLeast(0).toString() + " " + label }
            .clickable(role = Role.Button, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value.coerceAtLeast(0).toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Keep the complete name beside the counters only when all three fit at the current font size. */
@Composable
fun ProfileNameAndCounts(
    name: String, followers: Int, following: Int,
    onFollowers: () -> Unit, onFollowing: () -> Unit,
    isVerified: Boolean = false,
    badges: (@Composable RowScope.() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val nameStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold)
    val valueStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    val labelStyle = MaterialTheme.typography.bodySmall
    fun width(text: String, style: TextStyle): Int =
        textMeasurer.measure(AnnotatedString(text), style = style, softWrap = false, maxLines = 1).size.width
    val minimumCounterWidth = with(density) { 56.dp.roundToPx() }
    val followersWidth = maxOf(minimumCounterWidth, width(followers.coerceAtLeast(0).toString(), valueStyle), width("Seguidores", labelStyle))
    val followingWidth = maxOf(minimumCounterWidth, width(following.coerceAtLeast(0).toString(), valueStyle), width("Siguiendo", labelStyle))
    val counterAndNameGaps = with(density) { 16.dp.roundToPx() }
    val verifiedWidth = with(density) { if (isVerified) 24.dp.roundToPx() else 0 }
    val textRoundingMargin = with(density) { 2.dp.roundToPx() }
    val requiredWidth = width(name, nameStyle) + verifiedWidth + followersWidth + followingWidth + counterAndNameGaps + textRoundingMargin
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inline = requiredWidth <= with(density) { maxWidth.roundToPx() }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (inline) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ProfileNameLabel(name, isVerified, nameStyle, Modifier.weight(1f), maxLines = 1)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FollowCount(followers, "Seguidores", onFollowers)
                        FollowCount(following, "Siguiendo", onFollowing)
                    }
                }
            } else {
                // A long name and a 200% font can grow vertically without taking counter or badge space.
                ProfileNameLabel(name, isVerified, nameStyle, Modifier.fillMaxWidth())
                ProfileFollowCounts(followers, following, onFollowers, onFollowing)
            }
            badges?.let { badgeContent ->
                Row(verticalAlignment = Alignment.CenterVertically, content = badgeContent)
            }
        }
    }
}

@Composable
private fun ProfileNameLabel(
    name: String,
    isVerified: Boolean,
    style: TextStyle,
    modifier: Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            name,
            style = style,
            maxLines = maxLines,
            softWrap = maxLines != 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (isVerified) {
            Icon(Icons.Default.CheckCircle, "Verificado", tint = Color(0xFF1DA1F2), modifier = Modifier.size(20.dp))
        }
    }
}

/** Cover and overlapping avatar share real bounds for drawing, taps and measured content height. */
@Composable
internal fun ProfileIdentityHeader(
    modifier: Modifier = Modifier,
    cover: @Composable () -> Unit,
    avatar: @Composable () -> Unit,
    identity: @Composable ColumnScope.() -> Unit,
) {
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            Box { cover() }
            Box { avatar() }
            Column(content = identity)
        },
    ) { measurables, constraints ->
        val inset = 16.dp.roundToPx()
        val gap = 12.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
        val coverPlaceable = measurables[0].measure(loose.copy(minWidth = constraints.maxWidth))
        val avatarPlaceable = measurables[1].measure(loose.copy(maxWidth = (constraints.maxWidth - inset * 2).coerceAtLeast(0)))
        val identityStart = inset + avatarPlaceable.width + gap
        val identityWidth = (constraints.maxWidth - identityStart - inset).coerceAtLeast(0)
        val identityPlaceable = measurables[2].measure(loose.copy(minWidth = identityWidth, maxWidth = identityWidth))
        val avatarTop = (coverPlaceable.height - 50.dp.roundToPx()).coerceAtLeast(0)
        val identityTop = coverPlaceable.height + 8.dp.roundToPx()
        val height = maxOf(coverPlaceable.height, avatarTop + avatarPlaceable.height, identityTop + identityPlaceable.height)
        layout(constraints.maxWidth, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            coverPlaceable.placeRelative(0, 0)
            avatarPlaceable.placeRelative(inset, avatarTop)
            identityPlaceable.placeRelative(identityStart, identityTop)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfilePublicIdentity(
    handle: String, shortId: String, accent: Color, onOpenId: () -> Unit, isAdmin: Boolean = false,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
            Text(
                text = "@" + handle,
                style = MaterialTheme.typography.bodySmall,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = onOpenId,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Ver ID del perfil" },
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            Text("Ver ID", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp))
        }
        if (isAdmin) {
            Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                conectate.com.presentation.components.AdminBadge(showVerifiedLine = false)
            }
        }
    }
}
