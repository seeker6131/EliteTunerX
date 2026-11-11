package com.elitetunerx.hud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.elitetunerx.MainActivity
import com.elitetunerx.R
import com.elitetunerx.Prefs
import com.elitetunerx.core.Reading
import com.elitetunerx.core.Snapshot
import com.elitetunerx.core.Telemetry
import com.elitetunerx.session.ActiveSession
import com.elitetunerx.session.Recorder
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

class HudService : Service() {
    companion object {
        const val ACTION_STOP = "com.elitetunerx.hud.STOP"
        const val ACTION_REC = "com.elitetunerx.hud.REC"
        private const val CHANNEL = "hud"
        val running = MutableStateFlow(false)

        fun canDraw(ctx: Context) = Settings.canDrawOverlays(ctx)
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, HudService::class.java))
        fun stop(ctx: Context) {
            if (running.value) ctx.startService(Intent(ctx, HudService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = MainScope()
    private lateinit var wm: WindowManager
    private var root: LinearLayout? = null
    private lateinit var text: TextView
    private lateinit var rec: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private var compact = false
    private var last: Pair<Snapshot?, ActiveSession?> = null to null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        running.value = true
        Telemetry.acquire(this)
        wm = getSystemService(WindowManager::class.java)
        if (canDraw(this)) addOverlay()
        scope.launch {
            combine(Telemetry.snapshot, Recorder.active) { s, a -> s to a }.collect {
                last = it
                render()
                updateNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_REC -> Recorder.toggle(this, Prefs.label(this))
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        Telemetry.release()
        running.value = false
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addOverlay() {
        val bg = GradientDrawable().apply {
            setColor(Color.argb(170, 4, 10, 18)); cornerRadius = dp(10).toFloat(); setStroke(dp(1), Color.argb(160, 98, 230, 255))
        }
        text = TextView(this).apply {
            typeface = Typeface.MONOSPACE; textSize = 11f; setTextColor(Color.WHITE); setPadding(dp(8), dp(6), dp(8), dp(4))
        }
        rec = TextView(this).apply {
            typeface = Typeface.DEFAULT_BOLD; textSize = 11f; setPadding(dp(8), dp(2), dp(8), dp(6)); text = "● REC"
            setTextColor(Color.rgb(255, 90, 90))
            setOnClickListener { Recorder.toggle(this@HudService, Prefs.label(this@HudService)) }
        }
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg; addView(text); addView(rec) }
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.get(this@HudService).getInt("hud_x", dp(12))
            y = Prefs.get(this@HudService).getInt("hud_y", dp(60))
        }
        text.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f; var sy = 0f; var ix = 0; var iy = 0; var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ix = lp.x; iy = lp.y; moved = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - sx).toInt(); val dy = (e.rawY - sy).toInt()
                        if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                        if (moved) { lp.x = ix + dx; lp.y = iy + dy; root?.let { wm.updateViewLayout(it, lp) } }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) Prefs.get(this@HudService).edit().putInt("hud_x", lp.x).putInt("hud_y", lp.y).apply()
                        else { compact = !compact; render() }
                    }
                }
                return true
            }
        })
        root = layout
        wm.addView(layout, lp)
    }

    private fun SpannableStringBuilder.kv(k: String, v: String, color: Int) {
        append(k)
        val s = length
        append(v)
        setSpan(ForegroundColorSpan(color), s, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun fpsColor(r: Reading): Int {
        val v = r.value ?: return Color.GRAY
        return when {
            v >= 55 -> Color.rgb(98, 255, 160)
            v >= 30 -> Color.rgb(255, 214, 98)
            else -> Color.rgb(255, 98, 98)
        }
    }

    private fun tempColor(r: Reading): Int {
        val v = r.value ?: return Color.GRAY
        return when {
            v < 60 -> Color.rgb(98, 230, 255)
            v < 75 -> Color.rgb(255, 214, 98)
            else -> Color.rgb(255, 98, 98)
        }
    }

    private fun render() {
        if (root == null) return
        val (s, a) = last
        val cyan = Color.rgb(98, 230, 255)
        val purple = Color.rgb(182, 154, 255)
        val sb = SpannableStringBuilder()
        if (s == null) {
            sb.append("ELITE TUNER X\nstarting…")
        } else {
            sb.kv("FPS ", s.fps.text.padEnd(6), fpsColor(s.fps))
            sb.kv(" ", s.frameTime.text, Color.WHITE)
            if (!compact) {
                sb.append('\n')
                sb.kv("GPU ", s.gpuBusy.text.padEnd(5), cyan); sb.kv(" ", s.gpuFreq.text.padEnd(9), Color.WHITE)
                sb.kv(" ", s.gpuTemp.text, tempColor(s.gpuTemp))
                sb.append('\n')
                val clocks = s.clusters.joinToString("/") { c -> c.curMhz?.let { String.format(Locale.US, "%.1f", it / 1000) } ?: "-" }
                sb.kv("CPU ", s.cpuUsage.text.padEnd(5), purple); sb.kv(" ", "${clocks}GHz ", Color.WHITE)
                sb.kv("", s.cpuTemp.text, tempColor(s.cpuTemp))
                sb.append('\n')
                sb.kv("PWR ", s.power.text.padEnd(8), Color.WHITE); sb.kv(" BAT ", s.batteryTemp.text, tempColor(s.batteryTemp))
                sb.append('\n')
                sb.kv("RAM ", String.format(Locale.US, "%.1f/%.1fGB", s.ramUsedGb, s.ramTotalGb), Color.WHITE)
                sb.kv("  ", s.thermalStatus, if (s.thermalStatus == "Nominal") cyan else Color.rgb(255, 160, 98))
                s.fgPackage?.let { sb.append('\n'); sb.kv("APP ", it.takeLast(28), Color.GRAY) }
            }
        }
        text.text = sb
        if (a != null) {
            val dur = ((System.currentTimeMillis() - a.startMs) / 1000).toInt()
            rec.text = String.format(Locale.US, "■ STOP  %02d:%02d  %d frames", dur / 60, dur % 60, synchronized(a) { a.frames.size })
            rec.setTextColor(Color.rgb(255, 90, 90))
        } else {
            rec.text = "● REC  " + Prefs.label(this).take(26)
            rec.setTextColor(Color.rgb(255, 150, 150))
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Performance HUD", NotificationManager.IMPORTANCE_LOW))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, HudService::class.java).setAction(ACTION_STOP), flags)
        val recI = PendingIntent.getService(this, 2, Intent(this, HudService::class.java).setAction(ACTION_REC), flags)
        val s = last.first
        val recording = last.second != null
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(if (recording) "Recording benchmark session" else "Elite Tuner X HUD")
            .setContentText(s?.let { "FPS ${it.fps.text} • GPU ${it.gpuBusy.text} • ${it.power.text}" } ?: "Starting telemetry…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, if (recording) "Stop recording" else "Record", recI)
            .addAction(0, "Close HUD", stop)
            .build()
    }

    private var lastNotif = 0L
    private fun updateNotification() {
        val now = System.currentTimeMillis()
        if (now - lastNotif < 3000) return
        lastNotif = now
        runCatching { getSystemService(NotificationManager::class.java).notify(7, notification()) }
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(7, notification())
    }
}
