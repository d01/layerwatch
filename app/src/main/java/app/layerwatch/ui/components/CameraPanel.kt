package app.layerwatch.ui.components

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.layerwatch.R
import app.layerwatch.camera.CameraState
import app.layerwatch.camera.PrinterCamera
import app.layerwatch.config.PrinterConfig
import app.layerwatch.printer.PrinterTls
import app.layerwatch.ui.theme.LocalBambuType
import app.layerwatch.ui.theme.Palette

/** Aspect ratio of the X1C chamber stream (1168×720). */
const val CameraAspect = 1168f / 720f

/**
 * The live chamber view. Video runs only while this is on screen. During a reconnect the
 * last frame stays up with a badge rather than going black.
 */
@OptIn(UnstableApi::class)
@Composable
fun CameraPanel(
    config: PrinterConfig,
    tls: PrinterTls,
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    crop: Boolean = false,
    fullscreenIcon: Int = R.drawable.ic_fullscreen,
    onFullscreen: (() -> Unit)? = null,
) {
    val t = LocalBambuType.current
    val context = LocalContext.current
    val camera = remember(config) { PrinterCamera(context.applicationContext, tls, config) }
    val state by camera.state.collectAsStateWithLifecycle()
    var player by remember { mutableStateOf<Player?>(null) }

    LifecycleStartEffect(camera) {
        player = camera.start()
        onStopOrDispose {
            player = null
            camera.stop()
        }
    }

    Box(
        modifier.clip(RoundedCornerShape(radius)).background(Color.Black)
            .semantics { contentDescription = "Chamber camera" },
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = if (crop) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setKeepContentOnPlayerReset(true)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        val (dot, label) = when (state.status) {
            CameraState.Status.Playing -> Palette.amber to "LIVE" + (state.cushionMs.takeIf { it > 0 }?.let { " · %.1f s".format(it / 1000f) } ?: "")
            CameraState.Status.Retrying -> Palette.faint to "Reconnecting video…"
            CameraState.Status.Connecting -> Palette.faint to "Connecting video…"
            CameraState.Status.Stopped -> Palette.faint to "Video paused"
        }
        Row(
            Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(999.dp))
                .background(Palette.ground.copy(alpha = 0.66f)).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Dot(dot, 7.dp)
            Text(label, style = t.eyebrow.copy(letterSpacing = t.eyebrow.letterSpacing * 0.75f), color = Palette.text)
        }

        if (onFullscreen != null) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(10.dp).size(44.dp).clip(CircleShape)
                    .background(Palette.ground.copy(alpha = 0.66f))
                    .clickable(role = Role.Button, onClickLabel = "Toggle full screen", onClick = onFullscreen),
                contentAlignment = Alignment.Center,
            ) { Glyph(fullscreenIcon, Palette.text, 18.dp, description = "Full screen") }
        }
    }
}
