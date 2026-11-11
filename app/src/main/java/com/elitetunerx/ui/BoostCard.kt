package com.elitetunerx.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elitetunerx.core.Shell
import com.elitetunerx.hud.HudService
import com.elitetunerx.tune.Boost
import com.elitetunerx.tune.BoostOptions
import com.elitetunerx.tune.BoostReport
import com.elitetunerx.tune.BoostTest
import com.elitetunerx.tune.Ram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
private fun Opt(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = Muted, fontSize = 11.sp, lineHeight = 14.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Cyan))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoostCard(target: String?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ram by remember { mutableStateOf(Boost.ram(ctx)) }
    var report by remember { mutableStateOf<BoostReport?>(null) }
    var log by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var o by remember { mutableStateOf(BoostOptions()) }
    var bloat by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var frozen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sel by remember { mutableStateOf(Boost.selectedBloat(ctx)) }
    var showBloat by remember { mutableStateOf(false) }
    var abSeconds by remember { mutableStateOf(30) }
    val phase by BoostTest.phase.collectAsState()
    val abResult by BoostTest.result.collectAsState()
    val ready = Shell.ready

    LaunchedEffect(Unit) { while (true) { ram = Boost.ram(ctx); delay(1500) } }
    LaunchedEffect(ready) {
        if (ready) withContext(Dispatchers.IO) { bloat = Boost.installedBloat(); frozen = Boost.frozenBloat() }
    }

    fun ramColor(r: Ram) = when { r.pct < 60 -> Good; r.pct < 80 -> Warn; else -> Bad }

    PanelCard("🚀 GAME BOOST") {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(String.format(Locale.US, "RAM %.0f%%", ram.pct), color = ramColor(ram), fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text(String.format(Locale.US, "%.2f / %.2f GB", ram.usedGb, ram.totalGb), color = Muted, fontSize = 13.sp)
        }
        report?.let { r ->
            Text(
                String.format(Locale.US, "%.0f%% → %.0f%%  (คืน RAM %.2f GB)", r.before.pct, r.after.pct, r.freedGb),
                color = if (r.freedGb > 0.05) Good else Warn, fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
        }
        if (!ready) Body("ต้องเปิด Shizuku ก่อน (แท็บ Monitor)", Warn)

        Opt("ปิดแอปเบื้องหลัง", "force-stop แอปที่ค้างอยู่ทุกตัว ยกเว้นเกม, launcher, คีย์บอร์ด, Shizuku", o.killApps) { o = o.copy(killApps = it) }
        Opt("แช่แข็งแอปขยะ (${sel.size} ตัว)", "ปิดโฆษณา/เก็บสถิติของ MIUI ไม่ให้ตื่นมากิน RAM/CPU • กดปุ่มเลือกด้านล่าง", o.freezeBloat) { o = o.copy(freezeBloat = it) }
        Opt("ปิด Doze ระหว่างเล่น", "ระบบไม่ดึงงานเบื้องหลังมาทำแทรกเกม", o.noDoze) { o = o.copy(noDoze = it) }
        Opt("ปิด phantom process killer", "กัน Android ฆ่า Wine/Box64 ใน Winlator (เกมเด้งกลางทาง)", o.phantom) { o = o.copy(phantom = it) }
        Opt("จำกัดแอปค้างใน RAM = 12", "แอปที่ปิดไปแล้วจะไม่ถูกเก็บใน RAM มาก → RAM ว่างให้เกมมากขึ้น", o.cachedLimit) { o = o.copy(cachedLimit = it) }
        Opt("⚠ ปิด Joyose (Xiaomi)", "เลิกให้ cloud ของ Xiaomi คุม FPS/ความร้อนต่อเกม — FPS อาจนิ่งขึ้น แต่เครื่องร้อนขึ้น", o.joyose) { o = o.copy(joyose = it) }
        Opt("⚠ หลอกสถานะความร้อน", "เกม Unity/Unreal จะไม่ลดกราฟิกเองเมื่อร้อน (ไม่ได้หยุด throttle ของ kernel) • คืนเองหลังรีบูต", o.thermalOverride) { o = o.copy(thermalOverride = it) }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(if (working) "กำลัง Boost…" else "🚀 BOOST", enabled = ready && !working) {
                working = true
                scope.launch {
                    val r = withContext(Dispatchers.IO) { Boost.apply(ctx, target, o) }
                    report = r; log = r.log; working = false
                    frozen = withContext(Dispatchers.IO) { Boost.frozenBloat() }
                }
            }
            SecondaryButton("คืนค่า Boost", enabled = ready && !working) {
                working = true
                scope.launch {
                    log = withContext(Dispatchers.IO) { Boost.revert(ctx) }; report = null; working = false
                    frozen = withContext(Dispatchers.IO) { Boost.frozenBloat() }
                }
            }
        }
        SecondaryButton(if (showBloat) "ซ่อนรายการแอปขยะ" else "เลือกแอปขยะที่จะแช่แข็ง") { showBloat = !showBloat }
        if (showBloat) {
            if (bloat.isEmpty()) Body(if (ready) "ไม่พบแอปขยะในรายการบนเครื่องนี้" else "ต้องเปิด Shizuku เพื่ออ่านรายการ")
            bloat.forEach { (pkg, name) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = pkg in sel, onCheckedChange = { c ->
                        sel = if (c) sel + pkg else sel - pkg; Boost.setSelectedBloat(ctx, sel)
                    }, colors = CheckboxDefaults.colors(checkedColor = Cyan))
                    Column {
                        Text(name + if (pkg in frozen) "  (แช่แข็งอยู่)" else "", color = Color.White, fontSize = 12.sp)
                        Text(pkg, color = Muted, fontSize = 10.sp)
                    }
                }
            }
        }
        log?.let { Mono(it, if (it.contains("✖")) Warn else Good) }
    }

    PanelCard("📊 A/B BOOST TEST — พิสูจน์ว่า FPS ดีขึ้นจริง") {
        Body("แอปจะเปิดเกม + HUD แล้ววัดอัตโนมัติ: ก่อน Boost → Boost → หลัง Boost แล้วสรุป % FPS และ 1% low ให้ดูบน HUD (ผลเก็บไว้ในแท็บ Sessions ด้วย) • ระหว่างวัดให้อยู่ที่จุดเดิมในเกม เล่นแบบเดิม")
        if (target == null) Body("เลือกแอปเป้าหมาย (เกม หรือ Winlator/GameHub) ด้านบนก่อน", Warn)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(20, 30, 60).forEach { s -> FilterChip(selected = abSeconds == s, onClick = { abSeconds = s }, label = { Text("รอบละ ${s}s") }) }
        }
        if (phase == null) {
            PrimaryButton("เริ่ม A/B Test", enabled = ready && target != null) {
                if (!HudService.canDraw(ctx)) {
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    return@PrimaryButton
                }
                if (!HudService.running.value) HudService.start(ctx)
                BoostTest.start(ctx, target!!, o, abSeconds)
                ctx.packageManager.getLaunchIntentForPackage(target)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        } else {
            Body(phase!!, Color.White)
            SecondaryButton("ยกเลิก") { BoostTest.cancel() }
        }
        abResult?.let { Mono(it, if (it.contains("✔")) Good else if (it.contains("✖")) Bad else Warn) }
    }

    PanelCard("🖥 SYSTEM RESOLUTION") {
        Body("ลดความละเอียดทั้งระบบ → GPU วาดพิกเซลน้อยลง เกม Android ได้ FPS เพิ่มชัด (Winlator ให้ลดความละเอียดในตัว Winlator) • ค่านี้คงอยู่แม้รีบูต กด 100% เพื่อคืน")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(100, 90, 85, 75, 67).forEach { p ->
                FilterChip(selected = false, enabled = ready && !working, onClick = {
                    working = true
                    scope.launch { log = withContext(Dispatchers.IO) { Boost.setScale(p) }; working = false }
                }, label = { Text("$p%") })
            }
        }
    }
}
