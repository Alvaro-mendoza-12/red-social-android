package conectate.com.presentation.live

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import conectate.com.R
import conectate.com.data.model.LiveBattleState
import conectate.com.data.model.LiveComment
import conectate.com.data.model.LiveGiftEvent
import conectate.com.data.model.LiveGuestParticipant
import conectate.com.data.model.LiveGuestRequest
import conectate.com.data.model.User

private data class FigmaGameOption(
    val name: String,
    val audience: String,
    @param:DrawableRes val image: Int
)

private val figmaGames = listOf(
    FigmaGameOption("Valorant", "12,4 mil directos", R.drawable.figma_live_game_valorant),
    FigmaGameOption("Fortnite", "9,8 mil directos", R.drawable.figma_live_game_fortnite),
    FigmaGameOption("League of Legends", "8,7 mil directos", R.drawable.figma_live_game_league),
    FigmaGameOption("Apex Legends", "6,1 mil directos", R.drawable.figma_live_game_apex),
    FigmaGameOption("Minecraft", "5,9 mil directos", R.drawable.figma_live_game_minecraft),
    FigmaGameOption("Call of Duty", "5,2 mil directos", R.drawable.figma_live_game_call_of_duty)
)

/** Pantalla 2/12 del archivo Figma: selector de juego a pantalla completa. */
@Composable
internal fun FigmaGamePickerScreen(
    selected: String?,
    onBack: () -> Unit,
    onSelect: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val visibleGames = remember(query) {
        val clean = query.trim()
        if (clean.isEmpty()) figmaGames else figmaGames.filter { it.name.contains(clean, ignoreCase = true) }
    }
    FigmaAuxiliarySurface {
        FigmaScreenHeader("Seleccionar Juego", onBack)
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            Text(
                "Elige el juego que transmitirás",
                color = LivePalette.Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp, bottom = 14.dp)
            )
            FigmaSearchField(query, "Buscar juego", { query = it })
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Juegos populares", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                Text("${visibleGames.size} disponibles", color = LivePalette.Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 22.dp)
            ) {
                gridItems(visibleGames, key = { it.name }) { game ->
                    val isSelected = selected == game.name
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(LivePalette.Surface)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) LivePalette.Cyan else LivePalette.Border,
                                shape = RoundedCornerShape(16.dp)
                            )
                            .clickable { onSelect(game.name) }
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1.35f)) {
                            Image(
                                painter = painterResource(game.image),
                                contentDescription = game.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Box(
                                Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.74f)))
                                )
                            )
                            if (isSelected) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(LivePalette.Cyan),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
                                }
                            }
                        }
                        Text(
                            game.name,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 11.dp, end = 11.dp, top = 10.dp)
                        )
                        Text(
                            game.audience,
                            color = LivePalette.Muted,
                            fontSize = 9.sp,
                            modifier = Modifier.padding(start = 11.dp, end = 11.dp, bottom = 11.dp, top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/** Pantalla 3/12 del archivo Figma: invitaciones con pestañas, búsqueda y estado por amigo. */
@Composable
internal fun FigmaInviteFriendsScreen(
    users: List<User>,
    selectedIds: Set<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val visibleUsers = remember(users, query) {
        val clean = query.trim()
        if (clean.isEmpty()) users else users.filter {
            it.displayName.contains(clean, ignoreCase = true) ||
                it.username.orEmpty().contains(clean, ignoreCase = true)
        }
    }
    FigmaAuxiliarySurface {
        FigmaScreenHeader("Invitar Amigos", onBack, trailing = {
            Text(
                "Listo",
                color = LivePalette.Cyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.clickable(onClick = onDone).padding(8.dp)
            )
        })
        val tabs = listOf("Amigos", "Recientes", "Seguidores")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            tabs.forEachIndexed { index, label ->
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { selectedTab = index },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        label,
                        color = if (selectedTab == index) Color.White else LivePalette.Muted,
                        fontSize = 12.sp,
                        fontWeight = if (selectedTab == index) FontWeight.ExtraBold else FontWeight.Medium,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                    Box(
                        Modifier
                            .height(2.dp)
                            .fillMaxWidth()
                            .background(if (selectedTab == index) LivePalette.Cyan else Color.Transparent)
                    )
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(14.dp))
            FigmaSearchField(query, "Buscar amigos", { query = it })
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Personas sugeridas", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.weight(1f))
                if (selectedIds.isNotEmpty()) {
                    Text("${selectedIds.size} invitados", color = LivePalette.Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (visibleUsers.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.Groups, null, tint = LivePalette.Muted, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("No hay personas para mostrar", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Sigue a personas para invitarlas a tu directo", color = LivePalette.Muted, fontSize = 11.sp)
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(visibleUsers, key = { it.id }) { user ->
                        val invited = selectedIds.contains(user.id)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onToggle(user.id) }
                                .padding(horizontal = 4.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            conectate.com.presentation.components.CircularProfileImage(
                                imageUrl = user.avatar_url, fallbackName = user.displayName,
                                size = 46.dp, openMomentOnClick = false, showMomentRing = false,
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(CircleShape)
                                    .background(LivePalette.Surface)
                                    .border(1.dp, LivePalette.Purple.copy(alpha = .6f), CircleShape)
                            )
                            Spacer(Modifier.width(11.dp))
                            Column(Modifier.weight(1f)) {
                                Text(user.displayName, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(user.username?.let { "@$it" } ?: "En Novvin", color = LivePalette.Muted, fontSize = 10.sp)
                            }
                            Box(
                                Modifier
                                    .widthIn(min = 78.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .then(
                                        if (invited) Modifier.background(LivePalette.Surface)
                                        else Modifier.background(LivePrimaryGradient)
                                    )
                                    .then(
                                        if (invited) Modifier.border(1.dp, LivePalette.Border, RoundedCornerShape(10.dp)) else Modifier
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    if (invited) "Invitado" else "Invitar",
                                    color = if (invited) LivePalette.Muted else Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Pantalla 6/12 del archivo Figma: tablero de regalos del creador. */
@Composable
internal fun FigmaReceivedGiftsScreen(
    events: List<LiveGiftEvent>,
    onBack: () -> Unit
) {
    val grouped = remember(events) {
        events.groupBy { it.senderName.ifBlank { "Usuario" } }
            .mapValues { (_, values) -> values.sumOf { it.gift.priceDiamonds } }
            .toList()
            .sortedByDescending { it.second }
    }
    val total = events.sumOf { it.gift.priceDiamonds }
    FigmaAuxiliarySurface {
        FigmaScreenHeader("Regalos Recibidos", onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(LivePalette.Purple.copy(alpha = .88f), Color(0xFF4C1D95), LivePalette.Cyan.copy(alpha = .72f))
                        )
                    )
                    .padding(18.dp)
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.CardGiftcard, null, tint = Color.White, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        LivePill("EN VIVO", background = LivePalette.Red)
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("Total recibido", color = Color.White.copy(alpha = .78f), fontSize = 11.sp)
                    Text(total.toString(), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                    Text("monedas", color = LivePalette.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Mayores colaboradores", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
            if (grouped.isEmpty()) {
                FigmaEmptyCard("Aún no has recibido regalos", "Los regalos aparecerán aquí en tiempo real")
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    grouped.take(3).forEachIndexed { index, donor ->
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(LivePalette.Surface)
                                .border(1.dp, if (index == 0) LivePalette.Gold.copy(alpha = .65f) else LivePalette.Border, RoundedCornerShape(16.dp))
                                .padding(horizontal = 6.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(Modifier.size(40.dp).clip(CircleShape).background(if (index == 0) LivePalette.Gold else LivePalette.Purple.copy(alpha = .35f)), contentAlignment = Alignment.Center) {
                                Text(donor.first.take(1).uppercase(), color = if (index == 0) LivePalette.DeepSurface else Color.White, fontWeight = FontWeight.Black)
                            }
                            Spacer(Modifier.height(7.dp))
                            Text(donor.first, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("+${donor.second}", color = if (index == 0) LivePalette.Gold else LivePalette.Cyan, fontSize = 11.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Historial reciente", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                Text("${events.size} regalos", color = LivePalette.Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(10.dp))
            events.asReversed().take(20).forEach { event ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(LivePalette.Purple.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.CardGiftcard, null, tint = LivePalette.Gold, modifier = Modifier.size(21.dp))
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text(event.senderName.ifBlank { "Usuario" }, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text("envió ${event.gift.name}", color = LivePalette.Muted, fontSize = 10.sp)
                    }
                    Text("+${event.gift.priceDiamonds}", color = LivePalette.Cyan, fontSize = 13.sp, fontWeight = FontWeight.Black)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Pantalla 10/12 del archivo Figma: resultado de batalla. */
@Composable
internal fun FigmaBattleResultScreen(
    battle: LiveBattleState,
    hostName: String,
    onDismiss: () -> Unit
) {
    val tied = battle.hostScore == battle.opponentScore
    val hostWon = battle.hostScore > battle.opponentScore
    val winner = when {
        tied -> "Empate"
        hostWon -> hostName.ifBlank { "Anfitrión" }
        else -> battle.opponentName.ifBlank { "Invitado" }
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF090511), Color(0xFF17103B), Color(0xFF090511))))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        val compact = maxHeight < 700.dp
        Image(
            painter = painterResource(R.drawable.figma_live_battle_result),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = .22f,
            modifier = Modifier.fillMaxSize()
        )
        LiveRoundIconButton(
            Icons.Filled.Close,
            "Cerrar resultado",
            onDismiss,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            containerColor = Color.Black.copy(alpha = .52f),
            borderColor = Color.White.copy(alpha = .16f)
        )
        Column(
            Modifier
                .align(Alignment.Center)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LivePill("RESULTADO DE BATALLA", background = LivePalette.Purple.copy(alpha = .86f))
            Spacer(Modifier.height(if (compact) 16.dp else 28.dp))
            Box(
                Modifier
                    .size(if (compact) 88.dp else 108.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(LivePalette.Gold, Color(0xFF9A6700))))
                    .border(3.dp, Color.White.copy(alpha = .75f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.EmojiEvents, null, tint = Color.White, modifier = Modifier.size(if (compact) 48.dp else 58.dp))
            }
            Spacer(Modifier.height(13.dp))
            Text(if (tied) "¡EMPATE!" else "¡VICTORIA!", color = LivePalette.Gold, fontSize = if (compact) 27.sp else 34.sp, fontWeight = FontWeight.Black)
            Text(winner, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(if (compact) 20.dp else 30.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = .42f))
                    .border(1.dp, Color.White.copy(alpha = .12f), RoundedCornerShape(18.dp))
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BattleResultPlayer(hostName.ifBlank { "Anfitrión" }, battle.hostScore, !tied && hostWon, Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("VS", color = LivePalette.Muted, fontSize = 12.sp, fontWeight = FontWeight.Black)
                    Text(formatBattleDuration(battle.durationSeconds), color = Color.White, fontSize = 10.sp)
                }
                BattleResultPlayer(battle.opponentName.ifBlank { "Invitado" }, battle.opponentScore, !tied && !hostWon, Modifier.weight(1f))
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BattleResultStat("Puntos totales", (battle.hostScore + battle.opponentScore).toString(), Modifier.weight(1f))
                BattleResultStat("Diferencia", kotlin.math.abs(battle.hostScore - battle.opponentScore).toString(), Modifier.weight(1f))
                BattleResultStat("Duración", formatBattleDuration(battle.durationSeconds), Modifier.weight(1f))
            }
            Spacer(Modifier.height(22.dp))
            LiveGradientButton("Continuar", onDismiss)
        }
    }
}

private fun formatBattleDuration(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    return "%d:%02d".format(safe / 60, safe % 60)
}

/** Pantalla 11/12 del archivo Figma: centro de actividad del creador durante el directo. */
@Composable
internal fun FigmaLiveNotificationsScreen(
    comments: List<LiveComment>,
    gifts: List<LiveGiftEvent>,
    requests: List<LiveGuestRequest>,
    guests: List<LiveGuestParticipant>,
    onReviewRequests: () -> Unit,
    onReviewGifts: () -> Unit,
    onBack: () -> Unit
) {
    FigmaAuxiliarySurface {
        FigmaScreenHeader("Notificaciones en Vivo", onBack, trailing = {
            LivePill("EN VIVO", background = LivePalette.Red)
        })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(LivePalette.Surface)
                        .border(1.dp, LivePalette.Border, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(LivePalette.Cyan.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Notifications, null, tint = LivePalette.Cyan)
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Actividad en tiempo real", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Uniones, regalos, comentarios y solicitudes", color = LivePalette.Muted, fontSize = 10.sp)
                    }
                    Box(Modifier.size(8.dp).clip(CircleShape).background(LivePalette.Cyan))
                }
            }
            requests.take(10).forEach { request ->
                item(key = "request-${request.socketId}") {
                    FigmaNotificationRow(
                        icon = Icons.Filled.PersonAdd,
                        accent = LivePalette.Purple,
                        title = "${request.name} quiere unirse",
                        subtitle = "Solicitud para participar en tu directo",
                        action = "Revisar",
                        onAction = onReviewRequests
                    )
                }
            }
            gifts.asReversed().take(14).forEach { gift ->
                item(key = "gift-${gift.transactionId}") {
                    FigmaNotificationRow(
                        icon = Icons.Filled.CardGiftcard,
                        accent = LivePalette.Gold,
                        title = "${gift.senderName} envió ${gift.gift.name}",
                        subtitle = "+${gift.gift.priceDiamonds} monedas",
                        action = "Ver",
                        onAction = onReviewGifts
                    )
                }
            }
            guests.asReversed().take(10).forEach { guest ->
                item(key = "guest-${guest.socketId}") {
                    FigmaNotificationRow(
                        icon = Icons.Filled.Groups,
                        accent = LivePalette.Cyan,
                        title = "${guest.name} se unió al directo",
                        subtitle = "Ahora participa en la transmisión"
                    )
                }
            }
            comments.asReversed().take(16).forEach { comment ->
                item(key = "comment-${comment.id}") {
                    FigmaNotificationRow(
                        icon = Icons.Filled.Favorite,
                        accent = LivePalette.Pink,
                        title = comment.userName.ifBlank { "Usuario" },
                        subtitle = comment.content
                    )
                }
            }
            if (comments.isEmpty() && gifts.isEmpty() && requests.isEmpty() && guests.isEmpty()) {
                item { FigmaEmptyCard("Sin actividad todavía", "Las notificaciones aparecerán en cuanto tu audiencia interactúe") }
            }
        }
    }
}

/** Pantalla 8/12 del archivo Figma: solicitud de batalla como modal centrado. */
@Composable
internal fun FigmaBattleInvitationDialog(
    battle: LiveBattleState,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Dialog(onDismissRequest = onReject) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 390.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(LivePalette.Surface)
                .border(1.dp, LivePalette.Purple.copy(alpha = .72f), RoundedCornerShape(24.dp))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(LivePalette.Purple, Color(0xFF4C1D95))))
                    .border(2.dp, LivePalette.Cyan, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.EmojiEvents, null, tint = Color.White, modifier = Modifier.size(38.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text("Solicitud de Batalla", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
            Text(
                "${battle.opponentName.ifBlank { "Un creador" }} te desafía a una batalla LIVE",
                color = LivePalette.Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(LivePalette.DeepSurface)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FigmaMiniMetric("Duración", formatBattleDuration(battle.durationSeconds))
                Box(Modifier.width(1.dp).height(34.dp).background(LivePalette.Border))
                FigmaMiniMetric("Objetivo", "Más regalos")
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .border(1.dp, LivePalette.Border, RoundedCornerShape(13.dp))
                        .clickable(onClick = onReject),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Rechazar", color = LivePalette.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(LivePrimaryGradient)
                        .clickable(onClick = onAccept),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Aceptar", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

@Composable
private fun FigmaAuxiliarySurface(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(LivePalette.Background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .fillMaxHeight()
        ) { content() }
    }
}

@Composable
private fun FigmaScreenHeader(
    title: String,
    onBack: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiveRoundIconButton(
            Icons.AutoMirrored.Filled.ArrowBack,
            "Volver",
            onBack,
            size = 34.dp,
            containerColor = Color.Transparent,
            borderColor = Color.Transparent
        )
        Text(
            title,
            color = Color.White,
            fontSize = 17.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (trailing != null) trailing() else Spacer(Modifier.size(34.dp))
    }
}

@Composable
private fun FigmaSearchField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = { onValueChange(it.take(48)) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = LivePalette.Muted, modifier = Modifier.size(19.dp)) },
        placeholder = { Text(placeholder, color = LivePalette.Muted, fontSize = 12.sp) },
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = LivePalette.Surface,
            unfocusedContainerColor = LivePalette.Surface,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = LivePalette.Cyan
        )
    )
}

@Composable
private fun FigmaEmptyCard(title: String, subtitle: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LivePalette.Surface)
            .border(1.dp, LivePalette.Border, RoundedCornerShape(16.dp))
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.CardGiftcard, null, tint = LivePalette.Muted, modifier = Modifier.size(34.dp))
        Spacer(Modifier.height(9.dp))
        Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(subtitle, color = LivePalette.Muted, fontSize = 10.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BattleResultPlayer(name: String, score: Int, winner: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(score.toString(), color = if (winner) LivePalette.Gold else LivePalette.Cyan, fontSize = 26.sp, fontWeight = FontWeight.Black)
        if (winner) LivePill("GANADOR", background = LivePalette.Gold, contentColor = LivePalette.DeepSurface)
    }
}

@Composable
private fun BattleResultStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Color.Black.copy(alpha = .36f))
            .padding(horizontal = 5.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(label, color = LivePalette.Muted, fontSize = 8.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}

@Composable
private fun FigmaNotificationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    title: String,
    subtitle: String,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(15.dp))
            .background(LivePalette.Surface)
            .border(1.dp, LivePalette.Border, RoundedCornerShape(15.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(accent.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = accent, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = LivePalette.Muted, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) {
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(enabled = onAction != null) { onAction?.invoke() }
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(action, color = accent, fontSize = 9.sp, fontWeight = FontWeight.Black)
            }
        } else {
            Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
        }
    }
}

@Composable
private fun FigmaMiniMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black)
        Text(label, color = LivePalette.Muted, fontSize = 9.sp)
    }
}
