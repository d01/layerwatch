package app.layerwatch.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.math.ceil
import kotlin.math.min

/**
 * A simulated printer, so the app can be explored without one (and reviewed by people
 * who don't own a Bambu printer). It loops through a short print of the bundled demo job
 * [JOB_NAME] — heating, printing [LAYERS] layers, finished, idle — and responds to the
 * light, pause, resume, stop and speed controls.
 *
 * It emits genuine `push_status` reports through [ReportMerger] and [PrinterStatus.from],
 * so the demo exercises the same parsing as a real printer.
 */
class DemoPrinter(
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 1_000,
) : PrinterLink {
    private val _link = MutableStateFlow<LinkState>(LinkState.Stopped)
    override val link: StateFlow<LinkState> = _link.asStateFlow()
    private val _status = MutableStateFlow<PrinterStatus?>(null)
    override val status: StateFlow<PrinterStatus?> = _status.asStateFlow()
    private val _lastReportAtMs = MutableStateFlow<Long?>(null)
    override val lastReportAtMs: StateFlow<Long?> = _lastReportAtMs.asStateFlow()

    private val merger = ReportMerger()
    private var job: Job? = null

    // Simulation state. `phaseSec` advances only while not paused.
    private var phaseSec = 0.0
    private var paused = false
    private var cancelledAtSec: Double? = null
    private var light = true
    private var speed = SpeedLevel.Standard
    private var taskNo = 1

    override fun start() {
        if (job?.isActive == true) return
        _link.value = LinkState.Online(clock())
        job = scope.launch {
            while (isActive) {
                emit()
                delay(tickMs)
                advance(tickMs / 1000.0)
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
        _link.value = LinkState.Stopped
    }

    override fun reconnectNow() = Unit

    override fun setChamberLight(on: Boolean): Boolean = act { light = on }
    override fun pause(): Boolean = act { if (printing()) paused = true }
    override fun resume(): Boolean = act { paused = false }
    override fun stopPrint(): Boolean = act { if (printing() || paused) { cancelledAtSec = phaseSec; paused = false } }
    override fun setSpeed(level: SpeedLevel): Boolean = act { speed = level }

    private fun act(change: () -> Unit): Boolean {
        change()
        emit()
        return true
    }

    private fun printing() = phaseSec in PREPARE_S..(PREPARE_S + PRINT_S) && cancelledAtSec == null

    /** Advances simulated time; [seconds] scaled by print speed while printing. */
    internal fun advance(seconds: Double) {
        if (paused) return
        val factor = if (printing() && phaseSec >= PREPARE_S) speed.percent / 100.0 else 1.0
        phaseSec += seconds * factor
        val cancelled = cancelledAtSec
        if (cancelled != null && phaseSec - cancelled > FAILED_HOLD_S) restartCycle()
        if (phaseSec >= CYCLE_S) restartCycle()
    }

    private fun restartCycle() {
        phaseSec = 0.0
        cancelledAtSec = null
        paused = false
        taskNo++
    }

    internal fun emit() {
        merger.apply(report().toString())
        _status.value = PrinterStatus.from(merger.state)
        _lastReportAtMs.value = clock()
    }

    /** One full push_status report for the current simulated moment. */
    internal fun report(): JsonObject {
        val t = phaseSec
        val cancelled = cancelledAtSec != null
        val printFrac = ((t - PREPARE_S) / PRINT_S).coerceIn(0.0, 1.0)
        val state = when {
            cancelled -> "FAILED"
            paused -> "PAUSE"
            t < PREPARE_S -> "PREPARE"
            t < PREPARE_S + PRINT_S -> "RUNNING"
            t < PREPARE_S + PRINT_S + FINISH_S -> "FINISH"
            else -> "IDLE"
        }
        val active = state == "PREPARE" || state == "RUNNING" || state == "PAUSE"
        val layer = when {
            t < PREPARE_S -> 0
            else -> min(LAYERS, 1 + (printFrac * LAYERS).toInt())
        }
        val heat = when {
            state == "PREPARE" -> (t / PREPARE_S).coerceIn(0.0, 1.0)
            active -> 1.0
            else -> 0.0
        }
        val nozzleTarget = if (active) 255.0 else 0.0
        val bedTarget = if (active) 70.0 else 0.0
        val nozzle = if (active) 30 + (255 - 30) * heat else 40.0
        val bed = if (active) 28 + (70 - 28) * heat else 35.0
        val stage = when (state) {
            "PREPARE" -> if (t < PREPARE_S / 2) 2 else 7
            "RUNNING" -> 0
            "PAUSE" -> 16
            else -> 255
        }
        return buildJsonObject {
            putJsonObject("print") {
                put("command", "push_status")
                put("gcode_state", state)
                put("stg_cur", stage)
                put("subtask_name", if (state == "IDLE") "" else JOB_NAME)
                put("task_id", if (state == "IDLE") "0" else "demo-$taskNo")
                put("gcode_file", "/data/Metadata/plate_1.gcode")
                put("mc_percent", if (state == "FINISH") 100 else (printFrac * 100).toInt())
                put("mc_remaining_time", if (active) ceil((1 - printFrac) * PLANNED_MIN).toInt() else 0)
                put("layer_num", if (state == "FINISH") LAYERS else layer)
                put("total_layer_num", if (state == "IDLE") 0 else LAYERS)
                put("nozzle_temper", nozzle)
                put("nozzle_target_temper", nozzleTarget)
                put("bed_temper", bed)
                put("bed_target_temper", bedTarget)
                putJsonObject("device") { putJsonObject("ctc") { putJsonObject("info") { put("temp", if (active) 38 else 31) } } }
                put("cooling_fan_speed", if (state == "RUNNING") "9" else "0")
                put("big_fan1_speed", "0")
                put("big_fan2_speed", if (active) "10" else "0")
                put("heatbreak_fan_speed", if (active) "15" else "0")
                put("spd_lvl", speed.code)
                putJsonArray("lights_report") {
                    add(buildJsonObject { put("node", "chamber_light"); put("mode", if (light) "on" else "off") })
                }
                put("print_error", if (cancelled) 0x0300400CL else 0L)
                putJsonArray("hms") {}
                put("wifi_signal", "-52dBm")
                put("nozzle_diameter", "0.8")
                putJsonArray("mapping") { add(JsonPrimitive(2)) }
                put("fun2", "0")
                putJsonObject("ams") {
                    put("tray_now", if (active) "2" else "255")
                    putJsonArray("ams") {
                        add(buildJsonObject {
                            put("id", "0")
                            put("info", "2003")
                            put("humidity", "2")
                            put("humidity_raw", "31")
                            put("temp", "26.5")
                            put("dry_time", 0)
                            put("tray", buildJsonArray {
                                add(tray(0, "PLA", "PLA Basic", "FFFFFFFF", 64))
                                add(tray(1, "PLA", "PLA Basic", "F4B04AFF", 88))
                                add(tray(2, "PETG", "PETG Basic", "000000FF", 71))
                                add(tray(3, "PETG", "PETG Translucent", "00000000", 23))
                            })
                        })
                    }
                }
            }
        }
    }

    private fun tray(id: Int, type: String, name: String, color: String, remain: Int) = buildJsonObject {
        put("id", id.toString())
        put("tray_type", type)
        put("tray_sub_brands", name)
        put("tray_color", color)
        put("remain", remain)
        put("tray_weight", "1000")
    }

    companion object {
        /** The demo job's file name, without `.gcode.3mf`; the app bundles that file. */
        const val JOB_NAME = "bleed_rest"
        const val LAYERS = 17
        private const val PLANNED_MIN = 12
        private const val PREPARE_S = 45.0
        private const val PRINT_S = PLANNED_MIN * 60.0 // in step with the job's own plan
        private const val FINISH_S = 60.0
        private const val CYCLE_S = PREPARE_S + PRINT_S + FINISH_S + 60.0
        private const val FAILED_HOLD_S = 30.0
    }
}

