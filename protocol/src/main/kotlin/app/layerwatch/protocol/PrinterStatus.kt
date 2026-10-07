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
    val isHeating get() = (target ?: 0.0) > 0.0
}

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

data class AmsUnit(
    val id: Int,
    val humidityLevel: Int?,
    val temperatureC: Double?,
    val trays: List<Tray>,
)

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
    val chamberC: Double?,
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
            nozzle = Temperature(p.dbl("nozzle_temper"), p.dbl("nozzle_target_temper")),
            bed = Temperature(p.dbl("bed_temper"), p.dbl("bed_target_temper")),
            // Newer X1 firmware reports chamber here; older firmware used chamber_temper.
            chamberC = p.path("device", "ctc", "info", "temp").dbl() ?: p.dbl("chamber_temper"),
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
        )

        /** Fan speeds arrive as "0".."15" (gear steps); the app and printer screen show percent. */
        private fun fanPercent(raw: String?): Int? =
            raw?.toIntOrNull()?.let { (it.coerceIn(0, 15) * 100 / 15.0).roundToInt() }

        private fun parseAms(p: JsonObject): List<AmsUnit> =
            (p.path("ams", "ams") as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { unit ->
                val id = unit.int("id") ?: return@mapNotNull null
                AmsUnit(
                    id = id,
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
private fun JsonElement?.dbl(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
