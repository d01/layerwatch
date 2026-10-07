package app.layerwatch.protocol.job

/**
 * Where things happen along the print, in the slicer's planned progress (0–100), for the
 * progress notification: segments coloured by the loaded filament, and points at the
 * first layer and each filament change.
 */
data class ProgressMarkers(val segments: List<Segment>, val points: List<Point>) {
    /** [tool] is the slicer filament slot (0-based) loaded for this stretch. */
    data class Segment(val fromPct: Int, val toPct: Int, val tool: Int)
    data class Point(val pct: Int, val kind: Kind, val layer: Int, val toTool: Int? = null)
    enum class Kind { FirstLayerDone, FilamentChange }

    companion object {
        fun from(model: GcodeModel): ProgressMarkers? {
            val total = model.layers.firstOrNull()?.plannedRemainingMin?.takeIf { it > 0 } ?: return null
            fun pctAt(layer: Int): Int {
                val r = model.layer(layer)?.plannedRemainingMin ?: return 0
                return ((total - r) * 100.0 / total).toInt().coerceIn(0, 100)
            }
            val points = mutableListOf<Point>()
            if (model.layers.size >= 2) points += Point(pctAt(2), Kind.FirstLayerDone, 2)
            model.toolChanges.forEach { points += Point(pctAt(it.layer), Kind.FilamentChange, it.layer, it.toTool) }

            val segments = mutableListOf<Segment>()
            var from = 0
            var tool = model.initialTool ?: model.toolChanges.firstOrNull()?.fromTool ?: 0
            for (c in model.toolChanges) {
                val at = pctAt(c.layer)
                if (at > from) segments += Segment(from, at, tool)
                from = at
                tool = c.toTool
            }
            segments += Segment(from, 100, tool)
            return ProgressMarkers(segments, points.sortedBy { it.pct })
        }

        /** Planned minutes from [currentLayer] until the next filament change, if any. */
        fun minutesToNextChange(model: GcodeModel, currentLayer: Int): Pair<ToolChange, Int>? {
            val next = model.toolChanges.firstOrNull { it.layer > currentLayer } ?: return null
            val rNow = model.layer(currentLayer)?.plannedRemainingMin ?: return null
            val rThen = model.layer(next.layer)?.plannedRemainingMin ?: return null
            return next to (rNow - rThen).coerceAtLeast(0)
        }
    }
}
