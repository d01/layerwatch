package app.bambumon.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.bambumon.camera.CameraState
import app.bambumon.camera.PrinterCamera
import app.bambumon.config.PrinterConfig
import app.bambumon.printer.PrinterTls

@OptIn(UnstableApi::class)
@Composable
fun CameraScreen(config: PrinterConfig, tls: PrinterTls, onForget: () -> Unit) {
    val context = LocalContext.current
    val camera = remember(config) { PrinterCamera(context.applicationContext, tls, config) }
    val state by camera.state.collectAsStateWithLifecycle()
    var player by remember { mutableStateOf<Player?>(null) }

    // Video only while the screen is visible; never in the background.
    LifecycleStartEffect(camera) {
        player = camera.start()
        onStopOrDispose {
            player = null
            camera.stop()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
            update = { it.player = player },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .safeDrawingPadding()
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                .padding(8.dp)
        ) {
            Text(statsLine(state), color = Color.White, style = MaterialTheme.typography.bodySmall)
            state.lastError?.let {
                Text("last error: $it", color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(
            onClick = onForget,
            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding(),
        ) { Text("Forget printer", color = Color.White) }
    }
}

private fun statsLine(s: CameraState): String = buildString {
    append(s.status.name)
    if (s.width > 0) append(" · ${s.width}×${s.height}")
    s.firstFrameMs?.let { append(" · first frame ${it}ms") }
    append(" · dropped ${s.droppedFrames} · freezes ${s.rebuffers}")
    if (s.reconnects > 0) append(" · reconnects ${s.reconnects}")
}
