package app.layerwatch.ui.details

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.layerwatch.protocol.job.JobFile
import app.layerwatch.ui.components.Panel
import app.layerwatch.ui.theme.LocalBambuType
import app.layerwatch.ui.theme.Palette

/** One object on the plate, merged from slice info (ids) and plate info (geometry). */
private data class PlateItem(val id: Int, val name: String, val areaMm2: Double?, val layerHeight: Double?, val skipped: Boolean)

/**
 * Objects on the plate. Tap a part on the slicer's top view (or a row) to see its details;
 * the slicer's pick mask identifies the part under the finger and outlines it exactly.
 * View only.
 */
@Composable
fun ObjectsCard(job: JobFile, top: ImageBitmap?, modifier: Modifier = Modifier) {
    val t = LocalBambuType.current
    val items = remember(job) { plateItems(job) }
    if (items.isEmpty()) return
    val pick = remember(job) { job.pickImage?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    val highlight = remember(pick, selected) { selected?.let { id -> pick?.let { maskFor(it, id) } } }

    Panel(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (items.size == 1) "Object" else "Objects · ${items.size}", style = t.section, color = Palette.text, modifier = Modifier.weight(1f))
            if (top != null && pick != null) Text("tap a part for details", style = t.caption, color = Palette.muted)
        }
        if (top != null) {
            Box(
                Modifier.fillMaxWidth().widthIn(max = 420.dp).aspectRatio(1f).align(Alignment.CenterHorizontally)
                    .clip(RoundedCornerShape(14.dp)).background(Palette.well)
                    .semantics { contentDescription = "Top view of the plate. Tap a part to select it." }
                    .pointerInput(pick, items) {
                        detectTapGestures { pos ->
                            val p = pick ?: return@detectTapGestures
                            val x = (pos.x / size.width * p.width).toInt().coerceIn(0, p.width - 1)
                            val y = (pos.y / size.height * p.height).toInt().coerceIn(0, p.height - 1)
                            val c = p.getPixel(x, y)
                            if (android.graphics.Color.alpha(c) < 128) { selected = null; return@detectTapGestures }
                            val id = JobFile.pickObjectId(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c))
                            selected = id.takeIf { i -> items.any { it.id == i } }
                        }
                    },
            ) {
                Image(top, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                highlight?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            }
        }
        items.forEach { o ->
            val on = o.id == selected
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .background(if (on) Palette.amberSoft else Palette.cardRaised)
                    .clickable(role = Role.Button, onClickLabel = "Select ${o.name}") { selected = if (on) null else o.id }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(if (on) Palette.amber else Palette.idleBar))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(o.name, style = t.label, color = Palette.text, maxLines = 1)
                    val facts = listOfNotNull(
                        o.areaMm2?.let { "%.0f mm² footprint".format(it) },
                        o.layerHeight?.let { "%.2f mm layers".format(it) },
                        "skipped".takeIf { o.skipped },
                    ).joinToString(" · ")
                    if (facts.isNotEmpty()) Text(facts, style = t.caption, color = if (o.skipped) Palette.danger else Palette.muted)
                }
            }
        }
    }
}

private fun plateItems(job: JobFile): List<PlateItem> {
    val geometry = job.plateInfo?.objects.orEmpty()
    return job.slice?.objects.orEmpty().map { o ->
        // Slice info keys objects by identify_id; plate info by a different id, but the
        // names match (one geometry entry per object name on a plate).
        val g = geometry.firstOrNull { it.name == o.name }
        PlateItem(o.identifyId, o.name.removeSuffix(".stl").removeSuffix(".3mf").ifBlank { "Object ${o.identifyId}" }, g?.areaMm2, g?.layerHeight, o.skipped)
    }
}

/** The selected object's pixels from the pick mask, tinted amber, for overlaying the top view. */
private fun maskFor(pick: Bitmap, id: Int): ImageBitmap {
    val w = pick.width
    val h = pick.height
    val src = IntArray(w * h)
    pick.getPixels(src, 0, w, 0, 0, w, h)
    val amber = 0x73F4B04A // ~45% alpha
    for (i in src.indices) {
        val c = src[i]
        val match = (c ushr 24) >= 128 && JobFile.pickObjectId((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) == id
        src[i] = if (match) amber else 0
    }
    return Bitmap.createBitmap(src, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
}
