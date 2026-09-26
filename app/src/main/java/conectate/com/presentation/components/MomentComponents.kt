package conectate.com.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import conectate.com.data.model.Moment
import conectate.com.data.model.User

@Composable
fun MomentsRow(
    moments: List<Moment>,
    currentUser: User?,
    onAddMomentClick: () -> Unit,
    onMomentClick: (Moment) -> Unit,
    modifier: Modifier = Modifier
) {
    // Separate current user's moments
    val myMoments = moments.filter { it.userId == currentUser?.id }
    val primaryMyMoment = primaryOwnerMoment(myMoments)
    val otherMoments = moments.filter { it.userId != currentUser?.id }

    // Group others by user and sort: Unseen children first
    val uniqueUserMoments = remember(otherMoments) {
        otherMoments.groupBy { it.userId }.values.map { 
            val sorted = it.sortedByDescending { m -> m.createdAt }
            val hasUnseen = it.any { m -> !m.isViewed }
            sorted.first() to hasUnseen
        }.sortedByDescending { it.second } // Unseen first
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        LazyRow(
            modifier = Modifier.padding(vertical = 12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // "My Moment" item
            item {
                MyMomentItemV2(
                    userAvatarUrl = currentUser?.avatar_url,
                    userAvatarMediaType = currentUser?.avatar_media_type,
                    activeMoments = myMoments,
                    onClick = {
                        primaryMyMoment?.let(onMomentClick) ?: onAddMomentClick()
                    }
                )
            }

            // Other users' moments
            items(uniqueUserMoments.size) { index ->
                val (moment, hasUnseen) = uniqueUserMoments[index]
                Box(modifier = Modifier.width(100.dp)) {
                    StoryCardV2(
                         isAddButton = false,
                         mediaUrl = moment.cardPreviewUrl(),
                         mediaType = moment.mediaType,
                         userAvatarUrl = moment.userAvatarUrl,
                         username = moment.userDisplayName ?: moment.username ?: "Usuario",
                         isViewed = !hasUnseen,
                         momentCount = otherMoments.count { it.userId == moment.userId },
                         onClick = { onMomentClick(moment) }
                    )
                }
            }
        }
    }
}


@Composable
fun MomentGrid(
    moments: List<Moment>,
    currentUser: User?,
    onAddMomentClick: () -> Unit,
    onMomentClick: (Moment) -> Unit,
    modifier: Modifier = Modifier,
    bottomContent: @Composable () -> Unit = {}
) {
    // Separate current user's moments
    val myMoments = moments.filter { it.userId == currentUser?.id }
    val primaryMyMoment = primaryOwnerMoment(myMoments)
    val otherMoments = moments.filter { it.userId != currentUser?.id }

    // Group others by user and partition into unseen/seen
    // Compute directly to ensure recomposition when moments change
    val groups = otherMoments.groupBy { it.userId }.values.map { 
        val sortedByUser = it.sortedByDescending { m -> m.createdAt }
        val hasUnseen = it.any { m -> !m.isViewed }
        sortedByUser.first() to hasUnseen
    }
    val unseenUsers = groups.filter { it.second }
    val seenUsers = groups.filter { !it.second }
    
    android.util.Log.d("MomentGrid", "Partition: ${unseenUsers.size} unseen users, ${seenUsers.size} seen users")

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxSize()
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            Text(
                "Novedades",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // "My Moment" Card
        item {
            StoryCardV2(
                isAddButton = true,
                mediaUrl = primaryMyMoment?.cardPreviewUrl(),
                mediaType = primaryMyMoment?.mediaType,
                userAvatarUrl = currentUser?.avatar_url,
                userAvatarMediaType = currentUser?.avatar_media_type,
                username = "Tú",
                momentCount = myMoments.size,
                onClick = {
                    primaryMyMoment?.let(onMomentClick) ?: onAddMomentClick()
                }
            )
        }

        // Unseen users ("Novedades")
        items(
            count = unseenUsers.size,
            key = { index -> "unseen_${unseenUsers[index].first.userId}" }
        ) { index ->
            val (moment, _) = unseenUsers[index]
            StoryCardV2(
                isAddButton = false,
                mediaUrl = moment.cardPreviewUrl(),
                mediaType = moment.mediaType,
                userAvatarUrl = moment.userAvatarUrl,
                username = moment.userDisplayName ?: moment.username ?: "Usuario",
                isViewed = false,
                momentCount = otherMoments.count { it.userId == moment.userId },
                onClick = { onMomentClick(moment) }
            )
        }

        // Section "Enterado" (only if there are seen moments)
        if (seenUsers.isNotEmpty()) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                Text(
                    "Enterado",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }

            items(
                count = seenUsers.size,
                key = { index -> "seen_${seenUsers[index].first.userId}" }
            ) { index ->
                val (moment, _) = seenUsers[index]
                StoryCardV2(
                    isAddButton = false,
                    mediaUrl = moment.cardPreviewUrl(),
                    mediaType = moment.mediaType,
                    userAvatarUrl = moment.userAvatarUrl,
                    username = moment.userDisplayName ?: moment.username ?: "Usuario",
                    isViewed = true,
                    momentCount = otherMoments.count { it.userId == moment.userId },
                    onClick = { onMomentClick(moment) }
                )
            }
        }
        
        // Bottom Content - "Mis Momentos" section
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            bottomContent()
        }
        
        // Bottom spacing
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
fun MyMomentItemV2(
    userAvatarUrl: String?,
    userAvatarMediaType: String? = null,
    activeMoments: List<Moment>,
    onClick: () -> Unit
) {
    val isLimitReached = activeMoments.size >= 10
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clickable(onClick = onClick)
        ) {
            // Animated Ring if has moments
            if (activeMoments.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(2.dp, Brush.sweepGradient(listOf(Color(0xFFFF5722), Color(0xFFFFC107))), CircleShape)
                        .padding(3.dp)
                ) {
                    CircularProfileImage(
                        imageUrl = userAvatarUrl,
                        mediaType = userAvatarMediaType,
                        size = 58.dp,
                        showBorder = false,
                    )
                }
            } else {
                CircularProfileImage(
                    imageUrl = userAvatarUrl,
                    mediaType = userAvatarMediaType,
                    size = 64.dp,
                    showBorder = false,
                )
            }
            
            // Add or Blocked Button
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .align(Alignment.BottomEnd)
                    .background(if (isLimitReached) Color.Gray else MaterialTheme.colorScheme.primary, CircleShape)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isLimitReached) Icons.Default.Lock else Icons.Default.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (isLimitReached) "Límite" else "Tú",
            fontSize = 11.sp,
            color = if (isLimitReached) Color.Red else Color.Unspecified,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Text(
            text = "${activeMoments.size}/10",
            fontSize = 9.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun StoryCardV2(
    isAddButton: Boolean,
    mediaUrl: String?,
    mediaType: String? = null,
    userAvatarUrl: String?,
    userAvatarMediaType: String? = null,
    username: String,
    isViewed: Boolean = false,
    momentCount: Int = 1,
    onClick: () -> Unit
) {
    val gradientBrush = remember {
        Brush.verticalGradient(listOf(Color(0xFF833AB4).copy(0.8f), Color(0xFFFD1D1D).copy(0.8f)))
    }

    Card(
        modifier = Modifier.fillMaxWidth().height(220.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background Thumbnail
            val mediaIsVideo = mediaUrl?.let { url ->
                val type = mediaType?.trim()?.lowercase().orEmpty()
                val cleanUrl = url.substringBefore('?').lowercase()
                type == "video" || type.startsWith("video/") ||
                    cleanUrl.endsWith(".mp4") || cleanUrl.endsWith(".m4v") || cleanUrl.endsWith(".mov")
            } == true
            if (mediaUrl != null && mediaIsVideo) {
                LoopingVideoPreview(
                    url = mediaUrl,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else if (mediaUrl != null) {
                AsyncImage(
                    model = mediaUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            } else if (!isAddButton) {
                Box(modifier = Modifier.fillMaxSize().background(gradientBrush))
            } else {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp))
                }
            }

            // Segmented Bar at Top
            if (momentCount > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    repeat(momentCount) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(2.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(0.6f))
                        )
                    }
                }
            }

            // User Info Overlay
            Box(
                modifier = Modifier.fillMaxSize().padding(12.dp)
            ) {
                // Avatar with "unseen" indicator
                Surface(
                    modifier = Modifier
                        .size(40.dp)
                        .align(Alignment.TopStart)
                        .border(2.dp, if (isViewed) Color.White.copy(0.5f) else Color.Red, CircleShape),
                    shape = CircleShape,
                    color = Color.Transparent
                ) {
                    CircularProfileImage(
                        imageUrl = userAvatarUrl,
                        mediaType = userAvatarMediaType,
                        size = 36.dp,
                        showBorder = false,
                    )
                }

                // Username at bottom
                Text(
                    text = username, // Repository now handles fallback logic ("Anónimo" or real name)
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.6f))))
                        .fillMaxWidth()
                        .padding(8.dp),
                    maxLines = 1
                )
                
                // Red Dot if unseen
                if (!isViewed && !isAddButton) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .align(Alignment.TopEnd)
                            .background(Color.Red, CircleShape)
                            .border(1.5.dp, Color.White, CircleShape)
                    )
                }
            }
            
            // Limit Reached Overlay
            if (isAddButton && momentCount >= 10) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.5f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Lock, null, tint = Color.White)
                        Text("Límite Alcanzado", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/** Video cards must receive the video itself; sending their JPEG thumbnail to ExoPlayer leaves a white card. */
internal fun Moment.cardPreviewUrl(): String {
    val normalizedType = mediaType.trim().lowercase()
    return if (normalizedType == "video" || normalizedType.startsWith("video/")) mediaUrl else thumbnailUrl ?: mediaUrl
}

/** The owner card opens the newest active Moment; creation stays on the explicit add control. */
internal fun primaryOwnerMoment(moments: List<Moment>): Moment? =
    moments.maxByOrNull { it.createdAt.orEmpty() }
