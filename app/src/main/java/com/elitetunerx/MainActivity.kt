package com.elitetunerx

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.elitetunerx.core.Telemetry
import com.elitetunerx.ui.BenchScreen
import com.elitetunerx.ui.Bg
import com.elitetunerx.ui.Cyan
import com.elitetunerx.ui.DriverScreen
import com.elitetunerx.ui.MonitorScreen
import com.elitetunerx.ui.Muted
import com.elitetunerx.ui.Panel
import com.elitetunerx.ui.Purple
import com.elitetunerx.ui.SessionsScreen
import com.elitetunerx.ui.TuneScreen
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching { Shizuku.addRequestPermissionResultListener(permListener) }
        setContent { EtxApp() }
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(permListener) }
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        Telemetry.acquire(this)
    }

    override fun onStop() {
        Telemetry.release()
        super.onStop()
    }
}

@Composable
private fun EtxApp() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        "Monitor" to Icons.Filled.Home, "Bench" to Icons.Filled.PlayArrow, "Drivers" to Icons.Filled.Build,
        "Tune" to Icons.Filled.Settings, "Sessions" to Icons.Filled.List,
    )
    MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Panel, primary = Cyan, secondary = Purple)) {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Panel) {
                    tabs.forEachIndexed { i, (label, icon) ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { Icon(icon, contentDescription = label) }, label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(selectedIconColor = Bg, indicatorColor = Cyan, unselectedIconColor = Muted, unselectedTextColor = Muted, selectedTextColor = Cyan)
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> MonitorScreen()
                    1 -> BenchScreen()
                    2 -> DriverScreen()
                    3 -> TuneScreen()
                    else -> SessionsScreen()
                }
            }
        }
    }
}
