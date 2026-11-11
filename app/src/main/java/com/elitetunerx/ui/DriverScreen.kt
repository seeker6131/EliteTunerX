package com.elitetunerx.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elitetunerx.vk.Compat
import com.elitetunerx.vk.CustomDriver
import com.elitetunerx.vk.Downloader
import com.elitetunerx.vk.DriverStore
import com.elitetunerx.vk.Level
import com.elitetunerx.vk.VkClient
import com.elitetunerx.vk.VkReport
import com.elitetunerx.vk.compute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

private const val SYSTEM = "system"

private fun js(s: String?): JSONObject? = s?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { it.optBoolean("ok") }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DriverScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var drivers by remember { mutableStateOf(DriverStore.list(ctx)) }
    val busy = remember { mutableStateMapOf<String, String>() }
    val probes = remember { mutableStateMapOf<String, String>() }
    val bws = remember { mutableStateMapOf<String, String>() }
    val css = remember { mutableStateMapOf<String, String>() }
    var msg by remember { mutableStateOf<String?>(null) }
    var shootout by remember { mutableStateOf<String?>(null) }

    fun load(id: String) {
        DriverStore.result(ctx, "probe", id)?.let { probes[id] = it }
        DriverStore.result(ctx, "bw", id)?.let { bws[id] = it }
        DriverStore.result(ctx, "cs", id)?.let { css[id] = it }
    }
    remember { load(SYSTEM); drivers.forEach { load(it.id) }; 0 }

    fun keepScreenOn(on: Boolean) {
        val w = (ctx as? ComponentActivity)?.window ?: return
        if (on) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    suspend fun probe(id: String, d: CustomDriver?) {
        busy[id] = "ตรวจ Vulkan feature…"
        val j = VkClient.probe(ctx, d); probes[id] = j; DriverStore.saveResult(ctx, "probe", id, j)
    }
    suspend fun bw(id: String, d: CustomDriver?) {
        busy[id] = "วัด transfer bandwidth…"
        val j = VkClient.bandwidth(ctx, d); bws[id] = j; DriverStore.saveResult(ctx, "bw", id, j)
    }
    suspend fun cs(id: String, d: CustomDriver?, seconds: Int) {
        busy[id] = if (seconds > 0) "Compute stress ${seconds}s (FP32 + throttling)…" else "Compute bench (FP32 GFLOPS + shader bandwidth)…"
        val j = VkClient.compute(ctx, d, seconds); css[id] = j; DriverStore.saveResult(ctx, "cs", id, j)
    }

    fun one(id: String, block: suspend () -> Unit) {
        scope.launch { keepScreenOn(true); try { block() } finally { busy.remove(id); keepScreenOn(false) } }
    }

    fun runShootout() {
        scope.launch {
            keepScreenOn(true)
            val all = listOf<Pair<String, CustomDriver?>>(SYSTEM to null) + drivers.map { it.id to it }
            try {
                all.forEachIndexed { i, (id, d) ->
                    shootout = "(${i + 1}/${all.size}) ${d?.name ?: "System driver"}"
                    probe(id, d); bw(id, d); cs(id, d, 0)
                    busy.remove(id)
                }
                shootout = null
            } finally {
                all.forEach { busy.remove(it.first) }
                shootout = null
                keepScreenOn(false)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            msg = try {
                val d = withContext(Dispatchers.IO) { DriverStore.import(ctx, uri) }
                drivers = DriverStore.list(ctx)
                "✔ นำเข้า ${d.name} (${d.libName})"
            } catch (t: Throwable) {
                "✖ นำเข้าไม่ได้: ${t.message}"
            }
        }
    }

    ScreenColumn {
        Header("DRIVER LAB", "Turnip / ไดรเวอร์ระบบ • Vulkan compute benchmark • DXVK / VKD3D compatibility • ดาวน์โหลดในแอป")

        // ---------------------------------------------------------------- shootout
        val names = listOf(SYSTEM to "System (Qualcomm)") + drivers.map { it.id to it.name }
        PanelCard("⚡ DRIVER SHOOTOUT") {
            Body("กดปุ่มเดียว: รัน Vulkan probe + bandwidth + compute benchmark กับไดรเวอร์ระบบและ Turnip ทุกตัวที่นำเข้า แล้วจัดอันดับ — ทุกการทดสอบรันบน GPU จริง ด้วยไดรเวอร์ตัวนั้นจริง")
            PrimaryButton(if (shootout == null) "เริ่ม Shootout (${names.size} ไดรเวอร์)" else "กำลังทดสอบ…", enabled = shootout == null && busy.isEmpty()) { runShootout() }
            shootout?.let {
                Body(it, Color.White)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Cyan)
            }
            data class RankRow(val name: String, val gflops: Double?, val shaderGBs: Double?, val copyGBs: Double?, val dxvk2: Boolean?, val vkd3d: Boolean?)
            val rows = names.map { (id, n) ->
                val c = js(css[id]); val b = js(bws[id])
                val dev = probes[id]?.let { VkReport.parse(it) }?.devices?.firstOrNull()
                val v = dev?.let { Compat.evaluate(it) }
                RankRow(n, c?.optDouble("fp32Gflops"), c?.optDouble("computeGBs"), b?.optDouble("copyGBs"),
                    v?.getOrNull(1)?.requiredOk, v?.getOrNull(3)?.requiredOk)
            }.filter { it.gflops != null || it.copyGBs != null }
            if (rows.isNotEmpty()) {
                val bestF = rows.mapNotNull { it.gflops }.maxOrNull()
                val bestB = rows.mapNotNull { it.shaderGBs }.maxOrNull()
                Row(Modifier.fillMaxWidth()) {
                    listOf("Driver" to 2f, "FP32 GFLOPS" to 1.2f, "Shader GB/s" to 1.2f, "DXVK2" to 0.8f, "VKD3D" to 0.8f).forEach { (t, w) ->
                        Text(t, color = Muted, fontSize = 10.sp, modifier = Modifier.weight(w))
                    }
                }
                rows.sortedByDescending { it.gflops ?: 0.0 }.forEach { r ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(r.name, color = Color.White, fontSize = 11.sp, modifier = Modifier.weight(2f))
                        Text(fmt(r.gflops, "%.0f"), color = if (r.gflops != null && r.gflops == bestF) Good else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f))
                        Text(fmt(r.shaderGBs, "%.1f"), color = if (r.shaderGBs != null && r.shaderGBs == bestB) Good else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1.2f))
                        Text(when (r.dxvk2) { true -> "✔"; false -> "✖"; null -> "?" }, color = if (r.dxvk2 == true) Good else Bad, modifier = Modifier.weight(0.8f))
                        Text(when (r.vkd3d) { true -> "✔"; false -> "✖"; null -> "?" }, color = if (r.vkd3d == true) Good else Bad, modifier = Modifier.weight(0.8f))
                    }
                }
                val top = rows.filter { it.gflops != null }.maxByOrNull { it.gflops!! }
                val sys = rows.firstOrNull { it.name.startsWith("System") }?.gflops
                if (top != null && sys != null && sys > 0 && top.name != rows.first().name) {
                    Body(String.format(Locale.US, "%s เร็วกว่าไดรเวอร์ระบบ %+.1f%% (FP32)", top.name, (top.gflops!! / sys - 1) * 100), Good)
                }
                Body("✔ DXVK2 / VKD3D = ไดรเวอร์มี requirement หลักครบ • เลือกตัวที่ GFLOPS สูงและรองรับ DXVK2 ไปใช้ใน Winlator/GameHub แล้วยืนยันด้วย HUD + Sessions", Muted, 11)
            }
        }

        DownloadCard(onDriverImported = { d -> drivers = DriverStore.list(ctx); load(d.id); msg = "✔ ติดตั้ง ${d.name} แล้ว — กด Shootout เพื่อเทียบ" })

        PanelCard("ทดสอบ DXVK ในเกมจริง") {
            Body("DXVK/VKD3D เป็นไฟล์ Windows (.dll) ทำงานได้เฉพาะภายใน Wine ของ Winlator/GameHub — แอปนี้จึงทดสอบ 2 ชั้น:\n1) ไดรเวอร์ Vulkan (Turnip/ระบบ) — รันบน GPU จริงในแอปนี้ (Shootout)\n2) DXVK แต่ละเวอร์ชัน — ดาวน์โหลดจากการ์ดด้านบน นำเข้าใน Winlator/GameHub แล้วใช้ HUD + ● REC (หยุดอัตโนมัติ 60 วิ) อัดผลในเกมจริง แล้วเทียบในแท็บ Sessions")
        }

        DriverCard(
            title = "SYSTEM DRIVER (Qualcomm)", sub = "libvulkan.so ของเฟิร์มแวร์",
            busy = busy[SYSTEM], probe = probes[SYSTEM], bw = bws[SYSTEM], cs = css[SYSTEM],
            onProbe = { one(SYSTEM) { probe(SYSTEM, null) } }, onBw = { one(SYSTEM) { bw(SYSTEM, null) } },
            onCompute = { sec -> one(SYSTEM) { cs(SYSTEM, null, sec) } }, onDelete = null
        )

        PanelCard("CUSTOM DRIVERS (Turnip / AdrenoTools .zip)") {
            Body("โหลดจากการ์ดดาวน์โหลดด้านบน หรือนำเข้าไฟล์ .zip เอง — ไดรเวอร์รันใน process แยกด้วย libadrenotools (ไม่ต้องรูท ไม่แตะไดรเวอร์ระบบ) ถ้าพังแอปจะไม่เด้ง")
            PrimaryButton("+ นำเข้าไฟล์ .zip") { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
            msg?.let { Body(it, if (it.startsWith("✔")) Good else Bad) }
        }
        drivers.forEach { d ->
            DriverCard(
                title = d.name, sub = "${d.version} • ${d.libName}",
                busy = busy[d.id], probe = probes[d.id], bw = bws[d.id], cs = css[d.id],
                onProbe = { one(d.id) { probe(d.id, d) } }, onBw = { one(d.id) { bw(d.id, d) } },
                onCompute = { sec -> one(d.id) { cs(d.id, d, sec) } },
                onDelete = {
                    DriverStore.delete(ctx, d); probes.remove(d.id); bws.remove(d.id); css.remove(d.id); drivers = DriverStore.list(ctx)
                }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadCard(onDriverImported: (CustomDriver) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(Downloader.presets.first()) }
    var customRepo by remember { mutableStateOf("") }
    var releases by remember { mutableStateOf<List<Downloader.Release>?>(null) }
    var openTag by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var loading by remember { mutableStateOf(false) }

    fun fetch(repo: String) {
        loading = true; releases = null; status = null; openTag = null
        scope.launch {
            try {
                releases = withContext(Dispatchers.IO) { Downloader.releases(repo) }
                if (releases!!.isEmpty()) status = "ไม่พบ release ที่มีไฟล์ให้ดาวน์โหลด"
            } catch (t: Throwable) {
                status = "✖ ${t.message ?: t}"
            } finally {
                loading = false
            }
        }
    }

    fun get(a: Downloader.Asset, kind: Downloader.Kind) {
        progress = 0f; status = "กำลังดาวน์โหลด ${a.name}…"
        scope.launch {
            try {
                val f = withContext(Dispatchers.IO) { Downloader.download(ctx, a) { p -> progress = p } }
                if (kind == Downloader.Kind.DRIVER && Downloader.isDriverAsset(a.name)) {
                    val d = withContext(Dispatchers.IO) { f.inputStream().use { DriverStore.importStream(ctx, it) }.also { f.delete() } }
                    status = "✔ ติดตั้งไดรเวอร์ ${d.name} (${d.libName})"
                    onDriverImported(d)
                } else {
                    val path = withContext(Dispatchers.IO) { Downloader.saveToDownloads(ctx, f) }
                    status = "✔ บันทึกที่ $path — เปิด Winlator/GameHub แล้วนำเข้าไฟล์นี้ (Contents / Components)"
                }
            } catch (t: Throwable) {
                status = "✖ ${t.message ?: t}"
            } finally {
                progress = null
            }
        }
    }

    PanelCard("⬇ ดาวน์โหลด TURNIP / DXVK / VKD3D") {
        Body("ดึงรายการเวอร์ชันจาก GitHub release ของผู้พัฒนาโดยตรง • Turnip = ติดตั้งเข้า Driver Lab ทันที • DXVK/VKD3D = บันทึกไปที่ Download/EliteTunerX")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Downloader.presets.forEach { p ->
                FilterChip(selected = source == p, onClick = { source = p; releases = null; status = null }, label = { Text(p.title, fontSize = 11.sp) })
            }
        }
        Body(source.note, Muted, 11)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(if (loading) "กำลังโหลด…" else "ดูเวอร์ชันทั้งหมด", enabled = !loading && progress == null) { fetch(source.repo) }
        }
        OutlinedTextField(
            customRepo, { customRepo = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("หรือใส่ GitHub repo เอง (owner/name)") }
        )
        if (customRepo.contains('/')) SecondaryButton("โหลดจาก $customRepo", enabled = !loading && progress == null) {
            source = Downloader.Source(customRepo, customRepo, if (customRepo.contains("turnip", true) || customRepo.contains("adreno", true)) Downloader.Kind.DRIVER else Downloader.Kind.COMPONENT, "repo ที่กำหนดเอง")
            fetch(customRepo)
        }
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth(), color = Cyan) }
        status?.let { Body(it, if (it.startsWith("✔")) Good else if (it.startsWith("✖")) Bad else Color.White) }
        releases?.forEach { r ->
            val open = openTag == r.tag
            Row(Modifier.fillMaxWidth()) {
                Text("${r.name}${if (r.prerelease) " (pre)" else ""}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(r.date, color = Muted, fontSize = 11.sp)
            }
            SecondaryButton(if (open) "ซ่อนไฟล์" else "ไฟล์ (${r.assets.size})") { openTag = if (open) null else r.tag }
            if (open) r.assets.forEach { a ->
                val mb = a.sizeBytes / 1048576.0
                SecondaryButton(String.format(Locale.US, "⬇ %s  (%.1f MB)", a.name, mb), enabled = progress == null) { get(a, source.kind) }
            }
        }
    }
}

