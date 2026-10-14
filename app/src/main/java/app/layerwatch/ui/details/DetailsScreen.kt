package app.layerwatch.ui.details

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.layerwatch.R
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import app.layerwatch.LayerwatchApp
import app.layerwatch.history.PrintRecord
import app.layerwatch.job.JobKey
import app.layerwatch.job.JobLoad
import app.layerwatch.protocol.job.JobFile
import app.layerwatch.ui.components.ControlButton
import app.layerwatch.ui.components.ControlStyle
import app.layerwatch.ui.components.Format
import app.layerwatch.ui.components.Glyph
import app.layerwatch.ui.components.Panel
import app.layerwatch.ui.theme.LocalBambuType
import app.layerwatch.ui.theme.Palette

/** Print details: build view, schedule, filament forecast, slicing. */
@Composable
fun DetailsScreen(onBack: () -> Unit, vm: DetailsViewModel = viewModel()) {
    BackHandler(onBack = onBack)
    DisposableEffect(vm) {
        val release = vm.acquireConnection()
        onDispose(release)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    DetailsContent(state, onBack, vm::retry, vm::skipObject)
}

/**
 * Details of a past print from its cached job file: the finished build, slicing summary
 * and objects. No live status, schedule or forecast.
 */
@Composable
fun ArchivedDetailsScreen(record: PrintRecord, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val graph = LocalContext.current.applicationContext as LayerwatchApp
    val key = JobKey(record.jobName ?: "Untitled print", 1)
    val load by produceState<JobLoad>(JobLoad.Parsing(key), record) {
        value = graph.jobs.readArchived(record)
            ?: JobLoad.Unavailable(key, "This job's file is no longer cached on the phone.")
    }
    DetailsContent(DetailsState(load = load), onBack, onRetry = {})
}

@Composable
private fun DetailsContent(state: DetailsState, onBack: () -> Unit, onRetry: () -> Unit, onSkip: (Int) -> Unit = {}) {
    val wide = currentWindowAdaptiveInfo().windowSizeClass.minWidthDp >= 600

    Column(Modifier.fillMaxSize().background(Palette.ground).safeDrawingPadding()) {
        Header(state, onBack, Modifier.padding(start = if (wide) 20.dp else 16.dp, end = 20.dp, top = 8.dp))
        when (val load = state.load) {
            is JobLoad.Ready -> {
                val images = rememberImages(load.job)
                if (wide) Wide(state, load.job, images, onSkip) else Narrow(state, load.job, images, onSkip)
            }
            else -> LoadingOrUnavailable(load, onRetry)
        }
    }
}

private class JobImages(val render: ImageBitmap?, val top: ImageBitmap?)

@Composable
private fun rememberImages(job: JobFile) = remember(job) {
    fun decode(b: ByteArray?) = b?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    JobImages(decode(job.plateImage), decode(job.topImage))
}

@Composable
private fun Header(state: DetailsState, onBack: () -> Unit, modifier: Modifier) {
    val t = LocalBambuType.current
    val s = state.status
    val model = (state.load as? JobLoad.Ready)?.job?.gcode
    val layer = s?.layer
    val z = layer?.let { model?.layer(it)?.z }
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Palette.card).clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Glyph(R.drawable.ic_back, Palette.text, 18.dp, description = "Back") }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val eyebrow = buildList {
                if (layer != null && s.totalLayers != null) add("Layer $layer / ${s.totalLayers}")
                z?.let { add("%.1f mm".format(it)) }
            }.joinToString(" · ").ifBlank { s?.let(Format::phase) ?: if (s == null && state.load is JobLoad.Ready) "Print details · finished" else "Print details" }
            Text(eyebrow.uppercase(), style = t.eyebrow, color = Palette.amber)
            Text(s?.jobName ?: (state.load as? JobLoad.Ready)?.key?.name ?: (state.load as? JobLoad.Unavailable)?.key?.name ?: "—", style = t.jobName, color = Palette.text, maxLines = 1)
        }
    }
}

@Composable
private fun LoadingOrUnavailable(load: JobLoad, onRetry: () -> Unit) {
    val t = LocalBambuType.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (load) {
                is JobLoad.Downloading -> {
                    Text("Fetching the job file from the printer", style = t.section, color = Palette.text)
                    val frac = load.total?.takeIf { it > 0 }?.let { load.bytes.toFloat() / it } ?: 0f
                    app.layerwatch.ui.components.Bar(frac, Palette.amber, height = 6.dp)
                    Text(
                        "%.1f".format(load.bytes / 1e6) + (load.total?.let { " of %.1f MB".format(it / 1e6) } ?: " MB") + " · once per print, then cached",
                        style = t.caption, color = Palette.muted,
                    )
                }
                is JobLoad.Parsing -> Text("Reading the toolpaths…", style = t.section, color = Palette.text)
                is JobLoad.Unavailable -> {
                    Text("Details aren't available", style = t.section, color = Palette.text)
                    Text(load.reason, style = t.body, color = Palette.muted)
                    ControlButton("Try again", ControlStyle.Secondary, onRetry, Modifier.fillMaxWidth(), minHeight = 48.dp) {}
                }
                JobLoad.None -> {
                    Text("No print in progress", style = t.section, color = Palette.text)
                    Text("Details appear here while the printer is printing a job sent over the network.", style = t.body, color = Palette.muted)
                }
                is JobLoad.Ready -> Unit
            }
        }
    }
}

