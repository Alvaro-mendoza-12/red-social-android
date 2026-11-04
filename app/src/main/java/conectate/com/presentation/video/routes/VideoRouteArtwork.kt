package conectate.com.presentation.video.routes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import conectate.com.data.localfallback.RuntimeBackendEndpoint
import conectate.com.data.localfallback.UbuntuStorageUrl
import androidx.lifecycle.compose.collectAsStateWithLifecycle

internal val RouteIndigo = Color(0xFF535BC1)
internal val RouteMint = Color(0xFFB7E5DF)
internal val RoutePeach = Color(0xFFF8D1BB)
internal val RouteSky = Color(0xFFD2DFFC)
internal val RouteLilac = Color(0xFFE2D7F6)
internal val RouteInk = Color(0xFF263149)

@Composable
internal fun routeAccent(): Color = if (MaterialTheme.colorScheme.surface.luminance() < .4f) RouteLilac else RouteIndigo

/** The drawing represents the actual number of entries and has no independent animation or downloads. */
@Composable
internal fun RouteTrailArtwork(opened: List<Boolean>, modifier: Modifier = Modifier, onColor: Color = RouteIndigo, offColor: Color = RouteIndigo) {
    Canvas(modifier) {
        if (opened.isEmpty()) return@Canvas
        val points = opened.indices.map { index ->
            Offset(size.width * (index + .5f) / opened.size, size.height * if (index % 2 == 0) .35f else .65f)
        }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.zipWithNext().forEach { (start, end) ->
                val middleX = (start.x + end.x) / 2
                cubicTo(middleX, start.y, middleX, end.y, end.x, end.y)
            }
        }
        drawPath(path, offColor.copy(alpha = .35f), style = Stroke(3.dp.toPx()))
        points.forEachIndexed { index, point ->
            drawCircle(if (opened[index]) onColor else offColor.copy(alpha = .15f), 8.dp.toPx(), point)
            drawCircle(if (opened[index]) onColor else offColor, 8.dp.toPx(), point, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
internal fun VideoRoutePoster(item: VideoRouteItem, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val endpoint by RuntimeBackendEndpoint.baseUrlFlow.collectAsStateWithLifecycle()
    val request = remember(context, item.thumbnailPath, endpoint) {
        UbuntuStorageUrl.thumbnail(item.thumbnailPath.takeIf(String::isNotBlank), width = 480)?.let { url ->
            ImageRequest.Builder(context).data(url).size(480, 640)
                .memoryCacheKey(UbuntuStorageUrl.stableCacheKey(url))
                .diskCacheKey(UbuntuStorageUrl.stableCacheKey(url))
                .crossfade(false).build()
        }
    }
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(RouteSky), contentAlignment = Alignment.Center) {
        RouteTrailArtwork(listOf(false, true, item.opened), Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp))
        if (request != null) AsyncImage(
            model = request, contentDescription = null,
            modifier = Modifier.fillMaxSize().testTag("route-poster-${item.videoId}"), contentScale = ContentScale.Crop,
        )
        Box(Modifier.size(36.dp).background(Color.White.copy(alpha = .9f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, null, tint = RouteInk, modifier = Modifier.size(24.dp))
        }
    }
}
