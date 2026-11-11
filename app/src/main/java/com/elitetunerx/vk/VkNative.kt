package com.elitetunerx.vk

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.elitetunerx.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

object VkNative {
    init { System.loadLibrary("etxvk") }
    external fun probe(driverDir: String?, driverLib: String?, hookDir: String): String
    external fun bandwidth(driverDir: String?, driverLib: String?, hookDir: String, mb: Int, iters: Int): String
}

/** Runs native Vulkan work in the isolated ":vk" process, then exits so every test gets a clean process. */
class VkWorkerService : Service() {
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        messenger = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                val reply = msg.replyTo
                val what = msg.what
                val d = Bundle(msg.data)
                thread {
                    val hook = applicationInfo.nativeLibraryDir.trimEnd('/') + "/"
                    val dir = d.getString("dir")
                    val lib = d.getString("lib")
                    val json = try {
                        when (what) {
                            VkClient.PROBE -> VkNative.probe(dir, lib, hook)
                            VkClient.BANDWIDTH -> VkNative.bandwidth(dir, lib, hook, d.getInt("mb", 256), d.getInt("iters", 16))
                            else -> JSONObject().put("ok", false).put("error", "unknown op").toString()
                        }
                    } catch (t: Throwable) {
                        JSONObject().put("ok", false).put("error", t.toString()).toString()
                    }
                    runCatching { reply.send(Message.obtain(null, what).apply { data = Bundle().apply { putString("json", json) } }) }
                    Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 600)
                }
            }
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder
}

object VkClient {
    const val PROBE = 1
    const val BANDWIDTH = 2

    private fun err(m: String) = JSONObject().put("ok", false).put("error", m).toString()

    suspend fun call(ctx: Context, what: Int, args: Bundle, timeoutMs: Long = 120_000): String = withContext(Dispatchers.Main) {
        val app = ctx.applicationContext
        val result = CompletableDeferred<String>()
        val replyHandler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                result.complete(msg.data?.getString("json") ?: err("empty reply"))
            }
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    val m = Message.obtain(null, what)
                    m.data = args
                    m.replyTo = Messenger(replyHandler)
                    Messenger(binder).send(m)
                } catch (t: Throwable) {
                    result.complete(err("send failed: $t"))
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                result.complete(err("Driver process crashed — this driver is unstable or incompatible with this GPU/firmware"))
            }

            override fun onBindingDied(name: ComponentName?) {
                result.complete(err("Driver process died"))
            }
        }
        if (!app.bindService(Intent(app, VkWorkerService::class.java), conn, Context.BIND_AUTO_CREATE)) {
            return@withContext err("could not start Vulkan worker")
        }
        try {
            withTimeoutOrNull(timeoutMs) { result.await() } ?: err("timeout after ${timeoutMs / 1000}s")
        } finally {
            runCatching { app.unbindService(conn) }
        }
    }

    suspend fun probe(ctx: Context, d: CustomDriver?): String =
        call(ctx, PROBE, Bundle().apply { d?.let { putString("dir", it.dir.absolutePath + "/"); putString("lib", it.libName) } })

    suspend fun bandwidth(ctx: Context, d: CustomDriver?, mb: Int = 256, iters: Int = 16): String =
        call(ctx, BANDWIDTH, Bundle().apply {
            d?.let { putString("dir", it.dir.absolutePath + "/"); putString("lib", it.libName) }
            putInt("mb", mb); putInt("iters", iters)
        })
}

data class CustomDriver(val id: String, val name: String, val version: String, val libName: String, val dir: File)

/** Imports custom Vulkan drivers packaged as .zip (AdrenoTools / Turnip format with meta.json). */
object DriverStore {
    private fun root(ctx: Context) = File(ctx.filesDir, "drivers").apply { mkdirs() }

    fun list(ctx: Context): List<CustomDriver> = root(ctx).listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { d ->
        val meta = File(d, "etx.json").takeIf { it.exists() } ?: return@mapNotNull null
        runCatching {
            val o = JSONObject(meta.readText())
            CustomDriver(d.name, o.optString("name"), o.optString("version"), o.getString("lib"), d)
        }.getOrNull()
    }.sortedBy { it.name }

    fun import(ctx: Context, uri: Uri): CustomDriver {
        val id = "drv" + System.currentTimeMillis()
        val dir = File(root(ctx), id).apply { mkdirs() }
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: error("cannot open file")
            ZipInputStream(input.buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = File(e.name).name
                    if (name.isEmpty() || name.startsWith(".")) continue
                    File(dir, name).outputStream().use { zin.copyTo(it) }
                }
            }
            val meta = File(dir, "meta.json").takeIf { it.exists() }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            val sos = dir.listFiles().orEmpty().filter { it.name.endsWith(".so") }
            val lib = meta?.optString("libraryName")?.takeIf { n -> n.isNotEmpty() && File(dir, n).exists() }
                ?: sos.firstOrNull { it.name.contains("vulkan") }?.name ?: sos.firstOrNull()?.name
                ?: error("no .so driver library inside the zip")
            val name = meta?.optString("name")?.takeIf { it.isNotEmpty() } ?: lib
            val version = meta?.optString("driverVersion")?.takeIf { it.isNotEmpty() } ?: meta?.optString("packageVersion") ?: ""
            File(dir, "etx.json").writeText(JSONObject().put("name", name).put("version", version).put("lib", lib).toString())
            return CustomDriver(id, name, version, lib, dir)
        } catch (t: Throwable) {
            dir.deleteRecursively()
            throw t
        }
    }

    fun delete(ctx: Context, d: CustomDriver) {
        d.dir.deleteRecursively()
        Prefs.get(ctx).edit().remove("vk_probe_${d.id}").remove("vk_bw_${d.id}").apply()
    }

    fun saveResult(ctx: Context, kind: String, id: String, json: String) =
        Prefs.get(ctx).edit().putString("vk_${kind}_$id", json).apply()

    fun result(ctx: Context, kind: String, id: String): String? = Prefs.get(ctx).getString("vk_${kind}_$id", null)
}
