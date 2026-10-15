package conectate.com.presentation.raffles

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import conectate.com.data.raffles.RaffleParticipant
import conectate.com.data.raffles.RaffleRound
import kotlin.math.sin
import kotlin.random.Random

@Stable
internal class RaffleDrawPresentationSession(initial: RaffleDrawPresentationState) {
    var state by mutableStateOf(initial)
    var celebratingKey by mutableStateOf<String?>(null)
    val rotation = Animatable(0f)
    val celebration = Animatable(0f)
}

@Composable
internal fun rememberRaffleDrawPresentationSession(round: RaffleRound?): RaffleDrawPresentationSession {
    val session = remember { RaffleDrawPresentationSession(RaffleDrawPresentationState().receive(round)) }
    val latestRound by rememberUpdatedState(round)
    LaunchedEffect(round?.id, round?.results) {
        val previousRound = session.state.roundId
        session.state = session.state.receive(round)
        if (previousRound != session.state.roundId) session.celebratingKey = null
    }
    val active = session.state.active
    val roundId = session.state.roundId
    LaunchedEffect(roundId, active?.position) {
        val result = active ?: return@LaunchedEffect
        val currentRound = latestRound?.takeIf { it.id == roundId } ?: return@LaunchedEffect
        val prior = currentRound.results.filter { it.position < result.position }.mapTo(hashSetOf()) { it.participant.id }
        val candidates = currentRound.participants.filter { it.id !in prior }
        val index = candidates.indexOfFirst { it.id == result.participant.id }
        if (index < 0) return@LaunchedEffect
        session.rotation.snapTo((session.rotation.value % 360f + 360f) % 360f)
        session.rotation.animateTo(wheelTargetRotation(session.rotation.value, index, candidates.size),
            tween(4_000, easing = raffleSpinEasing))
        if (session.state.roundId != roundId || session.state.active?.position != result.position) return@LaunchedEffect
        session.state = session.state.reveal(result.position)
        session.celebration.snapTo(0f)
        session.celebratingKey = "$roundId:${result.position}"
        session.celebration.animateTo(1f, tween(1_200, easing = LinearEasing))
        if (session.state.roundId == roundId && session.state.active?.position == result.position) {
            session.celebratingKey = null
            session.state = session.state.finish(result.position)
        }
    }
    return session
}

@Composable
internal fun RaffleDrawPresentation(
    round: RaffleRound?,
    originalParticipants: List<RaffleParticipant>,
    session: RaffleDrawPresentationSession = rememberRaffleDrawPresentationSession(round),
) {
    val presentation = session.state
    val roundIsCurrent = presentation.roundId == round?.id
    val active = presentation.active.takeIf { roundIsCurrent }
    val drawKey = active?.let { "${presentation.roundId}:${it.position}" }
    val celebrating = drawKey != null && session.celebratingKey == drawKey
    val storedResults = round?.results.orEmpty()
    val candidates = if (active != null) {
        val prior = storedResults.filter { it.position < active.position }.mapTo(hashSetOf()) { it.participant.id }
        (round?.participants ?: originalParticipants).filter { it.id !in prior }
    } else {
        val selected = storedResults.mapTo(hashSetOf()) { it.participant.id }
        (round?.participants ?: originalParticipants).filter { it.id !in selected }
    }
    val revealed = if (roundIsCurrent) storedResults.filter { it.position <= presentation.revealedPosition }
        .maxByOrNull { it.position } else null
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RaffleWheel(candidates, active?.participant?.id, active != null && !celebrating,
                animationKey = drawKey,
                rotationState = session.rotation)
            when {
                !roundIsCurrent -> Text("Preparando ruleta…", style = MaterialTheme.typography.titleMedium)
                active != null && !celebrating -> Text("Girando…", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.testTag("raffle_spinning"))
                revealed != null -> Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().testTag("raffle_winner")) {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${revealed.position}.ª posición", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(revealed.participant.name, style = MaterialTheme.typography.headlineSmall)
                        Text(if (round?.status == "completed" && presentation.pending.size <= 1) "Sorteo completado" else "¡Tenemos un ganador!",
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> Text("${candidates.size} participantes restantes", style = MaterialTheme.typography.titleMedium)
            }
        }
        if (celebrating) key(drawKey) {
            RaffleConfetti(Modifier.matchParentSize(), drawKey.hashCode()) { session.celebration.value }
        }
    }
}

private data class ConfettiPiece(val spread: Float, val lift: Float, val delay: Float, val spin: Float, val color: Color)

@Composable
private fun RaffleConfetti(modifier: Modifier, seed: Int, progress: () -> Float) {
    val pieces = remember(seed) {
        val random = Random(seed)
        List(72) { ConfettiPiece(random.nextFloat() * 2 - 1, .65f + random.nextFloat() * .5f,
            random.nextFloat() * .12f, random.nextFloat() * 720 - 360, raffleWheelColors[it % raffleWheelColors.size]) }
    }
    Canvas(modifier.testTag("raffle_confetti")) {
        pieces.forEachIndexed { index, piece ->
            val t = ((progress() - piece.delay) / (1 - piece.delay)).coerceIn(0f, 1f)
            val x = size.width / 2 + piece.spread * size.width * .72f * t
            val y = size.height * .70f - piece.lift * size.height * 1.25f * t + size.height * 1.35f * t * t
            val alpha = if (t < .7f) 1f else ((1 - t) / .3f).coerceIn(0f, 1f)
            rotate(piece.spin * t, Offset(x, y)) {
                if (index % 3 == 0) drawCircle(piece.color.copy(alpha = alpha), 3.dp.toPx(), Offset(x, y))
                else drawRect(piece.color.copy(alpha = alpha), Offset(x, y),
                    Size(5.dp.toPx(), (5 + 4 * kotlin.math.abs(sin(t * 9))).dp.toPx()))
            }
        }
    }
}
