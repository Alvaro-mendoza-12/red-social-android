package conectate.com.presentation.live

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal object LivePalette {
    val Background = Color.Black
    val DeepSurface = Color(0xFF050506)
    val Surface = Color(0xFF171719)
    val SurfaceElevated = Color(0xFF242426)
    val Border = Color(0xFF3A3A3D)
    val Purple = Color(0xFFFE2C55)
    val Cyan = Color(0xFF25F4EE)
    val Red = Color(0xFFFE2C55)
    val Pink = Color(0xFFFE2C55)
    val Gold = Color(0xFFFACC15)
    val Muted = Color(0xFF9CA3AF)
    val White = Color.White
}

internal val LivePrimaryGradient = Brush.horizontalGradient(
    listOf(Color(0xFFFF2D55), Color(0xFFFE2C55))
)

internal data class LiveResponsiveMetrics(
    val horizontalPadding: Dp,
    val topAvatarSize: Dp,
    val actionSize: Dp,
    val composerHeight: Dp,
    val chatHeight: Dp,
    val compact: Boolean,
    val horizontalActions: Boolean
)

internal fun liveMetrics(width: Dp, height: Dp): LiveResponsiveMetrics {
    val compact = width < 360.dp || height < 700.dp
    return LiveResponsiveMetrics(
        horizontalPadding = if (compact) 10.dp else 16.dp,
        topAvatarSize = if (compact) 32.dp else 36.dp,
        actionSize = if (compact) 38.dp else 44.dp,
        composerHeight = if (compact) 56.dp else 64.dp,
        chatHeight = if (height < width) 64.dp else if (compact) 132.dp else 160.dp,
        compact = compact,
        horizontalActions = height < width
    )
}

@Composable
internal fun LiveRoundIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    tint: Color = LivePalette.White,
    containerColor: Color = LivePalette.Surface,
    borderColor: Color = LivePalette.Border
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(size.coerceAtLeast(48.dp))
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(containerColor)
                .border(1.dp, borderColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(size * 0.5f)
            )
        }
    }
}

@Composable
internal fun LiveGradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(shape)
            .background(if (enabled) LivePrimaryGradient else Brush.linearGradient(listOf(Color.DarkGray, Color.Gray)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Box(Modifier.size(8.dp))
        }
        Text(
            text = text,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun LivePill(
    text: String,
    modifier: Modifier = Modifier,
    background: Color = Color.Black.copy(alpha = 0.58f),
    contentColor: Color = Color.White,
    border: BorderStroke? = null,
    leadingContent: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .then(if (border != null) Modifier.border(border, RoundedCornerShape(999.dp)) else Modifier)
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        leadingContent?.invoke(this)
        Text(
            text = text,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
