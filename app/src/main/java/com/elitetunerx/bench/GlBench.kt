package com.elitetunerx.bench

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import com.elitetunerx.Prefs
import com.elitetunerx.core.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Offscreen OpenGL ES 3 GPU benchmark. Renders into a 1920x1080 framebuffer with no vsync
 * (pbuffer + FBO), calling glFinish every frame, so the measured frame rate is pure GPU
 * throughput, not capped by the display. Telemetry (GPU busy/clock/temp, power) is sampled
 * during the run so throttling is visible.
 */
object GlBench {
    data class Progress(val phase: String, val fraction: Float, val liveFps: Double)

    data class Result(
        val time: Long, val mode: String, val renderer: String, val aluFps: Double, val fillFps: Double,
        val score: Int, val segments: List<Double>, val stabilityPct: Double?, val avgGpuBusy: Double?,
        val avgGpuMhz: Double?, val maxGpuTemp: Double?, val maxCpuTemp: Double?, val avgWatts: Double?,
        val durationS: Double,
    ) {
        fun toJson() = JSONObject().apply {
            put("time", time); put("mode", mode); put("renderer", renderer); put("aluFps", aluFps); put("fillFps", fillFps)
            put("score", score); put("segments", JSONArray(segments)); stabilityPct?.let { put("stability", it) }
            avgGpuBusy?.let { put("gpu", it) }; avgGpuMhz?.let { put("gpuMhz", it) }; maxGpuTemp?.let { put("gpuTemp", it) }
            maxCpuTemp?.let { put("cpuTemp", it) }; avgWatts?.let { put("watts", it) }; put("durationS", durationS)
        }

        companion object {
            fun fromJson(o: JSONObject): Result {
                fun d(k: String) = if (o.has(k)) o.optDouble(k) else null
                val seg = o.optJSONArray("segments") ?: JSONArray()
                return Result(
                    o.optLong("time"), o.optString("mode"), o.optString("renderer"), o.optDouble("aluFps"), o.optDouble("fillFps"),
                    o.optInt("score"), (0 until seg.length()).map { seg.getDouble(it) }, d("stability"), d("gpu"), d("gpuMhz"),
                    d("gpuTemp"), d("cpuTemp"), d("watts"), o.optDouble("durationS")
                )
            }
        }
    }

    val progress = MutableStateFlow<Progress?>(null)
    @Volatile var cancelled = false

    private const val W = 1920
    private const val H = 1080
    private const val FILL_LAYERS = 24

    private const val VS = """#version 300 es
layout(location = 0) in vec2 p;
out vec2 uv;
void main() { uv = p * 0.5 + 0.5; gl_Position = vec4(p, 0.0, 1.0); }
"""
    // Deterministic ALU-heavy workload: fixed Mandelbrot view, 256 iterations, fp32.
    private const val FS_ALU = """#version 300 es
precision highp float;
in vec2 uv;
out vec4 o;
void main() {
    vec2 c = (uv - 0.5) * vec2(3.2, 1.8) + vec2(-0.6, 0.0);
    vec2 z = vec2(0.0);
    float n = 0.0;
    for (int i = 0; i < 256; i++) {
        z = vec2(z.x * z.x - z.y * z.y, 2.0 * z.x * z.y) + c;
        if (dot(z, z) > 4.0) break;
        n += 1.0;
    }
    float t = n / 256.0;
    o = vec4(t, sin(n * 0.11) * 0.5 + 0.5, cos(n * 0.05) * 0.5 + 0.5, 1.0);
}
"""
    // Texture + blending workload (fill rate / bandwidth).
    private const val FS_FILL = """#version 300 es
precision mediump float;
in vec2 uv;
uniform sampler2D tex;
uniform float layer;
out vec4 o;
void main() {
    vec2 q = uv * vec2(3.0, 1.7) + vec2(layer * 0.013, layer * 0.007);
    o = texture(tex, q) * 0.04 + texture(tex, q * 1.9 + 0.3) * 0.02;
}
"""

    private class Gl {
        lateinit var dpy: EGLDisplay
        lateinit var ctx: EGLContext
        lateinit var surf: EGLSurface
        var fbo = 0; var target = 0; var noise = 0; var vao = 0; var vbo = 0
        var progAlu = 0; var progFill = 0; var uLayer = 0; var uTex = 0
        var renderer = "?"

        fun init() {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val ver = IntArray(2)
            check(EGL14.eglInitialize(dpy, ver, 0, ver, 1)) { "eglInitialize failed" }
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            check(EGL14.eglChooseConfig(dpy, attribs, 0, cfgs, 0, 1, num, 0) && num[0] > 0) { "no ES3 EGL config" }
            ctx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
            check(ctx != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
            surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) { "eglMakeCurrent failed" }
            renderer = (GLES20.glGetString(GLES20.GL_RENDERER) ?: "?") + " • " + (GLES20.glGetString(GLES20.GL_VERSION) ?: "")

            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0); target = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, target)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, W, H, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glGenFramebuffers(1, ids, 0); fbo = ids[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, target, 0)
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete" }

