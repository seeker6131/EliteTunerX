package com.elitetunerx.tune

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import com.elitetunerx.Prefs
import com.elitetunerx.core.Shell
import java.util.Locale

/**
 * Non-root tuning through the ADB shell user (Shizuku). Every tweak:
 *  1. saves the original value (once) so it can be reverted,
 *  2. applies the change,
 *  3. reads the value back from the system and reports what is now really in effect.
 */
object Tweaks {
    private fun sh(cmd: String) = Shell.sh(cmd, 15000)
    private fun get(ns: String, key: String) = sh("settings get $ns $key").out.trim()

    private fun saveOrig(ctx: Context, ns: String, key: String) {
        val p = Prefs.get(ctx)
        val k = "orig:$ns/$key"
        if (!p.contains(k)) p.edit().putString(k, get(ns, key)).apply()
    }

    private fun restore(ctx: Context, ns: String, key: String): String {
        val p = Prefs.get(ctx)
        val k = "orig:$ns/$key"
        val v = p.getString(k, null) ?: return "$key: nothing saved"
        val r = if (v == "null" || v.isEmpty()) sh("settings delete $ns $key") else sh("settings put $ns $key $v")
        p.edit().remove(k).apply()
        return "$key ← ${if (v == "null" || v.isEmpty()) "(default)" else v}" + if (!r.ok) " [${r.text()}]" else ""
    }

    private fun put(ctx: Context, ns: String, key: String, value: String): String {
        saveOrig(ctx, ns, key)
        val r = sh("settings put $ns $key $value")
        val now = get(ns, key)
        val ok = r.ok && now == value
        return "${if (ok) "✔" else "✖"} $ns/$key = $now" + if (!r.ok) " (${r.text()})" else ""
    }

    private fun need(): String? = if (!Shell.ready) "✖ ต้องเปิด Shizuku และอนุญาตสิทธิ์ก่อน" else null

    // ---------------------------------------------------------------- display
    fun supportedRates(ctx: Context): List<Float> {
        val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyList()
        val cur = d.mode
        return d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .map { it.refreshRate }.distinct().sorted()
    }

    fun currentRate(ctx: Context): Float =
        ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 0f

    fun setRefreshRate(ctx: Context, hz: Float): String {
        need()?.let { return it }
        val v = if (hz == hz.toInt().toFloat()) hz.toInt().toString() else String.format(Locale.US, "%.1f", hz)
        val out = StringBuilder()
        out.appendLine(put(ctx, "system", "peak_refresh_rate", v))
        out.appendLine(put(ctx, "system", "min_refresh_rate", v))
        Thread.sleep(900)
        out.append(String.format(Locale.US, "หน้าจอทำงานจริงตอนนี้: %.1f Hz", currentRate(ctx)))
        return out.toString()
    }

    fun revertRefreshRate(ctx: Context): String {
        val a = restore(ctx, "system", "peak_refresh_rate")
        val b = restore(ctx, "system", "min_refresh_rate")
        Thread.sleep(700)
        return "$a\n$b\n" + String.format(Locale.US, "หน้าจอตอนนี้: %.1f Hz", currentRate(ctx))
    }

    // ---------------------------------------------------------------- animations
    private val animKeys = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
    fun setAnimations(ctx: Context, scale: String): String = need() ?: animKeys.joinToString("\n") { put(ctx, "global", it, scale) }
    fun revertAnimations(ctx: Context): String = animKeys.joinToString("\n") { restore(ctx, "global", it) }

    // ---------------------------------------------------------------- power
    fun fixedPerformance(on: Boolean): String {
        need()?.let { return it }
        val r = sh("cmd power set-fixed-performance-mode-enabled $on")
        val v = sh("dumpsys power | grep -i -E 'fixed.?performance|mFixedPerformance' | head -n 5").out.trim()
        return (if (r.ok) "✔ " else "✖ ") + "fixed performance mode = $on" +
            (if (r.text().isNotEmpty()) "\n${r.text()}" else "") + (if (v.isNotEmpty()) "\nระบบรายงาน: $v" else "\n(ระบบไม่รายงานสถานะกลับ — ผลขึ้นกับ Power HAL ของ Xiaomi)")
    }

    // ---------------------------------------------------------------- game manager (Android 12+/13+)
    fun gameMode(pkg: String, mode: String): String {
        need()?.let { return it }
        val r = sh("cmd game mode $mode $pkg")
        val v = sh("cmd game list-modes $pkg 2>&1; dumpsys game 2>/dev/null | grep -A4 '$pkg' | head -n 8").out.trim()
        return (if (r.ok) "✔ " else "✖ ") + "game mode $mode → $pkg\n${r.text()}\nตรวจสอบ:\n$v"
    }

    fun gameIntervention(pkg: String, downscale: String, fps: String): String {
        need()?.let { return it }
        if (Build.VERSION.SDK_INT < 33) return "✖ ต้องการ Android 13 ขึ้นไป"
        val args = buildString {
            append("--mode 2")
            if (downscale != "1.0") append(" --downscale $downscale")
            if (fps != "0") append(" --fps $fps")
        }
        val r = sh("cmd game set $args $pkg")
        val v = sh("cmd game list-configs $pkg 2>&1 | head -n 12").out.trim()
        return (if (r.ok) "✔ " else "✖ ") + "cmd game set $args $pkg\n${r.text()}\nตรวจสอบ:\n$v\n(เปิดเกมใหม่เพื่อให้มีผล — ดูผลจริงได้จาก FPS ใน HUD)"
    }

