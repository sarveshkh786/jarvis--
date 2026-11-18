package com.dayal.jarvis

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import org.json.JSONObject

/** Turns an action from the brain into a real phone action. Returns an error sentence, or null if fine. */
object Actions {

    fun run(ctx: Context, a: JSONObject): String? = try {
        when (a.optString("type")) {
            "open_app" -> openApp(ctx, a.optString("name"))
            "call" -> call(ctx, a.optString("name"))
            "whatsapp" -> whatsapp(ctx, a.optString("name"), a.optString("message"))
            "sms" -> sms(ctx, a.optString("name"), a.optString("message"))
            "alarm" -> {
                start(
                    ctx, Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, a.optInt("hour"))
                        .putExtra(AlarmClock.EXTRA_MINUTES, a.optInt("minute"))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                ); null
            }
            "timer" -> {
                start(
                    ctx, Intent(AlarmClock.ACTION_SET_TIMER)
                        .putExtra(AlarmClock.EXTRA_LENGTH, a.optInt("seconds", 60))
                        .putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label", "Jarvis"))
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                ); null
            }
            "youtube" -> view(ctx, "https://www.youtube.com/results?search_query=" + Uri.encode(a.optString("query")))
            "search" -> search(ctx, a.optString("query"))
            "open_url" -> view(ctx, a.optString("url"))
            "maps" -> view(ctx, "https://www.google.com/maps/search/?api=1&query=" + Uri.encode(a.optString("query")))
            "torch" -> { torch(ctx, a.optBoolean("on", true)); null }
            "volume" -> { volume(ctx, a.optInt("level", 50)); null }
            else -> null
        }
    } catch (e: Exception) {
        JarvisLog.add("Action error: ${e.message}")
        "माफ़ कीजिए, ये काम नहीं हो पाया।"
    }

    private fun start(ctx: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun view(ctx: Context, url: String): String? {
        start(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url))); return null
    }

    private fun search(ctx: Context, q: String): String? {
        val web = Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)
        return try {
            start(ctx, web); null
        } catch (e: Exception) {
            view(ctx, "https://www.google.com/search?q=" + Uri.encode(q))
        }
    }

    private fun openApp(ctx: Context, name: String): String? {
        val pm = ctx.packageManager
        val want = name.lowercase().trim()
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        val match = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == want }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(want) }
            ?: apps.firstOrNull { want.contains(it.loadLabel(pm).toString().lowercase()) }
            ?: return "$name ऐप फ़ोन में नहीं मिला।"
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return "$name नहीं खुल पाया।"
        start(ctx, launch); return null
    }

    /** Returns a phone number for a name, or the input if it's already a number. */
    private fun findNumber(ctx: Context, name: String): String? {
        val digits = name.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 6) return digits
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val cr = ctx.contentResolver
        cr.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%${name.trim()}%"), null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) }
        return null
    }

    private fun call(ctx: Context, name: String): String? {
        val num = findNumber(ctx, name) ?: return "$name का नंबर कॉन्टैक्ट्स में नहीं मिला।"
        val canCall = ctx.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        start(ctx, Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$num")))
        return null
    }

    private fun waNumber(num: String): String {
        var d = num.filter { it.isDigit() }
        if (d.length == 10) d = "91$d"
        if (d.length == 11 && d.startsWith("0")) d = "91" + d.drop(1)
        return d
    }

    private fun whatsapp(ctx: Context, name: String, msg: String): String? {
        val num = findNumber(ctx, name) ?: return "$name का नंबर कॉन्टैक्ट्स में नहीं मिला।"
        view(ctx, "https://wa.me/${waNumber(num)}?text=" + Uri.encode(msg))
        return null
    }

    private fun sms(ctx: Context, name: String, msg: String): String? {
        val num = findNumber(ctx, name) ?: return "$name का नंबर कॉन्टैक्ट्स में नहीं मिला।"
        start(ctx, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")).putExtra("sms_body", msg))
        return null
    }

    fun torch(ctx: Context, on: Boolean) {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return
        cm.setTorchMode(id, on)
    }

    private fun volume(ctx: Context, level: Int) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * level.coerceIn(0, 100)) / 100, AudioManager.FLAG_SHOW_UI)
    }
}
