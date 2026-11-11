package com.elitetunerx.core

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.File
import kotlin.concurrent.thread

data class ShellResult(val code: Int, val out: String, val err: String) {
    val ok: Boolean get() = code == 0
    fun text(): String = (out.trim() + (if (err.isNotBlank()) "\n" + err.trim() else "")).trim()
}

/**
 * Runs commands either as the app (uid of this app) or, when Shizuku is running and
 * permission is granted, as the ADB shell user (uid 2000). The shell user is what unlocks
 * SurfaceFlinger frame timestamps, protected sysfs nodes and `settings`/`cmd` tweaks
 * without root.
 */
object Shell {
    enum class State { UNAVAILABLE, NO_PERMISSION, READY }

    fun state(): State = try {
        when {
            !Shizuku.pingBinder() || Shizuku.isPreV11() -> State.UNAVAILABLE
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.READY
            else -> State.NO_PERMISSION
        }
    } catch (t: Throwable) {
        State.UNAVAILABLE
    }

    val ready: Boolean get() = state() == State.READY

    /** 0 = root (Sui / Shizuku started with root), 2000 = adb shell */
    fun uid(): Int = try { Shizuku.getUid() } catch (t: Throwable) { -1 }

    // Shizuku.newProcess is private in API 13 but still the simplest way to spawn a shell process.
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
    }

    fun exec(cmd: Array<String>, timeoutMs: Long = 8000, privileged: Boolean = true): ShellResult {
        val proc: Process = try {
            if (privileged && ready) newProcess.invoke(null, cmd, null, null) as Process
            else Runtime.getRuntime().exec(cmd)
        } catch (t: Throwable) {
            return ShellResult(-1, "", (t.cause ?: t).toString())
        }
        var out = ""
        var err = ""
        val tOut = thread(isDaemon = true) { out = runCatching { proc.inputStream.bufferedReader().readText() }.getOrDefault("") }
        val tErr = thread(isDaemon = true) { err = runCatching { proc.errorStream.bufferedReader().readText() }.getOrDefault("") }
        val watchdog = thread(isDaemon = true) {
            try {
                Thread.sleep(timeoutMs)
                runCatching { proc.destroy() }
            } catch (_: InterruptedException) {
            }
        }
        tOut.join()
        tErr.join()
        val code = runCatching { proc.waitFor() }.getOrDefault(-1)
        watchdog.interrupt()
        return ShellResult(code, out, err)
    }

    fun sh(script: String, timeoutMs: Long = 8000, privileged: Boolean = true): ShellResult =
        exec(arrayOf("sh", "-c", script), timeoutMs, privileged)

    fun readDirect(path: String): String? = try {
        File(path).readText().trim().ifEmpty { null }
    } catch (t: Throwable) {
        null
    }
}

object Sysfs {
    /** Reads many small files with a single shell process (or directly as the app). */
    fun readMany(paths: Collection<String>, privileged: Boolean): Map<String, String?> {
        if (paths.isEmpty()) return emptyMap()
        if (!privileged) return paths.associateWith { Shell.readDirect(it) }
        val script = paths.joinToString("\n") { "echo '@@ $it'; cat '$it' 2>/dev/null" }
        val r = Shell.sh(script, 5000)
        val map = HashMap<String, String?>()
        var cur: String? = null
        val sb = StringBuilder()
        fun flush() {
            cur?.let { map[it] = sb.toString().trim().ifEmpty { null } }
            sb.setLength(0)
        }
        for (line in r.out.lineSequence()) {
            if (line.startsWith("@@ ")) {
                flush()
                cur = line.removePrefix("@@ ")
            } else {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
        }
        flush()
        return paths.associateWith { map[it] }
    }

    fun listDir(dir: String, privileged: Boolean): List<String> {
        val direct = File(dir).list()?.toList()
        if (!direct.isNullOrEmpty() || !privileged) return direct ?: emptyList()
        return Shell.exec(arrayOf("ls", dir), 3000).out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun firstNumber(s: String?): Double? =
        s?.let { Regex("-?\\d+(\\.\\d+)?").find(it)?.value?.toDoubleOrNull() }
}
