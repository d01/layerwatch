package app.layerwatch.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.roundToInt

/** Printer-wide job state, from `gcode_state`. */
enum class JobState {
    Idle, Preparing, Slicing, Running, Paused, Finished, Failed, Unknown;

    val isActive get() = this == Preparing || this == Slicing || this == Running || this == Paused

    companion object {
        fun parse(raw: String?) = when (raw?.uppercase()) {
            "IDLE" -> Idle
            "PREPARE" -> Preparing
            "SLICING" -> Slicing
            "RUNNING" -> Running
            "PAUSE" -> Paused
            "FINISH" -> Finished
            "FAILED" -> Failed
            else -> Unknown
        }
    }
}

enum class SpeedLevel(val code: Int, val label: String, val percent: Int) {
    Silent(1, "Silent", 50), Standard(2, "Standard", 100), Sport(3, "Sport", 124), Ludicrous(4, "Ludicrous", 166);

    companion object {
        fun parse(code: Int?) = entries.firstOrNull { it.code == code }
    }
}

data class Temperature(val current: Double?, val target: Double?) {
    /** The heater is on: there's a target to reach or hold. */
    val isHeating get() = (target ?: 0.0) > 0.0

    /**
     * Where the reading is relative to the target. The printer reports no heater duty, so
     * this is inferred; [HOLD_MARGIN_C] absorbs the few degrees a held heater wanders (a
     * nozzle held at 255 reads 251 to 256).
     */
    val heatState: HeatState
        get() {
            val t = target ?: 0.0
            val c = current ?: return if (t > 0.0) HeatState.Holding else HeatState.Off
            return when {
                t <= 0.0 -> HeatState.Off
                t - c > HOLD_MARGIN_C -> HeatState.HeatingUp
                c - t > HOLD_MARGIN_C -> HeatState.CoolingDown
                else -> HeatState.Holding
            }
        }

    companion object {
        const val HOLD_MARGIN_C = 3.0
    }
}

enum class HeatState { Off, HeatingUp, Holding, CoolingDown }

/** A Health Management System alert: `attr` and `code` combine into the 16-hex-digit id. */
data class HmsAlert(val attr: Long, val code: Long) {
    /** Bambu's published form, e.g. "0300_1200_0002_0001". */
    val id: String get() = "%08X%08X".format(attr, code).chunked(4).joinToString("_")
    val severity: Int get() = ((code ushr 16) and 0xFFFF).toInt()
    val wikiUrl: String get() = "https://wiki.bambulab.com/en/x1/troubleshooting/hmscode/$id"
}

data class Tray(
    /** Global slot index as used by `tray_now`: amsId * 4 + trayId. */
    val slot: Int,
    val material: String?,
    val name: String?,
    /** RRGGBBAA, as sent by the printer. */
    val colorHex: String?,
    /** Estimated percent remaining, or null when the AMS can't tell (non-Bambu spool). */
    val remainingPercent: Int?,
    /** Full spool net weight in grams (`tray_weight`), when the RFID tag says. */
    val spoolWeightG: Int?,
) {
    /** Estimated grams left on the spool, when both remaining % and spool weight are known. */
    val remainingG: Double? get() = if (remainingPercent != null && spoolWeightG != null) spoolWeightG * remainingPercent / 100.0 else null
}

/** AMS model, from bits 0–3 of the unit's `info` (Bambu Studio's DevAmsType). */
enum class AmsType(val label: String, val canDry: Boolean, val maxDryC: Int) {
    Ams("AMS", false, 0), AmsLite("AMS Lite", false, 0), Ams2Pro("AMS 2 Pro", true, 65), AmsHt("AMS HT", true, 85), Unknown("AMS", false, 0);

    companion object {
        fun of(code: Int) = when (code) { 1 -> Ams; 2 -> AmsLite; 3 -> Ams2Pro; 4 -> AmsHt; else -> Unknown }
    }
}

/** Drying state, from bits 4–7 of `info` (Bambu Studio's DryStatus). */
enum class DryStatus(val label: String) {
    Off("Off"), Checking("Starting"), Drying("Drying"), Cooling("Cooling"), Stopping("Stopping"),
    Error("Drying error"), HeatOutOfControl("Heater fault"), Testing("Testing"), Unknown("Unknown");

    companion object {
        fun of(code: Int) = entries.getOrNull(code)?.takeIf { it != Unknown } ?: Unknown
    }
}

/**
 * Why the printer says a unit can't dry right now (`dry_sf_reason`, Bambu Studio's
 * CannotDryReason). Code 6 ("drying in progress") is informational.
 */
fun cannotDryText(code: Int): String? = when (code) {
    0 -> "The printer is busy with a task"
    1, 8 -> "Not enough power: connect the AMS's 24 V adapter"
    2 -> "The AMS is busy"
    3, 10 -> "Filament is loaded through the AMS outlet: unload it first"
    4 -> "Drying is starting"
    5 -> "Not supported in this mode"
    7 -> "Firmware update in progress"
    else -> null
}

