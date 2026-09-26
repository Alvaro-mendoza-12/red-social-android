package conectate.com.presentation.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import conectate.com.R

enum class SocialPlatform(
    val key: String,
    val label: String,
    val placeholder: String,
    @param:DrawableRes val iconRes: Int,
) {
    INSTAGRAM(
        key = "instagram",
        label = "Instagram",
        placeholder = "https://instagram.com/usuario",
        iconRes = R.drawable.fontawesome_ic_instagram_brands,
    ),
    TIKTOK(
        key = "tiktok",
        label = "TikTok",
        placeholder = "https://tiktok.com/@usuario",
        iconRes = R.drawable.fontawesome_ic_tiktok_brands,
    ),
    FACEBOOK(
        key = "facebook",
        label = "Facebook",
        placeholder = "https://facebook.com/usuario",
        iconRes = R.drawable.fontawesome_ic_facebook_f_brands,
    ),
    X(
        key = "x",
        label = "X / Twitter",
        placeholder = "https://x.com/usuario",
        iconRes = R.drawable.novvin_ic_x_brand,
    ),
    WHATSAPP(
        key = "whatsapp",
        label = "WhatsApp",
        placeholder = "Código de país + número",
        iconRes = R.drawable.fontawesome_ic_whatsapp_brands,
    );

    companion object {
        fun fromKey(key: String): SocialPlatform? = entries.firstOrNull { it.key == key.lowercase() }
    }
}

@Composable
fun SocialBrandLogo(
    platform: SocialPlatform,
    modifier: Modifier = Modifier,
    size: Dp = 38.dp,
    contentDescription: String? = platform.label,
) {
    val shape = RoundedCornerShape(size * 0.3f)
    val background = when (platform) {
        SocialPlatform.INSTAGRAM -> Modifier.background(
            Brush.linearGradient(
                listOf(Color(0xFFFEDA75), Color(0xFFFA7E1E), Color(0xFFD62976), Color(0xFF962FBF), Color(0xFF4F5BD5))
            )
        )
        SocialPlatform.FACEBOOK -> Modifier.background(Color(0xFF1877F2))
        SocialPlatform.TIKTOK -> Modifier.background(Color.Black)
        SocialPlatform.X -> Modifier.background(Color(0xFF0A0A0A))
        SocialPlatform.WHATSAPP -> Modifier.background(Color(0xFF25D366))
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .then(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(platform.iconRes),
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(size * 0.58f),
        )
    }
}
