package com.elitetunerx.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.elitetunerx.session.Recorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

/** One measured value. [value] is null when no verified source exists (shown as N/A, never faked). */
data class Reading(val value: Double?, val text: String, val source: String) {
    val ok: Boolean get() = value != null

    companion object {
        fun na(source: String) = Reading(null, "N/A", source)
        fun of(v: Double?, fmt: String, unit: String, source: String) =
            if (v == null) na(source) else Reading(v, String.format(Locale.US, fmt, v) + unit, source)
    }
}

data class ClusterFreq(val name: String, val curMhz: Double?, val maxMhz: Double?)

data class Snapshot(
    val timeMs: Long,
    val shell: Shell.State,
    val fgPackage: String?,
    val fps: Reading,
    val frameTime: Reading,
    val newFrameTimes: List<Double>,
    val gpuBusy: Reading,
    val gpuFreq: Reading,
    val gpuTemp: Reading,
    val gpuModel: String?,
    val cpuUsage: Reading,
    val clusters: List<ClusterFreq>,
    val cpuTemp: Reading,
    val skinTemp: Reading,
    val batteryTemp: Reading,
    val power: Reading,
    val charging: Boolean,
    val ramUsedGb: Double,
    val ramTotalGb: Double,
    val thermalStatus: String,
    val headroom: Reading,
)

object Telemetry {
    val snapshot = MutableStateFlow<Snapshot?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var users = 0

    @Synchronized
    fun acquire(ctx: Context) {
        users++
        if (job == null) {
            val app = ctx.applicationContext
            job = scope.launch { loop(app) }
        }
    }

