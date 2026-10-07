package app.bambumon.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Folds the printer's `device/<serial>/report` messages into one current state.
 *
 * The X1C mostly sends complete `push_status` dumps, but firmware may also send partial
 * ones; both are deep-merged into the accumulated `print` object (objects merge key by
 * key, arrays and scalars replace). Other `print` commands, such as `gcode_line` replies,
 * are command acknowledgements rather than state and are ignored.
 */
class ReportMerger {
    var state: JsonObject = JsonObject(emptyMap())
        private set

    /** Returns true if [payload] was a status report and [state] changed. */
    fun apply(payload: String): Boolean {
        val root = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return false
        val print = root["print"] as? JsonObject ?: return false
        if ((print["command"] as? JsonPrimitive)?.content != "push_status") return false
        val merged = deepMerge(state, print)
        if (merged == state) return false
        state = merged
        return true
    }

    fun reset() {
        state = JsonObject(emptyMap())
    }

    companion object {
        internal fun deepMerge(base: JsonObject, update: JsonObject): JsonObject {
            val out = base.toMutableMap()
            for ((key, value) in update) {
                val old = out[key]
                out[key] = if (old is JsonObject && value is JsonObject) deepMerge(old, value) else value
            }
            return JsonObject(out)
        }
    }
}

internal fun JsonObject.path(vararg keys: String): JsonElement? {
    var cur: JsonElement = this
    for (k in keys) cur = (cur as? JsonObject)?.get(k) ?: return null
    return cur
}
