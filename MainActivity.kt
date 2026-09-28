package com.coldai.assistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private const val BG = 0xFF080A0E.toInt()
private const val SURFACE = 0xFF171B22.toInt()
private const val ACCENT = 0xFF62D5FA.toInt()
private const val MUTED = 0xFF9AA4B2.toInt()

data class ChatMsg(val user: Boolean, val text: String)

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("cold_ai", MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = android.os.Handler(mainLooper)
    private var messages = mutableListOf<ChatMsg>()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var listening = false
    private var speaking = true
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var entry: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaking = prefs.getBoolean("speak", true)
        tts = TextToSpeech(this) { result ->
            if (result == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.setLanguage(Locale("ru", "RU"))
            }
        }
        buildUi()
        loadHistory()
        if (messages.isEmpty()) {
            addMessage(false, "Привет! Я Колд. Нажми на микрофон или напиши команду. Для Gemini открой Настройки и добавь API-ключ.")
        } else messages.forEach { addBubble(it.user, it.text) }
        setStatus("Готов к работе")
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(12))
            setBackgroundColor(BG)
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "COLD AI"; textSize = 25f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(btn("Настройки") { settings() })
        header.addView(btn("Очистить") { clearHistory() })
        root.addView(header)

        scroll = ScrollView(this)
        chat = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, dp(12)) }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply {
            textSize = 13f; gravity = Gravity.CENTER; setTextColor(MUTED); setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(status)
        val mic = Button(this).apply {
            text = "🎙"; textSize = 30f; setTextColor(Color.BLACK)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(ACCENT)
            }
            setOnClickListener { toggleMic() }
        }
        root.addView(mic, LinearLayout.LayoutParams(dp(82), dp(82)).apply { gravity = Gravity.CENTER })
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        entry = EditText(this).apply {
            hint = "Напиши команду или вопрос…"; setTextColor(Color.WHITE); setHintTextColor(MUTED)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(SURFACE); cornerRadius = dp(22).toFloat()
            }
        }
        row.addView(entry, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn("➤") {
            val s = entry.text.toString().trim()
            if (s.isNotEmpty()) { entry.setText(""); handle(s) }
        })
        root.addView(row)
        setContentView(root)
    }

    private fun btn(label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(ACCENT); setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { action() }
    }

    private fun addBubble(user: Boolean, text: String) {
        val tv = TextView(this).apply {
            this.text = text; textSize = 16f; setTextColor(if (user) Color.BLACK else Color.WHITE)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (user) ACCENT else SURFACE); cornerRadius = dp(16).toFloat()
            }
            setOnLongClickListener {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("COLD AI", text))
                setStatus("Скопировано"); true
            }
        }
        chat.addView(tv, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = if (user) Gravity.END else Gravity.START
            setMargins(if (user) dp(48) else 0, dp(4), if (user) 0 else dp(48), dp(4))
        })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addMessage(user: Boolean, text: String) {
        messages.add(ChatMsg(user, text))
        if (messages.size > 100) messages = messages.takeLast(100).toMutableList()
        saveHistory()
        addBubble(user, text)
    }

    private fun setStatus(s: String) { status.text = s }
    private fun saveHistory() {
        val arr = JSONArray()
        messages.forEach { arr.put(JSONObject().put("u", it.user).put("t", it.text)) }
        prefs.edit().putString("history", arr.toString()).apply()
    }
    private fun loadHistory() {
        try {
            val a = JSONArray(prefs.getString("history", "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                messages.add(ChatMsg(o.optBoolean("u"), o.optString("t")))
            }
        } catch (_: Exception) { messages.clear() }
    }
    private fun clearHistory() {
        AlertDialog.Builder(this).setTitle("Очистить историю?")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Очистить") { _, _ ->
                messages.clear(); chat.removeAllViews(); saveHistory()
                addMessage(false, "История очищена.")
            }.show()
    }

    private fun settings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0) }
        val key = EditText(this).apply {
            hint = "Gemini API key"; inputType = 129; setText(prefs.getString("api_key", ""))
        }
        val model = EditText(this).apply {
            hint = "Модель Gemini"; setText(prefs.getString("model", "gemini-2.0-flash"))
        }
        val voice = Button(this).apply {
            text = if (speaking) "Озвучка: ВКЛ" else "Озвучка: ВЫКЛ"
            setOnClickListener {
                speaking = !speaking; prefs.edit().putBoolean("speak", speaking).apply()
                text = if (speaking) "Озвучка: ВКЛ" else "Озвучка: ВЫКЛ"
            }
        }
        box.addView(key); box.addView(model); box.addView(voice)
        AlertDialog.Builder(this).setTitle("Настройки").setView(box)
            .setPositiveButton("Сохранить") { _, _ ->
                prefs.edit().putString("api_key", key.text.toString().trim())
                    .putString("model", model.text.toString().trim().ifBlank { "gemini-2.0-flash" }).apply()
                setStatus("Настройки сохранены")
            }.setNegativeButton("Отмена", null).show()
    }

    private fun say(s: String) {
        addMessage(false, s)
        setStatus("Готово")
        if (speaking && ttsReady) tts?.speak(s.take(3500), TextToSpeech.QUEUE_FLUSH, null, "cold")
    }

    private fun toggleMic() {
        if (listening) {
            recognizer?.cancel(); listening = false; setStatus("Остановлено"); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 10); return
        }
        startRecognition()
    }

    private fun startRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { say("Служба распознавания речи недоступна."); return }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { setStatus("Слушаю…") }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { setStatus("Распознаю…") }
            override fun onError(error: Int) { listening = false; setStatus("Ошибка распознавания ($error)") }
            override fun onResults(results: Bundle?) {
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) handle(text) else setStatus("Не расслышал")
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
        }
        listening = true
        recognizer?.startListening(intent)
    }

    private fun handle(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        addMessage(true, text)
        val t = text.lowercase(Locale.ROOT).replace("ё", "е")
        when {
            t in listOf("привет", "здравствуй", "привет колд") -> say("Привет! Чем помочь?")
            t.contains("который час") || t == "время" ->
                say("Сейчас " + SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
            t.contains("какая сегодня дата") || t == "дата" ->
                say("Сегодня " + SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date()))
            t.contains("фонарик") || t.contains("фонарь") -> {
                val on = !(t.contains("выключ") || t.contains("отключ") || t.contains("погас"))
                try {
                    val cm = getSystemService(CAMERA_SERVICE) as CameraManager
                    val id = cm.cameraIdList.firstOrNull {
                        cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                    if (id == null) say("Фонарик не найден.") else { cm.setTorchMode(id, on); say(if (on) "Фонарик включён." else "Фонарик выключен.") }
                } catch (_: Exception) { say("Не удалось управлять фонариком.") }
            }
            t.contains("громче") || t.contains("прибавь звук") -> {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                say("Сделал громче.")
            }
            t.contains("тише") || t.contains("убавь звук") -> {
                val am = getSystemService(AUDIO_SERVICE) as AudioManager
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                say("Сделал тише.")
            }
            t.contains("пауза") || t.contains("останови музыку") -> say("Для управления плеером используй кнопки самого плеера.")
            t.contains("настройки wi-fi") || t.contains("настройки wifi") -> openSystemSettings(Settings.ACTION_WIFI_SETTINGS, "Открываю настройки Wi-Fi.")
            t.contains("настройки bluetooth") -> openSystemSettings(Settings.ACTION_BLUETOOTH_SETTINGS, "Открываю настройки Bluetooth.")
            t.contains("настройки экрана") || t.contains("яркость") -> openSystemSettings(Settings.ACTION_DISPLAY_SETTINGS, "Открываю настройки экрана.")
            t.contains("настройки звука") -> openSystemSettings(Settings.ACTION_SOUND_SETTINGS, "Открываю настройки звука.")
            t.contains("настройки батареи") || t.contains("аккумулятор") -> openSystemSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS, "Открываю настройки батареи.")
            t.contains("открой настройки") || t == "настройки" -> openSystemSettings(Settings.ACTION_SETTINGS, "Открываю настройки Android.")
            t.startsWith("найди ") || t.startsWith("поищи ") || t.startsWith("поиск ") -> {
                val q = text.substringAfter(' ').trim()
                try { startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, q)); say("Ищу в интернете: $q") }
                catch (_: Exception) { say("Не удалось открыть поиск.") }
            }
            t.startsWith("открой ") || t.startsWith("запусти ") -> openTarget(text.substringAfter(' ').trim())
            else -> askGemini(text)
        }
    }

    private fun openSystemSettings(action: String, message: String) {
        try { startActivity(Intent(action)); say(message) }
        catch (_: Exception) { say("Эти настройки недоступны на устройстве.") }
    }

    private fun openTarget(target: String) {
        if (target.contains(".") && !target.contains(" ")) {
            val url = if (target.startsWith("http")) target else "https://$target"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                say("Открываю $target")
            } catch (_: Exception) { say("Не удалось открыть сайт.") }
            return
        }
        val pm = packageManager
        val launch = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = try { pm.queryIntentActivities(launch, 0) } catch (_: Exception) { emptyList() }
        val found = matches.firstOrNull { it.loadLabel(pm).toString().contains(target, true) }
        if (found == null) { say("Не нашёл приложение $target."); return }
        val intent = pm.getLaunchIntentForPackage(found.activityInfo.packageName)
        if (intent == null) say("Не удалось запустить приложение.") else {
            try { startActivity(intent); say("Открываю ${found.loadLabel(pm)}") }
            catch (_: Exception) { say("Не удалось открыть приложение.") }
        }
    }

    private fun askGemini(question: String) {
        val key = prefs.getString("api_key", "").orEmpty()
        if (key.isBlank()) { say("Чтобы отвечать на вопросы, добавь Gemini API-ключ в настройках."); return }
        setStatus("Думаю…")
        worker.execute {
            try {
                val contents = JSONArray()
                messages.takeLast(14).forEach { m ->
                    contents.put(JSONObject()
                        .put("role", if (m.user) "user" else "model")
                        .put("parts", JSONArray().put(JSONObject().put("text", m.text))))
                }
                // The current question is already in the conversation history.
                val body = JSONObject()
                    .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                        "Ты — Колд, дружелюбный помощник Android. Отвечай по-русски ясно и кратко."))))
                    .put("contents", contents)
                val model = prefs.getString("model", "gemini-2.0-flash") ?: "gemini-2.0-flash"
                val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent").openConnection() as HttpURLConnection)
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000; conn.readTimeout = 45000; conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-goog-api-key", key)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val response = stream.bufferedReader().use { it.readText() }
                conn.disconnect()
                if (code !in 200..299) throw Exception("Gemini вернул код $code: ${response.take(300)}")
                val parts = JSONObject(response).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")
                val answer = if (parts != null) (0 until parts.length()).joinToString("") { parts.optJSONObject(it)?.optString("text").orEmpty() }.trim() else ""
                if (answer.isBlank()) throw Exception("Пустой ответ Gemini")
                main.post { say(answer) }
            } catch (e: Exception) {
                main.post { say("Ошибка Gemini: ${e.message ?: "проверь интернет и ключ"}") }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecognition()
        else if (requestCode == 10) setStatus("Нужен доступ к микрофону")
    }

    override fun onDestroy() {
        recognizer?.destroy()
        tts?.stop(); tts?.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }
}