    @Synchronized
    fun release() {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) {
            job?.cancel(); job = null
        }
    }

    // ------------------------------------------------------------------ discovery
    private class Probe {
        val direct = HashSet<String>()
        val shell = HashSet<String>()
        var policies = listOf<Pair<String, String>>()
        var gpuBusyPath: String? = null
        var gpuBusyPair = false
        var gpuFreqPath: String? = null
        var gpuFreqToMhz = 1e-6
        var gpuTempPath: String? = null
        var gpuModel: String? = null
        var cpuZones = listOf<String>()
        var gpuZones = listOf<String>()
        var skinZones = listOf<String>()
        var procStat = false
        fun via(path: String) = when (path) {
            in direct -> "app"
            in shell -> "shell"
            else -> null
        }
    }

    /** Tries each path as the app first, then as the shell user. Remembers what worked. */
    private fun resolve(p: Probe, paths: List<String>, priv: Boolean): Map<String, String?> {
        val res = HashMap<String, String?>()
        Sysfs.readMany(paths, false).forEach { (k, v) -> if (v != null) { res[k] = v; p.direct += k } }
        val missing = paths.filter { res[it] == null }
        if (priv && missing.isNotEmpty()) {
            Sysfs.readMany(missing, true).forEach { (k, v) -> if (v != null) { res[k] = v; p.shell += k } }
        }
        return res
    }

    private fun discover(priv: Boolean): Probe {
        val p = Probe()
        // CPU clusters
        val polDir = "/sys/devices/system/cpu/cpufreq"
        val pols = Sysfs.listDir(polDir, priv).filter { it.startsWith("policy") }
            .sortedBy { it.removePrefix("policy").toIntOrNull() ?: 0 }
        val polFiles = pols.flatMap { listOf("$polDir/$it/scaling_cur_freq", "$polDir/$it/cpuinfo_max_freq", "$polDir/$it/related_cpus") }
        val pr = resolve(p, polFiles, priv)
        p.policies = pols.map { pol ->
            val cpus = pr["$polDir/$pol/related_cpus"]?.trim()?.split(Regex("\\s+")).orEmpty()
            val label = if (cpus.size > 1) "CPU${cpus.first()}-${cpus.last()}" else "CPU${cpus.firstOrNull() ?: pol.removePrefix("policy")}"
            label to "$polDir/$pol"
        }

        // Adreno GPU (kgsl)
        val k = "/sys/class/kgsl/kgsl-3d0"
        val gpuFiles = listOf(
            "$k/gpu_busy_percentage", "$k/gpubusy", "$k/gpuclk", "$k/devfreq/cur_freq", "$k/clock_mhz",
            "$k/temp", "$k/gpu_model", "/proc/stat"
        )
        val g = resolve(p, gpuFiles, priv)
        when {
            g["$k/gpu_busy_percentage"] != null -> p.gpuBusyPath = "$k/gpu_busy_percentage"
            g["$k/gpubusy"] != null -> { p.gpuBusyPath = "$k/gpubusy"; p.gpuBusyPair = true }
        }
        when {
            g["$k/gpuclk"] != null -> { p.gpuFreqPath = "$k/gpuclk"; p.gpuFreqToMhz = 1e-6 }
            g["$k/devfreq/cur_freq"] != null -> { p.gpuFreqPath = "$k/devfreq/cur_freq"; p.gpuFreqToMhz = 1e-6 }
            g["$k/clock_mhz"] != null -> { p.gpuFreqPath = "$k/clock_mhz"; p.gpuFreqToMhz = 1.0 }
        }
        if (g["$k/temp"] != null) p.gpuTempPath = "$k/temp"
        p.gpuModel = g["$k/gpu_model"]
        p.procStat = g["/proc/stat"] != null

        // Thermal zones
        val tz = "/sys/class/thermal"
        val zones = Sysfs.listDir(tz, priv).filter { it.startsWith("thermal_zone") }
        val types = resolve(p, zones.map { "$tz/$it/type" }, priv)
        val cpu = ArrayList<String>(); val gpu = ArrayList<String>(); val skin = ArrayList<String>()
        zones.forEach { z ->
            val t = types["$tz/$z/type"]?.lowercase() ?: return@forEach
            val path = "$tz/$z/temp"
            when {
                t.contains("gpu") -> gpu += path
                t.contains("cpu") && !t.contains("cpuss-max") -> cpu += path
                t.contains("skin") || t.contains("quiet") || t.contains("xo-therm") || t.contains("sys-therm") ||
                    t.contains("board") -> skin += path
            }
        }
        val tr = resolve(p, (cpu + gpu + skin), priv)
        p.cpuZones = cpu.filter { tr[it] != null }.take(24)
        p.gpuZones = gpu.filter { tr[it] != null }.take(12)
        p.skinZones = skin.filter { tr[it] != null }.take(6)
        return p
    }

    // ------------------------------------------------------------------ sampling
    private fun temp(raw: String?): Double? {
        val v = Sysfs.firstNumber(raw) ?: return null
        val c = if (abs(v) >= 1000) v / 1000.0 else v
        return if (c <= 0 || c > 150) null else c
    }

    private fun srcOf(p: Probe, path: String?, label: String): String =
        if (path == null) "$label: not readable" else "$label (${p.via(path) ?: "?"})"

    private suspend fun loop(ctx: Context) {
        var lastState: Shell.State? = null
        var probe: Probe? = null
        var prevStat: LongArray? = null
        var tick = 0
        val frames = ArrayList<Double>()
        var lastFps: FpsMeter.Result? = null
        var flipFps: Double? = null
        var lastFrameAt = 0L
        val am = ctx.getSystemService(ActivityManager::class.java)
        val pm = ctx.getSystemService(PowerManager::class.java)
        val bm = ctx.getSystemService(BatteryManager::class.java)

        while (currentCoroutineContext().isActive) {
            val t0 = SystemClock.elapsedRealtime()
            val st = Shell.state()
            if (st != lastState || probe == null || tick % 120 == 0) {
                probe = runCatching { discover(st == Shell.State.READY) }.getOrElse { Probe() }
                if (st != lastState) FpsMeter.reset()
                lastState = st
            }
            val p = probe!!
            val priv = st == Shell.State.READY

            if (priv) {
                val r = runCatching { FpsMeter.poll() }.getOrNull()
                if (r != null) {
                    lastFps = r
                    frames.addAll(r.frameTimesMs)
                    if (r.frameTimesMs.isNotEmpty()) lastFrameAt = t0
                    if (r.displayFps != null) flipFps = r.displayFps
                }
            }

            if (tick % 2 == 0) {
                val snap = runCatching {
                    sample(ctx, p, priv, st, frames.toList(), lastFps, flipFps, t0 - lastFrameAt, prevStat, am, pm, bm)
                }.getOrNull()
                frames.clear()
                if (snap != null) {
                    prevStat = snap.second
                    snapshot.value = snap.first
                    Recorder.onSnapshot(snap.first)
                }
            }
            tick++
            val spent = SystemClock.elapsedRealtime() - t0
            delay((500 - spent).coerceAtLeast(40))
        }
    }

    private fun sample(
        ctx: Context, p: Probe, priv: Boolean, st: Shell.State, frames: List<Double>, fpsRes: FpsMeter.Result?,
        flipFps: Double?, sinceLastFrameMs: Long, prevStat: LongArray?,
        am: ActivityManager, pm: PowerManager, bm: BatteryManager,
    ): Pair<Snapshot, LongArray?> {
        val wanted = ArrayList<String>()
        p.policies.forEach { (_, d) -> wanted += "$d/scaling_cur_freq"; wanted += "$d/cpuinfo_max_freq" }
        listOfNotNull(p.gpuBusyPath, p.gpuFreqPath, p.gpuTempPath).forEach { wanted += it }
        wanted += p.cpuZones; wanted += p.gpuZones; wanted += p.skinZones
        if (p.procStat) wanted += "/proc/stat"

        val directPaths = wanted.filter { it in p.direct }
        val shellPaths = wanted.filter { it in p.shell }
        val v = HashMap<String, String?>()
        v.putAll(Sysfs.readMany(directPaths, false))
        if (priv && shellPaths.isNotEmpty()) v.putAll(Sysfs.readMany(shellPaths, true))

        // FPS
        val fps: Reading
        val ft: Reading
        when {
            frames.isNotEmpty() -> {
                val sum = frames.sum()
                val f = frames.size / (sum / 1000.0)
                fps = Reading.of(f, "%.1f", "", FpsMeter.SRC_LAYER)
                ft = Reading.of(sum / frames.size, "%.2f", " ms", FpsMeter.SRC_LAYER)
            }
            fpsRes?.layer != null -> {
                // Layer exists but presented no new frame: the app is genuinely showing 0 new frames.
                val f = if (sinceLastFrameMs > 1500) 0.0 else null
                fps = if (f == null) Reading.na(FpsMeter.SRC_LAYER) else Reading.of(f, "%.1f", "", FpsMeter.SRC_LAYER)
                ft = Reading.na(FpsMeter.SRC_LAYER)
            }
            flipFps != null && priv -> {
                fps = Reading.of(flipFps, "%.1f", "", FpsMeter.SRC_FLIP)
                ft = Reading.of(if (flipFps > 0) 1000.0 / flipFps else null, "%.2f", " ms", FpsMeter.SRC_FLIP)
            }
            else -> {
                val why = if (priv) "no layer found" else "needs Shizuku"
                fps = Reading.na(why); ft = Reading.na(why)
            }
        }

        // GPU
        val gpuBusy = p.gpuBusyPath?.let { path ->
            val raw = v[path]
            val value = if (p.gpuBusyPair) {
                val n = raw?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toDoubleOrNull() }
                if (n != null && n.size >= 2) (if (n[1] > 0) n[0] / n[1] * 100.0 else 0.0) else null
            } else Sysfs.firstNumber(raw)
            Reading.of(value?.coerceIn(0.0, 100.0), "%.0f", "%", srcOf(p, path, "kgsl " + path.substringAfterLast('/')))
        } ?: Reading.na(if (priv) "kgsl busy node blocked on this firmware" else "needs Shizuku")
        val gpuFreq = p.gpuFreqPath?.let { path ->
            Reading.of(Sysfs.firstNumber(v[path])?.times(p.gpuFreqToMhz), "%.0f", " MHz", srcOf(p, path, "kgsl " + path.substringAfterLast('/')))
        } ?: Reading.na(if (priv) "kgsl clock node blocked" else "needs Shizuku")
        val gpuTempVal = p.gpuTempPath?.let { temp(v[it]) } ?: p.gpuZones.mapNotNull { temp(v[it]) }.maxOrNull()
        val gpuTemp = Reading.of(gpuTempVal, "%.1f", " °C", if (p.gpuTempPath != null) "kgsl temp" else "thermal zones (gpu*) max")

        // CPU
        var newStat: LongArray? = null
        val cpuUsage = run {
            val line = v["/proc/stat"]?.lineSequence()?.firstOrNull { it.startsWith("cpu ") }
            val nums = line?.trim()?.split(Regex("\\s+"))?.drop(1)?.mapNotNull { it.toLongOrNull() }
            if (nums == null || nums.size < 4) return@run Reading.na(if (priv) "/proc/stat unreadable" else "needs Shizuku (/proc/stat)")
            val idle = nums[3] + (nums.getOrNull(4) ?: 0)
            val total = nums.take(8).sum()
            newStat = longArrayOf(idle, total)
            if (prevStat == null) return@run Reading.na("/proc/stat (warming up)")
            val dt = total - prevStat[1]
            val di = idle - prevStat[0]
            val u = if (dt > 0) (1.0 - di.toDouble() / dt) * 100.0 else 0.0
            Reading.of(u.coerceIn(0.0, 100.0), "%.0f", "%", "/proc/stat (${p.via("/proc/stat")})")
        }
        val clusters = p.policies.map { (name, d) ->
            ClusterFreq(name, Sysfs.firstNumber(v["$d/scaling_cur_freq"])?.div(1000.0), Sysfs.firstNumber(v["$d/cpuinfo_max_freq"])?.div(1000.0))
        }
        val cpuTemp = Reading.of(p.cpuZones.mapNotNull { temp(v[it]) }.maxOrNull(), "%.1f", " °C", "thermal zones (cpu*) max")
        val skinTemp = Reading.of(p.skinZones.mapNotNull { temp(v[it]) }.maxOrNull(), "%.1f", " °C", "thermal zones (skin/board)")

        // Battery / power
        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val batTemp = bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)?.takeIf { it != Int.MIN_VALUE }?.div(10.0)
        val voltage = bat?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.takeIf { it > 0 }?.div(1000.0)
        val status = bat?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val curRaw = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val amps = if (curRaw == Int.MIN_VALUE || curRaw == 0) null
        else if (abs(curRaw) >= 20000) abs(curRaw) / 1_000_000.0 else abs(curRaw) / 1000.0
        val watts = if (amps != null && voltage != null) amps * voltage else null
        val power = Reading.of(watts, "%.2f", " W", if (charging) "battery current×voltage (charging: input power)" else "battery current×voltage (whole device)")
        val batteryTemp = Reading.of(batTemp, "%.1f", " °C", "BatteryManager")

        // RAM
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gb = 1024.0 * 1024 * 1024

        val thermal = when (pm.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Nominal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Unknown"
        }
        val headroom = if (Build.VERSION.SDK_INT >= 30) {
            val h = pm.getThermalHeadroom(0).toDouble()
            if (h.isNaN() || h <= 0) Reading.na("PowerManager headroom unsupported")
            else Reading.of(h * 100, "%.0f", "%", "PowerManager.getThermalHeadroom (100% = throttling)")
        } else Reading.na("API < 30")

        val snap = Snapshot(
            timeMs = System.currentTimeMillis(), shell = st, fgPackage = fpsRes?.pkg,
            fps = fps, frameTime = ft, newFrameTimes = frames,
            gpuBusy = gpuBusy, gpuFreq = gpuFreq, gpuTemp = gpuTemp, gpuModel = p.gpuModel,
            cpuUsage = cpuUsage, clusters = clusters, cpuTemp = cpuTemp, skinTemp = skinTemp,
            batteryTemp = batteryTemp, power = power, charging = charging,
            ramUsedGb = (mi.totalMem - mi.availMem).coerceAtLeast(0) / gb, ramTotalGb = mi.totalMem / gb,
            thermalStatus = thermal, headroom = headroom,
        )
        return snap to newStat
    }
}
