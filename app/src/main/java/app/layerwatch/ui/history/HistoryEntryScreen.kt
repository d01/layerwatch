package app.layerwatch.ui.history

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
import androidx.compose.runtime.produceState
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
import app.layerwatch.LayerwatchApp
import app.layerwatch.R
import app.layerwatch.history.Outcome
import app.layerwatch.history.PrintRecord
import app.layerwatch.history.Download
import app.layerwatch.history.TimelapseDownloadJob
import app.layerwatch.history.TimelapseRef
import app.layerwatch.history.saveToMovies
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.layerwatch.ui.components.Bar
import app.layerwatch.ui.components.DetailsLink
import app.layerwatch.ui.details.ArchivedDetailsScreen
import app.layerwatch.ui.components.ControlButton
import app.layerwatch.ui.components.ControlStyle
import app.layerwatch.ui.components.Format
import app.layerwatch.ui.components.Glyph
import app.layerwatch.ui.components.Panel
import app.layerwatch.ui.theme.LocalBambuType
import app.layerwatch.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface Lapse {
    data object Searching : Lapse
    data class None(val why: String) : Lapse
    data class Found(val ref: TimelapseRef, val thumb: ImageBitmap?) : Lapse
}

/** One print: its timelapse from the printer (play / save), final snapshot and summary. */
@OptIn(UnstableApi::class)
@Composable
fun HistoryEntryScreen(record: PrintRecord, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val t = LocalBambuType.current
    val context = LocalContext.current
    val app = context.applicationContext as LayerwatchApp
    val scope = rememberCoroutineScope()
    var lapse by remember(record.key) { mutableStateOf<Lapse>(Lapse.Searching) }
    var playing by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    val hasDetails by produceState(false, record) { value = app.jobs.hasArchived(record) }
    if (showDetails && hasDetails) {
        ArchivedDetailsScreen(record, onBack = { showDetails = false })
        return
    }
    val snapshot = remember(record) {
        app.history.snapshot(record)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
    }

    // The printer connection must be up for FTPS; hold it while this page is open.
    DisposableEffect(Unit) {
        val release = app.printer.acquire()
        onDispose(release)
    }
    LaunchedEffect(record.key, record.timelapse) {
        val name = record.timelapse ?: run {
            lapse = Lapse.None(
                if (record.outcome == Outcome.InProgress) "The timelapse is linked once the printer writes it, as the print ends."
                else "No timelapse linked to this print. Layerwatch links it when it sees the print end; timelapse may have been off.",
            )
            return@LaunchedEffect
        }
        lapse = runCatching { app.timelapses.find(name) }.fold(
            onSuccess = { ref ->
                if (ref == null) Lapse.None("This print's timelapse ($name) is no longer on the printer.")
                else Lapse.Found(ref, app.timelapses.thumbnail(ref)?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() })
            },
            onFailure = { Lapse.None("Couldn't reach the printer's storage: ${it.message}") },
        )
    }

    // Downloads live in the app (a user-initiated job), not this screen: leaving doesn't stop them.
    val downloads by app.timelapses.downloads.collectAsStateWithLifecycle()
    var wantPlay by remember { mutableStateOf(false) }
    val found = lapse as? Lapse.Found
    val cached = found?.ref?.let { app.timelapses.cachedFile(it) }
    val dl: Download? = found?.let { downloads[it.ref.name] } ?: cached?.let { Download.Done(it) }
    val ready = (dl as? Download.Done)?.file
    LaunchedEffect(ready, wantPlay) { if (ready != null && wantPlay) playing = true }

    fun play() {
        val ref = found?.ref ?: return
        wantPlay = true
        if (ready == null && dl !is Download.Running) TimelapseDownloadJob.start(context, ref, saveWhenDone = false)
    }

    fun save() {
        val ref = found?.ref ?: return
        if (ready != null) scope.launch { saved = if (withContext(Dispatchers.IO) { saveToMovies(context, ready) }) "Saved to Movies" else "Save failed" }
        else { TimelapseDownloadJob.start(context, ref, saveWhenDone = true); saved = "Saving when downloaded" }
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
                if (playing && ready != null) {
                    VideoPlayer(ready)
                } else {
                    val poster = found?.thumb ?: snapshot
                    poster?.let { Image(it, "Final snapshot", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    if (found != null) {
                        Box(
                            Modifier.align(Alignment.Center).size(64.dp).clip(CircleShape).background(Palette.ground.copy(alpha = 0.72f))
                                .clickable(role = Role.Button, onClickLabel = "Play timelapse") { play() },
                            contentAlignment = Alignment.Center,
                        ) { Glyph(R.drawable.ic_play, Palette.text, 24.dp) }
                    }
                }
            }
            when (val l = lapse) {
                Lapse.Searching -> Text("Looking for the timelapse on the printer…", style = t.caption, color = Palette.muted)
                is Lapse.None -> Text(l.why, style = t.caption, color = Palette.muted)
                is Lapse.Found -> {
                    when (dl) {
                        is Download.Running -> {
                            Bar((dl.bytes.toFloat() / dl.total.coerceAtLeast(1)).coerceIn(0f, 1f), Palette.amber, height = 6.dp)
                            Text(
                                "Downloading %.1f of %.1f MB · keeps going if you leave".format(dl.bytes / 1e6, dl.total / 1e6),
                                style = t.caption, color = Palette.muted,
                            )
                        }
                        is Download.Failed -> {
                            Text("Download stopped: ${dl.message}", style = t.caption, color = Palette.errorText)
                            ControlButton("Retry download", ControlStyle.Secondary, { play() }, Modifier.fillMaxWidth(), minHeight = 44.dp) {}
                        }
                        else -> Unit
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ControlButton("Play timelapse", ControlStyle.Primary, { play() }, Modifier.weight(1f), minHeight = 48.dp) {}
                        ControlButton(saved ?: "Save to phone", ControlStyle.Secondary, { save() }, Modifier.weight(1f), minHeight = 48.dp, enabled = saved == null) {}
                    }
                    Text(
                        "%.1f MB on the printer".format(l.ref.size / 1e6) + if (ready != null) " · downloaded" else "",
                        style = t.caption, color = Palette.faint,
                    )
                }
            }

            Panel(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (hasDetails) {
                    DetailsLink { showDetails = true }
                }
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
    Outcome.Stopped -> "Stopped" to Palette.muted
    Outcome.InProgress -> "In progress" to Palette.amber
    Outcome.Unknown -> "Ended" to Palette.muted
}