@Composable
private fun DriverCard(
    title: String, sub: String, busy: String?, probe: String?, bw: String?, cs: String?,
    onProbe: () -> Unit, onBw: () -> Unit, onCompute: (Int) -> Unit, onDelete: (() -> Unit)?,
) {
    var showAll by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    PanelCard(title) {
        Body(sub)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("ตรวจ Vulkan", enabled = busy == null, onClick = onProbe)
            SecondaryButton("Compute bench", enabled = busy == null) { onCompute(0) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Stress 60s", enabled = busy == null) { onCompute(60) }
            SecondaryButton("Bandwidth", enabled = busy == null, onClick = onBw)
            onDelete?.let { SecondaryButton("ลบ", enabled = busy == null, onClick = it) }
        }
        busy?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = Cyan, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Body(it, Color.White)
            }
        }
        cs?.let { j ->
            val o = runCatching { JSONObject(j) }.getOrNull()
            if (o?.optBoolean("ok") == true) {
                Text(String.format(Locale.US, "%.0f GFLOPS FP32", o.optDouble("fp32Gflops")), color = Good, fontSize = 20.sp, fontWeight = FontWeight.Black)
                Mono(String.format(Locale.US, "shader bandwidth %.1f GB/s • timer %s", o.optDouble("computeGBs"), o.optString("timer")))
                val sus = o.optJSONArray("sustained")
                if (sus != null && sus.length() > 1) {
                    val st = o.optDouble("stability")
                    Mono(String.format(Locale.US, "sustained %ds: stability %.1f%% (ช้าสุด/เร็วสุด)", sus.length(), st), if (st >= 90) Good else if (st >= 75) Warn else Bad)
                    Mono((0 until sus.length()).joinToString(" ") { String.format(Locale.US, "%.0f", sus.getDouble(it)) }, Muted)
                }
            } else Body("✖ compute: ${o?.optString("error") ?: j}", Bad)
        }
        bw?.let { j ->
            val o = runCatching { JSONObject(j) }.getOrNull()
            if (o?.optBoolean("ok") == true) {
                Mono(String.format(Locale.US, "transfer: copy %.1f GB/s • fill %.1f GB/s (%d MB × %d)",
                    o.optDouble("copyGBs"), o.optDouble("fillGBs"), o.optInt("bufferMb"), o.optInt("iters")), Good)
            } else Body("✖ bandwidth: ${o?.optString("error") ?: j}", Bad)
        }
        probe?.let { j ->
            val rep = VkReport.parse(j)
            if (!rep.ok) {
                Body("✖ ${rep.error}", Bad)
                return@PanelCard
            }
            val dev = rep.devices.firstOrNull() ?: run { Body("ไม่พบ GPU", Bad); return@PanelCard }
            Text(dev.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Mono("Vulkan ${dev.apiVersion} • driver ${dev.driverVersion} • ${dev.driverKind}")
            if (dev.driverInfo.isNotEmpty()) Mono(dev.driverInfo, Muted)
            Mono("extensions ${dev.extensions.size} • conformance ${dev.conformance.ifEmpty { "-" }} • VRAM heap ${dev.heapsMb.maxOrNull() ?: 0} MB", Muted)
            Compat.evaluate(dev).forEach { lv ->
                val c = when {
                    !lv.requiredOk -> Bad
                    !lv.gamesOk -> Warn
                    else -> Good
                }
                Text(lv.layer, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Body(lv.verdict + " • " + lv.note, c, 11)
                SecondaryButton(if (expanded == lv.layer) "ซ่อนรายละเอียด" else "ดูรายการตรวจ") { expanded = if (expanded == lv.layer) null else lv.layer }
                if (expanded == lv.layer) lv.checks.forEach { ck ->
                    val tag = when (ck.level) { Level.REQUIRED -> "ต้องมี"; Level.GAMES -> "เกม"; Level.RECOMMENDED -> "แนะนำ" }
                    Mono("${if (ck.pass) "✔" else "✖"} [$tag] ${ck.label}", if (ck.pass) Good else if (ck.level == Level.RECOMMENDED) Muted else Bad)
                }
            }
            SecondaryButton(if (showAll) "ซ่อน feature ทั้งหมด" else "ดู feature / extension ทั้งหมด") { showAll = !showAll }
            if (showAll) {
                Mono(dev.features.entries.sortedBy { it.key }.joinToString("\n") { "${if (it.value) "✔" else "·"} ${it.key}" })
                Mono(dev.limits.entries.sortedBy { it.key }.joinToString("\n") { "${it.key} = ${it.value}" }, Muted)
                Mono(dev.extensions.sorted().joinToString("\n"), Muted)
            }
        }
    }
}
