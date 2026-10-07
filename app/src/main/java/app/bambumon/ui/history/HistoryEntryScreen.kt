package app.bambumon.ui.history

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import app.bambumon.BambumonApp
import app.bambumon.R
import app.bambumon.history.Outcome
import app.bambumon.history.PrintRecord
import app.bambumon.history.TimelapseRef
import app.bambumon.ui.components.Bar
import app.bambumon.ui.components.ControlButton
import app.bambumon.ui.components.ControlStyle
import app.bambumon.ui.components.Format
import app.bambumon.ui.components.Glyph
import app.bambumon.ui.components.Panel
import app.bambumon.ui.theme.LocalBambuType
import app.bambumon.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Lapse {
    data object Searching : Lapse
    data class None(val why: String) : Lapse
    data class Found(val ref: TimelapseRef, val thumb: ImageBitmap?) : Lapse
    data class Downloading(val ref: TimelapseRef, val bytes: Long) : Lapse
    data class Ready(val ref: TimelapseRef, val file: File) : Lapse
}

/** One print: its timelapse from the printer (play / save), final snapshot and summary. */
@OptIn(UnstableApi::class)
@Composable
fun HistoryEntryScreen(record: PrintRecord, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val t = LocalBambuType.current
    val context = LocalContext.current
    val app = context.applicationContext as BambumonApp
    val scope = rememberCoroutineScope()
    var lapse by remember(record.key) { mutableStateOf<Lapse>(Lapse.Searching) }
    var playing by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    val snapshot = remember(record) {
        app.history.snapshot(record)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
    }

    // The printer connection must be up for FTPS; hold it while this page is open.
    DisposableEffect(Unit) {
        val release = app.printer.acquire()
        onDispose(release)
    }
    LaunchedEffect(record.key) {
        lapse = runCatching { app.timelapses.find(record.key) }.fold(
            onSuccess = { ref ->
                if (ref == null) Lapse.None("No timelapse for this print on the printer. Timelapse may have been off.")
                else Lapse.Found(ref, app.timelapses.thumbnail(ref)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() })
            },
            onFailure = { Lapse.None("Couldn't reach the printer's storage: ${it.message}") },
        )
    }

    fun fetch(then: (File) -> Unit) {
        val ref = when (val l = lapse) {
            is Lapse.Found -> l.ref
            is Lapse.Ready -> return then(l.file)
            else -> return
        }
        scope.launch {
            runCatching {
                app.timelapses.video(ref) { n -> lapse = Lapse.Downloading(ref, n) }
            }.onSuccess { f -> lapse = Lapse.Ready(ref, f); then(f) }
                .onFailure { lapse = Lapse.None("Download failed: ${it.message}") }
        }
    }

    Column(
        Modifier.fillMaxSize().background(Palette.ground).safeDrawingPadding().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Palette.card).clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Glyph(R.drawable.ic_back, Palette.text, 18.dp, description = "Back") }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val (label, color) = outcomeLabel(record.outcome)
                Text(label.uppercase(), style = t.eyebrow, color = color)
                Text(record.jobName ?: "Untitled print", style = t.jobName, color = Palette.text, maxLines = 1)
            }
        }

        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1168f / 720f).clip(RoundedCornerShape(20.dp)).background(androidx.compose.ui.graphics.Color.Black)) {
                val l = lapse
                if (playing && l is Lapse.Ready) {
                    VideoPlayer(l.file)
                } else {
                    val poster = (l as? Lapse.Found)?.thumb ?: snapshot
                    poster?.let { Image(it, "Final snapshot", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    if (l is Lapse.Found || l is Lapse.Ready) {
                        Box(
                            Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Palette.ground.copy(alpha = 0.72f))
                                .clickable(role = Role.Button, onClickLabel = "Play timelapse") { fetch { playing = true } },
                            contentAlignment = Alignment.Center,
                        ) { Glyph(R.drawable.ic_play, Palette.text, 24.dp) }
                    }
                }
            }
            when (val l = lapse) {
                Lapse.Searching -> Text("Looking for the timelapse on the printer…", style = t.caption, color = Palette.muted)
                is Lapse.None -> Text(l.why, style = t.caption, color = Palette.muted)
                is Lapse.Downloading -> {
                    Bar((l.bytes.toFloat() / l.ref.size).coerceIn(0f, 1f), Palette.amber, height = 6.dp)
                    Text("Downloading %.1f of %.1f MB · once, then plays offline".format(l.bytes / 1e6, l.ref.size / 1e6), style = t.caption, color = Palette.muted)
                }
                is Lapse.Found, is Lapse.Ready -> {
                    val ref = (l as? Lapse.Found)?.ref ?: (l as Lapse.Ready).ref
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ControlButton("Play timelapse", ControlStyle.Primary, { fetch { playing = true } }, Modifier.weight(1f), minHeight = 48.dp) {}
                        ControlButton(saved ?: "Save to phone", ControlStyle.Secondary, {
                            fetch { f -> scope.launch { saved = if (saveToMovies(context, f)) "Saved to Movies" else "Save failed" } }
                        }, Modifier.weight(1f), minHeight = 48.dp, enabled = saved == null) {}
                    }
                    Text(
                        "%.1f MB on the printer".format(ref.size / 1e6) + if (ref.certain) "" else " · latest timelapse; this print's file couldn't be confirmed",
                        style = t.caption, color = Palette.faint,
                    )
                }
            }

            Panel(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                summaryRows(record).forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Text(k, style = t.label, color = Palette.muted, modifier = Modifier.weight(1f))
                        Text(v, style = t.mono, color = Palette.text)
                    }
                    Box(Modifier.fillMaxWidth().size(height = 1.dp, width = 0.dp).background(Palette.hairline))
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(file: File) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false) } },
        modifier = Modifier.fillMaxSize(),
    )
}

private fun summaryRows(r: PrintRecord): List<Pair<String, String>> = listOfNotNull(
    "Followed" to Format.clockTime(r.firstSeenAtMs) + " – " + (r.endedAtMs?.let(Format::clockTime) ?: "…"),
    r.endedAtMs?.let { "Duration followed" to Format.duration(((it - r.firstSeenAtMs) / 60_000).toInt()) },
    r.totalLayers?.let { "Layers" to "$it" },
    r.printError?.let { "Print error" to it },
)

private fun outcomeLabel(o: Outcome) = when (o) {
    Outcome.Finished -> "Finished" to Palette.blue
    Outcome.Failed -> "Failed" to Palette.danger
    Outcome.InProgress -> "In progress" to Palette.amber
    Outcome.Unknown -> "Ended" to Palette.muted
}

/** Copies a downloaded timelapse into Movies/Bambumon (no storage permission needed). */
private suspend fun saveToMovies(context: Context, file: File): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Bambumon")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values))
        resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear()
        values.put(MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    }.getOrDefault(false)
}
