package com.elitetunerx.vk

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads components straight from their official GitHub releases.
 *  - Turnip / AdrenoTools drivers (.zip) are imported into the Driver Lab so they can be benchmarked in-app.
 *  - DXVK / VKD3D-Proton / anything else is saved to Download/EliteTunerX for Winlator / GameHub to import.
 */
object Downloader {
    enum class Kind { DRIVER, COMPONENT }

    data class Source(val title: String, val repo: String, val kind: Kind, val note: String)
    data class Asset(val name: String, val url: String, val sizeBytes: Long)
    data class Release(val tag: String, val name: String, val date: String, val prerelease: Boolean, val assets: List<Asset>)

    val presets = listOf(
        Source("Turnip (K11MCH1 AdrenoToolsDrivers)", "K11MCH1/AdrenoToolsDrivers", Kind.DRIVER, "Mesa Turnip ที่แพ็กเป็น AdrenoTools .zip — นำเข้าแล้วเทสได้ทันที"),
        Source("Turnip CI (whitebelyash)", "whitebelyash/freedreno_turnip-CI", Kind.DRIVER, "Turnip build รายวันจาก Mesa main"),
        Source("DXVK (doitsujin)", "doitsujin/dxvk", Kind.COMPONENT, "D3D8/9/10/11 → Vulkan • บันทึกไว้ที่ Download/EliteTunerX"),
        Source("DXVK-Sarek (async, ไดรเวอร์เก่า)", "pythonlover02/DXVK-Sarek", Kind.COMPONENT, "DXVK สาย 1.10 สำหรับไดรเวอร์ที่ไม่ถึง Vulkan 1.3"),
        Source("VKD3D-Proton", "HansKristian-Work/vkd3d-proton", Kind.COMPONENT, "D3D12 → Vulkan • บันทึกไว้ที่ Download/EliteTunerX"),
    )

    private fun get(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "EliteTunerX")
        c.connectTimeout = 15000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        return c
    }

    fun releases(repo: String): List<Release> {
        val c = get("https://api.github.com/repos/$repo/releases?per_page=15")
        try {
            if (c.responseCode == 403) error("GitHub API จำกัดจำนวนครั้ง (60 ครั้ง/ชม.) — รอสักครู่แล้วลองใหม่")
            if (c.responseCode != 200) error("GitHub ตอบกลับ ${c.responseCode} สำหรับ $repo")
            val arr = JSONArray(c.inputStream.bufferedReader().readText())
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val aa = o.optJSONArray("assets") ?: JSONArray()
                Release(
                    o.optString("tag_name"), o.optString("name").ifEmpty { o.optString("tag_name") },
                    o.optString("published_at").take(10), o.optBoolean("prerelease"),
                    (0 until aa.length()).map { j ->
                        val a = aa.getJSONObject(j)
                        Asset(a.optString("name"), a.optString("browser_download_url"), a.optLong("size"))
                    }
                )
            }.filter { it.assets.isNotEmpty() }
        } finally {
            c.disconnect()
        }
    }

    /** Downloads [a] to the app cache, reporting progress 0..1. */
    fun download(ctx: Context, a: Asset, onProgress: (Float) -> Unit): File {
        val out = File(File(ctx.cacheDir, "dl").apply { mkdirs() }, a.name)
        var url = a.url
        // follow cross-host redirects (github.com → objects.githubusercontent.com) manually
        repeat(5) {
            val c = get(url)
            c.instanceFollowRedirects = false
            val code = c.responseCode
            if (code in 300..399) {
                url = c.getHeaderField("Location") ?: error("redirect without location")
                c.disconnect()
                return@repeat
            }
            if (code != 200) error("ดาวน์โหลดไม่สำเร็จ: HTTP $code")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: a.sizeBytes
            c.inputStream.use { inp ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            c.disconnect()
            return out
        }
        error("too many redirects")
    }

    /** Copies a downloaded file into the public Download/EliteTunerX folder. Returns the visible path. */
    fun saveToDownloads(ctx: Context, f: File): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, f.name)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EliteTunerX")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("cannot create file in Downloads")
        resolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } } ?: error("cannot write Downloads")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        f.delete()
        return "Download/EliteTunerX/${f.name}"
    }

    fun isDriverAsset(name: String) = name.endsWith(".zip", ignoreCase = true)
}
