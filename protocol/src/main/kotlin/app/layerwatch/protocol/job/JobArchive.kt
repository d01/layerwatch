package app.layerwatch.protocol.job

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class SliceFilament(
    /** 1-based slot id as the slicer numbers filaments. */
    val id: Int,
    val type: String?,
    val colorHex: String?,
    val usedM: Double?,
    val usedG: Double?,
)

data class SliceObject(val identifyId: Int, val name: String, val skipped: Boolean)

data class SliceWarning(val code: String, val message: String)

/** `Metadata/slice_info.config`: the slicer's summary of the plate. */
data class SliceInfo(
    val slicer: String?,
    val predictionSec: Int?,
    val weightG: Double?,
    val firstLayerSec: Double?,
    val supportUsed: Boolean?,
    val nozzleDiameter: String?,
    val filaments: List<SliceFilament>,
    val objects: List<SliceObject>,
    val warnings: List<SliceWarning>,
)

data class PlateObject(val id: Int, val name: String, val areaMm2: Double?, val layerHeight: Double?)

/** `Metadata/plate_N.json`. */
data class PlateInfo(val bedType: String?, val objects: List<PlateObject>)

/**
 * The handful of slicer settings the app shows, from `Metadata/project_settings.config`.
 * Per-filament values are arrays indexed by 0-based filament slot.
 */
class SlicerSettings(private val raw: JsonObject) {
    fun string(key: String): String? = (raw[key] as? JsonPrimitive)?.content
    fun stringAt(key: String, index: Int): String? = when (val v = raw[key]) {
        is JsonArray -> (v.getOrNull(index) ?: v.firstOrNull())?.let { (it as? JsonPrimitive)?.content }
        is JsonPrimitive -> v.content
        else -> null
    }
    fun double(key: String): Double? = string(key)?.removeSuffix("%")?.toDoubleOrNull()
    fun doubleAt(key: String, index: Int): Double? = stringAt(key, index)?.toDoubleOrNull()

    val printProfile get() = string("print_settings_id")
    val layerHeight get() = double("layer_height")
    val firstLayerHeight get() = double("initial_layer_print_height")
    val wallLoops get() = double("wall_loops")?.toInt()
    val topShells get() = double("top_shell_layers")?.toInt()
    val bottomShells get() = double("bottom_shell_layers")?.toInt()
    val infillDensity get() = string("sparse_infill_density")
    val infillPattern get() = string("sparse_infill_pattern")
    val outerWallSpeed get() = double("outer_wall_speed")
    val innerWallSpeed get() = double("inner_wall_speed")
    val infillSpeed get() = double("sparse_infill_speed")
    val acceleration get() = double("default_acceleration")
    val seam get() = string("seam_position")
    val supports get() = string("enable_support")?.let { it == "1" || it == "true" }
    val brim get() = string("brim_type")
    val bedType get() = string("curr_bed_type")
    fun nozzleTemp(slot: Int) = doubleAt("nozzle_temperature", slot)
    fun filamentProfile(slot: Int) = stringAt("filament_settings_id", slot)
    fun filamentCostPerKg(slot: Int) = doubleAt("filament_cost", slot)
    fun filamentDensity(slot: Int) = doubleAt("filament_density", slot)
    fun filamentDiameter(slot: Int) = doubleAt("filament_diameter", slot)

    /** Bed temperature for the plate type in use (Bambu keys one setting per plate). */
    fun bedTemp(slot: Int): Double? {
        val key = when (bedType?.lowercase()) {
            "textured pei plate" -> "textured_plate_temp"
            "cool plate", "cool plate (smooth)" -> "cool_plate_temp"
            "engineering plate" -> "eng_plate_temp"
            "high temp plate", "smooth pei plate" -> "hot_plate_temp"
            else -> "hot_plate_temp"
        }
        return doubleAt(key, slot) ?: doubleAt("hot_plate_temp", slot)
    }
}

/** Everything the app uses from a sliced job file (`<name>.gcode.3mf`). */
class JobFile(
    val plate: Int,
    val slice: SliceInfo?,
    val plateInfo: PlateInfo?,
    val settings: SlicerSettings?,
    /** Slicer renders: 3/4 view, top view, and the object-id mask (see [pickObjectId]). */
    val plateImage: ByteArray?,
    val topImage: ByteArray?,
    val pickImage: ByteArray?,
    val gcode: GcodeModel?,
) {
    companion object {
        /** The pick mask encodes an object's `identify_id` as RGB: r + g·256 + b·65536. */
        fun pickObjectId(r: Int, g: Int, b: Int): Int = r or (g shl 8) or (b shl 16)
    }
}

object JobArchive {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads a `.gcode.3mf`. [plate] picks `plate_N.*` (the printer reports it in
     * `gcode_file`). Set [withGcode] false to skip toolpath parsing.
     *
     * Takes a file, not a stream: Bambu/Orca archives store entries uncompressed *with*
     * data descriptors, which `ZipInputStream` can't read; `ZipFile` uses the central
     * directory instead. The G-code entry is still parsed as a stream.
     */
    fun read(file: File, plate: Int = 1, withGcode: Boolean = true): JobFile = ZipFile(file).use { zip ->
        fun bytes(name: String): ByteArray? = zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
        JobFile(
            plate = plate,
            slice = bytes("Metadata/slice_info.config")?.let { parseSliceInfo(it, plate) },
            plateInfo = bytes("Metadata/plate_$plate.json")?.let(::parsePlate),
            settings = bytes("Metadata/project_settings.config")?.let {
                runCatching { SlicerSettings(json.parseToJsonElement(it.decodeToString()) as JsonObject) }.getOrNull()
            },
            plateImage = bytes("Metadata/plate_$plate.png"),
            topImage = bytes("Metadata/top_$plate.png"),
            pickImage = bytes("Metadata/pick_$plate.png"),
            gcode = if (!withGcode) null else zip.getEntry("Metadata/plate_$plate.gcode")?.let { e ->
                zip.getInputStream(e).bufferedReader(Charsets.UTF_8).use(GcodeParser::parse)
            },
        )
    }

