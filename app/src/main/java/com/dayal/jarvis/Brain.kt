package com.dayal.jarvis

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Talks to the Gemini API. Returns {"say": "...", "action": {...} | null}. */
class Brain(private val apiKey: String, private val model: String) {

    private val history = ArrayList<JSONObject>()

    fun ask(userText: String, contextInfo: String): JSONObject {
        val userMsg = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", userText)))

        val contents = JSONArray()
        history.forEach { contents.put(it) }
        contents.put(userMsg)

        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM + "\n\n" + contextInfo)))
            )
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject().put("responseMimeType", "application/json").put("temperature", 0.4)
            )

        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 40000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", apiKey)
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val raw = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("Gemini $code: ${raw.take(300)}")

        val reply = JSONObject(raw)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
            .getString("text")

        history.add(userMsg)
        history.add(
            JSONObject().put("role", "model")
                .put("parts", JSONArray().put(JSONObject().put("text", reply)))
        )
        while (history.size > 12) history.removeAt(0)

        val clean = reply.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            JSONObject(clean)
        } catch (e: Exception) {
            JSONObject().put("say", clean)
        }
    }

    companion object {
        private const val SYSTEM = """You are Jarvis, a voice assistant running on the user's Android phone, in the style of Tony Stark's Jarvis: calm, loyal, a little witty, and brief.
The user speaks Hindi, English or Hinglish. Reply in the language they used. Write Hindi in Devanagari script so the Hindi voice reads it correctly. Keep "say" to one or two short sentences, because it is spoken aloud.

Return ONLY a JSON object: {"say": "<what to speak>", "action": <one action object or null>}

Actions you can use:
{"type":"open_app","name":"<app name in English, e.g. YouTube, WhatsApp, Camera>"}
{"type":"call","name":"<contact name or phone number>"}
{"type":"whatsapp","name":"<contact name or phone number>","message":"<text>"}
{"type":"sms","name":"<contact name or phone number>","message":"<text>"}
{"type":"alarm","hour":<0-23>,"minute":<0-59>,"label":"<short label>"}
{"type":"timer","seconds":<number>,"label":"<short label>"}
{"type":"youtube","query":"<what to play or search>"}
{"type":"search","query":"<web search>"}
{"type":"open_url","url":"<https url>"}
{"type":"maps","query":"<place or directions query>"}
{"type":"torch","on":<true|false>}
{"type":"volume","level":<0-100>}

Rules:
- Use an action only when the user asks you to do something on the phone.
- For live information you cannot know (news, weather, scores, prices), use a "search" action and say you are opening the results. Never invent such facts.
- Contact names: keep them as the user said them (e.g. "Mummy", "Rahul").
- If the request is unclear, ask a short question in "say" with action null."""
    }
}
