package com.elitetunerx.tune

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.elitetunerx.Prefs
import com.elitetunerx.core.Shell
import com.elitetunerx.session.Recorder
import com.elitetunerx.session.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** RAM state as Android reports it (same numbers as the system memory screen). */
data class Ram(val usedGb: Double, val totalGb: Double) {
    val pct: Double get() = if (totalGb > 0) usedGb / totalGb * 100 else 0.0
    override fun toString() = String.format(Locale.US, "%.0f%% (%.2f / %.2f GB)", pct, usedGb, totalGb)
}

data class BoostOptions(
    val killApps: Boolean = true,
    val freezeBloat: Boolean = true,
    val noDoze: Boolean = true,
    val phantom: Boolean = true,
    val thermalOverride: Boolean = false,
    val joyose: Boolean = false,
    val cachedLimit: Boolean = false,
)

data class BoostReport(val before: Ram, val after: Ram, val log: String) {
    val freedGb get() = before.usedGb - after.usedGb
}

object Boost {
    private fun sh(c: String) = Shell.sh(c, 20000)

    fun ram(ctx: Context): Ram {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        val gb = 1024.0 * 1024 * 1024
        return Ram((mi.totalMem - mi.availMem).coerceAtLeast(0) / gb, mi.totalMem / gb)
    }

    /**
     * Xiaomi/HyperOS background packages that are safe to freeze (ads, analytics, feedback, preinstalled
     * Facebook stubs, app store auto-updater). Only those installed on the device are shown.
     */
    val bloat = listOf(
        "com.miui.analytics" to "MIUI Analytics (เก็บสถิติ)",
        "com.miui.msa.global" to "MSA (ระบบโฆษณา)",
        "com.miui.daemon" to "MIUI Daemon (ส่งข้อมูลการใช้งาน)",
        "com.miui.bugreport" to "Bug report",
        "com.xiaomi.mipicks" to "GetApps (อัปเดตเบื้องหลัง)",
        "com.miui.hybrid" to "Quick Apps",
        "com.mi.globalminusscreen" to "App Vault",
        "com.facebook.services" to "Facebook Services",
        "com.facebook.system" to "Facebook App Installer",
        "com.facebook.appmanager" to "Facebook App Manager",
        "com.miui.yellowpage" to "Yellow pages",
        "com.xiaomi.glgm" to "Games (ศูนย์เกม Xiaomi)",
    )

    fun installedBloat(): List<Pair<String, String>> {
        val all = sh("pm list packages").out.lines().map { it.removePrefix("package:").trim() }.toSet()
        return bloat.filter { it.first in all }
    }

    fun frozenBloat(): Set<String> =
        sh("pm list packages -d").out.lines().map { it.removePrefix("package:").trim() }.toSet()

    fun selectedBloat(ctx: Context): Set<String> =
        Prefs.get(ctx).getStringSet("bloat_sel", null) ?: bloat.take(4).map { it.first }.toSet()

    fun setSelectedBloat(ctx: Context, s: Set<String>) = Prefs.get(ctx).edit().putStringSet("bloat_sel", s).apply()

    fun freeze(pkg: String, on: Boolean): String {
        val r = if (on) sh("pm disable-user --user 0 $pkg") else sh("pm enable --user 0 $pkg")
        return (if (r.ok) "✔ " else "✖ ") + (if (on) "แช่แข็ง " else "คืน ") + pkg + if (!r.ok) " (${r.text().take(80)})" else ""
    }

    private fun protectedPkgs(ctx: Context, target: String?): Set<String> {
        val home = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName
        val ime = sh("settings get secure default_input_method").out.trim().substringBefore('/')
        return setOfNotNull(
            target, home, ime, ctx.packageName, "moe.shizuku.privileged.api", "com.android.systemui",
            "com.google.android.gms", "com.android.vending",
        )
    }

