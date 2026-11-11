package com.elitetunerx.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.elitetunerx.Prefs
import com.elitetunerx.core.Reading
import com.elitetunerx.core.Shell
import com.elitetunerx.core.Telemetry
import com.elitetunerx.hud.HudService
import com.elitetunerx.session.Recorder
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import java.util.Locale

@Composable
fun ShizukuCard() {
    val ctx = LocalContext.current
    var state by remember { mutableStateOf(Shell.state()) }
    LaunchedEffect(Unit) { while (true) { state = Shell.state(); delay(1000) } }
    PanelCard("SHIZUKU BRIDGE (ไม่ต้องรูท)") {
        when (state) {
            Shell.State.READY -> {
                val uid = Shell.uid()
                Body("✔ พร้อมใช้งาน — รันคำสั่งในสิทธิ์ ${if (uid == 0) "root" else "ADB shell (uid $uid)"}: วัด FPS จริง, อ่าน GPU sensor, จูนระบบได้", Good)
            }
            Shell.State.NO_PERMISSION -> {
                Body("Shizuku ทำงานอยู่ แต่ยังไม่ได้อนุญาตแอปนี้", Warn)
                PrimaryButton("อนุญาตสิทธิ์ Shizuku") { runCatching { Shizuku.requestPermission(1001) } }
            }
            Shell.State.UNAVAILABLE -> {
                Body("ยังไม่ได้เปิด Shizuku — FPS, GPU load และการจูนต้องใช้ Shizuku (เปิดผ่าน Wireless debugging ครั้งเดียวต่อการรีบูต)", Warn)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("เปิดแอป Shizuku") {
                        val i = ctx.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (i != null) ctx.startActivity(i)
                        else ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
                    }
                    SecondaryButton("วิธีตั้งค่า") {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/")))
                    }
                }
            }
        }
    }
}

@Composable
fun MonitorScreen() {
    val ctx = LocalContext.current
    val snap by Telemetry.snapshot.collectAsState()
    val hudRunning by HudService.running.collectAsState()
    val active by Recorder.active.collectAsState()
    var label by remember { mutableStateOf(Prefs.label(ctx)) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(active) { while (active != null) { tick++; delay(1000) } }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { HudService.start(ctx) }

    ScreenColumn {
        Header("ELITE TUNER X", "SNAPDRAGON 8 ELITE • LIVE TELEMETRY • ${snap?.gpuModel ?: "Adreno"}")
        ShizukuCard()

        val s = snap
        val na = Reading.na("starting…")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("FPS", s?.fps ?: na, Modifier.weight(1f), Good)
            Metric("FRAME TIME", s?.frameTime ?: na, Modifier.weight(1f), Good)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("GPU LOAD", s?.gpuBusy ?: na, Modifier.weight(1f))
            Metric("GPU CLOCK", s?.gpuFreq ?: na, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("CPU LOAD", s?.cpuUsage ?: na, Modifier.weight(1f), Purple)
            Metric("POWER", s?.power ?: na, Modifier.weight(1f), Purple)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("GPU TEMP", s?.gpuTemp ?: na, Modifier.weight(1f), Warn)
            Metric("CPU TEMP", s?.cpuTemp ?: na, Modifier.weight(1f), Warn)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Metric("SKIN TEMP", s?.skinTemp ?: na, Modifier.weight(1f), Warn)
            Metric("BATTERY TEMP", s?.batteryTemp ?: na, Modifier.weight(1f), Warn)
        }
        PanelCard("CPU CLUSTERS / RAM / THERMAL") {
            if (s == null) Body("กำลังเริ่ม…")
            else {
                s.clusters.forEach { c ->
                    Mono(String.format(Locale.US, "%-8s %6s / %s MHz", c.name, c.curMhz?.let { "%.0f".format(Locale.US, it) } ?: "N/A",
                        c.maxMhz?.let { "%.0f".format(Locale.US, it) } ?: "?"))
                }
                Mono(String.format(Locale.US, "RAM      %.2f / %.2f GB", s.ramUsedGb, s.ramTotalGb))
                Mono("Thermal  ${s.thermalStatus}   headroom ${s.headroom.text}", if (s.thermalStatus == "Nominal") Good else Warn)
                Mono("App      ${s.fgPackage ?: "-"}", Muted)
            }
        }

        PanelCard("GAME HUD + BENCHMARK RECORDER") {
            Body("HUD ลอยทับเกม (Winlator / GameHub / เกม Android) แสดง FPS จริงจาก SurfaceFlinger, GPU, CPU, อุณหภูมิ, วัตต์ • ลากเพื่อย้าย • แตะเพื่อย่อ • กด ● REC เพื่ออัดเซสชันเบนช์มาร์ก")
            OutlinedTextField(
                value = label, onValueChange = { label = it; Prefs.setLabel(ctx, it) }, singleLine = true,
                label = { Text("ชื่อเซสชันถัดไป เช่น GTA V • DXVK 2.4 • Turnip 25.1") }, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!hudRunning) PrimaryButton("เปิด HUD") {
                    if (!HudService.canDraw(ctx)) {
                        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                    } else if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else HudService.start(ctx)
                } else SecondaryButton("ปิด HUD") { HudService.stop(ctx) }
                val a = active
                if (a == null) SecondaryButton("● เริ่มอัด") { Recorder.start(label) }
                else PrimaryButton("■ หยุดอัด") { Recorder.stop(ctx) }
            }
            active?.let { a ->
                val dur = ((System.currentTimeMillis() - a.startMs) / 1000).toInt() + tick * 0
                Text(String.format(Locale.US, "กำลังอัด: %s • %02d:%02d • %d เฟรม • %s", a.label, dur / 60, dur % 60, synchronized(a) { a.frames.size }, a.pkg ?: "-"),
                    color = Bad, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (!HudService.canDraw(ctx)) Body("ต้องอนุญาต \"แสดงทับแอปอื่น\" ก่อน (กดเปิด HUD เพื่อไปหน้าตั้งค่า)", Warn)
        }

        PanelCard("ความแม่นยำของข้อมูล") {
            Body("ทุกค่าแสดงแหล่งที่มาใต้ตัวเลข (app = อ่านตรง, shell = ผ่าน Shizuku) • ถ้าเฟิร์มแวร์บล็อก sensor จะแสดง N/A — ไม่มีการสุ่มหรือเดาค่า", Color.White)
            Body("FPS/Frame time: timestamp ที่ SurfaceFlinger ส่งภาพขึ้นจอจริง (แม่นระดับนาโนวินาที) • GPU load/clock: ตัวนับของ kernel driver (kgsl) ตัวเดียวกับที่ Snapdragon Profiler ใช้ • Power: กระแส×แรงดันแบตเตอรี่ทั้งเครื่อง")
        }
    }
}
