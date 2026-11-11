package com.elitetunerx.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.elitetunerx.bench.GlBench
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BenchScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by GlBench.progress.collectAsState()
    var history by remember { mutableStateOf(GlBench.history(ctx)) }
    var error by remember { mutableStateOf<String?>(null) }
    val running = progress != null

    fun start(minutes: Int) {
        error = null
        val act = ctx as? ComponentActivity
        act?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        scope.launch {
            try {
                val r = withContext(Dispatchers.Default) { GlBench.run(minutes) }
                GlBench.save(ctx, r)
                history = GlBench.history(ctx)
            } catch (t: Throwable) {
                error = t.message ?: t.toString()
            } finally {
                act?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    ScreenColumn {
        Header("GPU BENCHMARK", "OpenGL ES 3 • offscreen 1920×1080 • ไม่ติด vsync • glFinish ทุกเฟรม")
        PanelCard("ทดสอบ") {
            Body("Quick (~25 วิ): วัดพลัง ALU (shader คำนวณหนัก) + Fill/Texture (bandwidth) • Stability: รันต่อเนื่องเพื่อดูว่าเครื่องร้อนแล้วลดสปีดกี่ % (thermal throttling)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Quick", enabled = !running) { start(0) }
                SecondaryButton("Stability 5 นาที", enabled = !running) { start(5) }
                SecondaryButton("15 นาที", enabled = !running) { start(15) }
            }
            progress?.let { p ->
                Text("${p.phase} • ${fmt(p.liveFps, "%.1f")} fps", color = Color.White, fontSize = 13.sp)
                LinearProgressIndicator(progress = { p.fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = Cyan)
                SecondaryButton("ยกเลิก") { GlBench.cancelled = true }
            }
            error?.let { Body("✖ $it", Bad) }
            Body("ควรปิด HUD/แอปอื่น, ถอดสายชาร์จ และให้เครื่องเย็นก่อนเทียบผล เพื่อให้ผลซ้ำได้")
        }

        if (history.isNotEmpty()) {
            val best = history.maxOf { it.score }
            history.forEach { r ->
                PanelCard("${r.mode} • ${SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(r.time))}") {
                    Text("SCORE ${r.score}", color = if (r.score == best) Good else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                    Mono("ALU   ${fmt(r.aluFps, "%.1f")} fps @1080p")
                    Mono("FILL  ${fmt(r.fillFps, "%.1f")} fps @1080p ×24 layers")
                    r.stabilityPct?.let { st ->
                        Mono("STABILITY ${fmt(st, "%.1f")}%  (ช้าสุด/เร็วสุด)", if (st >= 90) Good else if (st >= 75) Warn else Bad)
                        Mono("segments: " + r.segments.joinToString(" ") { fmt(it, "%.0f") }, Muted)
                    }
                    Mono("GPU ${fmt(r.avgGpuBusy, "%.0f", "%")} @ ${fmt(r.avgGpuMhz, "%.0f", " MHz")} • GPU max ${fmt(r.maxGpuTemp, "%.1f", "°C")} • CPU max ${fmt(r.maxCpuTemp, "%.1f", "°C")}", Muted)
                    Mono("Power ${fmt(r.avgWatts, "%.2f", " W")} • ${fmt(r.durationS, "%.0f", " s")}", Muted)
                    Body(r.renderer, Muted, 10)
                }
            }
            SecondaryButton("ล้างประวัติ") { GlBench.clear(ctx); history = emptyList() }
        }
    }
}