    /** Force-stops every running third-party app except the game, launcher, keyboard and Shizuku. */
    fun killApps(ctx: Context, target: String?): String {
        val third = sh("pm list packages -3").out.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotEmpty() }.toSet()
        val running = sh("ps -A -o NAME").out.lines().map { it.trim().substringBefore(':') }.toSet()
        val keep = protectedPkgs(ctx, target)
        val victims = third.filter { it in running && it !in keep }
        if (victims.isNotEmpty()) sh(victims.joinToString("; ") { "am force-stop $it" })
        sh("am kill-all")
        return "ปิดแอปเบื้องหลัง ${victims.size} แอป" + if (victims.isNotEmpty()) ": " + victims.joinToString(", ") { it.substringAfterLast('.') } else ""
    }

    fun apply(ctx: Context, target: String?, o: BoostOptions): BoostReport {
        val before = ram(ctx)
        val log = StringBuilder()
        if (!Shell.ready) return BoostReport(before, before, "✖ ต้องเปิด Shizuku ก่อน")
        if (o.freezeBloat) {
            val sel = selectedBloat(ctx)
            installedBloat().filter { it.first in sel }.forEach { log.appendLine(freeze(it.first, true)) }
        }
        if (o.joyose) log.appendLine(freeze("com.xiaomi.joyose", true) + " — ปิดการคุม FPS/ความร้อนจาก cloud ของ Xiaomi")
        if (o.killApps) log.appendLine("✔ " + killApps(ctx, target))
        if (o.noDoze) {
            val r = sh("dumpsys deviceidle disable")
            log.appendLine((if (r.ok) "✔ " else "✖ ") + "ปิด Doze ระหว่างเล่น")
        }
        if (target != null) {
            val r = sh("am set-standby-bucket $target active")
            log.appendLine((if (r.ok) "✔ " else "✖ ") + "ตั้ง $target เป็น active")
        }
        if (o.phantom) log.appendLine(Tweaks.phantomKiller(ctx, true).lineSequence().first())
        if (o.cachedLimit) {
            val r = sh("device_config put activity_manager max_cached_processes 12")
            log.appendLine((if (r.ok) "✔ " else "✖ ") + "จำกัดแอปค้างใน RAM = 12")
        }
        if (o.thermalOverride) {
            val r = sh("cmd thermalservice override-status 0")
            log.appendLine((if (r.ok) "✔ " else "✖ ") + "thermal status → none (เกมจะไม่ลดกราฟิกเอง)")
        }
        Thread.sleep(2500)
        val after = ram(ctx)
        Prefs.get(ctx).edit().putBoolean("boost_on", true).apply()
        return BoostReport(before, after, log.toString().trim())
    }

    fun revert(ctx: Context): String {
        if (!Shell.ready) return "✖ ต้องเปิด Shizuku ก่อน"
        val log = StringBuilder()
        val frozen = frozenBloat()
        (bloat.map { it.first } + "com.xiaomi.joyose").filter { it in frozen }.forEach { log.appendLine(freeze(it, false)) }
        sh("dumpsys deviceidle enable"); log.appendLine("✔ เปิด Doze คืน")
        sh("device_config delete activity_manager max_cached_processes"); log.appendLine("✔ คืนค่าจำนวนแอปค้าง")
        sh("cmd thermalservice reset"); log.appendLine("✔ คืนค่า thermal status")
        log.appendLine(Tweaks.phantomKiller(ctx, false).lineSequence().first())
        Prefs.get(ctx).edit().putBoolean("boost_on", false).apply()
        return log.toString().trim()
    }

    // ------------------------------------------------------------------ system resolution
    fun physicalSize(): Pair<Int, Int>? {
        val m = Regex("Physical size: (\\d+)x(\\d+)").find(sh("wm size").out) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    fun physicalDensity(): Int? = Regex("Physical density: (\\d+)").find(sh("wm density").out)?.groupValues?.get(1)?.toInt()

    fun setScale(pct: Int): String {
        if (!Shell.ready) return "✖ ต้องเปิด Shizuku ก่อน"
        if (pct >= 100) {
            sh("wm size reset; wm density reset")
            return "✔ คืนความละเอียดเต็ม: " + sh("wm size").out.trim().replace('\n', ' ')
        }
        val (w, h) = physicalSize() ?: return "✖ อ่านความละเอียดจอไม่ได้"
        val d = physicalDensity() ?: return "✖ อ่าน density ไม่ได้"
        val nw = (w * pct / 100) / 2 * 2
        val nh = (h * pct / 100) / 2 * 2
        val nd = d * pct / 100
        val r = sh("wm size ${nw}x$nh; wm density $nd")
        return (if (r.ok) "✔ " else "✖ ") + "ความละเอียดระบบ $pct%: ${nw}x$nh (density $nd) — จำนวนพิกเซลลดเหลือ ${pct * pct / 100}% • ค่านี้อยู่ถาวรแม้รีบูต กด 100% เพื่อคืน"
    }
}

