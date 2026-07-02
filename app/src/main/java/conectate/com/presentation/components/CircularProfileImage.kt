package conectate.com.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import conectate.com.data.localfallback.UbuntuStorageUrl

@Composable
fun CircularProfileImage(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    showBorder: Boolean = true,
    borderColor: Color = MaterialTheme.colorScheme.primary,
    borderWidth: Dp = 2.dp,
    isVerified: Boolean = false
) {
    val context = LocalContext.current
    val resolvedImageUrl = remember(imageUrl, UbuntuStorageUrl.baseUrl) {
        UbuntuStorageUrl.rewrite(imageUrl)
    }
    val request = remember(resolvedImageUrl) {
        resolvedImageUrl?.let {
            ImageRequest.Builder(context)
                .data(it)
                .memoryCacheKey(UbuntuStorageUrl.stableCacheKey(imageUrl))
                .diskCacheKey(UbuntuStorageUrl.stableCacheKey(imageUrl))
                .crossfade(false)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .build()
        }
    }
    Box(modifier = modifier.size(size)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .then(
                    if (showBorder) {
                        Modifier.border(
                            BorderStroke(borderWidth, borderColor),
                            shape = CircleShape
                        )
                    } else {
                        Modifier
                    }
                )
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (resolvedImageUrl.isNullOrEmpty()) {
                // Placeholder icon
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Profile",
                    modifier = Modifier.size(size * 0.6f),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                // Load image with Coil
                SubcomposeAsyncImage(
                    model = request,
                    contentDescription = "Profile Image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    loading = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.size(size * 0.6f),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    },
                    error = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Error loading image",
                            modifier = Modifier.size(size * 0.6f),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                )
            }
        }
        
        if (isVerified) {
            Box(
                modifier = Modifier
                    .size(size * 0.32f)
                    .align(Alignment.BottomEnd)
                    .background(Color.White, CircleShape)
                    .padding(1.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Verificado",
                    modifier = Modifier.fillMaxSize(),
                    tint = Color(0xFF1DA1F2)
                )
            }
        }
    }
}
