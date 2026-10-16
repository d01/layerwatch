package app.layerwatch.protocol.job

/** What a stretch of extrusion is, from the slicer's `; FEATURE:` / `;TYPE:` comments. */
enum class Feature {
    OuterWall, InnerWall, OverhangWall, SparseInfill, InternalSolidInfill, TopSurface, BottomSurface,
    Bridge, InternalBridge, GapFill, Ironing, Support, SupportInterface, Brim, Skirt, PrimeTower, Custom, Other;

    companion object {
        /** Maps OrcaSlicer / Bambu Studio / PrusaSlicer feature names. */
        fun parse(name: String): Feature = when (name.trim().lowercase()) {
            "outer wall", "external perimeter" -> OuterWall
            "inner wall", "perimeter" -> InnerWall
            "overhang wall", "overhang perimeter" -> OverhangWall
            "sparse infill", "internal infill" -> SparseInfill
            "internal solid infill", "solid infill" -> InternalSolidInfill
            "top surface", "top solid infill" -> TopSurface
            "bottom surface" -> BottomSurface
            "bridge", "bridge infill" -> Bridge
            "internal bridge" -> InternalBridge
            "gap infill", "gap fill" -> GapFill
            "ironing" -> Ironing
            "support", "support material" -> Support
            "support interface", "support material interface", "support transition" -> SupportInterface
            "brim" -> Brim
            "skirt", "skirt/brim" -> Skirt
            "prime tower", "wipe tower" -> PrimeTower
            "custom" -> Custom
            else -> Other
        }
    }
}

/** One printed layer. Paths `firstPath until firstPath + pathCount` belong to it. */
data class GcodeLayer(
    /** 1-based, matching the printer's `layer_num`. */
    val number: Int,
    val z: Float,
    val firstPath: Int,
    val pathCount: Int,
    /** Slicer's planned minutes remaining at the start of this layer (from `M73 R`). */
    val plannedRemainingMin: Int?,
    /** Filament extruded in this layer, mm, by tool (filament slot index, 0-based). */
    val extrudedMmByTool: Map<Int, Float>,
    val features: Set<Feature>,
) {
    val extrudedMm: Float get() = extrudedMmByTool.values.sum()
}

/** A filament swap that happens at the start of [layer]. */
data class ToolChange(val layer: Int, val fromTool: Int?, val toTool: Int)

/** Header facts the slicer writes at the top of the G-code. */
data class GcodeHeader(
    val generator: String?,
    val totalEstimatedSec: Int?,
    val modelPrintSec: Int?,
    val totalLayers: Int?,
    val maxZ: Float?,
)

/**
 * Toolpaths of a whole print, packed for drawing: every extruding polyline is a run of
 * (x, y) points in [points]; path `i` spans points `pathStart[i] until pathStart[i + 1]`
 * (the last ends at the array's logical end), lies in layer [pathLayer]`[i]` and is
 * feature [Feature.entries]`[pathFeature[i]]`, of object [pathObject]`[i]` (the slicer's
 * `identify_id`, or [NO_OBJECT] for purge lines, prime tower and the like). Paths are
 * stored in print order, so each layer's paths are contiguous.
 */
class GcodeModel(
    val header: GcodeHeader,
    val layers: List<GcodeLayer>,
    val points: FloatArray,
    val pointCount: Int,
    val pathStart: IntArray,
    val pathLayer: IntArray,
    val pathFeature: ByteArray,
    val pathObject: IntArray,
    val pathCount: Int,
    val toolChanges: List<ToolChange>,
    /** Extent of all extrusion: minX, minY, maxX, maxY. */
    val bounds: FloatArray,
    /** The tool loaded for the first printed layer, if the G-code says. */
    val initialTool: Int?,
) {
    fun layer(number: Int): GcodeLayer? = layers.getOrNull(number - 1)?.takeIf { it.number == number }
        ?: layers.firstOrNull { it.number == number }

    fun pathPoints(path: Int): IntRange {
        val end = if (path + 1 < pathCount) pathStart[path + 1] else pointCount
        return pathStart[path] until end
    }

    fun feature(path: Int): Feature = Feature.entries[pathFeature[path].toInt()]

    /** The object a path belongs to (its `identify_id`), or null outside any object. */
    fun objectOf(path: Int): Int? = pathObject[path].takeIf { it != NO_OBJECT }

    /** Filament still to extrude after [layer] has finished, mm by tool. */
    fun extrudedAfter(layer: Int): Map<Int, Float> {
        val out = HashMap<Int, Float>()
        for (l in layers) if (l.number > layer) for ((t, mm) in l.extrudedMmByTool) out[t] = (out[t] ?: 0f) + mm
        return out
    }

    /** First layer after [layer] that prints [feature], if any. */
    fun nextLayerWith(feature: Feature, after: Int): GcodeLayer? =
        layers.firstOrNull { it.number > after && feature in it.features }

    /** Last layer that prints [feature], if any. */
    fun lastLayerWith(feature: Feature): GcodeLayer? = layers.lastOrNull { feature in it.features }

    companion object {
        const val NO_OBJECT = -1
    }
}
