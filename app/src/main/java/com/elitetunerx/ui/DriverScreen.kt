package com.elitetunerx.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import com.elitetunerx.vk.DriverStore
import com.elitetunerx.vk.Level
import com.elitetunerx.vk.VkClient
import com.elitetunerx.vk.VkReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val SYSTEM = "system"

@Composable
fun DriverScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var drivers by remember { mutableStateOf(DriverStore.list(ctx)) }
    val busy = remember { mutableStateMapOf<String, String>() }
    val probes = remember { mutableStateMapOf<String, String>() }
    val bws = remember { mutableStateMapOf<String, String>() }
    var msg by remember { mutableStateOf<String?>(null) }

    fun load(id: String) {
        DriverStore.result(ctx, "probe", id)?.let { probes[id] = it }
        DriverStore.result(ctx, "bw", id)?.let { bws[id] = it }
    }
    remember { load(SYSTEM); drivers.forEach { load(it.id) }; 0 }

    fun runProbe(id: String, d: CustomDriver?) {
        busy[id] = "กำลังตรวจไดรเวอร์…"
        scope.launch {
            val j = VkClient.probe(ctx, d)
            probes[id] = j; DriverStore.saveResult(ctx, "probe", id, j); busy.remove(id)
        }
    }

    fun runBw(id: String, d: CustomDriver?) {
        busy[id] = "กำลังวัด bandwidth…"
        scope.launch {
            val j = VkClient.bandwidth(ctx, d)
            bws[id] = j; DriverStore.saveResult(ctx, "bw", id, j); busy.remove(id)
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
        Header("DRIVER LAB", "Vulkan probe • DXVK / VKD3D-Proton / D8VK compatibility • Turnip loader • bandwidth bench")
        PanelCard("วิธีทดสอบ DXVK ทุกเวอร์ชันให้เห็นผลจริง") {
            Body("1) ตรวจว่าไดรเวอร์ (ระบบ หรือ Turnip ที่นำเข้า) มี Vulkan feature ครบตามที่ DXVK/VKD3D แต่ละรุ่นต้องการ — ตารางด้านล่างอ่านจาก GPU จริง\n2) DXVK ทำงานภายใน Wine ของ Winlator/GameHub — เลือกเวอร์ชันในแอปนั้น แล้วใช้ HUD + ● REC อัดผล ไปเทียบในแท็บ Sessions (avg FPS, 1% low, วัตต์)")
        }

        DriverCard(
            title = "SYSTEM DRIVER (Qualcomm)", sub = "libvulkan.so ของเฟิร์มแวร์",
            busy = busy[SYSTEM], probe = probes[SYSTEM], bw = bws[SYSTEM],
            onProbe = { runProbe(SYSTEM, null) }, onBw = { runBw(SYSTEM, null) }, onDelete = null
        )

        PanelCard("CUSTOM DRIVERS (Turnip / AdrenoTools .zip)") {
            Body("นำเข้าไฟล์ .zip ไดรเวอร์ (รูปแบบ AdrenoTools ที่มี meta.json เช่น Turnip จาก K11MCH1 / Mr. Purple) แอปจะโหลดไดรเวอร์นั้นใน process แยกด้วย libadrenotools — ไม่ต้องรูท ไม่แตะไดรเวอร์ระบบ ถ้าไดรเวอร์พังจะไม่ทำให้แอปเด้ง")
            PrimaryButton("+ นำเข้าไดรเวอร์ .zip") { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
            msg?.let { Body(it, if (it.startsWith("✔")) Good else Bad) }
        }
        drivers.forEach { d ->
            DriverCard(
                title = d.name, sub = "${d.version} • ${d.libName}",
                busy = busy[d.id], probe = probes[d.id], bw = bws[d.id],
                onProbe = { runProbe(d.id, d) }, onBw = { runBw(d.id, d) },
                onDelete = { DriverStore.delete(ctx, d); probes.remove(d.id); bws.remove(d.id); drivers = DriverStore.list(ctx) }
            )
        }

        val bwRows = (listOf(SYSTEM to "System") + drivers.map { it.id to it.name }).mapNotNull { (id, name) ->
            val o = bws[id]?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { it.optBoolean("ok") } ?: return@mapNotNull null
            Triple(name, o.optDouble("copyGBs"), o.optDouble("fillGBs"))
        }
        if (bwRows.size >= 2) PanelCard("เทียบ Vulkan bandwidth") {
            val bestCopy = bwRows.maxOf { it.second }
            bwRows.forEach { (n, c, f) ->
                Mono(String.format(java.util.Locale.US, "%-22s copy %6.1f GB/s  fill %6.1f GB/s", n.take(22), c, f), if (c == bestCopy) Good else Color.White)
            }
        }
    }
}

@Composable
private fun DriverCard(
    title: String, sub: String, busy: String?, probe: String?, bw: String?,
    onProbe: () -> Unit, onBw: () -> Unit, onDelete: (() -> Unit)?,
) {
    var showAll by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf<String?>(null) }
    PanelCard(title) {
        Body(sub)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("ตรวจ Vulkan", enabled = busy == null, onClick = onProbe)
            SecondaryButton("วัด Bandwidth", enabled = busy == null, onClick = onBw)
            onDelete?.let { SecondaryButton("ลบ", enabled = busy == null, onClick = it) }
        }
        busy?.let {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = Cyan, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Body(it, Color.White)
            }
        }
        bw?.let { j ->
            val o = runCatching { JSONObject(j) }.getOrNull()
            if (o?.optBoolean("ok") == true) {
                Mono(String.format(java.util.Locale.US, "Bandwidth: copy %.1f GB/s • fill %.1f GB/s (buffer %d MB × %d)",
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
                Row(Modifier.fillMaxWidth()) {
                    Text(lv.layer, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(if (expanded == lv.layer) "▲" else "▼", color = Cyan, fontSize = 13.sp)
                }
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
