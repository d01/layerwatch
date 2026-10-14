package app.layerwatch.protocol

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON payloads for `device/<serial>/request`. Shapes match what Bambu Studio sends on
 * the LAN; the printer echoes `sequence_id` in its reply, so each call takes a fresh one.
 */
object PrinterCommands {
    fun pushAll(seq: Long) = print(seq, "pushall", root = "pushing")

    fun pause(seq: Long) = print(seq, "pause")
    fun resume(seq: Long) = print(seq, "resume")
    fun stop(seq: Long) = print(seq, "stop")

    fun speed(seq: Long, level: SpeedLevel) = buildJsonObject {
        putJsonObject("print") {
            put("sequence_id", seq.toString())
            put("command", "print_speed")
            put("param", level.code.toString())
        }
    }.toString()

    /**
     * Skip objects on the running plate, by `identify_id` (as in slice_info and the pick
     * mask). The printer adds them to `s_obj`; already-skipped ids needn't be resent.
     */
    fun skipObjects(seq: Long, ids: List<Int>) = buildJsonObject {
        putJsonObject("print") {
            put("sequence_id", seq.toString())
            put("command", "skip_objects")
            putJsonArray("obj_list") { ids.forEach { add(JsonPrimitive(it)) } }
        }
    }.toString()

    fun chamberLight(seq: Long, on: Boolean) = buildJsonObject {
        putJsonObject("system") {
            put("sequence_id", seq.toString())
            put("command", "ledctrl")
            put("led_node", "chamber_light")
            put("led_mode", if (on) "on" else "off")
            put("led_on_time", 500)
            put("led_off_time", 500)
            put("loop_times", 0)
            put("interval_time", 0)
        }
    }.toString()

    private fun print(seq: Long, command: String, root: String = "print") = buildJsonObject {
        putJsonObject(root) {
            put("sequence_id", seq.toString())
            put("command", command)
        }
    }.toString()
}