/**
 * Automatic A/B test inside the real game:
 *  A) record N seconds as-is → B) apply Boost → C) record N seconds again → compare.
 */
object BoostTest {
    val phase = MutableStateFlow<String?>(null)
    val result = MutableStateFlow<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    val running get() = job?.isActive == true

    fun cancel() {
        job?.cancel(); job = null
        Recorder.active.value?.let { Recorder.appCtx?.let { c -> Recorder.stop(c) } }
        phase.value = null
    }

    private suspend fun record(label: String, seconds: Int): Session? {
        Recorder.start(label, seconds)
        while (Recorder.active.value != null) {
            val a = Recorder.active.value ?: break
            val left = seconds - ((System.currentTimeMillis() - a.startMs) / 1000).toInt()
            phase.value = "$label • เหลือ ${left.coerceAtLeast(0)} วิ — อยู่จุดเดิม เล่นแบบเดิม"
            delay(500)
        }
        return Recorder.lastSaved.value
    }

    fun start(ctx: Context, target: String, opts: BoostOptions, seconds: Int = 30, loadWait: Int = 20) {
        if (running) return
        result.value = null
        val app = ctx.applicationContext
        job = scope.launch {
            try {
                for (i in loadWait downTo 1) {
                    phase.value = "เข้าเกมแล้วไปจุดทดสอบ… เริ่มวัดใน $i วิ"
                    delay(1000)
                }
                val game = target.substringAfterLast('.')
                val a = record("A/B $game • ก่อน Boost", seconds) ?: error("วัดรอบแรกไม่ได้")
                phase.value = "กำลัง Boost…"
                val rep = withContext(Dispatchers.IO) { Boost.apply(app, target, opts) }
                for (i in 8 downTo 1) {
                    phase.value = "Boost แล้ว (RAM ${String.format(Locale.US, "%.0f%%→%.0f%%", rep.before.pct, rep.after.pct)}) • รอระบบนิ่ง $i วิ"
                    delay(1000)
                }
                val b = record("A/B $game • หลัง Boost", seconds) ?: error("วัดรอบสองไม่ได้")
                val fa = a.summary.avgFps; val fb = b.summary.avgFps
                val la = a.summary.low1; val lb = b.summary.low1
                fun pct(x: Double?, y: Double?) = if (x != null && y != null && x > 0) String.format(Locale.US, "%+.1f%%", (y / x - 1) * 100) else "N/A"
                fun f(v: Double?) = v?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"
                val verdict = when {
                    fa == null || fb == null -> "วัด FPS ไม่ได้ (ต้องเปิด Shizuku และอยู่ในเกม)"
                    fb / fa >= 1.03 || (la != null && lb != null && lb / la >= 1.05) -> "✔ Boost ช่วยได้จริงในเกมนี้"
                    fb / fa <= 0.97 -> "✖ หลัง Boost แย่ลง — น่าจะเพราะความร้อนสะสม ลองเทสตอนเครื่องเย็น"
                    else -> "≈ ต่างกันไม่เกิน 3% — เกมนี้ไม่ได้ติดที่ RAM/เบื้องหลัง (ติดที่ GPU/ความร้อน)"
                }
                result.value = "FPS ${f(fa)} → ${f(fb)} (${pct(fa, fb)}) • 1% low ${f(la)} → ${f(lb)} (${pct(la, lb)})\n" +
                    "RAM ${String.format(Locale.US, "%.0f%% → %.0f%%", rep.before.pct, rep.after.pct)} (คืน ${String.format(Locale.US, "%.2f", rep.freedGb)} GB)\n$verdict"
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) result.value = "✖ ${t.message}"
            } finally {
                phase.value = null
            }
        }
    }
}
