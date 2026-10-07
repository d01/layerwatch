package app.bambumon.ui.details

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.bambumon.protocol.job.Feature
import app.bambumon.protocol.job.GcodeModel
import app.bambumon.ui.components.Format
import app.bambumon.ui.theme.LocalBambuType
import app.bambumon.ui.theme.Palette
import kotlin.math.min

/** Feature colours shared by the layer and 3D views and their legends. */
object FeatureColors {
    val earlier = Color(0xFF262B30)
    fun of(f: Feature): Color = when (f) {
        Feature.OuterWall -> Color(0xFFF4B04A)
        Feature.InnerWall -> Color(0xFFB8925A)
        Feature.OverhangWall -> Color(0xFFFF7A59)
        Feature.SparseInfill -> Color(0xFF4F6B88)
        Feature.InternalSolidInfill -> Color(0xFF7E95AD)
        Feature.TopSurface -> Color(0xFFEDEFF1)
        Feature.BottomSurface -> Color(0xFFB0BEC5)
        Feature.Bridge, Feature.InternalBridge -> Color(0xFF4DB6AC)
        Feature.GapFill -> Color(0xFF9C8AB4)
        Feature.Ironing -> Color(0xFFD7DCE0)
        Feature.Support, Feature.SupportInterface -> Color(0xFF6B7280)
        else -> Color(0xFF5C646C)
    }

    fun label(f: Feature): String = when (f) {
        Feature.OuterWall -> "Outer wall"
        Feature.InnerWall -> "Inner wall"
        Feature.OverhangWall -> "Overhang"
        Feature.SparseInfill -> "Sparse infill"
        Feature.InternalSolidInfill -> "Solid infill"
        Feature.TopSurface -> "Top surface"
        Feature.BottomSurface -> "Bottom surface"
        Feature.Bridge -> "Bridge"
        Feature.InternalBridge -> "Internal bridge"
        Feature.GapFill -> "Gap fill"
        Feature.Ironing -> "Ironing"
        Feature.Support -> "Support"
        Feature.SupportInterface -> "Support interface"
        Feature.Brim -> "Brim"
        Feature.Skirt -> "Skirt"
        Feature.PrimeTower -> "Prime tower"
        else -> "Other"
    }

    private val wallLike = setOf(Feature.OuterWall, Feature.InnerWall, Feature.OverhangWall)
    fun isWall(f: Feature) = f in wallLike
}

/** Builds and caches one Compose [Path] per (layer, feature) — layers are drawn many times while scrubbing. */
class LayerPaths(private val model: GcodeModel) {
    private val cache = object : LinkedHashMap<Int, Map<Feature, Path>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Map<Feature, Path>>?) = size > 160
    }

    fun of(layer: Int): Map<Feature, Path> = cache.getOrPut(layer) {
        val l = model.layer(layer) ?: return@getOrPut emptyMap()
        val out = HashMap<Feature, Path>()
        val pts = model.points
        for (p in l.firstPath until l.firstPath + l.pathCount) {
            val path = out.getOrPut(model.feature(p)) { Path() }
            val range = model.pathPoints(p)
            path.moveTo(pts[range.first * 2], pts[range.first * 2 + 1])
            for (k in range.first + 1..range.last) path.lineTo(pts[k * 2], pts[k * 2 + 1])
        }
        out
    }
}

