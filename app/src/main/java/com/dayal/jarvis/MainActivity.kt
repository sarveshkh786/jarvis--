package com.dayal.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun label(t: String, size: Float = 14f) = TextView(this).apply {
            text = t; textSize = size; setPadding(0, pad / 2, 0, pad / 4)
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; isAllCaps = false; setOnClickListener { onClick() }
        }

        root.addView(label("Jarvis", 30f))
        status = label("")
        root.addView(status)

        root.addView(label("Gemini API key (free from aistudio.google.com)"))
        val key = EditText(this).apply {
            setText(Prefs.geminiKey(this@MainActivity))
            hint = "Paste your key here"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(key)

        root.addView(label("Gemini model"))
        val model = EditText(this).apply { setText(Prefs.model(this@MainActivity)) }
        root.addView(model)

        root.addView(label("Language"))
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val hi = RadioButton(this).apply { id = View.generateViewId(); text = "Hindi" }
        val en = RadioButton(this).apply { id = View.generateViewId(); text = "English" }
        group.addView(hi); group.addView(en)
        group.check(if (Prefs.lang(this).startsWith("hi")) hi.id else en.id)
        root.addView(group)

        fun save() {
            Prefs.save(this, key.text.toString(), model.text.toString(), if (group.checkedRadioButtonId == hi.id) "hi-IN" else "en-IN")
        }
        root.addView(button("Save settings") { save(); toast("Saved") })

        root.addView(label("One-time setup", 18f))
        root.addView(button("1. Give permissions (mic, calls, contacts, notifications)") { askPermissions() })
        root.addView(button("2. Allow \"Display over other apps\" (to open apps by voice)") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("3. Battery: let Jarvis run in background") { askBattery() })

        root.addView(label("Run", 18f))
        root.addView(button("Start Jarvis") {
            save()
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                toast("Pehle microphone permission dijiye (button 1)")
                askPermissions()
            } else {
                JarvisService.stop(this)
                JarvisService.start(this)
                toast("Jarvis started. Bolo: \"Jarvis\"")
                refresh()
            }
        })
        root.addView(button("Stop Jarvis") { JarvisService.stop(this); refresh() })

        root.addView(label("Test without speaking"))
        val test = EditText(this).apply { hint = "e.g. YouTube kholo" }
        root.addView(test)
        root.addView(button("Send to Jarvis") {
            save()
            val t = test.text.toString().trim()
            if (t.isNotEmpty() && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                JarvisService.start(this, t)
            } else if (t.isNotEmpty()) {
                toast("Pehle microphone permission dijiye (button 1)")
            }
        })

        root.addView(label("Log", 18f))
        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        JarvisLog.listener = { refresh() }
        refresh()
    }

    override fun onPause() {
        JarvisLog.listener = null
        super.onPause()
    }

    private fun refresh() {
        val pm = getSystemService(PowerManager::class.java)
        val checks = listOf(
            "Mic" to (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED),
            "Overlay" to Settings.canDrawOverlays(this),
            "Battery" to pm.isIgnoringBatteryOptimizations(packageName),
            "Gemini key" to Prefs.geminiKey(this).isNotBlank()
        )
        status.text = (if (JarvisService.running) "Status: running\n" else "Status: stopped\n") +
            checks.joinToString("   ") { (n, ok) -> (if (ok) "✓ " else "✗ ") + n }
        logView.text = JarvisLog.text()
    }

    private fun askPermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun askBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
