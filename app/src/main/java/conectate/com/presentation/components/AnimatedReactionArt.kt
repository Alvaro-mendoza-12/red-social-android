package conectate.com.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A luminous beat, a launch ring and independent sparkles; never changes the reaction count. */
@Composable
fun AnimatedReactionArt(emoji: String, progress: Float, seed: Int, modifier: Modifier = Modifier) {
    val heart = emoji in setOf("❤️", "❤", "heart", "like")
    val colors = listOf(Color(0xFFFF4E91), Color(0xFFA679FF), Color(0xFFFF6E77), Color(0xFFEF73C2))
    val color = colors[(seed and Int.MAX_VALUE) % colors.size]
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center; val u = size.minDimension
            val ring = (progress / .26f).coerceIn(0f, 1f)
            if (ring < 1f) drawCircle(color.copy(alpha = (1-ring)*.65f), u*(.12f+ring*.47f), c, style = Stroke(u*.025f))
            if (heart) {
                val pulse = 1f + sin(progress * PI.toFloat() * 8f) * .045f
                val w=u*.76f*pulse; val x=c.x-w*.5f; val y=c.y-w*.4f
                val path = Path().apply { moveTo(c.x,y+w*.22f); cubicTo(x-w*.08f,y-w*.15f,x-w*.08f,y+w*.47f,c.x,y+w*.83f); cubicTo(x+w*1.08f,y+w*.47f,x+w*1.08f,y-w*.15f,c.x,y+w*.22f); close() }
                drawCircle(Brush.radialGradient(listOf(color.copy(alpha=.35f),Color.Transparent)),u*.52f,c)
                drawPath(path,Brush.linearGradient(listOf(Color(0xFFFFC3E1),color,Color(0xFFBA2871)),Offset(x,y),Offset(x+w,y+w)))
                drawOval(Color.White.copy(alpha=.65f),Offset(x+w*.15f,y+w*.13f),androidx.compose.ui.geometry.Size(w*.12f,w*.07f))
            }
            repeat(3) { i ->
                val a = progress*4f+i*2.094f+(seed%11); val r=u*(.33f+sin(progress*PI.toFloat())*.18f)
                val p=Offset(c.x+cos(a)*r,c.y+sin(a)*r);val s=u*.04f*(1-progress).coerceAtLeast(.1f)
                val star=Path().apply { moveTo(p.x,p.y-s*2);lineTo(p.x+s*.5f,p.y-s*.5f);lineTo(p.x+s*2,p.y);lineTo(p.x+s*.5f,p.y+s*.5f);lineTo(p.x,p.y+s*2);lineTo(p.x-s*.5f,p.y+s*.5f);lineTo(p.x-s*2,p.y);lineTo(p.x-s*.5f,p.y-s*.5f);close() }
                drawPath(star,Color(0xFFFFE1A7).copy(alpha=(1-progress).coerceIn(0f,1f)))
            }
        }
        if (!heart) Text(emoji, fontSize = 31.sp, modifier = Modifier.graphicsLayer {
            rotationZ = sin(progress*PI.toFloat()*if(emoji=="👏") 16 else 5)*if(emoji=="👏") 9f else 5f
            scaleX = 1f + sin(progress*PI.toFloat()*6)*.055f;scaleY=1f-sin(progress*PI.toFloat()*6)*.035f
        })
    }
}
