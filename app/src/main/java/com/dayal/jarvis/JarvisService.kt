package com.dayal.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.speech.RecognitionListener as AndroidListener
import org.vosk.android.RecognitionListener as VoskListener

/**
 * Always-on service:
 *  1. Vosk listens offline for the wake word "Jarvis".
 *  2. Android speech recognition hears the command (Hindi or English).
 *  3. Gemini decides what to say and which phone action to run.
 *  4. Text-to-speech answers, then it goes back to waiting for "Jarvis".
 */
class JarvisService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val CHANNEL = "jarvis"
        private const val NOTIF_ID = 7
        const val EXTRA_CMD = "cmd"

        @Volatile var running = false

        fun start(c: Context, cmd: String? = null) {
            val i = Intent(c, JarvisService::class.java)
            if (cmd != null) i.putExtra(EXTRA_CMD, cmd)
            c.startForegroundService(i)
        }

        fun stop(c: Context) {
            c.stopService(Intent(c, JarvisService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var wake: SpeechService? = null
    private var wakeRec: Recognizer? = null
    private var stt: SpeechRecognizer? = null
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var brain: Brain? = null
    @Volatile private var busy = false

    private val hindi get() = Prefs.lang(this).startsWith("hi")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startAsForeground("Starting…")
        tts = TextToSpeech(this, this)
        val key = Prefs.geminiKey(this)
        if (key.isNotBlank()) brain = Brain(key, Prefs.model(this))

        StorageService.unpack(this, "model", "model",
            { m: Model ->
                model = m
                log("Wake word model ready")
                listenForWake()
            },
            { e: java.io.IOException ->
                log("Model error: ${e.message}")
                updateNotif("Wake word model failed to load")
            })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground(if (busy) "Working…" else "Listening for \"Jarvis\"")
        intent?.getStringExtra(EXTRA_CMD)?.let { cmd ->
            busy = true
            stopWake()
            handle(cmd)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopWake()
        stt?.destroy()
        if (::tts.isInitialized) tts.shutdown()
        model?.close()
        log("Jarvis stopped")
        super.onDestroy()
    }

    // ---------- notification ----------

    private fun buildNotif(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Jarvis")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startAsForeground(text: String) {
        val n = buildNotif(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotif(text))
    }

    private fun log(s: String) = JarvisLog.add(s)

    // ---------- voice output ----------

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            log("Text-to-speech failed to start")
            return
        }
        tts.language = Locale.forLanguageTag(Prefs.lang(this))
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { main.post { afterSpeak(utteranceId) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { main.post { afterSpeak(utteranceId) } }
        })
        ttsReady = true
    }

    private fun speak(text: String, id: String) {
        log("Jarvis: $text")
        if (!ttsReady) {
            main.postDelayed({ afterSpeak(id) }, 300); return
        }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun afterSpeak(id: String?) {
        if (id == "ack") listenForCommand() else finishTurn()
    }

    private fun finishTurn() {
        busy = false
        main.postDelayed({ listenForWake() }, 400)
    }

    // ---------- wake word (offline, Vosk) ----------

    private fun listenForWake() {
        val m = model ?: return
        if (busy || !running) return
        try {
            stopWake()
            val rec = Recognizer(m, 16000f, "[\"jarvis\", \"hey jarvis\", \"[unk]\"]")
            wakeRec = rec
            val svc = SpeechService(rec, 16000f)
            wake = svc
            svc.startListening(object : VoskListener {
                override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
                override fun onResult(hypothesis: String?) = check(hypothesis, "text")
                override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")
                override fun onError(exception: Exception?) { log("Wake error: ${exception?.message}") }
                override fun onTimeout() {}
            })
            updateNotif("Listening for \"Jarvis\"")
        } catch (e: Exception) {
            log("Wake start failed: ${e.message}")
        }
    }

    private fun check(json: String?, field: String) {
        if (busy || json == null) return
        val heard = try { JSONObject(json).optString(field) } catch (e: Exception) { "" }
        if (heard.contains("jarvis")) {
            busy = true
            main.post { onWake() }
        }
    }

    private fun stopWake() {
        try { wake?.stop(); wake?.shutdown() } catch (_: Exception) {}
        wake = null
        try { wakeRec?.close() } catch (_: Exception) {}
        wakeRec = null
    }

    private fun onWake() {
        stopWake()
        log("Wake word heard")
        speak(if (hindi) "जी, बोलिए" else "Yes?", "ack")
    }

    // ---------- command (Android speech recognition) ----------

    private fun listenForCommand() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak(if (hindi) "फ़ोन में स्पीच रिकग्निशन नहीं मिला।" else "Speech recognition is not available.", "reply")
            return
        }
        stt?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        stt = r
        r.setRecognitionListener(object : AndroidListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (text.isNullOrBlank()) finishTurn() else handle(text)
            }
            override fun onError(error: Int) { log("Didn't catch that (code $error)"); finishTurn() }
            override fun onReadyForSpeech(params: Bundle?) { updateNotif("Listening to you…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { updateNotif("Thinking…") }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.lang(this))
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        r.startListening(i)
    }

    // ---------- thinking + doing ----------

    private fun handle(text: String) {
        log("You: $text")
        updateNotif("Thinking…")

        quickReply(text)?.let { speak(it, "reply"); return }

        val b = brain
        if (b == null) {
            speak(
                if (hindi) "पहले ऐप में Gemini की key डालिए, फिर मैं हर सवाल का जवाब दूँगा।"
                else "Please add your Gemini key in the app first.", "reply"
            )
            return
        }

        val now = SimpleDateFormat("EEEE d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date())
        Thread {
            val result: Pair<String, JSONObject?> = try {
                val r = b.ask(text, "Current date and time on the phone: $now")
                r.optString("say") to r.optJSONObject("action")
            } catch (e: Exception) {
                log("Brain error: ${e.message}")
                (if (hindi) "इंटरनेट या Gemini में दिक्कत है।" else "I couldn't reach Gemini.") to null
            }
            main.post {
                val error = result.second?.let { Actions.run(this, it) }
                speak(error ?: result.first.ifBlank { if (hindi) "हो गया।" else "Done." }, "reply")
            }
        }.start()
    }

    /** Instant answers that don't need the internet. */
    private fun quickReply(t: String): String? {
        val s = t.lowercase()
        val asksTime = listOf("time", "टाइम", "समय", "बजे", "baje").any { s.contains(it) } &&
            listOf("kya", "kitne", "क्या", "कितने", "what").any { s.contains(it) }
        if (asksTime) {
            val now = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())
            return if (hindi) "अभी $now हुए हैं।" else "It's $now."
        }
        if (listOf("torch", "टॉर्च", "flashlight", "फ्लैशलाइट").any { s.contains(it) }) {
            val off = listOf("off", "band", "बंद", "बुझा", "ऑफ").any { s.contains(it) }
            return try {
                Actions.torch(this, !off)
                if (hindi) (if (off) "टॉर्च बंद।" else "टॉर्च चालू।") else (if (off) "Torch off." else "Torch on.")
            } catch (e: Exception) {
                if (hindi) "टॉर्च नहीं चल पाई।" else "Couldn't use the torch."
            }
        }
        return null
    }
}