            val rnd = Random(1234)
            val nb = ByteBuffer.allocateDirect(512 * 512 * 4).order(ByteOrder.nativeOrder())
            repeat(512 * 512 * 4) { nb.put(rnd.nextInt(256).toByte()) }
            nb.position(0)
            GLES20.glGenTextures(1, ids, 0); noise = ids[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noise)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 512, 512, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, nb)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_REPEAT)

            val quad = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
            val fb = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(quad)
            fb.position(0)
            GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
            GLES30.glBindVertexArray(vao)
            GLES20.glGenBuffers(1, ids, 0); vbo = ids[0]
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quad.size * 4, fb, GLES20.GL_STATIC_DRAW)
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0)

            progAlu = program(VS, FS_ALU)
            progFill = program(VS, FS_FILL)
            uLayer = GLES20.glGetUniformLocation(progFill, "layer")
            uTex = GLES20.glGetUniformLocation(progFill, "tex")
        }

        private fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader compile failed: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }

        private fun program(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { "program link failed: " + GLES20.glGetProgramInfoLog(p) }
            return p
        }

        fun drawAlu() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, W, H)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUseProgram(progAlu)
            GLES30.glBindVertexArray(vao)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glFinish()
        }

        fun drawFill() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            GLES20.glViewport(0, 0, W, H)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            GLES20.glUseProgram(progFill)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, noise)
            GLES20.glUniform1i(uTex, 0)
            GLES30.glBindVertexArray(vao)
            for (i in 0 until FILL_LAYERS) {
                GLES20.glUniform1f(uLayer, i.toFloat())
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            }
            GLES20.glFinish()
        }

        fun release() {
            runCatching {
                EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(dpy, surf)
                EGL14.eglDestroyContext(dpy, ctx)
                EGL14.eglTerminate(dpy)
            }
        }
    }

    private class Stats {
        val gpu = ArrayList<Double>(); val mhz = ArrayList<Double>(); val watts = ArrayList<Double>()
        var gpuTemp: Double? = null; var cpuTemp: Double? = null; var lastTs = 0L
        fun poll() {
            val s = Telemetry.snapshot.value ?: return
            if (s.timeMs == lastTs) return
            lastTs = s.timeMs
            s.gpuBusy.value?.let { gpu += it }
            s.gpuFreq.value?.let { mhz += it }
            s.power.value?.let { watts += it }
            s.gpuTemp.value?.let { t -> gpuTemp = maxOf(gpuTemp ?: t, t) }
            s.cpuTemp.value?.let { t -> cpuTemp = maxOf(cpuTemp ?: t, t) }
        }
    }

    /** Blocking. Call from a background thread. stabilityMinutes = 0 → quick run. */
    fun run(stabilityMinutes: Int): Result {
        cancelled = false
        val gl = Gl()
        val stats = Stats()
        val t0 = System.nanoTime()
        try {
            progress.value = Progress("Init GPU", 0f, 0.0)
            gl.init()
            val segments = ArrayList<Double>()
            fun phase(name: String, seconds: Double, base: Float, span: Float, draw: () -> Unit): Double {
                val start = System.nanoTime()
                val end = start + (seconds * 1e9).toLong()
                var n = 0
                var lastUi = start
                var lastN = 0
                while (System.nanoTime() < end && !cancelled) {
                    draw(); n++
                    val now = System.nanoTime()
                    if (now - lastUi > 500_000_000L) {
                        val live = (n - lastN) / ((now - lastUi) / 1e9)
                        lastUi = now; lastN = n
                        stats.poll()
                        progress.value = Progress(name, base + span * ((now - start) / (seconds * 1e9)).toFloat(), live)
                    }
                }
                return n / ((System.nanoTime() - start) / 1e9)
            }
            phase("Warm-up", 3.0, 0f, 0.05f) { gl.drawAlu() }
            val alu: Double
            val fill: Double
            val mode: String
            if (stabilityMinutes <= 0) {
                mode = "Quick"
                alu = phase("ALU (Mandelbrot 1080p)", 12.0, 0.05f, 0.5f) { gl.drawAlu() }
                fill = phase("Fill/Texture (24 layers 1080p)", 10.0, 0.55f, 0.45f) { gl.drawFill() }
            } else {
                mode = "Stability ${stabilityMinutes}m"
                val segs = (stabilityMinutes * 60 / 15).coerceAtLeast(2)
                for (i in 0 until segs) {
                    if (cancelled) break
                    segments += phase("Stability ${i + 1}/$segs", 15.0, i.toFloat() / segs, 1f / segs) { gl.drawAlu() }
                }
                alu = segments.firstOrNull() ?: 0.0
                fill = phase("Fill/Texture", 6.0, 0.99f, 0.01f) { gl.drawFill() }
            }
            check(!cancelled) { "cancelled" }
            val stability = if (segments.size >= 2) segments.min() / segments.max() * 100.0 else null
            val score = (alu * 10 + fill * 8).roundToInt()
            return Result(
                System.currentTimeMillis(), mode, gl.renderer, alu, fill, score, segments, stability,
                stats.gpu.takeIf { it.isNotEmpty() }?.average(), stats.mhz.takeIf { it.isNotEmpty() }?.average(),
                stats.gpuTemp, stats.cpuTemp, stats.watts.takeIf { it.isNotEmpty() }?.average(), (System.nanoTime() - t0) / 1e9
            )
        } finally {
            gl.release()
            progress.value = null
        }
    }

    fun history(ctx: Context): List<Result> {
        val arr = runCatching { JSONArray(Prefs.get(ctx).getString("gl_history", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { runCatching { Result.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    fun save(ctx: Context, r: Result) {
        val list = (listOf(r) + history(ctx)).take(60)
        Prefs.get(ctx).edit().putString("gl_history", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    fun clear(ctx: Context) = Prefs.get(ctx).edit().remove("gl_history").apply()
}
