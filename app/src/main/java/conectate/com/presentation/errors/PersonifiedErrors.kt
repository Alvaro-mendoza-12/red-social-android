package conectate.com.presentation.errors

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import conectate.com.R
import conectate.com.core.result.ErrorCharacter
import conectate.com.core.result.ErrorLevel
import conectate.com.core.result.NovvinErrors
import conectate.com.core.result.NovvinProblem
import kotlinx.coroutines.launch

private val errorBackground = Color(0xFF15112B)
private val errorColors = darkColorScheme(
    primary = Color(0xFFDAC1FF),
    onPrimary = Color(0xFF2E1452),
    surface = Color(0xFF251B40),
    onSurface = Color(0xFFF9F3FF),
    onSurfaceVariant = Color(0xFFDFD2ED),
    outline = Color(0xFFAB91C2)
)

private data class CharacterArt(val name: String, val image: Int, val accent: Color)

private fun ErrorCharacter.art(): CharacterArt = when (this) {
    ErrorCharacter.Confused -> CharacterArt("La curiosa", R.drawable.error_character_confused, Color(0xFF91DFFF))
    ErrorCharacter.Guardian -> CharacterArt("La guardiana", R.drawable.error_character_guardian, Color(0xFFFFD489))
    ErrorCharacter.Protector -> CharacterArt("La protectora", R.drawable.error_character_protector, Color(0xFFFF9BAE))
    ErrorCharacter.Detective -> CharacterArt("La detective", R.drawable.error_character_detective, Color(0xFFD7ADFF))
    ErrorCharacter.Overloaded -> CharacterArt("El técnico", R.drawable.error_character_overloaded, Color(0xFFFF9CD9))
    ErrorCharacter.Sleeper -> CharacterArt("El dormilón", R.drawable.error_character_sleeper, Color(0xFF8FF3CB))
}

@Composable
private fun rememberErrorSpeech(): ErrorSpeech {
    val context = LocalContext.current.applicationContext
    val speech = remember(context) { ErrorSpeech(context) }
    DisposableEffect(speech) { onDispose { speech.close() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { speech.stop() }
    return speech
}

@Composable
private fun ErrorSurface(
    modifier: Modifier,
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    MaterialTheme(colorScheme = errorColors) {
        Column(
            modifier = modifier
                .background(Brush.verticalGradient(listOf(errorBackground, Color(0xFF271942), errorBackground)))
                .verticalScroll(scroll)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}

@Composable
internal fun PersonifiedErrorState(
    problem: NovvinProblem,
    modifier: Modifier = Modifier,
    title: String = problem.title,
    description: String = problem.description,
    actionLabel: String = "Reintentar",
    onRetry: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val speech = rememberErrorSpeech()
    LaunchedEffect(problem) { speech.stop() }
    ErrorSurface(modifier.fillMaxSize()) {
        Brand()
        ProblemCard(problem, speech, title, description)
        if (onRetry != null) Button(onClick = onRetry) { Text(actionLabel) }
        if (secondaryLabel != null && onSecondary != null) {
            OutlinedButton(onClick = onSecondary) { Text(secondaryLabel) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ErrorGalleryScreen(onBack: () -> Unit) {
    var selection by rememberSaveable { mutableIntStateOf(404) }
    val problem = NovvinErrors[selection] ?: checkNotNull(NovvinErrors[404])
    val speech = rememberErrorSpeech()
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    fun select(code: Int) {
        speech.stop()
        selection = code
        scope.launch { scroll.animateScrollTo(0) }
    }

    ErrorSurface(Modifier.fillMaxSize(), scroll) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Brand()
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
            }
        }
        Text("Errores con personalidad", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        ProblemCard(problem, speech)
        Text("Personajes", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NovvinErrors.catalog.distinctBy { it.character }.forEach { entry ->
                val art = entry.character.art()
                val selected = entry.character == problem.character
                Surface(
                    selected = selected,
                    onClick = { select(checkNotNull(entry.code)) },
                    modifier = Modifier.widthIn(max = 120.dp).heightIn(min = 88.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = errorColors.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) art.accent else errorColors.outline)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Portrait(entry.character, Modifier.fillMaxWidth(), animate = false)
                        Text(art.name, modifier = Modifier.padding(6.dp), textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Text("Códigos de error", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            NovvinErrors.catalog.forEach { entry ->
                val code = checkNotNull(entry.code)
                FilterChip(selected = code == selection, onClick = { select(code) },
                    label = { Text(code.toString()) })
            }
        }
    }
}

@Composable
private fun Brand() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Image(painterResource(R.mipmap.ic_launcher_round), contentDescription = null, modifier = Modifier.size(42.dp))
        Text("NOVVIN", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProblemCard(
    problem: NovvinProblem,
    speech: ErrorSpeech,
    title: String = problem.title,
    description: String = problem.description,
) {
    val art = problem.character.art()
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier = Modifier.widthIn(max = 430.dp).fillMaxWidth().clip(shape)
            .background(errorColors.surface).border(1.dp, art.accent.copy(alpha = 0.55f), shape).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Portrait(problem.character, Modifier.widthIn(max = 220.dp).fillMaxWidth(), animate = true)
        val level = when (problem.level) {
            ErrorLevel.Notice -> "Aviso"
            ErrorLevel.Connection -> "Conexión"
            ErrorLevel.Critical -> "Error crítico"
        }
        Text(listOfNotNull(problem.code?.toString(), level).joinToString(" · "), color = art.accent,
            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() })
        Text(description, color = errorColors.onSurfaceVariant, textAlign = TextAlign.Center)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            Button(onClick = { speech.listen(problem) }, enabled = !speech.active,
                colors = ButtonDefaults.buttonColors(containerColor = art.accent, contentColor = errorBackground)) {
                Text("Escuchar")
            }
            OutlinedButton(onClick = speech::stop, enabled = speech.active) { Text("Detener") }
        }
        if (speech.unavailable) Text("La voz en español no está disponible en este dispositivo.",
            color = errorColors.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Portrait(character: ErrorCharacter, modifier: Modifier, animate: Boolean) {
    val art = character.art()
    val movement = remember(character) { Animatable(0f) }
    if (animate) LaunchedEffect(character) {
        movement.animateTo(0f, keyframes {
            durationMillis = 1200
            0f at 0
            1f at 320
            -0.55f at 680
            0.18f at 980
        })
    }
    Box(modifier.aspectRatio(1.25f).clip(RoundedCornerShape(14.dp)).background(errorBackground)) {
        Image(painterResource(art.image), contentDescription = null,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val value = movement.value
                when (character) {
                    ErrorCharacter.Confused -> rotationZ = value * 3f
                    ErrorCharacter.Guardian -> translationY = value * 3.dp.toPx()
                    ErrorCharacter.Protector -> translationX = value * 3.dp.toPx()
                    ErrorCharacter.Detective -> { rotationZ = value * -2f; translationY = value * -3.dp.toPx() }
                    ErrorCharacter.Overloaded -> { rotationZ = value * 1.5f; translationX = value * 3.dp.toPx() }
                    ErrorCharacter.Sleeper -> { scaleX = 1f + value * 0.028f; scaleY = scaleX }
                }
            }, contentScale = ContentScale.Crop)
    }
}
