package com.elitetunerx.core

import android.os.SystemClock

/**
 * Measures the real frame rate of the focused app from SurfaceFlinger's present
 * timestamps (`dumpsys SurfaceFlinger --latency <layer>`). These are the exact times each
 * frame of the layer reached the display, so FPS and frame-times derived from them are what
 * the user actually sees. Requires the shell user (Shizuku).
 */
object FpsMeter {
    data class Result(
        val frameTimesMs: List<Double>,
        val displayFps: Double?,
        val pkg: String?,
        val layer: String?,
        val source: String,
    )

    const val SRC_LAYER = "SurfaceFlinger present timestamps"
    const val SRC_FLIP = "SurfaceFlinger page-flip counter (whole display)"

    private var pkg: String? = null
    private var layer: String? = null
    private var lastTs = 0L
    private var lastFocusCheck = 0L
    private var lastLayerPick = 0L
    private var emptyPolls = 0
    private var flipCount = -1L
    private var flipAt = 0L

    @Synchronized
    fun reset() {
        pkg = null; layer = null; lastTs = 0; lastFocusCheck = 0; lastLayerPick = 0; emptyPolls = 0; flipCount = -1
    }

    val currentLayer: String? get() = layer

    @Synchronized
    fun poll(): Result {
        val now = SystemClock.elapsedRealtime()
        if (pkg == null || now - lastFocusCheck > 3000) {
            lastFocusCheck = now
            val p = focusedPackage()
            if (p != null && p != pkg) {
                pkg = p; layer = null; lastTs = 0
            }
        }
        val p = pkg
        if (p != null && (layer == null || emptyPolls >= 6 || now - lastLayerPick > 15000)) {
            lastLayerPick = now
            emptyPolls = 0
            val l = pickLayer(p)
            if (l != layer) {
                layer = l; lastTs = 0
            }
        }
        val l = layer
        if (l != null) {
            val ts = latency(l)
            if (ts.isNotEmpty()) {
                val ft = consume(ts)
                if (ft.isEmpty()) emptyPolls++ else emptyPolls = 0
                return Result(ft, null, p, l, SRC_LAYER)
            }
        }
        val f = pageFlips()
        var fps: Double? = null
        if (f != null) {
            if (flipCount >= 0 && now > flipAt && f >= flipCount) fps = (f - flipCount) * 1000.0 / (now - flipAt)
            flipCount = f
            flipAt = now
        }
        return Result(emptyList(), fps, p, null, SRC_FLIP)
    }

    private fun consume(ts: List<Long>): List<Double> {
        if (lastTs == 0L) {
            lastTs = ts.last()
            return emptyList()
        }
        val fresh = ts.filter { it > lastTs }
        if (fresh.isEmpty()) return emptyList()
        // SurfaceFlinger keeps ~128 frames. If the oldest kept frame is newer than the last one we saw,
        // frames were dropped from the history between polls; only use intervals we really observed.
        val overflow = ts.first() > lastTs
        val seq = if (overflow) fresh else listOf(lastTs) + fresh
        lastTs = fresh.last()
        val out = ArrayList<Double>(seq.size)
        for (i in 1 until seq.size) out.add((seq[i] - seq[i - 1]) / 1e6)
        return out
    }

    fun focusedPackage(): String? {
        val r = Shell.sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp' | head -n 4", 4000)
        return Regex("u\\d+ ([A-Za-z][\\w.]*)/").find(r.out)?.groupValues?.get(1)
    }

    private fun latency(layer: String): List<Long> {
        val r = Shell.exec(arrayOf("dumpsys", "SurfaceFlinger", "--latency", layer), 3000)
        val out = ArrayList<Long>(128)
        r.out.lineSequence().drop(1).forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 3) {
                val present = parts[1].toLongOrNull()
                if (present != null && present > 0 && present != Long.MAX_VALUE) out.add(present)
            }
        }
        out.sort()
        return out
    }

    private fun pickLayer(pkg: String): String? {
        val all = Shell.exec(arrayOf("dumpsys", "SurfaceFlinger", "--list"), 4000).out.lines()
            .map { it.trim() }
            .filter { it.contains(pkg) && !it.startsWith("Background for") && !it.contains("Bounds for") }
        if (all.isEmpty()) return null
        val ranked = all.sortedByDescending {
            (if (it.contains("SurfaceView")) 4 else 0) + (if (it.contains("BLAST")) 2 else 0) + (if (it.contains("#")) 1 else 0)
        }.take(8)
        var best: String? = null
        var bestScore = -1L
        val nowNs = System.nanoTime()
        for (c in ranked) {
            val ts = latency(c)
            if (ts.size < 2) continue
            val last = ts.last()
            val recent = if (nowNs - last < 2_000_000_000L) ts.count { it > last - 1_000_000_000L }.toLong() else 0L
            if (recent > bestScore) {
                bestScore = recent; best = c
            }
        }
        return best ?: ranked.firstOrNull { it.contains("SurfaceView") }
    }

    private fun pageFlips(): Long? {
        val r = Shell.exec(arrayOf("service", "call", "SurfaceFlinger", "1013"), 3000)
        val m = Regex("Parcel\\(\\s*([0-9a-fA-F]{8})\\s+([0-9a-fA-F]{8})").find(r.out) ?: return null
        return m.groupValues[2].toLongOrNull(16)
    }
}
