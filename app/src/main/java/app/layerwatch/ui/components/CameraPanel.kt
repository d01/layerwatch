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
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.asImageBitmap
import app.layerwatch.protocol.cameraKind
import app.layerwatch.protocol.PrinterModel
import app.layerwatch.protocol.Liveview
import app.layerwatch.camera.Snapshot
import app.layerwatch.camera.JpegCameraFeed
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
    if (config.isDemo) return DemoCamera(modifier, radius)
    val context = LocalContext.current
    val status by (context.applicationContext as app.layerwatch.LayerwatchApp).printer.status.collectAsStateWithLifecycle()
    val liveview = status?.liveview
    if (liveview == Liveview.Disabled) return CameraMessage(
        modifier, radius, "LAN Only Liveview is off",
        "Turn it on in the printer's network settings to watch the camera here.",
    )
    if (cameraKind(PrinterModel.fromSerial(config.serial), liveview) == PrinterModel.CameraKind.Jpeg) {
        return JpegCameraPanel(config, tls, modifier, radius, crop, fullscreenIcon, onFullscreen)
    }
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

/** The demo has no camera: the demo job's slicer render, clearly labelled. */
@Composable
private fun DemoCamera(modifier: Modifier, radius: Dp) {
    val t = LocalBambuType.current
    Box(modifier.clip(RoundedCornerShape(radius)).background(Palette.well), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.drawable.demo_camera), "Demo print (simulated, no camera)",
            contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(16.dp),
        )
        Row(
            Modifier.align(Alignment.TopStart).padding(10.dp).clip(RoundedCornerShape(999.dp))
                .background(Palette.ground.copy(alpha = 0.66f)).padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Dot(Palette.blue, 7.dp)
            Text("DEMO · SIMULATED", style = t.eyebrow.copy(letterSpacing = t.eyebrow.letterSpacing * 0.75f), color = Palette.text)
        }
    }
}

/** P1/A1 camera: a still every second or two over port 6000, shown as it arrives. */
@Composable
private fun JpegCameraPanel(
    config: PrinterConfig, tls: PrinterTls, modifier: Modifier, radius: Dp, crop: Boolean,
    fullscreenIcon: Int, onFullscreen: (() -> Unit)?,
) {
    val t = LocalBambuType.current
    val feed = remember(config) { JpegCameraFeed(tls, config) }
    val state by feed.state.collectAsStateWithLifecycle()
    val frame by feed.frame.collectAsStateWithLifecycle()
    LifecycleStartEffect(feed) {
        Snapshot.viewerStarted()
        feed.start()
        onStopOrDispose {
            feed.stop()
            Snapshot.viewerStopped()
        }
    }
    Box(
        modifier.clip(RoundedCornerShape(radius)).background(Color.Black)
            .semantics { contentDescription = "Chamber camera" },
    ) {
        frame?.let {
            androidx.compose.foundation.Image(
                it.asImageBitmap(), null,
                contentScale = if (crop) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        val (dot, label) = when (state.status) {
            CameraState.Status.Playing -> Palette.amber to "LIVE · STILLS"
            CameraState.Status.Retrying -> Palette.faint to "Reconnecting camera…"
            CameraState.Status.Connecting -> Palette.faint to "Connecting camera…"
            CameraState.Status.Stopped -> Palette.faint to "Camera paused"
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

/** In place of the camera when it can't be shown, with what to do about it. */
@Composable
private fun CameraMessage(modifier: Modifier, radius: Dp, title: String, body: String) {
    val t = LocalBambuType.current
    Column(
        modifier.clip(RoundedCornerShape(radius)).background(Palette.well).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = t.label, color = Palette.text)
        Text(body, style = t.caption, color = Palette.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
