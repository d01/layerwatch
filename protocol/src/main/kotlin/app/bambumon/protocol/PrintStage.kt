package app.bambumon.protocol

/**
 * The printer's current sub-stage (`stg_cur`). Codes follow the community-documented
 * table (ha-bambulab); unknown codes are kept so a newer firmware still shows something.
 */
sealed class PrintStage(val code: Int, val label: String, val isPause: Boolean = false) {
    class Known(code: Int, label: String, isPause: Boolean) : PrintStage(code, label, isPause)
    class Unknown(code: Int) : PrintStage(code, "Stage $code")

    override fun equals(other: Any?) = other is PrintStage && other.code == code
    override fun hashCode() = code
    override fun toString() = "PrintStage($code, $label)"

    companion object {
        private val table: Map<Int, Pair<String, Boolean>> = mapOf(
            0 to ("Printing" to false),
            1 to ("Auto bed leveling" to false),
            2 to ("Heatbed preheating" to false),
            3 to ("Sweeping XY mech mode" to false),
            4 to ("Changing filament" to false),
            5 to ("Paused (M400)" to true),
            6 to ("Paused: filament runout" to true),
            7 to ("Heating hotend" to false),
            8 to ("Calibrating extrusion" to false),
            9 to ("Scanning bed surface" to false),
            10 to ("Inspecting first layer" to false),
            11 to ("Identifying build plate" to false),
            12 to ("Calibrating micro lidar" to false),
            13 to ("Homing toolhead" to false),
            14 to ("Cleaning nozzle tip" to false),
            15 to ("Checking extruder temperature" to false),
            16 to ("Paused by user" to true),
            17 to ("Paused: front cover falling" to true),
            18 to ("Calibrating micro lidar" to false),
            19 to ("Calibrating extrusion flow" to false),
            20 to ("Paused: nozzle temperature malfunction" to true),
            21 to ("Paused: heatbed temperature malfunction" to true),
            22 to ("Unloading filament" to false),
            23 to ("Paused: skipped step" to true),
            24 to ("Loading filament" to false),
            25 to ("Calibrating motor noise" to false),
            26 to ("Paused: AMS lost" to true),
            27 to ("Paused: heatbreak fan speed low" to true),
            28 to ("Paused: chamber temperature control error" to true),
            29 to ("Cooling chamber" to false),
            30 to ("Paused by G-code" to true),
            31 to ("Motor noise showoff" to false),
            32 to ("Paused: nozzle filament covered" to true),
            33 to ("Paused: cutter error" to true),
            34 to ("Paused: first layer error" to true),
            35 to ("Paused: nozzle clog" to true),
        )

        /** Null for the idle codes (-1, 255). */
        fun fromCode(code: Int): PrintStage? {
            if (code == -1 || code == 255) return null
            val (label, pause) = table[code] ?: return Unknown(code)
            return Known(code, label, pause)
        }
    }
}
