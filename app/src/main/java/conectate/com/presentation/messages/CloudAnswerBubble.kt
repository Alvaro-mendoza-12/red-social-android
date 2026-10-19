package conectate.com.presentation.messages

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.*
import androidx.compose.ui.text.style.TextOverflow

/** Rounded lobes are part of the shape, so the answer remains readable at larger font sizes. */
internal object AnswerCloudShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width; val h = size.height
        return Outline.Generic(Path().apply {
            moveTo(w * .17f, h * .88f)
            cubicTo(-w * .04f, h * .88f, -w * .03f, h * .32f, w * .17f, h * .32f)
            cubicTo(w * .17f, h * .04f, w * .39f, -h * .01f, w * .48f, h * .17f)
            cubicTo(w * .59f, -h * .03f, w * .85f, h * .08f, w * .83f, h * .33f)
            cubicTo(w * 1.04f, h * .30f, w * 1.04f, h * .88f, w * .83f, h * .88f)
            cubicTo(w * .72f, h * 1.01f, w * .60f, h * .95f, w * .53f, h * .91f)
            cubicTo(w * .42f, h * 1.01f, w * .26f, h * .99f, w * .17f, h * .88f)
            close()
        })
    }
}

@Composable
internal fun CloudAnswerBubble(text: String, slot: Int, moving: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val phase = rememberQuestionCloudPhase(slot, moving)
    Surface(
        onClick = onClick, shape = AnswerCloudShape, color = Color.White, contentColor = Color(0xFF20252C),
        shadowElevation = 2.dp,
        modifier = modifier.graphicsLayer {
            // Read per-frame state in the rendering layer. The text and cloud
            // shape do not need to be recomposed and measured while drifting.
            val drift = questionCloudDrift(phase.value, slot)
            translationX = drift.x.dp.toPx()
            translationY = drift.y.dp.toPx()
        }.heightIn(min = 58.dp)
    ) {
        Box(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(text, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}
