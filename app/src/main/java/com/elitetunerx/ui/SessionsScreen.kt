package com.elitetunerx.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.elitetunerx.session.Session
import com.elitetunerx.session.SessionStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
private fun LineChart(values: List<Double>, color: Color, modifier: Modifier, maxHint: Double? = null) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val max = (maxHint ?: values.max()).coerceAtLeast(1e-6)
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * step
            val y = size.height - (v / max).toFloat().coerceIn(0f, 1f) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawLine(Muted.copy(alpha = 0.3f), Offset(0f, size.height), Offset(size.width, size.height))
        drawPath(path, color, style = Stroke(width = 2f))
    }
}

private fun downsampleMax(xs: DoubleArray, n: Int): List<Double> {
    if (xs.size <= n) return xs.toList()
    val bucket = xs.size.toDouble() / n
    return (0 until n).map { i ->
        val a = (i * bucket).toInt(); val b = ((i + 1) * bucket).toInt().coerceAtMost(xs.size)
        var m = 0.0
        for (j in a until b) if (xs[j] > m) m = xs[j]
        m
    }
}

@Composable
fun SessionsScreen() {
    val ctx = LocalContext.current
    val version by SessionStore.version.collectAsState()
    val sessions = remember(version) { SessionStore.list(ctx) }
    val selected = remember { mutableStateListOf<String>() }
    var open by remember { mutableStateOf<String?>(null) }

    ScreenColumn {
        Header("SESSIONS", "ผลอัดจาก HUD • เลือก ≥2 เซสชันเพื่อเทียบ DXVK / Driver / การจูน")
        if (sessions.isEmpty()) PanelCard("ยังไม่มีเซสชัน") {
            Body("วิธีทดสอบ DXVK แต่ละเวอร์ชันให้เห็นผลจริง:\n1. ตั้งชื่อเซสชันในแท็บ Monitor เช่น \"GTA V • DXVK 2.3 • Turnip 25\"\n2. เปิด HUD → เข้าเกมใน Winlator/GameHub\n3. ไปจุดเดิมในเกม กด ● REC 60–120 วินาที แล้วกด ■\n4. เปลี่ยน DXVK/ไดรเวอร์ใน Winlator แล้วทำซ้ำจุดเดิม\n5. กลับมาที่นี่ เลือกเซสชันแล้วเทียบ avg FPS / 1% low / วัตต์")
        }

        val cmp = sessions.filter { it.id in selected }
        if (cmp.size >= 2) CompareCard(cmp)

        sessions.forEach { s ->
            val sm = s.summary
            PanelCard(s.label) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = s.id in selected, onCheckedChange = { if (it) selected += s.id else selected -= s.id },
                        colors = CheckboxDefaults.colors(checkedColor = Cyan)
                    )
                    Body("${SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(s.startMs))} • ${fmt(s.durationS, "%.0f", " s")} • ${s.pkg ?: "-"}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Stat("AVG", fmt(sm.avgFps)); Stat("1% LOW", fmt(sm.low1)); Stat("0.1% LOW", fmt(sm.low01)); Stat("W", fmt(sm.avgWatts, "%.2f"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(if (open == s.id) "ซ่อน" else "รายละเอียด") { open = if (open == s.id) null else s.id }
                    SecondaryButton("แชร์ CSV") {
                        val f = SessionStore.exportCsv(ctx, s)
                        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }, "Export session"))
                    }
                    SecondaryButton("ลบ") { selected -= s.id; SessionStore.delete(ctx, s.id) }
                }
                if (open == s.id) Details(s)
            }
        }
    }
}

@Composable
private fun Stat(k: String, v: String) {
    androidx.compose.foundation.layout.Column {
        Text(k, color = Muted, fontSize = 10.sp)
        Text(v, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Details(s: Session) {
    val sm = s.summary
    Mono("frames ${sm.frames} • median ${fmt(sm.medianFt, "%.2f", " ms")} • p99 ${fmt(sm.p99Ft, "%.2f", " ms")}")
    Mono("frame-time stdev ${fmt(sm.ftStdev, "%.2f", " ms")} • jank ${fmt(sm.jankPct, "%.2f", "%")} (เฟรม > 2× median)")
    Mono("GPU ${fmt(sm.avgGpu, "%.0f", "%")} @ ${fmt(sm.avgGpuMhz, "%.0f", " MHz")} • CPU ${fmt(sm.avgCpu, "%.0f", "%")}")
    Mono("max CPU ${fmt(sm.maxCpuTemp, "%.1f", "°C")} • max GPU ${fmt(sm.maxGpuTemp, "%.1f", "°C")} • ${fmt(sm.fpsPerWatt, "%.1f", " FPS/W")}")
    Body("FPS ต่อวินาที", Good, 11)
    LineChart(s.samples.map { it.fps ?: 0.0 }, Good, Modifier.fillMaxWidth().height(80.dp))
    Body("Frame time (ms, ค่าสูงสุดต่อช่วง — สไปก์ = กระตุก)", Warn, 11)
    LineChart(downsampleMax(s.frameTimes, 300), Warn, Modifier.fillMaxWidth().height(80.dp), maxHint = (sm.p99Ft ?: 33.0) * 2)
    Body("GPU load %", Cyan, 11)
    LineChart(s.samples.map { it.gpu ?: 0.0 }, Cyan, Modifier.fillMaxWidth().height(60.dp), maxHint = 100.0)
}

@Composable
private fun CompareCard(list: List<Session>) {
    PanelCard("เปรียบเทียบ ${list.size} เซสชัน") {
        data class MetricRow(val name: String, val higherBetter: Boolean, val get: (Session) -> Double?, val pattern: String)
        val rows = listOf(
            MetricRow("Avg FPS", true, { it.summary.avgFps }, "%.1f"),
            MetricRow("1% low", true, { it.summary.low1 }, "%.1f"),
            MetricRow("0.1% low", true, { it.summary.low01 }, "%.1f"),
            MetricRow("Jank %", false, { it.summary.jankPct }, "%.2f"),
            MetricRow("FT stdev ms", false, { it.summary.ftStdev }, "%.2f"),
            MetricRow("GPU %", false, { it.summary.avgGpu }, "%.0f"),
            MetricRow("Watts", false, { it.summary.avgWatts }, "%.2f"),
            MetricRow("FPS/W", true, { it.summary.fpsPerWatt }, "%.1f"),
            MetricRow("Max GPU °C", false, { it.summary.maxGpuTemp }, "%.1f"),
        )
        list.forEachIndexed { i, s -> Mono("[${i + 1}] ${s.label}", Purple) }
        rows.forEach { r ->
            val vals = list.map(r.get)
            val present = vals.filterNotNull()
            val best = if (present.isEmpty()) null else if (r.higherBetter) present.max() else present.min()
            Row(Modifier.fillMaxWidth()) {
                Text(r.name, color = Muted, fontSize = 11.sp, modifier = Modifier.weight(1.3f))
                vals.forEach { v ->
                    Text(fmt(v, r.pattern), color = if (v != null && v == best && present.size > 1) Good else Color.White,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
            }
        }
        val base = list.last().summary.avgFps
        val top = list.first().summary.avgFps
        if (base != null && top != null && base > 0) {
            Body(String.format(Locale.US, "[1] เทียบกับ [%d]: %+.1f%% avg FPS", list.size, (top / base - 1) * 100), Color.White)
        }
    }
}
