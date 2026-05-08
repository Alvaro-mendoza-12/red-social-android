package conectate.com.presentation.social.components

import androidx.compose.runtime.Composable

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.os.Vibrator
import android.os.VibrationEffect
import kotlinx.coroutines.delay

@Composable
fun ChallengeTimer(
    durationSeconds: Int,
    onTimerFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    var timeLeft by remember { mutableStateOf(durationSeconds) }
    val context = LocalContext.current
    val vibrator = remember { context.getSystemService(Vibrator::class.java) }

    LaunchedEffect(key1 = durationSeconds) {
        timeLeft = durationSeconds
        while (timeLeft > 0) {
            delay(1000L)
            timeLeft--
            
            // Vibración si es crítico
            if (timeLeft < 60) {
                vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
        onTimerFinished()
    }

    val minutes = timeLeft / 60
    val seconds = timeLeft % 60
    val timeString = "%02d:%02d".format(minutes, seconds)
    
    val isCritical = timeLeft < 60
    
    val timerColor by animateColorAsState(
        targetValue = if (isCritical) Color.Red else MaterialTheme.colorScheme.primary,
        animationSpec = tween(durationMillis = 500),
        label = "TimerColor"
    )
    
    val infiniteTransition = rememberInfiniteTransition(label = "PulseTransition")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isCritical) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseScale"
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = timeString,
            style = MaterialTheme.typography.displaySmall.copy(
                fontSize = 48.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            ),
            color = timerColor,
            modifier = Modifier.scale(scale)
        )
        Text(
            text = if (isCritical) "¡DEPRISA!" else "TIEMPO RESTANTE",
            style = MaterialTheme.typography.labelMedium,
            color = timerColor.copy(alpha = 0.7f),
            fontWeight = FontWeight.Bold
        )
    }
}
