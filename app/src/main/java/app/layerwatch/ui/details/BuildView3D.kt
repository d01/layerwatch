package app.layerwatch.ui.details

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import app.layerwatch.protocol.job.Feature
import app.layerwatch.protocol.job.GcodeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max

/**
 * The cut-away build view: every layer's outer walls as see-through lines, coloured by
 * whether they're printed (amber deepening with height), printing (bright), or still to
 * print (faint, thinned). Skipped objects are faint red below the current layer and gone
 * above it, since they won't print. Drag to orbit, pinch to zoom, double-tap to reset.
 */
@Composable
fun BuildView3D(model: GcodeModel, layer: Int, modifier: Modifier = Modifier, skipped: Set<Int> = emptySet()) {
    // Geometry is built once per model (and skip set), off the main thread.
    val geometry by produceState<WallGeometry?>(null, model, skipped) {
        value = withContext(Dispatchers.Default) { WallGeometry.build(model, skipped) }
    }
    var view by remember { mutableStateOf<BuildGlView?>(null) }
    AndroidView(
        factory = { BuildGlView(it).also { v -> view = v } },
        update = { v ->
            geometry?.let(v::show)
            v.setLayer(layer)
        },
        modifier = modifier.semantics { contentDescription = "3D view of the print, cut at layer $layer. Drag to rotate." },
    )
    // GLSurfaceView's render thread must follow the activity's pause/resume.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, view) {
        val v = view ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> v.onPause()
                Lifecycle.Event.ON_RESUME -> v.onResume()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

/**
 * Interleaved (x, y, z, layer) line-segment vertices, centred and scaled to about unit size.
 * Skipped objects' vertices carry the layer negated, for the shader to tint.
 */
class WallGeometry(
    val history: FloatArray,
    val vertexCount: Int,
    val model: GcodeModel,
    val center: FloatArray,
    val scale: Float,
    /** Bounding-sphere radius in normalised units, for framing the camera. */
    val radius: Float,
    val skipped: Set<Int>,
) {
    /** All walls of one layer, in the same normalised space, for the bright current layer. Skipped objects aren't printing. */
    fun layerWalls(layer: Int): FloatArray {
        val l = model.layer(layer) ?: return FloatArray(0)
        val out = FloatBuilder()
        for (p in l.firstPath until l.firstPath + l.pathCount) {
            if (!FeatureColors.isWall(model.feature(p)) || model.objectOf(p) in skipped) continue
            appendPath(model, p, l.z, layer.toFloat(), center, scale, out)
        }
        return out.toArray()
    }

    companion object {
        private val HISTORY = setOf(Feature.OuterWall, Feature.OverhangWall)

        fun build(model: GcodeModel, skipped: Set<Int> = emptySet()): WallGeometry {
            val b = model.bounds
            val zMax = model.layers.lastOrNull()?.z ?: 1f
            val center = floatArrayOf((b[0] + b[2]) / 2, (b[1] + b[3]) / 2, zMax / 2)
            val scale = 2f / max(max(b[2] - b[0], b[3] - b[1]), zMax).coerceAtLeast(1f)
            val out = FloatBuilder()
            for (l in model.layers) {
                for (p in l.firstPath until l.firstPath + l.pathCount) {
                    if (model.feature(p) !in HISTORY) continue
                    val tag = if (model.objectOf(p) in skipped) -l.number.toFloat() else l.number.toFloat()
                    appendPath(model, p, l.z, tag, center, scale, out)
                }
            }
            val w = b[2] - b[0]
            val h = b[3] - b[1]
            val radius = 0.5f * scale * kotlin.math.sqrt(w * w + h * h + zMax * zMax)
            return WallGeometry(out.toArray(), out.size / 4, model, center, scale, radius, skipped)
        }

        private fun appendPath(model: GcodeModel, p: Int, z: Float, layer: Float, c: FloatArray, s: Float, out: FloatBuilder) {
            val pts = model.points
            val r = model.pathPoints(p)
            for (k in r.first until r.last) {
                out.add((pts[k * 2] - c[0]) * s, (pts[k * 2 + 1] - c[1]) * s, (z - c[2]) * s, layer)
                out.add((pts[k * 2 + 2] - c[0]) * s, (pts[k * 2 + 3] - c[1]) * s, (z - c[2]) * s, layer)
            }
        }
    }
}

private class FloatBuilder {
    private var a = FloatArray(1 shl 16)
    var size = 0
        private set

    fun add(x: Float, y: Float, z: Float, w: Float) {
        if (size + 4 > a.size) a = a.copyOf(a.size * 2)
        a[size] = x; a[size + 1] = y; a[size + 2] = z; a[size + 3] = w
        size += 4
    }

    fun toArray(): FloatArray = a.copyOf(size)
}

@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class BuildGlView(context: Context) : GLSurfaceView(context) {
    private val renderer = Renderer()

    private var shown: WallGeometry? = null

    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun show(g: WallGeometry) {
        if (g === shown) return
        shown = g
        queueEvent { renderer.setGeometry(g) }
        requestRender()
    }

    fun setLayer(layer: Int) {
        queueEvent { renderer.setLayer(layer) }
        requestRender()
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            renderer.zoom = (renderer.zoom * d.scaleFactor).coerceIn(0.5f, 6f)
            requestRender()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (e2.pointerCount > 1) return false
            // ~0.2°/px: a full-width drag across a phone screen is a bit over half a turn.
            renderer.yaw -= dx * 0.2f
            renderer.pitch = (renderer.pitch - dy * 0.2f).coerceIn(-5f, 89f)
            requestRender()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            renderer.resetView()
            requestRender()
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true) // don't let the page scroll steal drags
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

}

private class Renderer : GLSurfaceView.Renderer {
    @Volatile var yaw = DEFAULT_YAW
    @Volatile var pitch = DEFAULT_PITCH
    @Volatile var zoom = 1f

    private var program = 0
    private var uMvp = 0
    private var uCurrent = 0
    private var historyVbo = 0
    private var historyCount = 0
    private var currentVbo = 0
    private var currentCount = 0
    private var geometry: WallGeometry? = null
    private var layer = 1
    private var shownLayer = -1
    private val proj = FloatArray(16)
    private var aspect = 1f
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)

    fun resetView() {
        yaw = DEFAULT_YAW; pitch = DEFAULT_PITCH; zoom = 1f
    }

    fun setGeometry(g: WallGeometry) {
        geometry = g
        if (program == 0) return
        historyCount = g.vertexCount
        upload(historyVbo, g.history)
        shownLayer = -1
    }

    fun setLayer(l: Int) {
        layer = l
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(VERTEX, FRAGMENT)
        uMvp = GLES30.glGetUniformLocation(program, "uMvp")
        uCurrent = GLES30.glGetUniformLocation(program, "uCurrent")
        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        historyVbo = ids[0]
        currentVbo = ids[1]
        geometry?.let(::setGeometry)
        GLES30.glClearColor(0x0B / 255f, 0x0D / 255f, 0x0F / 255f, 1f)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        // Normal alpha blending: hundreds of stacked walls shade toward the design's amber
        // instead of saturating to white (as additive blending did).
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
        Matrix.perspectiveM(proj, 0, FOV_Y, aspect, 0.1f, 50f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        val g = geometry ?: return
        if (shownLayer != layer) {
            val walls = g.layerWalls(layer)
            currentCount = walls.size / 4
            upload(currentVbo, walls)
            shownLayer = layer
        }
        Matrix.setIdentityM(view, 0)
        Matrix.translateM(view, 0, 0f, 0f, -fitDistance(g.radius) / zoom)
        Matrix.rotateM(view, 0, pitch - 90f, 1f, 0f, 0f) // model is Z-up
        Matrix.rotateM(view, 0, yaw, 0f, 0f, 1f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES30.glUniform1f(uCurrent, layer.toFloat())
        draw(historyVbo, historyCount)
        draw(currentVbo, currentCount)
    }

    /** Camera distance at which the bounding sphere fits the narrower field of view. */
    private fun fitDistance(radius: Float): Float {
        val halfY = Math.toRadians(FOV_Y / 2.0)
        val halfX = kotlin.math.atan(kotlin.math.tan(halfY) * aspect)
        val half = minOf(halfY, halfX)
        return (radius / kotlin.math.sin(half)).toFloat() * FIT_MARGIN
    }

    private fun draw(vbo: Int, count: Int) {
        if (count == 0) return
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 1, GLES30.GL_FLOAT, false, 16, 12)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, count)
    }

    private fun upload(vbo: Int, data: FloatArray) {
        val buf: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data).also { it.position(0) }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buf, GLES30.GL_STATIC_DRAW)
    }

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String) = GLES30.glCreateShader(type).also {
            GLES30.glShaderSource(it, src)
            GLES30.glCompileShader(it)
        }
        return GLES30.glCreateProgram().also {
            GLES30.glAttachShader(it, compile(GLES30.GL_VERTEX_SHADER, vs))
            GLES30.glAttachShader(it, compile(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glLinkProgram(it)
        }
    }

    private companion object {
        const val FOV_Y = 30f
        /** The sphere is loose around a part; a little under 1 fills the frame better. */
        const val FIT_MARGIN = 0.82f
        const val DEFAULT_YAW = -35f
        const val DEFAULT_PITCH = 28f

        const val VERTEX = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in float aLayer;
uniform mat4 uMvp;
uniform float uCurrent;
out vec4 vColor;
void main() {
    gl_Position = uMvp * vec4(aPos, 1.0);
    if (aLayer < 0.0) {
        // A skipped object: faint red where it may have printed, nothing above.
        vColor = -aLayer < uCurrent - 0.5 ? vec4(1.0, 0.353, 0.31, 0.3) : vec4(0.0);
    } else if (aLayer < uCurrent - 0.5) {
        float t = aLayer / max(uCurrent, 1.0);
        vColor = vec4(0.35 + 0.6 * t, 0.27 + 0.42 * t, 0.10 + 0.19 * t, 0.5);
    } else if (aLayer < uCurrent + 0.5) {
        vColor = vec4(1.0, 0.886, 0.659, 1.0);
    } else {
        vColor = mod(aLayer, 5.0) < 0.5 ? vec4(0.23, 0.25, 0.27, 0.55) : vec4(0.0);
    }
}"""

        const val FRAGMENT = """#version 300 es
precision mediump float;
in vec4 vColor;
out vec4 fragColor;
void main() {
    if (vColor.a <= 0.0) discard;
    fragColor = vColor;
}"""
    }
}