    fun gameReset(pkg: String): String {
        need()?.let { return it }
        val r = sh("cmd game reset $pkg; cmd game mode standard $pkg")
        return (if (r.ok) "✔ " else "✖ ") + "reset game config $pkg\n${r.text()}"
    }

    // ---------------------------------------------------------------- memory
    fun killBackground(ctx: Context): String {
        need()?.let { return it }
        val am = ctx.getSystemService(ActivityManager::class.java)
        fun avail(): Long = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem
        val before = avail()
        val r = sh("am kill-all")
        Thread.sleep(2000)
        val after = avail()
        val mb = (after - before) / (1024.0 * 1024.0)
        return (if (r.ok) "✔ " else "✖ ") + String.format(
            Locale.US, "ปิดโปรเซสเบื้องหลัง: RAM ว่าง %.0f MB → %.0f MB (%+.0f MB)",
            before / 1048576.0, after / 1048576.0, mb
        ) + if (!r.ok) "\n${r.text()}" else ""
    }

    // Android 12+ kills "phantom" child processes (Wine/Box64 inside Winlator, Termux…) when > 32.
    fun phantomKiller(ctx: Context, disable: Boolean): String {
        need()?.let { return it }
        val cmds = if (disable)
            "device_config set_sync_disabled_for_tests persistent; device_config put activity_manager max_phantom_processes 2147483647; settings put global settings_enable_monitor_phantom_procs false"
        else
            "device_config delete activity_manager max_phantom_processes; device_config set_sync_disabled_for_tests none; settings put global settings_enable_monitor_phantom_procs true"
        val r = sh(cmds)
        val v = sh("device_config get activity_manager max_phantom_processes; settings get global settings_enable_monitor_phantom_procs").out.trim()
        return (if (r.ok) "✔ " else "✖ ") + (if (disable) "ปิด phantom process killer" else "คืนค่า phantom process killer") + "\nตรวจสอบ: ${v.replace("\n", " / ")}"
    }

    fun batteryWhitelist(pkg: String, add: Boolean): String {
        need()?.let { return it }
        val r = sh("cmd deviceidle whitelist ${if (add) "+" else "-"}$pkg")
        val v = sh("cmd deviceidle whitelist | grep '$pkg'").out.trim()
        return (if (r.ok) "✔ " else "✖ ") + "${r.text()}\nตรวจสอบ: " + (if (v.isEmpty()) "ไม่อยู่ใน whitelist" else "อยู่ใน whitelist ($v)")
    }

    fun compileSpeed(pkg: String, reset: Boolean): String {
        need()?.let { return it }
        val r = if (reset) sh("cmd package compile --reset $pkg") else sh("cmd package compile -m speed -f $pkg")
        val v = sh("dumpsys package dexopt | grep -A3 '\\[$pkg\\]' | head -n 6").out.trim()
        return (if (r.ok) "✔ " else "✖ ") + (if (reset) "reset compilation" else "AOT compile (speed)") + " $pkg\n${r.text()}\nสถานะ dexopt:\n$v"
    }

    // ---------------------------------------------------------------- GPU drivers for an app
    fun angle(ctx: Context, pkg: String, enable: Boolean): String {
        need()?.let { return it }
        return if (enable) {
            put(ctx, "global", "angle_gl_driver_selection_pkgs", pkg) + "\n" + put(ctx, "global", "angle_gl_driver_selection_values", "angle") +
                "\n(มีผลเฉพาะแอปที่ใช้ OpenGL ES — ปิดแล้วเปิดแอปใหม่)"
        } else {
            restore(ctx, "global", "angle_gl_driver_selection_pkgs") + "\n" + restore(ctx, "global", "angle_gl_driver_selection_values")
        }
    }

    fun updatableDriverInfo(): String {
        if (!Shell.ready) return "ต้องใช้ Shizuku"
        val p0 = sh("getprop ro.gfx.driver.0").out.trim()
        val p1 = sh("getprop ro.gfx.driver.1").out.trim()
        return "production driver: ${p0.ifEmpty { "ไม่มี" }}\nprerelease driver: ${p1.ifEmpty { "ไม่มี" }}"
    }

    fun updatableDriver(ctx: Context, pkg: String, enable: Boolean): String {
        need()?.let { return it }
        val p0 = sh("getprop ro.gfx.driver.0").out.trim()
        val p1 = sh("getprop ro.gfx.driver.1").out.trim()
        if (p0.isEmpty() && p1.isEmpty()) return "✖ เฟิร์มแวร์นี้ไม่มี Updatable GPU Driver (ro.gfx.driver.0/1 ว่าง) — สลับไดรเวอร์ระบบแบบไม่รูทไม่ได้ ให้ใช้ Turnip ภายใน Winlator/GameHub แทน"
        val key = if (p0.isNotEmpty()) "updatable_driver_production_opt_in_apps" else "updatable_driver_prerelease_opt_in_apps"
        return if (enable) put(ctx, "global", key, pkg) + "\n(ปิดแล้วเปิดแอปใหม่)" else restore(ctx, "global", key)
    }

    fun thermalReport(): String {
        if (!Shell.ready) return "ต้องใช้ Shizuku"
        return sh("dumpsys thermalservice | head -n 60").out.trim()
    }

    fun revertAll(ctx: Context): String {
        val p = Prefs.get(ctx)
        val keys = p.all.keys.filter { it.startsWith("orig:") }
        if (keys.isEmpty()) return "ไม่มีค่าที่ต้องคืน"
        return keys.joinToString("\n") { k ->
            val (ns, key) = k.removePrefix("orig:").split("/", limit = 2)
            restore(ctx, ns, key)
        }
    }
}