    /**
     * Plates whose G-code [file] holds. A file sent from the slicer carries only the plate
     * being printed, so re-sending another plate of the same project replaces it.
     */
    fun plates(file: File): List<Int> = ZipFile(file).use { zip ->
        zip.entries().asSequence().mapNotNull { e ->
            PLATE_GCODE.matchEntire(e.name)?.groupValues?.get(1)?.toIntOrNull()
        }.sorted().toList()
    }

    /** The `; total layer number:` header of a plate's G-code, without parsing the toolpaths. */
    fun headerLayers(file: File, plate: Int): Int? = ZipFile(file).use { zip ->
        val e = zip.getEntry("Metadata/plate_$plate.gcode") ?: return null
        zip.getInputStream(e).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.take(HEADER_LINES).firstOrNull { it.startsWith("; total layer number:") }
                ?.substringAfter(':')?.trim()?.toIntOrNull()
        }
    }

    private val PLATE_GCODE = Regex("""Metadata/plate_(\d+)\.gcode""")
    private const val HEADER_LINES = 400

    internal fun parseSliceInfo(bytes: ByteArray, plate: Int): SliceInfo? = runCatching {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(bytes.inputStream())
        val headers = doc.getElementsByTagName("header_item").elements()
            .associate { it.getAttribute("key") to it.getAttribute("value") }
        val plates = doc.getElementsByTagName("plate").elements()
        val p = plates.firstOrNull { meta(it)["index"] == plate.toString() } ?: plates.firstOrNull() ?: return null
        val m = meta(p)
        val slicer = headers["OrcaSlicer-Version"]?.let { "OrcaSlicer $it" }
            ?: headers["X-BBL-Client-Version"]?.let { "Bambu Studio $it" }
        SliceInfo(
            slicer = slicer,
            predictionSec = m["prediction"]?.toIntOrNull(),
            weightG = m["weight"]?.toDoubleOrNull(),
            firstLayerSec = m["first_layer_time"]?.toDoubleOrNull(),
            supportUsed = m["support_used"]?.let { it == "true" },
            nozzleDiameter = m["nozzle_diameters"],
            filaments = p.getElementsByTagName("filament").elements().mapNotNull {
                SliceFilament(
                    id = it.getAttribute("id").toIntOrNull() ?: return@mapNotNull null,
                    type = it.getAttribute("type").ifBlank { null },
                    colorHex = it.getAttribute("color").ifBlank { null },
                    usedM = it.getAttribute("used_m").toDoubleOrNull(),
                    usedG = it.getAttribute("used_g").toDoubleOrNull(),
                )
            },
            objects = p.getElementsByTagName("object").elements().mapNotNull {
                SliceObject(
                    identifyId = it.getAttribute("identify_id").toIntOrNull() ?: return@mapNotNull null,
                    name = it.getAttribute("name"),
                    skipped = it.getAttribute("skipped") == "true",
                )
            },
            warnings = p.getElementsByTagName("warning").elements().map {
                val code = it.getAttribute("msg")
                SliceWarning(code, warningText(code))
            },
        )
    }.getOrNull()

    internal fun parsePlate(bytes: ByteArray): PlateInfo? = runCatching {
        val o = json.parseToJsonElement(bytes.decodeToString()) as JsonObject
        PlateInfo(
            bedType = (o["bed_type"] as? JsonPrimitive)?.content,
            objects = (o["bbox_objects"] as? JsonArray).orEmpty().mapNotNull { e ->
                val obj = e as? JsonObject ?: return@mapNotNull null
                PlateObject(
                    id = obj["id"].int() ?: return@mapNotNull null,
                    name = (obj["name"] as? JsonPrimitive)?.content ?: "",
                    areaMm2 = obj["area"].dbl(),
                    layerHeight = obj["layer_height"].dbl(),
                )
            },
        )
    }.getOrNull()

    /** Friendly text for slicer warning codes we know; otherwise the code, humanized. */
    fun warningText(code: String): String = when (code) {
        "bed_temperature_too_high_than_filament" -> "Bed hotter than the filament recommends"
        "filament_not_compatible_with_plate", "bed_filament_not_compatible" -> "Filament not suited to this plate"
        "prime_tower_outside" -> "Prime tower is outside the plate"
        else -> code.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    private fun meta(e: Element) = e.getElementsByTagName("metadata").elements()
        .filter { it.parentNode == e }
        .associate { it.getAttribute("key") to it.getAttribute("value") }

    private fun org.w3c.dom.NodeList.elements(): List<Element> = (0 until length).mapNotNull { item(it) as? Element }

    private fun JsonElement?.int() = (this as? JsonPrimitive)?.intOrNull
    private fun JsonElement?.dbl() = (this as? JsonPrimitive)?.doubleOrNull
}
