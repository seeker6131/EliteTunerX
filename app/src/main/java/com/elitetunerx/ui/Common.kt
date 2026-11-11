package com.elitetunerx.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elitetunerx.core.Reading
import java.util.Locale

val Bg = Color(0xFF070D16)
val Panel = Color(0xFF101D2B)
val Panel2 = Color(0xFF162839)
val Cyan = Color(0xFF62E6FF)
val Purple = Color(0xFFB69AFF)
val Muted = Color(0xFF91A8BC)
val Good = Color(0xFF62FFA0)
val Warn = Color(0xFFFFD662)
val Bad = Color(0xFFFF6262)

@Composable
fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content
    )
}

@Composable
fun Header(title: String, sub: String) {
    Column {
        Text(title, color = Cyan, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text(sub, color = Muted, fontSize = 11.sp)
    }
}

@Composable
fun PanelCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            content()
        }
    }
}

@Composable
fun Metric(title: String, r: Reading, modifier: Modifier = Modifier, accent: Color = Cyan) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Text(r.text, color = if (r.ok) Color.White else Muted, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(r.source, color = Muted, fontSize = 9.sp, lineHeight = 11.sp)
        }
    }
}

@Composable
fun Body(text: String, color: Color = Muted, size: Int = 12) = Text(text, color = color, fontSize = size.sp, lineHeight = (size + 4).sp)

@Composable
fun Mono(text: String, color: Color = Color.White) =
    Text(text, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp)

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick, modifier, enabled = enabled, colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Bg),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) { Text(text, fontWeight = FontWeight.Bold, fontSize = 13.sp) }

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) =
    OutlinedButton(onClick, modifier, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
        Text(text, color = if (enabled) Cyan else Muted, fontSize = 13.sp)
    }

fun fmt(v: Double?, pattern: String = "%.1f", unit: String = ""): String =
    v?.let { String.format(Locale.US, pattern, it) + unit } ?: "N/A"