// ---------- layouts ----------

@Composable
private fun Wide(state: DetailsState, job: JobFile, images: JobImages, onSkip: (Int) -> Unit) {
    Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        BuildPanel(state, job, images, Modifier.weight(1.22f).fillMaxHeight(), fill = true)
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.status != null) {
                ScheduleCard(state.schedule, Modifier.fillMaxWidth())
                job.gcode?.let { ComingUpCard(upcoming(it, state.currentLayer), Modifier.fillMaxWidth()) }
                FilamentCard(state.filament, job, state.status, Modifier.fillMaxWidth())
            }
            SlicingCard(job, images.render, Modifier.fillMaxWidth(), full = true)
            ObjectsCard(job, images.top, Modifier.fillMaxWidth(), state.skip, onSkip)
        }
    }
}

@Composable
private fun Narrow(state: DetailsState, job: JobFile, images: JobImages, onSkip: (Int) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BuildPanel(state, job, images, Modifier.fillMaxWidth(), fill = false)
        if (state.status != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScheduleCard(state.schedule, Modifier.weight(1f), compact = true)
                FilamentCard(state.filament, job, state.status, Modifier.weight(1f), compact = true)
            }
            job.gcode?.let { ComingUpCard(upcoming(it, state.currentLayer), Modifier.fillMaxWidth()) }
        }
        SlicingCard(job, images.render, Modifier.fillMaxWidth(), full = true)
        ObjectsCard(job, images.top, Modifier.fillMaxWidth(), state.skip, onSkip)
    }
}

/**
 * Build view panel: 3D build (phase 4; the slicer render until then) or the current
 * layer from the top, with a scrubber that follows the printing layer until moved.
 */
@Composable
private fun BuildPanel(state: DetailsState, job: JobFile, images: JobImages, modifier: Modifier, fill: Boolean) {
    val t = LocalBambuType.current
    val model = job.gcode
    var mode by rememberSaveable { mutableStateOf("3D build") }
    var pinned by rememberSaveable { mutableStateOf<Int?>(null) } // null = follow live
    val total = model?.layers?.size ?: 0
    val live = (if (state.status == null) total else state.currentLayer).coerceIn(1, total.coerceAtLeast(1))
    val layer = (pinned ?: live).coerceIn(1, total.coerceAtLeast(1))
    val paths = remember(model) { model?.let(::LayerPaths) }

    Panel(modifier, padding = 14.dp, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Segmented(listOf("3D build", "Layer"), mode, { mode = it })
        // Layer view fits the part's footprint; the 3D view needs height for the whole build.
        val aspect = if (mode == "Layer") viewAspect(model) else 0.95f
        val viewModifier = if (fill) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().aspectRatio(aspect)
        Box(viewModifier.clip(RoundedCornerShape(16.dp)).background(Palette.ground), contentAlignment = Alignment.Center) {
            if (mode == "Layer" && model != null && paths != null) {
                LayerCanvas(model, paths, layer, Modifier.fillMaxSize())
            } else if (model != null) {
                BuildView3D(model, layer, Modifier.fillMaxSize())
            } else {
                images.render?.let { Image(it, "Slicer render of the plate", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            }
        }
        if (model != null) {
            if (mode == "Layer") FeatureLegend(model.layer(layer)?.features.orEmpty()) else BuildLegend(layer)
            LayerScrubber(
                layer = layer, liveLayer = live, total = total, following = pinned == null,
                onLayer = { pinned = it.takeIf { l -> l != live } }, onLive = { pinned = null },
                z = model.layer(layer)?.z,
            )
        }
    }
}

private fun viewAspect(model: app.layerwatch.protocol.job.GcodeModel?): Float {
    val b = model?.bounds ?: return 1f
    val w = b[2] - b[0]
    val h = b[3] - b[1]
    return if (w > 0 && h > 0) (w / h).coerceIn(0.8f, 1.8f) else 1f
}

@Composable
private fun Segmented(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    val t = LocalBambuType.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.well).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (on) Palette.outline else androidx.compose.ui.graphics.Color.Transparent)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(o) }),
                contentAlignment = Alignment.Center,
            ) { Text(o, style = t.label, color = if (on) Palette.text else Palette.muted) }
        }
    }
}

@Composable
private fun BuildLegend(layer: Int) {
    val t = LocalBambuType.current
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(Palette.amber to "Printed", androidx.compose.ui.graphics.Color(0xFFFFE2A8) to "Layer $layer", androidx.compose.ui.graphics.Color(0xFF3A4046) to "Still to print").forEach { (c, l) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(width = 10.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(c))
                Text(l, style = t.caption, color = Palette.muted)
            }
        }
    }
}
