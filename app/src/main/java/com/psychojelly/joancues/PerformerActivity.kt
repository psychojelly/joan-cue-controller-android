package com.psychojelly.joancues

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.NetworkInterface

/**
 * Performer mode UI — a glanceable status board. The audio itself is driven
 * by PerformerService; this screen just shows what's happening:
 * connection status, preload progress, the big current cue, active stems,
 * and a short log.
 */
class PerformerActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var cueView: TextView
    private lateinit var stemsView: TextView
    private lateinit var logView: TextView
    private val ticker = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val night = Color.parseColor("#0a0a0f")
        val gold = Color.parseColor("#d4af6a")
        val soft = Color.parseColor("#a4a6b8")

        fun text(size: Float, color: Int, mono: Boolean = false) = TextView(this).apply {
            textSize = size; setTextColor(color)
            if (mono) typeface = Typeface.MONOSPACE
            setPadding(32, 12, 32, 12)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(night)
        }

        root.addView(text(14f, gold).apply { this.text = "JOAN OF THE CITY — PERFORMER"; gravity = Gravity.CENTER })
        statusView = text(13f, soft, mono = true)
        root.addView(statusView)

        cueView = text(44f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(32, 48, 32, 48)
            this.text = "—"
        }
        root.addView(cueView)

        root.addView(text(12f, gold).apply { this.text = "ACTIVE STEMS" })
        stemsView = text(15f, Color.WHITE, mono = true).apply { this.text = "(none)" }
        root.addView(stemsView)

        root.addView(text(12f, gold).apply { this.text = "LOG" })
        logView = text(12f, soft, mono = true)
        root.addView(logView)

        root.addView(android.widget.Button(this).apply {
            text = "⚙  SWITCH MODE"
            isAllCaps = false
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@PerformerActivity)
                    .setTitle("Switch mode?")
                    .setMessage("Stops the performer listener and returns to the mode picker.")
                    .setPositiveButton("Switch") { _, _ ->
                        Prefs.clearMode(this@PerformerActivity)
                        PerformerService.stop(this@PerformerActivity)
                        startActivity(android.content.Intent(this@PerformerActivity, ModePickerActivity::class.java)
                            .putExtra(ModePickerActivity.EXTRA_FORCE_PICKER, true))
                        finish()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER; setMargins(0, 32, 0, 48) }
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(night); addView(root) })

        PerformerService.start(this)
        ticker.post(object : Runnable {
            override fun run() { refresh(); ticker.postDelayed(this, 250) }
        })
    }

    private fun refresh() {
        val eng = PerformerService.engine
        val ip = localIp() ?: "?"
        statusView.text = "this device: $ip : ${PerformerService.OSC_PORT}\n${MasterClock.status()}\n${eng?.status ?: "starting…"}"
        cueView.text = eng?.lastCueId ?: "—"
        val stems = eng?.activeStems().orEmpty()
        stemsView.text = if (stems.isEmpty()) "(none)"
        else stems.joinToString("\n") { (n, v) -> "%-28s vol %.2f".format(n, v) }
        logView.text = synchronized(PerformerService.recentLog) { PerformerService.recentLog.joinToString("\n") }
    }

    override fun onDestroy() {
        ticker.removeCallbacksAndMessages(null)
        super.onDestroy()
        // Note: the service keeps running (that's the point). Stop it from the
        // notification via app settings, or add an explicit stop control later.
    }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
            ?.hostAddress
    } catch (e: Exception) { null }
}
