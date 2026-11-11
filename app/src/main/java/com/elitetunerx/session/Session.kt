package com.elitetunerx.session

import android.content.Context
import com.elitetunerx.core.Snapshot
import com.elitetunerx.core.Telemetry
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

data class Sample(
    val t: Double, val fps: Double?, val gpu: Double?, val gpuMhz: Double?, val cpu: Double?,
    val cpuTemp: Double?, val gpuTemp: Double?, val watts: Double?, val batTemp: Double?,
)

data class Summary(
    val frames: Int, val durationS: Double, val avgFps: Double?, val low1: Double?, val low01: Double?,
    val medianFt: Double?, val p99Ft: Double?, val ftStdev: Double?, val jankPct: Double?,
    val avgGpu: Double?, val avgGpuMhz: Double?, val avgCpu: Double?, val maxCpuTemp: Double?,
    val maxGpuTemp: Double?, val avgWatts: Double?, val fpsPerWatt: Double?,
)

class Session(
    val id: String, val label: String, val pkg: String?, val startMs: Long, val durationS: Double,
    val frameTimes: DoubleArray, val samples: List<Sample>,
) {
    val summary: Summary by lazy { summarize() }

    private fun pct(sorted: DoubleArray, p: Double): Double? {
        if (sorted.isEmpty()) return null
        val idx = (ceil(p / 100.0 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }

    private fun avg(xs: List<Double?>): Double? = xs.filterNotNull().takeIf { it.isNotEmpty() }?.average()

    private fun summarize(): Summary {
        val ft = frameTimes
        val sorted = ft.copyOf().also { it.sort() }
        val total = ft.sum()
        val avgFps = if (total > 0) ft.size / (total / 1000.0) else avg(samples.map { it.fps })
        val p99 = pct(sorted, 99.0)
        val p999 = pct(sorted, 99.9)
        val median = pct(sorted, 50.0)
        val mean = if (ft.isNotEmpty()) total / ft.size else null
        val sd = mean?.let { m -> sqrt(ft.sumOf { (it - m) * (it - m) } / ft.size) }
        val jank = median?.let { m -> ft.count { it > 2 * m } * 100.0 / ft.size }
        val watts = avg(samples.map { it.watts })
        return Summary(
            frames = ft.size, durationS = durationS, avgFps = avgFps,
            low1 = p99?.let { 1000.0 / it }, low01 = p999?.let { 1000.0 / it },
            medianFt = median, p99Ft = p99, ftStdev = sd, jankPct = jank,
            avgGpu = avg(samples.map { it.gpu }), avgGpuMhz = avg(samples.map { it.gpuMhz }),
            avgCpu = avg(samples.map { it.cpu }),
            maxCpuTemp = samples.mapNotNull { it.cpuTemp }.maxOrNull(),
            maxGpuTemp = samples.mapNotNull { it.gpuTemp }.maxOrNull(),
            avgWatts = watts, fpsPerWatt = if (avgFps != null && watts != null && watts > 0) avgFps / watts else null,
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("label", label); put("pkg", pkg ?: ""); put("startMs", startMs); put("durationS", durationS)
        put("frameTimes", JSONArray().apply { frameTimes.forEach { put(Math.round(it * 1000) / 1000.0) } })
        put("samples", JSONArray().apply {
            samples.forEach { s ->
                put(JSONObject().apply {
                    put("t", s.t); s.fps?.let { put("fps", it) }; s.gpu?.let { put("gpu", it) }; s.gpuMhz?.let { put("gpuMhz", it) }
                    s.cpu?.let { put("cpu", it) }; s.cpuTemp?.let { put("cpuTemp", it) }; s.gpuTemp?.let { put("gpuTemp", it) }
                    s.watts?.let { put("watts", it) }; s.batTemp?.let { put("batTemp", it) }
                })
            }
        })
    }

    fun toCsv(): String {
        val sb = StringBuilder()
        val s = summary
        fun f(v: Double?) = v?.let { String.format(Locale.US, "%.3f", it) } ?: ""
        sb.append("# Elite Tuner X session\n# label,$label\n# package,${pkg ?: ""}\n# duration_s,${f(durationS)}\n")
        sb.append("# avg_fps,${f(s.avgFps)}\n# 1pct_low,${f(s.low1)}\n# 0.1pct_low,${f(s.low01)}\n# jank_pct,${f(s.jankPct)}\n")
        sb.append("# avg_gpu_pct,${f(s.avgGpu)}\n# avg_gpu_mhz,${f(s.avgGpuMhz)}\n# avg_watts,${f(s.avgWatts)}\n")
        sb.append("t_s,fps,gpu_pct,gpu_mhz,cpu_pct,cpu_temp_c,gpu_temp_c,watts,battery_temp_c\n")
        samples.forEach { x -> sb.append("${f(x.t)},${f(x.fps)},${f(x.gpu)},${f(x.gpuMhz)},${f(x.cpu)},${f(x.cpuTemp)},${f(x.gpuTemp)},${f(x.watts)},${f(x.batTemp)}\n") }
        sb.append("\nframe_index,frame_time_ms\n")
        frameTimes.forEachIndexed { i, v -> sb.append(i).append(',').append(f(v)).append('\n') }
        return sb.toString()
    }

    companion object {
        fun fromJson(o: JSONObject): Session {
            val fa = o.optJSONArray("frameTimes") ?: JSONArray()
            val ft = DoubleArray(fa.length()) { fa.getDouble(it) }
            val sa = o.optJSONArray("samples") ?: JSONArray()
            fun JSONObject.d(k: String): Double? = if (has(k)) optDouble(k) else null
            val samples = (0 until sa.length()).map { i ->
                val s = sa.getJSONObject(i)
                Sample(s.optDouble("t"), s.d("fps"), s.d("gpu"), s.d("gpuMhz"), s.d("cpu"), s.d("cpuTemp"), s.d("gpuTemp"), s.d("watts"), s.d("batTemp"))
            }
            return Session(
                o.getString("id"), o.optString("label"), o.optString("pkg").ifEmpty { null }, o.optLong("startMs"),
                o.optDouble("durationS"), ft, samples
            )
        }
    }
}

class ActiveSession(val id: String, val label: String, var pkg: String?, val startMs: Long) {
    val frames = ArrayList<Double>()
    val samples = ArrayList<Sample>()
}

object Recorder {
    val active = MutableStateFlow<ActiveSession?>(null)

    fun start(label: String) {
        if (active.value != null) return
        val now = System.currentTimeMillis()
        active.value = ActiveSession(now.toString(), label.ifBlank { "Session" }, Telemetry.snapshot.value?.fgPackage, now)
    }

    fun onSnapshot(s: Snapshot) {
        val a = active.value ?: return
        synchronized(a) {
            if (a.pkg == null) a.pkg = s.fgPackage
            a.frames.addAll(s.newFrameTimes)
            a.samples.add(
                Sample(
                    (s.timeMs - a.startMs) / 1000.0, s.fps.value, s.gpuBusy.value, s.gpuFreq.value, s.cpuUsage.value,
                    s.cpuTemp.value, s.gpuTemp.value, s.power.value, s.batteryTemp.value
                )
            )
        }
    }

    fun stop(ctx: Context): Session? {
        val a = active.value ?: return null
        active.value = null
        val sess = synchronized(a) {
            Session(a.id, a.label, a.pkg, a.startMs, (System.currentTimeMillis() - a.startMs) / 1000.0, a.frames.toDoubleArray(), a.samples.toList())
        }
        SessionStore.save(ctx, sess)
        return sess
    }

    fun toggle(ctx: Context, label: String) {
        if (active.value == null) start(label) else stop(ctx)
    }
}

object SessionStore {
    val version = MutableStateFlow(0)

    private fun dir(ctx: Context) = File(ctx.filesDir, "sessions").apply { mkdirs() }

    fun save(ctx: Context, s: Session) {
        File(dir(ctx), "${s.id}.json").writeText(s.toJson().toString())
        version.value++
    }

    fun list(ctx: Context): List<Session> =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { runCatching { Session.fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.startMs }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.json").delete()
        version.value++
    }

    fun exportCsv(ctx: Context, s: Session): File {
        val d = File(ctx.cacheDir, "export").apply { mkdirs() }
        val safe = s.label.replace(Regex("[^A-Za-z0-9._-]+"), "_").take(40)
        return File(d, "etx_${safe}_${s.id}.csv").apply { writeText(s.toCsv()) }
    }
}
