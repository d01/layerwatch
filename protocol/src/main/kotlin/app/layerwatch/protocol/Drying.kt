package app.layerwatch.protocol

/**
 * What to send with [PrinterCommands.startDrying]. [filament] is the material type the
 * printer shows for the run; the AMS heats to [tempC] for [hours], rotating the spools
 * when [rotateSpools] so they dry evenly, and cools to [coolingC] before finishing.
 */
data class DryingSettings(
    val filament: String,
    val tempC: Int,
    val hours: Int,
    val rotateSpools: Boolean,
    val coolingC: Int = DryingPreset.DEFAULT_COOLING_C,
) {
    companion object {
        const val MIN_HOURS = 1
        const val MAX_HOURS = 24
    }
}

/**
 * Bambu's drying values for one filament product, from Bambu Studio's filament profiles
 * (bundled as `drying_presets.tsv`; refresh with tools/update_drying.py).
 */
data class DryingPreset(
    /** `filament_id`, as AMS trays report it in `tray_info_idx` (e.g. GFA00). */
    val id: String,
    val name: String,
    /** Material type, sent as the command's `filament`. */
    val type: String,
    private val idleTempC: Map<AmsType, Int>,
    private val idleHours: Map<AmsType, Int>,
    /** Above this the filament softens; also the temperature the AMS cools to before stopping. */
    val softeningC: Int,
    /** Bambu Studio won't dry above this while the filament is loaded in a feeder; 0 = no limit given. */
    val heatDistortionC: Int,
    /** AMS models Bambu says dry this filament fully; others only partly. */
    val driesFullyOn: Set<AmsType>,
    /** Profile printer names with nozzle, e.g. "X1 Carbon 0.8". */
    val printers: Set<String>,
) {
    fun tempC(type: AmsType): Int? = idleTempC[type]
    fun hours(type: AmsType): Int? = idleHours[type]

    /** Bambu Studio's starting values for this filament on [type]. */
    fun settings(type: AmsType) = DryingSettings(
        filament = this.type,
        tempC = tempC(type) ?: type.minDryC,
        hours = hours(type) ?: 8,
        rotateSpools = false,
        coolingC = softeningC,
    )

    companion object {
        /** Bambu PLA Basic: what Bambu Studio assumes for empty or unknown trays. */
        const val FALLBACK_ID = "GFA00"
        /** Bambu Studio's cooling temperature when a filament has no preset. */
        const val DEFAULT_COOLING_C = 50
    }
}

object DryingPresets {
    val all: List<DryingPreset> by lazy {
        val stream = DryingPresets::class.java.getResourceAsStream("/drying_presets.tsv") ?: return@lazy emptyList()
        stream.bufferedReader().useLines { lines -> lines.filter { it.isNotEmpty() && it[0] != '#' }.mapNotNull(::parse).toList() }
    }

    private val byId by lazy { all.associateBy { it.id } }

    fun byId(id: String?): DryingPreset? = id?.let(byId::get)

    /** The filaments Bambu Studio offers for [model] with [nozzle] (e.g. "0.8"); all of them if either is unknown. */
    fun forPrinter(model: PrinterModel, nozzle: String?): List<DryingPreset> {
        if (model == PrinterModel.Unknown || nozzle == null) return all.sortedBy { it.name }
        val key = "${model.displayName} $nozzle"
        return all.filter { key in it.printers }.sortedBy { it.name }
    }

    private fun parse(line: String): DryingPreset? {
        val c = line.split('\t')
        if (c.size < 15) return null
        val n = c.subList(3, 13).map { it.toIntOrNull() ?: return null }
        val types = listOf(AmsType.Ams2Pro, AmsType.AmsHt)
        return DryingPreset(
            id = c[0], name = c[1], type = c[2],
            idleTempC = types.zip(n.subList(0, 2)).toMap(),
            idleHours = types.zip(n.subList(4, 6)).toMap(),
            softeningC = n[8],
            heatDistortionC = n[9],
            driesFullyOn = c[13].split(',').mapNotNull { mapOf("2pro" to AmsType.Ams2Pro, "ht" to AmsType.AmsHt)[it] }.toSet(),
            printers = c[14].split(',').filter { it.isNotEmpty() }.toSet(),
        )
    }
}

/** The preset for what's in [tray], falling back to PLA Basic as Bambu Studio does. */
fun dryingPresetFor(tray: Tray): DryingPreset? =
    DryingPresets.byId(tray.filamentId) ?: DryingPresets.byId(DryingPreset.FALLBACK_ID)

/** Trays with filament in the feeder (the printer reports a material for them). */
val AmsUnit.loadedTrays: List<Tray> get() = trays.filter { it.material != null }

/**
 * Bambu Studio's default: of the loaded filaments, the one needing the lowest temperature,
 * so nothing in the AMS is overheated; PLA Basic when nothing is loaded.
 */
fun defaultDryingPreset(unit: AmsUnit): DryingPreset? =
    unit.loadedTrays.mapNotNull(::dryingPresetFor).minByOrNull { it.tempC(unit.type) ?: Int.MAX_VALUE }
        ?: DryingPresets.byId(DryingPreset.FALLBACK_ID)

/** Why the app won't offer to start drying a unit right now; null when it can start. */
fun dryingBlocker(unit: AmsUnit, status: PrinterStatus): String? = when {
    !unit.type.canDry -> "This AMS can't dry filament"
    unit.isDrying -> "Already drying"
    status.jobState.isActive -> "The printer is busy with a print"
    status.activeSlot != null && status.activeSlot / 4 == unit.id -> "Filament is loaded through the AMS outlet: unload it first"
    else -> null
}

/**
 * Why [tempC] is too hot for filament left in the feeders, as Bambu Studio checks it:
 * any loaded filament whose heat-distortion temperature is below it. Null when it's fine.
 */
fun feederHeatProblem(unit: AmsUnit, tempC: Int): String? {
    val tooHot = unit.loadedTrays.mapNotNull { tray -> dryingPresetFor(tray)?.takeIf { it.heatDistortionC in 1 until tempC }?.let { tray to it } }
    if (tooHot.isEmpty()) return null
    val limit = tooHot.minOf { it.second.heatDistortionC }
    val slots = tooHot.joinToString(", ") { "slot ${it.first.slot % 4 + 1}" }
    return "Too hot for the filament in $slots (limit $limit °C): pull it back out of the feeder or lower the temperature"
}
