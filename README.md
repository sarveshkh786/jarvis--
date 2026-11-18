# Jarvis for Android

A voice assistant for your Android phone. Say **"Jarvis"**, then ask in Hindi or English.

## How it works
1. **Wake word:** offline, using [Vosk](https://alphacephei.com/vosk/). It keeps listening in the background, and a "Jarvis" notification stays visible.
2. **Your command:** your phone's own speech recognition (Hindi `hi-IN` or English `en-IN`).
3. **Brain:** Google Gemini API (free tier key from https://aistudio.google.com/apikey).
4. **Voice:** your phone's text-to-speech.

## What it can do
Open apps, call contacts, open WhatsApp/SMS with a typed message, set alarms and timers, play on YouTube, Google search, Maps, torch on/off, volume, tell the time, and answer questions.

## Install
1. Open the **Releases** page of this repo on your phone and download `Jarvis.apk`.
2. Allow "Install unknown apps" for your browser when Android asks.
3. Open Jarvis and do the three setup buttons (permissions, display over other apps, battery).
4. Paste your Gemini key, tap **Save**, then **Start Jarvis**.
5. Say "Jarvis", wait for "जी, बोलिए", then speak.

## Notes
- The APK is built automatically by GitHub Actions on every push to `main`.
- Each build is signed with a fresh debug key, so to install a newer build, uninstall the old Jarvis first (then paste your Gemini key again).
- Phone makers like Xiaomi, Vivo, Oppo and Realme kill background apps aggressively. Also turn on **Autostart** for Jarvis and lock it in recent apps.
