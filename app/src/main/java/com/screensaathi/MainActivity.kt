package com.screensaathi

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Setup gateway only. Grants the three permissions, lets the user open the demo
 * screen, and starts the overlay. Not part of the demo flow itself — once the
 * pill is up, the user never comes back here.
 */
class MainActivity : AppCompatActivity() {

    private val micRequest = 101

    private companion object {
        const val EXTRA_ACTION = "saathi_action"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_overlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
        }

        findViewById<Button>(R.id.btn_mic).setOnClickListener {
            if (!hasMic()) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.RECORD_AUDIO), micRequest
                )
            }
        }

        findViewById<Button>(R.id.btn_accessibility).setOnClickListener {
            explainAccessibilityThenOpenSettings()
        }

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                toast("पहले स्क्रीन पर दिखाने की अनुमति दें।")
                return@setOnClickListener
            }
            launchAssistant()
        }

    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        handleRehearsalIntent(intent)
        if (!isFinishing && intent?.getStringExtra(EXTRA_ACTION) == null &&
            Settings.canDrawOverlays(this) && hasMic() && isAccessibilityEnabled()) {
            // An APK update stops the foreground service. Opening the app
            // again should restore the floating control automatically.
            if (!OverlayService.isRunning()) launchAssistant()
            else moveTaskToBack(true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRehearsalIntent(intent)
    }

    /**
     * Lets a rehearsal be driven from outside — `adb shell am start ... --es
     * saathi_action run|choose`. OverlayService is not exported (it must not
     * be), so this exported activity is the only door in. It forwards and gets
     * straight back out of the way.
     */
    private fun handleRehearsalIntent(intent: Intent?) {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: return
        when (action) {
            "preview" -> {
                if (BuildConfig.DEBUG) {
                    val destination = intent.getStringExtra("destination").orEmpty()
                    val pickup = intent.getStringExtra("pickup")
                    OverlayService.previewDebug(this, destination, pickup)
                }
            }
            "run" -> {
                val task = intent.getStringExtra("task") ?: "book_taxi"
                val language = intent.getStringExtra("language") ?: "en-IN"
                OverlayService.runTask(this, task, language)
            }
            "choose" -> OverlayService.choose(this, intent.getIntExtra("choice", 0))
            // adb: am start -n com.screensaathi/.MainActivity --es saathi_action highlight --es query "Wi-Fi"
            "highlight" -> {
                val q = intent.getStringExtra("query").orEmpty()
                if (q.isNotBlank()) OverlayService.highlight(this, q)
            }
        }
        intent.removeExtra(EXTRA_ACTION)
        finish()
    }

    /**
     * `BIND_ACCESSIBILITY_SERVICE` is the single scariest permission dialog on
     * Android, and the OS prompt explains none of it — the audit that flagged
     * this called it out as the single biggest reason a first-time user quits
     * before ever seeing the product work. This dialog is the one screen
     * between "unexplained scary permission" and an informed decision; the
     * full accounting lives in docs/DATA_HANDLING.md if that's not enough.
     */
    private fun explainAccessibilityThenOpenSettings() {
        AlertDialog.Builder(this)
            .setTitle("स्क्रीन पढ़ने की अनुमति क्यों?")
            .setMessage(
                "इससे सहायक रैपिडो की खुली स्क्रीन पर जगह, गाड़ी और किराया पढ़ सकेगा।\n\n" +
                    "यात्रा की झलक फ़ोन पर ही तैयार होती है। स्क्रीन किसी क्लाउड सेवा को नहीं भेजी जाती। " +
                    "सहायक आपकी चुनी हुई गाड़ी बुक करता है, लेकिन भुगतान या ओटीपी नहीं भरता।\n\n" +
                    "अगली स्क्रीन पर रैपिडो सहायक स्क्रीन रीडर चालू करें।"
            )
            .setPositiveButton("आगे बढ़ें") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .setNegativeButton("अभी नहीं", null)
            .show()
    }

    private fun refreshStatus() {
        val overlay = if (Settings.canDrawOverlays(this)) "✓" else "✗"
        val mic = if (hasMic()) "✓" else "✗"
        val acc = if (isAccessibilityEnabled()) "✓" else "✗"
        findViewById<TextView>(R.id.setup_status).text =
            "स्क्रीन $overlay   माइक $mic   स्क्रीन रीडर $acc"
    }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun launchAssistant() {
        OverlayService.start(this)
        window.decorView.postDelayed({
            if (OverlayService.isRunning() && !isFinishing) moveTaskToBack(true)
        }, 600L)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = "$packageName/$packageName.ScreenReaderService"
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        if (!am.isEnabled) return false
        val setting = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(setting)
        for (s in splitter) {
            if (s.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshStatus()
    }
}