data class AmsUnit(
    val id: Int,
    val humidityLevel: Int?,
    val temperatureC: Double?,
    val trays: List<Tray>,
    val type: AmsType = AmsType.Unknown,
    val dryStatus: DryStatus = DryStatus.Off,
    /** Minutes of drying left (`dry_time`); 0 when no timer is running. */
    val dryMinutesLeft: Int = 0,
    /** Relative humidity in % (`humidity_raw`), finer than the 1–5 [humidityLevel]. */
    val humidityPercent: Int? = null,
    val cannotDryReasons: List<Int> = emptyList(),
) {
    /** Bambu Studio's rule, plus a running timer (some firmware leaves the status bits 0). */
    val isDrying: Boolean get() = dryStatus in setOf(DryStatus.Checking, DryStatus.Drying, DryStatus.Cooling, DryStatus.Error, DryStatus.HeatOutOfControl) ||
        (dryMinutesLeft in 1..24 * 60 && dryStatus != DryStatus.Stopping)
}

/** A typed, UI-ready view of the merged `print` report. Every field tolerates absence. */
data class PrinterStatus(
    val jobState: JobState,
    val stage: PrintStage?,
    val jobName: String?,
    /** The printer's per-print counter (`task_id`); null when it reports "0". */
    val taskId: String?,
    /** Which plate of the job file is printing, from `gcode_file` (`.../plate_N.gcode`). */
    val plateIndex: Int?,
    val percent: Int?,
    val remainingMinutes: Int?,
    val layer: Int?,
    val totalLayers: Int?,
    val nozzle: Temperature,
    val bed: Temperature,
    /** Chamber sensor; target set only on printers that heat the chamber (H2 series). */
    val chamber: Temperature?,
    /** Per-extruder temperatures on dual-nozzle printers (index 0 = right, 1 = left); else empty. */
    val nozzles: List<Temperature>,
    val partFanPercent: Int?,
    val auxFanPercent: Int?,
    val chamberFanPercent: Int?,
    val heatbreakFanPercent: Int?,
    val speed: SpeedLevel?,
    val chamberLightOn: Boolean?,
    /** `print_error`, 0 when none; formatted like HMS ids as "0300_4000". */
    val printErrorCode: Long,
    val hms: List<HmsAlert>,
    val ams: List<AmsUnit>,
    /** Slot currently feeding the extruder; null if none or the external spool. */
    val activeSlot: Int?,
    val wifiDbm: Int?,
    val nozzleDiameter: String?,
    /** AMS slot (global, amsId*4+tray) feeding each of the job's filaments, from `mapping`. */
    val amsMapping: List<Int>,
    /**
     * The printer advertises remote AMS drying (`fun2` bit 5, as Bambu Studio checks).
     * False on the X1C as of firmware 01.12; controls should only appear when true.
     */
    /** From `ipcam.rtsp_url`; null until the printer has reported its camera block. */
    val liveview: Liveview?,
    /**
     * Firmware that only accepts signed commands unless Developer Mode is on (`fun` bit
     * 29, as ha-bambulab checks). Pause, stop, speed and light are then refused.
     */
    val commandsLocked: Boolean,
    val supportsRemoteDrying: Boolean = false,
    /** Firmware accepts `skip_objects` (`fun` bit 49, as Bambu Studio checks). */
    val supportsSkipObjects: Boolean = false,
    /** `identify_id`s of objects skipped on the running plate (`s_obj`). */
    val skippedObjects: Set<Int> = emptySet(),
) {
    val printError: String? get() =
        printErrorCode.takeIf { it != 0L }?.let { "%08X".format(it).chunked(4).joinToString("_") }

    companion object {
        fun from(p: JsonObject) = PrinterStatus(
            jobState = JobState.parse(p.str("gcode_state")),
            stage = p.int("stg_cur")?.let(PrintStage::fromCode),
            jobName = p.str("subtask_name")?.takeIf { it.isNotBlank() },
            taskId = p.str("task_id")?.takeIf { it.isNotBlank() && it != "0" },
            plateIndex = p.str("gcode_file")?.let { Regex("""plate_(\d+)\.gcode""").find(it)?.groupValues?.get(1)?.toIntOrNull() },
            percent = p.int("mc_percent"),
            remainingMinutes = p.int("mc_remaining_time"),
            layer = p.int("layer_num"),
            totalLayers = p.int("total_layer_num"),
            nozzle = p.dbl("nozzle_temper")?.let { Temperature(it, p.dbl("nozzle_target_temper")) }
                ?: extruders(p).firstOrNull() ?: Temperature(null, null),
            bed = p.dbl("bed_temper")?.let { Temperature(it, p.dbl("bed_target_temper")) }
                ?: packedTemperature(p.path("device", "bed", "info", "temp")) ?: Temperature(null, null),
            // Newer firmware reports the chamber under device.ctc; older used chamber_temper.
            chamber = packedTemperature(p.path("device", "ctc", "info", "temp"))
                ?: p.dbl("chamber_temper")?.let { Temperature(it, null) },
            nozzles = extruders(p).takeIf { it.size >= 2 }.orEmpty(),
            partFanPercent = fanPercent(p.str("cooling_fan_speed")),
            auxFanPercent = fanPercent(p.str("big_fan1_speed")),
            chamberFanPercent = fanPercent(p.str("big_fan2_speed")),
            heatbreakFanPercent = fanPercent(p.str("heatbreak_fan_speed")),
            speed = SpeedLevel.parse(p.int("spd_lvl")),
            chamberLightOn = (p["lights_report"] as? JsonArray)
                ?.filterIsInstance<JsonObject>()
                ?.firstOrNull { it.str("node") == "chamber_light" }
                ?.str("mode")?.let { it == "on" },
            printErrorCode = p.long("print_error") ?: 0L,
            hms = (p["hms"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull {
                val attr = it.long("attr") ?: return@mapNotNull null
                val code = it.long("code") ?: return@mapNotNull null
                HmsAlert(attr, code)
            },
            ams = parseAms(p),
            activeSlot = p.path("ams", "tray_now").int()?.takeIf { it in 0..253 },
            wifiDbm = p.str("wifi_signal")?.removeSuffix("dBm")?.toIntOrNull(),
            nozzleDiameter = p.str("nozzle_diameter"),
            amsMapping = (p["mapping"] as? JsonArray).orEmpty().mapNotNull { it.int() },
            liveview = (p["ipcam"] as? JsonObject)?.let { Liveview.from(it.str("rtsp_url")) },
            commandsLocked = p.str("fun")?.toLongOrNull(16)?.let { (it ushr 29) and 1L == 1L } ?: false,
            supportsSkipObjects = p.str("fun")?.toLongOrNull(16)?.let { (it ushr 49) and 1L == 1L } ?: false,
            skippedObjects = (p["s_obj"] as? JsonArray).orEmpty().mapNotNull { it.int() }.toSet(),
            supportsRemoteDrying = p.str("fun2")?.toLongOrNull(16)?.let { (it ushr 5) and 1L == 1L } ?: false,
        )

        /** Fan speeds arrive as "0".."15" (gear steps); the app and printer screen show percent. */
        private fun fanPercent(raw: String?): Int? =
            raw?.toIntOrNull()?.let { (it.coerceIn(0, 15) * 100 / 15.0).roundToInt() }

        private fun parseAms(p: JsonObject): List<AmsUnit> =
            (p.path("ams", "ams") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { unit ->
                val id = unit.int("id") ?: return@mapNotNull null
                val info = unit.str("info")?.toLongOrNull(16) ?: 0L
                AmsUnit(
                    id = id,
                    type = AmsType.of((info and 0xF).toInt()),
                    dryStatus = DryStatus.of(((info ushr 4) and 0xF).toInt()),
                    dryMinutesLeft = unit.int("dry_time")?.coerceAtLeast(0) ?: 0,
                    humidityPercent = unit.int("humidity_raw")?.takeIf { it in 0..100 },
                    cannotDryReasons = (unit["dry_sf_reason"] as? JsonArray).orEmpty().mapNotNull { it.int() },
                    humidityLevel = unit.int("humidity"),
                    temperatureC = unit.dbl("temp"),
                    trays = (unit["tray"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { t ->
                        val trayId = t.int("id") ?: return@mapNotNull null
                        Tray(
                            slot = id * 4 + trayId,
                            material = t.str("tray_type")?.takeIf { it.isNotBlank() },
                            name = t.str("tray_sub_brands")?.takeIf { it.isNotBlank() },
                            colorHex = t.str("tray_color")?.takeIf { it.isNotBlank() },
                            remainingPercent = t.int("remain")?.takeIf { it in 0..100 },
                            spoolWeightG = t.int("tray_weight")?.takeIf { it > 0 },
                        )
                    },
                )
            }
    }
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content
private fun JsonObject.int(key: String): Int? = this[key].int()
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.content.toLongOrNull() }
private fun JsonObject.dbl(key: String): Double? = this[key].dbl()

// The printer sends numbers both as JSON numbers and as strings ("humidity": "2").
private fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.let { it.longOrNull?.toInt() ?: it.content.toIntOrNull() }
/**
 * `device.*.info.temp` packs the current temperature in the low 16 bits and the target in
 * the high 16 (e.g. 0x0046_0046 = 70° heading for 70°). A plain value has no target.
 */
internal fun packedTemperature(e: JsonElement?): Temperature? {
    val v = e.dbl() ?: return null
    if (v != Math.floor(v) || v < 0x10000) return Temperature(v, null)
    val n = v.toLong()
    return Temperature((n and 0xFFFF).toDouble(), ((n ushr 16) and 0xFFFF).toDouble())
}

/** `device.extruder.info[]` by id; dual-nozzle printers list two. */
private fun extruders(p: JsonObject): List<Temperature> =
    (p.path("device", "extruder", "info") as? JsonArray).orEmpty()
        .mapNotNull { it as? JsonObject }
        .sortedBy { it.dbl("id") ?: 0.0 }
        .mapNotNull { packedTemperature(it["temp"]) }

private fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
