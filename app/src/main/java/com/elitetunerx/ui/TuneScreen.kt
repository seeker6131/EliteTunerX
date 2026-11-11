package com.elitetunerx.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.elitetunerx.Prefs
import com.elitetunerx.core.Shell
import com.elitetunerx.tune.Tweaks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class AppEntry(val label: String, val pkg: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TuneScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf(Prefs.targetPkg(ctx)) }
    var picking by remember { mutableStateOf(false) }
    val logs = remember { mutableStateOf(mapOf<String, String>()) }
    var running by remember { mutableStateOf<String?>(null) }

    fun act(id: String, block: () -> String) {
        running = id
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching(block).getOrElse { "✖ ${it.message}" } }
            logs.value = logs.value + (id to r)
            running = null
        }
    }

    @Composable
    fun Log(id: String) {
        if (running == id) Body("กำลังทำงาน…", Color.White)
        logs.value[id]?.let { Mono(it, if (it.startsWith("✖")) Bad else Good) }
    }

    ScreenColumn {
        Header("TUNING", "ปรับจริงผ่าน Shizuku • ทุกค่าถูกอ่านกลับมายืนยัน • กดคืนค่าได้ทุกเมื่อ")
        if (!Shell.ready) ShizukuCard()

        PanelCard("ข้อจำกัดที่ต้องรู้ (ไม่รูท)") {
            Body("Android ไม่อนุญาตให้แอปที่ไม่รูทล็อก CPU/GPU clock, voltage หรือ governor — แอปไหนอ้างว่าทำได้โดยไม่รูทคือหลอก • สิ่งที่ทำได้จริงคือคำสั่งระดับ ADB shell ด้านล่าง ซึ่งมีผลจริงและวัดผลได้ด้วย HUD/Sessions", Warn)
        }

        PanelCard("แอปเป้าหมาย") {
            Body(target ?: "ยังไม่ได้เลือก (เช่น Winlator / GameHub / เกม)", Color.White)
            PrimaryButton("เลือกแอป") { picking = true }
        }

        // ---------------- display
        val rates = remember { Tweaks.supportedRates(ctx) }
        PanelCard("REFRESH RATE LOCK") {
            Body(String.format(Locale.US, "ล็อกอัตรารีเฟรชจอ (ปัจจุบัน %.0f Hz) — กันจอลด Hz เองระหว่างเล่น", Tweaks.currentRate(ctx)))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rates.forEach { hz ->
                    FilterChip(selected = false, onClick = { act("hz") { Tweaks.setRefreshRate(ctx, hz) } }, label = { Text(String.format(Locale.US, "%.0f Hz", hz)) })
                }
                SecondaryButton("คืนค่า") { act("hz") { Tweaks.revertRefreshRate(ctx) } }
            }
            Log("hz")
        }

        PanelCard("ANIMATION SPEED") {
            Body("ลด/ปิดแอนิเมชันระบบ — UI ตอบสนองไวขึ้น")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("0", "0.5", "1.0").forEach { s -> FilterChip(selected = false, onClick = { act("anim") { Tweaks.setAnimations(ctx, s) } }, label = { Text("${s}x") }) }
                SecondaryButton("คืนค่า") { act("anim") { Tweaks.revertAnimations(ctx) } }
            }
            Log("anim")
        }

        PanelCard("FIXED PERFORMANCE MODE") {
            Body("ขอให้ Power HAL ใช้โปรไฟล์ประสิทธิภาพคงที่ (คำสั่งเดียวกับที่ Google ใช้ทำเบนช์มาร์กให้ผลซ้ำได้) — ผลจริงขึ้นกับ Xiaomi ดูได้จาก GPU/CPU clock ใน Monitor")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("เปิด") { act("fixed") { Tweaks.fixedPerformance(true) } }
                SecondaryButton("ปิด") { act("fixed") { Tweaks.fixedPerformance(false) } }
            }
            Log("fixed")
        }

        PanelCard("MEMORY") {
            Body("ปิดโปรเซสเบื้องหลังก่อนเล่น (แสดง RAM ที่ได้คืนจริง) • ปิด phantom process killer ของ Android 12+ ที่ชอบฆ่า Wine/Box64 ใน Winlator จนเกมเด้ง")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("ล้าง RAM") { act("mem") { Tweaks.killBackground(ctx) } }
                SecondaryButton("ปิด phantom killer") { act("mem") { Tweaks.phantomKiller(ctx, true) } }
                SecondaryButton("คืนค่า phantom") { act("mem") { Tweaks.phantomKiller(ctx, false) } }
            }
            Log("mem")
        }

        val pkg = target
        if (pkg != null) {
            PanelCard("GAME MODE • $pkg") {
                Body("Android Game Manager: ตั้งโหมด performance และ intervention (ลดความละเอียดเรนเดอร์ / จำกัด FPS ให้นิ่ง) — มีผลกับแอปที่ไม่ได้ปิดฟีเจอร์นี้ ต้องเปิดแอปใหม่")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Performance") { act("game") { Tweaks.gameMode(pkg, "performance") } }
                    SecondaryButton("Battery") { act("game") { Tweaks.gameMode(pkg, "battery") } }
                    SecondaryButton("Reset") { act("game") { Tweaks.gameReset(pkg) } }
                }
                var scale by remember { mutableStateOf("1.0") }
                var fps by remember { mutableStateOf("0") }
                Body("Downscale ความละเอียด", Color.White)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("1.0", "0.9", "0.8", "0.7", "0.6", "0.5").forEach { s -> FilterChip(selected = scale == s, onClick = { scale = s }, label = { Text(s) }) }
                }
                Body("FPS cap (0 = ไม่จำกัด)", Color.White)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("0", "30", "45", "60", "90", "120").forEach { f -> FilterChip(selected = fps == f, onClick = { fps = f }, label = { Text(f) }) }
                }
                PrimaryButton("ใช้ intervention") { act("game") { Tweaks.gameIntervention(pkg, scale, fps) } }
                Log("game")
            }

            PanelCard("APP OPTIMIZE • $pkg") {
                Body("AOT compile (speed) ลดอาการกระตุกจาก JIT ของตัวแอป • เพิ่มใน battery whitelist กันระบบหยุดแอปเบื้องหลัง")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Compile speed") { act("app") { Tweaks.compileSpeed(pkg, false) } }
                    SecondaryButton("Reset compile") { act("app") { Tweaks.compileSpeed(pkg, true) } }
                    SecondaryButton("Whitelist +") { act("app") { Tweaks.batteryWhitelist(pkg, true) } }
                    SecondaryButton("Whitelist −") { act("app") { Tweaks.batteryWhitelist(pkg, false) } }
                }
                Log("app")
            }

            PanelCard("GPU DRIVER ต่อแอป • $pkg") {
                Body("ANGLE: แปลง OpenGL ES ของแอปเป็น Vulkan (บางเกม GLES ลื่นขึ้น/แก้บั๊ก) • Updatable driver: ใช้ไดรเวอร์ GPU ที่อัปเดตได้ของระบบ (ถ้าเฟิร์มแวร์มี)")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("ANGLE เปิด") { act("drv") { Tweaks.angle(ctx, pkg, true) } }
                    SecondaryButton("ANGLE ปิด") { act("drv") { Tweaks.angle(ctx, pkg, false) } }
                    SecondaryButton("Updatable เปิด") { act("drv") { Tweaks.updatableDriver(ctx, pkg, true) } }
                    SecondaryButton("Updatable ปิด") { act("drv") { Tweaks.updatableDriver(ctx, pkg, false) } }
                    SecondaryButton("ตรวจ") { act("drv") { Tweaks.updatableDriverInfo() } }
                }
                Log("drv")
            }
        }

        PanelCard("THERMAL REPORT") {
            Body("อ่านสถานะ thermal service ของระบบ (sensor, throttling, cooling devices)")
            SecondaryButton("อ่านรายงาน") { act("th") { Tweaks.thermalReport() } }
            Log("th")
        }

        PanelCard("คืนค่าทั้งหมด") {
            Body("คืนค่า settings ทุกตัวที่แอปนี้เคยแก้ กลับเป็นค่าเดิม")
            PrimaryButton("Revert all") { act("all") { Tweaks.revertAll(ctx) } }
            Log("all")
        }
    }

    if (picking) AppPicker(onDismiss = { picking = false }) { p -> target = p; Prefs.setTargetPkg(ctx, p); picking = false }
}

@Composable
private fun AppPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val apps = remember {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val infos = pm.queryIntentActivities(intent, 0)
        infos.map {
            AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName)
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด") } },
        title = { Text("เลือกแอป") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, singleLine = true, label = { Text("ค้นหา") }, modifier = Modifier.fillMaxWidth())
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    apps.filter { q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true) }.forEach { a ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(a.pkg) }.padding(vertical = 8.dp)) {
                            Text(a.label, color = Color.White)
                            Text(a.pkg, color = Muted)
                        }
                    }
                }
            }
        }
    )
}
