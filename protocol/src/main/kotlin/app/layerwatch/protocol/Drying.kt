package app.layerwatch.protocol

/**
 * What to send with [PrinterCommands.startDrying]. [filament] is the material name the
 * printer shows for the run; the AMS heats to [tempC] for [hours], rotating the spools
 * when [rotateSpools] so they dry evenly.
 */
data class DryingSettings(
    val filament: String,
    val tempC: Int,
    val hours: Int,
    val rotateSpools: Boolean,
) {
    companion object {
        const val MIN_C = 45
        const val MIN_HOURS = 1
        const val MAX_HOURS = 24
        /** Temperature the AMS cools to before it reports the run finished, as Bambu Studio sends. */
        const val COOLING_C = 45

        /** Starting values for [material] on [type], capped at what the unit can reach. */
        fun suggested(material: String, type: AmsType): DryingSettings {
            val preset = presets.firstOrNull { it.material.equals(material, ignoreCase = true) } ?: presets.first()
            return DryingSettings(preset.material, preset.tempC.coerceIn(MIN_C, type.maxDryC.coerceAtLeast(MIN_C)), preset.hours, rotateSpools = false)
        }

        /** Materials offered in the picker, each with Layerwatch's default temperature and time. */
        val presets: List<Preset> = listOf(
            Preset("PLA", 55, 8),
            Preset("PETG", 65, 8),
            Preset("PLA-S", 60, 8),
            Preset("TPU", 65, 8),
            Preset("ABS", 65, 8),
            Preset("ASA", 65, 8),
            Preset("PA", 65, 12),
            Preset("PC", 65, 8),
        )
    }

    data class Preset(val material: String, val tempC: Int, val hours: Int)
}

/** Why the app won't offer to start drying a unit right now; null when it can start. */
fun dryingBlocker(unit: AmsUnit, status: PrinterStatus): String? = when {
    !unit.type.canDry -> "This AMS can't dry filament"
    unit.isDrying -> "Already drying"
    status.jobState.isActive -> "The printer is busy with a print"
    status.activeSlot != null && status.activeSlot / 4 == unit.id -> "Filament is loaded through the AMS outlet: unload it first"
    else -> null
}

/** The loaded materials in [unit], most common first, for the material picker's default. */
fun loadedMaterials(unit: AmsUnit): List<String> =
    unit.trays.mapNotNull { it.material }.groupingBy { it }.eachCount().entries
        .sortedByDescending { it.value }.map { it.key }
