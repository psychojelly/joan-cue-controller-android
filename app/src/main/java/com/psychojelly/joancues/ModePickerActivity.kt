package com.psychojelly.joancues

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.net.NetworkInterface

/**
 * Launcher screen: choose what this tablet is.
 * One tap on show day; deliberately no auto-remember so a tablet can never
 * silently boot into the wrong role.
 */
class ModePickerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val night = Color.parseColor("#0a0a0f")
        val gold = Color.parseColor("#d4af6a")

        fun bigButton(label: String, sub: String, onClick: () -> Unit) = Button(this).apply {
            text = "$label\n$sub"
            textSize = 20f
            isAllCaps = false
            setPadding(48, 48, 48, 48)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(48, 24, 48, 24) }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(night)
        }

        root.addView(TextView(this).apply {
            text = "JOAN OF THE CITY"
            textSize = 26f; setTextColor(gold); gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "What is this tablet?"
            textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        })

        root.addView(bigButton("🎛  Operator", "Cue controller + server — fires cues to the group") {
            startActivity(Intent(this, MainActivity::class.java))
        })
        root.addView(bigButton("🎭  Performer", "Listens for cues on :7000 and plays the audio") {
            startActivity(Intent(this, PerformerActivity::class.java))
        })

        root.addView(TextView(this).apply {
            val ip = localIp() ?: "unknown"
            text = "this device: $ip"
            textSize = 12f; setTextColor(Color.parseColor("#6f7186")); gravity = Gravity.CENTER
            setPadding(0, 40, 0, 0)
        })

        setContentView(root)
    }

    private fun localIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.contains('.') == true }
            ?.hostAddress
    } catch (e: Exception) { null }
}
