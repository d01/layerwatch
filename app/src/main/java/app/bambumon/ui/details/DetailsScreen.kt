package app.bambumon.ui.details

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
import androidx.compose.runtime.remember
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
import app.bambumon.R
import app.bambumon.job.JobLoad
import app.bambumon.protocol.job.JobFile
import app.bambumon.ui.components.ControlButton
import app.bambumon.ui.components.ControlStyle
import app.bambumon.ui.components.Format
import app.bambumon.ui.components.Glyph
import app.bambumon.ui.components.Panel
import app.bambumon.ui.theme.LocalBambuType
import app.bambumon.ui.theme.Palette

/** Print details: build view, schedule, filament forecast, slicing. */
@Composable
fun DetailsScreen(onBack: () -> Unit, vm: DetailsViewModel = viewModel()) {
    BackHandler(onBack = onBack)
    DisposableEffect(vm) {
        val release = vm.acquireConnection()
        onDispose(release)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val wide = currentWindowAdaptiveInfo().windowSizeClass.minWidthDp >= 600

    Column(Modifier.fillMaxSize().background(Palette.ground).safeDrawingPadding()) {
        Header(state, onBack, Modifier.padding(start = if (wide) 20.dp else 16.dp, end = 20.dp, top = 8.dp))
        when (val load = state.load) {
            is JobLoad.Ready -> {
                val images = rememberImages(load.job)
                if (wide) Wide(state, load.job, images) else Narrow(state, load.job, images)
            }
            else -> LoadingOrUnavailable(load, vm::retry)
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
            }.joinToString(" · ").ifBlank { s?.let(Format::phase) ?: "Print details" }
            Text(eyebrow.uppercase(), style = t.eyebrow, color = Palette.amber)
            Text(s?.jobName ?: (state.load as? JobLoad.Ready)?.key?.name ?: "—", style = t.jobName, color = Palette.text, maxLines = 1)
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
                    app.bambumon.ui.components.Bar(frac, Palette.amber, height = 6.dp)
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
private fun Wide(state: DetailsState, job: JobFile, images: JobImages) {
    Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        BuildPanel(state, job, images, Modifier.weight(1.22f).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ScheduleCard(state.schedule, Modifier.fillMaxWidth())
            FilamentCard(state.filament, job, state.status, Modifier.fillMaxWidth())
            SlicingCard(job, images.render, Modifier.fillMaxWidth(), full = true)
        }
    }
}

@Composable
private fun Narrow(state: DetailsState, job: JobFile, images: JobImages) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BuildPanel(state, job, images, Modifier.fillMaxWidth().aspectRatio(0.86f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScheduleCard(state.schedule, Modifier.weight(1f), compact = true)
            FilamentCard(state.filament, job, state.status, Modifier.weight(1f), compact = true)
        }
        SlicingCard(job, images.render, Modifier.fillMaxWidth(), full = true)
    }
}

/** Build view panel. Phases 3–4 put the layer and 3D views here; for now, the slicer's render. */
@Composable
private fun BuildPanel(state: DetailsState, job: JobFile, images: JobImages, modifier: Modifier) {
    Panel(modifier, padding = 14.dp) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Palette.ground), contentAlignment = Alignment.Center) {
            images.render?.let { Image(it, "Slicer render of the plate", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
    }
}