/** Top view of one layer: its paths by feature over faint outlines of the layers below. */
@Composable
fun LayerCanvas(model: GcodeModel, paths: LayerPaths, layer: Int, modifier: Modifier = Modifier) {
    val b = model.bounds
    Canvas(modifier.semantics { contentDescription = "Top view of layer $layer toolpaths" }) {
        val pad = 12.dp.toPx()
        val w = b[2] - b[0]
        val h = b[3] - b[1]
        if (w <= 0f || h <= 0f) return@Canvas
        val scale = min((size.width - 2 * pad) / w, (size.height - 2 * pad) / h)
        val ox = (size.width - w * scale) / 2
        val oy = (size.height - h * scale) / 2
        withTransform({
            translate(ox, oy + h * scale)
            scale(scale, -scale, pivot = androidx.compose.ui.geometry.Offset.Zero) // printer Y points up
            translate(-b[0], -b[1])
        }) {
            val px = 1f / scale
            // Earlier layers' outer walls, thinned out to ~40 outlines.
            val step = maxOf(1, layer / 40)
            var k = layer - step
            while (k >= 1) {
                paths.of(k)[Feature.OuterWall]?.let { drawPath(it, FeatureColors.earlier, style = Stroke(width = 1f * px)) }
                k -= step
            }
            val current = paths.of(layer)
            // Infill first, walls on top.
            current.entries.sortedBy { FeatureColors.isWall(it.key) }.forEach { (f, path) ->
                drawPath(
                    path, FeatureColors.of(f),
                    style = Stroke(width = (if (FeatureColors.isWall(f)) 2.2f else 1.3f) * px, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

@Composable
fun FeatureLegend(features: Collection<Feature>, modifier: Modifier = Modifier) {
    val t = LocalBambuType.current
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        features.sortedBy { it.ordinal }.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(10.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(FeatureColors.of(f)))
                Text(FeatureColors.label(f), style = t.caption, color = Palette.muted)
            }
        }
    }
}

/** Layer scrubber: slider with −/+ steps and a Live chip that snaps back to the printing layer. */
@Composable
fun LayerScrubber(
    layer: Int,
    liveLayer: Int,
    total: Int,
    following: Boolean,
    onLayer: (Int) -> Unit,
    onLive: () -> Unit,
    z: Float?,
) {
    val t = LocalBambuType.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StepButton("−", "Previous layer") { onLayer((layer - 1).coerceAtLeast(1)) }
            Slider(
                value = layer.toFloat(),
                onValueChange = { onLayer(it.toInt().coerceIn(1, total)) },
                valueRange = 1f..total.toFloat().coerceAtLeast(1f),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFFFFE2A8), activeTrackColor = Palette.amber,
                    inactiveTrackColor = Palette.track,
                ),
                modifier = Modifier.weight(1f),
            )
            StepButton("+", "Next layer") { onLayer((layer + 1).coerceAtMost(total)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val where = when {
                layer == liveLayer -> "now printing"
                layer < liveLayer -> "printed"
                else -> "to print"
            }
            Text(
                "Layer $layer" + (z?.let { " · z %.1f".format(it) } ?: "") + " · $where",
                style = t.mono, color = Palette.text, modifier = Modifier.weight(1f),
            )
            val chipColor = if (following) Palette.amberText else Palette.muted
            Row(
                Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(999.dp))
                    .background(if (following) Palette.amberSoft else Palette.cardRaised)
                    .clickable(role = Role.Button, onClickLabel = "Follow the printing layer", onClick = onLive)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(if (following) Palette.amber else Palette.faint))
                Text("Live", style = t.caption, color = chipColor)
            }
        }
    }
}

@Composable
private fun StepButton(label: String, description: String, onClick: () -> Unit) {
    val t = LocalBambuType.current
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Palette.cardRaised)
            .border(1.dp, Palette.outline, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = t.section, color = Palette.text) }
}

data class Upcoming(val feature: Feature, val text: String, val minutes: Int?)

/** What the G-code says happens next, with the slicer's planned time to each. */
fun upcoming(model: GcodeModel, current: Int): List<Upcoming> {
    val rNow = model.layer(current)?.plannedRemainingMin
    fun eta(layer: Int) = model.layer(layer)?.plannedRemainingMin?.let { r -> rNow?.minus(r) }?.takeIf { it >= 0 }
    val out = mutableListOf<Upcoming>()
    model.nextLayerWith(Feature.InternalSolidInfill, current)?.takeIf { Feature.InternalSolidInfill !in (model.layer(current)?.features ?: emptySet()) }
        ?.let { out += Upcoming(Feature.InternalSolidInfill, "Solid infill begins · layer ${it.number}", eta(it.number)) }
    model.nextLayerWith(Feature.OverhangWall, current)?.let {
        val last = model.lastLayerWith(Feature.OverhangWall)!!.number
        out += Upcoming(Feature.OverhangWall, (if (it.number == last) "Last overhang wall" else "Overhang walls") + " · layer ${it.number}", eta(it.number))
    }
    model.nextLayerWith(Feature.Bridge, current)?.let { out += Upcoming(Feature.Bridge, "Bridging · layer ${it.number}", eta(it.number)) }
    model.lastLayerWith(Feature.SparseInfill)?.takeIf { it.number > current }
        ?.let { out += Upcoming(Feature.SparseInfill, "Infill ends · layer ${it.number}", eta(it.number)) }
    model.nextLayerWith(Feature.TopSurface, current)?.let { out += Upcoming(Feature.TopSurface, "Top surface · layer ${it.number}", eta(it.number)) }
    model.toolChanges.firstOrNull { it.layer > current }?.let { out += Upcoming(Feature.Other, "Filament change · layer ${it.layer}", eta(it.layer)) }
    model.lastLayerWith(Feature.OuterWall)?.takeIf { it.number > current }
        ?.let { out += Upcoming(Feature.OuterWall, "Final wall · layer ${it.number}", eta(it.number)) }
    return out.sortedBy { it.minutes ?: Int.MAX_VALUE }
}

@Composable
fun ComingUpCard(items: List<Upcoming>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val t = LocalBambuType.current
    app.bambumon.ui.components.Panel(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Coming up", style = t.section, color = Palette.text, modifier = Modifier.padding(bottom = 6.dp))
        items.forEach { u ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(10.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(FeatureColors.of(u.feature)))
                Text(u.text, style = t.label, color = Palette.text, modifier = Modifier.weight(1f))
                Text(u.minutes?.let { "~" + Format.duration(it) } ?: "", style = t.mono, color = Palette.muted)
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.hairline))
        }
    }
}
