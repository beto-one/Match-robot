package com.mathrobot.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

data class Msg(val text: String, val mine: Boolean)

class MainActivity : ComponentActivity() {

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val serverUrl = "https://Kagenou.pythonanywhere.com/ask?q="
    private val scope = MainScope()

    private var tts: TextToSpeech? = null
    private var socket: BluetoothSocket? = null
    private var out: OutputStream? = null

    private val messages = mutableStateListOf<Msg>()
    private var status by mutableStateOf("Not connected")
    private var connected by mutableStateOf(false)
    private var busy by mutableStateOf(false)
    private var speaking by mutableStateOf(false)
    private var soundOn by mutableStateOf(false)
    private var langFa by mutableStateOf(true)
    private var listenTick by mutableIntStateOf(0)
    private var handsFree by mutableStateOf(false)
    private var awake = false
    private var awakeAt = 0L
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    private val startTask = Runnable { startRecognizer() }
    private val wakeRegex = Regex(
        listOf("هی امو", "امو", "ایمو", "آمو", "hey emo", "emo", "imo", "amo")
            .joinToString("|") { Regex.escape(it) },
        RegexOption.IGNORE_CASE
    )
    private val micPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) setHandsFree(true) else toast("Microphone permission is needed")
    }
    private var devices by mutableStateOf<List<BluetoothDevice>>(emptyList())
    private var showDevices by mutableStateOf(false)

    private val speechLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode == RESULT_OK) {
            val list = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val text = list?.firstOrNull()
            if (!text.isNullOrBlank()) ask(text)
        }
        if (handsFree) restartSoon()
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openDevicePicker() else toast("Bluetooth permission is needed")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.US)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        runOnUiThread {
                            speaking = true
                            stopRecognizer()
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        runOnUiThread {
                            speaking = false
                            restartSoon()
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        runOnUiThread { speaking = false }
                    }
                })
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        messages.add(Msg("Hi! I am Math Robot. Tap the mic or type a math problem.", false))
        setContent { App() }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        sender.shutdown()
        tts?.shutdown()
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    // ---------- Logic ----------

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun normalize(s: String): String {
        val sb = StringBuilder()
        for (ch in s) {
            sb.append(
                when (ch) {
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    '×' -> '*'
                    '÷' -> '/'
                    else -> ch
                }
            )
        }
        return sb.toString().trim()
    }

    private fun startListening() {
        stopRecognizer()
        sendLine("S:LISTEN")
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (langFa) "fa-IR" else "en-US")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say your math problem")
        }
        try {
            speechLauncher.launch(i)
        } catch (e: ActivityNotFoundException) {
            toast("Voice input is not available on this phone. Type your question instead.")
        }
    }

    private fun ask(q: String) {
        if (busy) return
        val question = normalize(q)
        if (question.isEmpty()) return
        messages.add(Msg(q.trim(), true))
        busy = true
        stopRecognizer()
        sendLine("S:THINK")
        scope.launch {
            val ans = withContext(Dispatchers.IO) { fetch(question) }
            messages.add(Msg(ans, false))
            busy = false
            sendAnswer(ans)
            speak(ans)
            restartSoon()
        }
    }

    private fun fetch(q: String): String {
        return try {
            val url = URL(serverUrl + URLEncoder.encode(q, "UTF-8"))
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 40000
            val s = c.inputStream.bufferedReader().use { it.readText() }
            s.trim().ifEmpty { "Empty answer" }
        } catch (e: Exception) {
            "No internet or server error"
        }
    }

    private fun speak(text: String) {
        if (!soundOn) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ans")
    }

    private fun sendLine(line: String) {
        val o = out ?: return
        sender.execute {
            try {
                o.write((line + "\n").toByteArray())
                o.flush()
            } catch (e: Exception) {
                runOnUiThread {
                    connected = false
                    status = "Connection lost"
                }
            }
        }
    }

    private fun sendAnswer(text: String) {
        val clean = text.replace(Regex("[^\\x20-\\x7E]"), " ").replace(Regex("\\s+"), " ").trim().take(160)
        sendLine("A:" + clean)
    }

    private fun adapter(): BluetoothAdapter? =
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @SuppressLint("MissingPermission")
    private fun openDevicePicker() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            permLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        val a = adapter()
        if (a == null || !a.isEnabled) {
            toast("Please turn on Bluetooth")
            return
        }
        devices = a.bondedDevices.toList()
        showDevices = true
    }

    @SuppressLint("MissingPermission")
    private fun deviceName(d: BluetoothDevice): String = d.name ?: "Unknown device"

    @SuppressLint("MissingPermission")
    private fun connect(d: BluetoothDevice) {
        status = "Connecting..."
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    socket?.close()
                    adapter()?.cancelDiscovery()
                    val s = d.createRfcommSocketToServiceRecord(sppUuid)
                    s.connect()
                    socket = s
                    out = s.outputStream
                    startReader(s)
                    true
                } catch (e: Exception) {
                    false
                }
            }
            connected = ok
            status = if (ok) "Connected: " + deviceName(d) else "Connection failed - try again"
        }
    }

    // Arduino can send "LISTEN" (talk button) to start voice input on the phone
    private fun startReader(s: BluetoothSocket) {
        Thread {
            try {
                val r = s.inputStream.bufferedReader()
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.trim() == "LISTEN") runOnUiThread { listenTick++ }
                }
            } catch (_: Exception) {
            }
            if (socket === s) {
                runOnUiThread {
                    connected = false
                    status = "Disconnected"
                }
            }
        }.start()
    }

    // ---------- Hands-free (wake word) ----------

    private fun setHandsFree(on: Boolean) {
        if (on) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                toast("Speech recognition is not available on this phone")
                return
            }
            handsFree = true
            awake = false
            startRecognizer()
        } else {
            handsFree = false
            awake = false
            stopRecognizer()
        }
    }

    private fun restartSoon(delayMs: Long = 300L) {
        handler.removeCallbacks(startTask)
        if (handsFree) handler.postDelayed(startTask, delayMs)
    }

    private fun stopRecognizer() {
        handler.removeCallbacks(startTask)
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun startRecognizer() {
        if (!handsFree || busy || speaking) return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this)
            recognizer?.setRecognitionListener(recListener)
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (langFa) "fa-IR" else "en-US")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        }
        try {
            recognizer?.startListening(i)
        } catch (e: Exception) {
            restartSoon(1500L)
        }
    }

    private val recListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onError(error: Int) {
            if (!handsFree) return
            if (awake && System.currentTimeMillis() - awakeAt > 10000) {
                awake = false
                sendLine("S:IDLE")
            }
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                setHandsFree(false)
                toast("Microphone permission is needed")
                return
            }
            restartSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1200L else 300L)
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            handleHeard(list ?: arrayListOf())
        }
    }

    private fun cleanHeard(s: String): String =
        normalize(s)
            .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
            .replace('ي', 'ی')
            .replace('ك', 'ک')
            .lowercase()

    private fun stripWake(s: String): String {
        val m = wakeRegex.find(s) ?: return s.trim()
        return s.substring(m.range.last + 1).trim(' ', ',', '،', '.', '!', '?', '؟')
    }

    // Only reacts after the wake word; everything else is ignored.
    private fun handleHeard(candidates: List<String>) {
        if (!handsFree) return
        val texts = candidates.map { cleanHeard(it) }.filter { it.isNotEmpty() }
        if (texts.isEmpty()) {
            restartSoon()
            return
        }
        val now = System.currentTimeMillis()
        if (awake && now - awakeAt > 10000) {
            awake = false
            sendLine("S:IDLE")
        }
        if (!awake) {
            val hit = texts.firstOrNull { wakeRegex.containsMatchIn(it) }
            if (hit == null) {
                restartSoon()
                return
            }
            awake = true
            awakeAt = now
            sendLine("S:LISTEN")
            val rest = stripWake(hit)
            if (rest.length >= 2) {
                awake = false
                ask(rest)
            } else {
                restartSoon()
            }
        } else {
            val rest = stripWake(texts.first())
            if (rest.length >= 2) {
                awake = false
                ask(rest)
            } else {
                restartSoon()
            }
        }
    }

    // ---------- UI ----------

    @Composable
    private fun App() {
        var input by remember { mutableStateOf("") }
        val listState = rememberLazyListState()

        LaunchedEffect(listenTick) { if (listenTick > 0) startListening() }
        LaunchedEffect(messages.size, busy) {
            if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size)
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF070B1F), Color(0xFF1B1464), Color(0xFF3B0F5E))
                    )
                )
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Math Robot", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                        Text(
                            status,
                            color = if (connected) Color(0xFF4ADE80) else Color(0xFFFCA5A5),
                            fontSize = 12.sp
                        )
                    }
                    Chip(if (soundOn) "🔊" else "🔇") {
                        soundOn = !soundOn
                        if (!soundOn) tts?.stop()
                    }
                    Spacer(Modifier.width(6.dp))
                    Chip(if (langFa) "FA" else "EN") { langFa = !langFa }
                    Spacer(Modifier.width(6.dp))
                    Chip(if (connected) "🔗 Arduino" else "🔌 Connect") { openDevicePicker() }
                }

                // Hands-free toggle
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Chip(if (handsFree) "👂 Hands-free: ON" else "👂 Hands-free: OFF") {
                        setHandsFree(!handsFree)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (handsFree) {
                        Text("Say Emo (امو) first, then ask", color = Color(0xFF86EFAC), fontSize = 12.sp)
                    }
                }

                // Robot face
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    RobotFace(speaking, busy, connected)
                }

                // Chat
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { m -> Bubble(m) }
                    if (busy) item { Bubble(Msg("Thinking...", false)) }
                }

                // Suggestions
                if (messages.size <= 1 && !busy) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "12*15",
                            "(5+3)/2",
                            "Ali has 5 apples and eats 3. How many are left?",
                            "Area of a circle with radius 3?"
                        ).forEach { s -> Chip(s) { ask(s) } }
                    }
                }

                // Mic
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    MicButton { if (!busy) startListening() }
                }

                // Input bar
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(28.dp))
                            .background(Color(0x22FFFFFF))
                            .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(28.dp))
                            .padding(horizontal = 18.dp, vertical = 14.dp)
                    ) {
                        if (input.isEmpty()) {
                            Text("Type a math problem...", color = Color(0x88FFFFFF), fontSize = 16.sp)
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                            cursorBrush = SolidColor(Color.White),
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Color(0xFF22D3EE), Color(0xFF3B82F6))))
                            .clickable {
                                if (input.isNotBlank() && !busy) {
                                    ask(input)
                                    input = ""
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("➤", color = Color.White, fontSize = 20.sp)
                    }
                }
            }

            if (showDevices) {
                Dialog(onDismissRequest = { showDevices = false }) {
                    Column(
                        Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF1E1B4B))
                            .padding(20.dp)
                    ) {
                        Text("Choose Bluetooth device", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        if (devices.isEmpty()) {
                            Text(
                                "No paired devices. First pair HC-05 in the phone Bluetooth settings (PIN 1234 or 0000).",
                                color = Color(0xCCFFFFFF),
                                fontSize = 14.sp
                            )
                        }
                        devices.forEach { d ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0x22FFFFFF))
                                    .clickable {
                                        showDevices = false
                                        connect(d)
                                    }
                                    .padding(14.dp)
                            ) {
                                Text(deviceName(d), color = Color.White, fontWeight = FontWeight.Bold)
                                Text(d.address, color = Color(0xAAFFFFFF), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Chip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0x22FFFFFF))
            .border(1.dp, Color(0x44FFFFFF), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
fun Bubble(m: Msg) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start
    ) {
        val shape = RoundedCornerShape(20.dp)
        val bg = if (m.mine) {
            Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)))
        } else {
            Brush.linearGradient(listOf(Color(0x3322D3EE), Color(0x2222D3EE)))
        }
        Box(
            Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(bg)
                .then(if (m.mine) Modifier else Modifier.border(1.dp, Color(0x6622D3EE), shape))
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(m.text, color = Color.White, fontSize = 16.sp)
        }
    }
}

@Composable
fun MicButton(onClick: () -> Unit) {
    val inf = rememberInfiniteTransition(label = "mic")
    val ringScale by inf.animateFloat(
        initialValue = 1f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringScale"
    )
    val ringAlpha by inf.animateFloat(
        initialValue = 0.55f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ringAlpha"
    )
    Box(Modifier.size(100.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(72.dp)
                .scale(ringScale)
                .alpha(ringAlpha)
                .clip(CircleShape)
                .background(Color(0xFFEC4899))
        )
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFFEC4899), Color(0xFF8B5CF6))))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text("🎤", fontSize = 30.sp)
        }
    }
}

@Composable
fun RobotFace(speaking: Boolean, busy: Boolean, connected: Boolean) {
    val inf = rememberInfiniteTransition(label = "face")
    val talk by inf.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(230, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "talk"
    )
    val glow by inf.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    val look by inf.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "look"
    )
    val blink = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            blink.animateTo(0.1f, tween(90))
            blink.animateTo(1f, tween(110))
        }
    }

    Canvas(Modifier.size(140.dp)) {
        val w = size.width
        val h = size.height
        val pink = Color(0xFFF472B6)
        val cyan = Color(0xFF22D3EE)

        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0x5522D3EE), Color.Transparent),
                center = center,
                radius = w * 0.62f
            ),
            radius = w * 0.62f,
            center = center
        )

        // headphones
        drawArc(
            color = pink,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.08f, h * 0.02f),
            size = Size(w * 0.84f, h * 0.7f),
            style = Stroke(width = 10f, cap = StrokeCap.Round)
        )
        drawRoundRect(pink, Offset(w * 0.02f, h * 0.36f), Size(w * 0.12f, h * 0.3f), CornerRadius(14f))
        drawRoundRect(pink, Offset(w * 0.86f, h * 0.36f), Size(w * 0.12f, h * 0.3f), CornerRadius(14f))

        // screen
        drawRoundRect(
            color = Color(0xFF020617),
            topLeft = Offset(w * 0.14f, h * 0.2f),
            size = Size(w * 0.72f, h * 0.62f),
            cornerRadius = CornerRadius(w * 0.14f)
        )
        drawRoundRect(
            color = cyan,
            topLeft = Offset(w * 0.14f, h * 0.2f),
            size = Size(w * 0.72f, h * 0.62f),
            cornerRadius = CornerRadius(w * 0.14f),
            style = Stroke(width = 3f)
        )

        // eyes
        val ew = w * 0.14f
        val eh = h * 0.22f * blink.value
        val dx = if (busy) look * w * 0.03f else 0f
        for (cx in listOf(w * 0.37f, w * 0.63f)) {
            drawRoundRect(
                color = cyan,
                topLeft = Offset(cx + dx - ew / 2, h * 0.43f - eh / 2),
                size = Size(ew, eh),
                cornerRadius = CornerRadius(ew * 0.4f)
            )
        }

        // mouth
        if (speaking) {
            drawRoundRect(
                color = pink,
                topLeft = Offset(w * 0.44f, h * 0.65f),
                size = Size(w * 0.12f, h * 0.08f * talk + 4f),
                cornerRadius = CornerRadius(10f)
            )
        } else if (busy) {
            drawCircle(pink, radius = 6f, center = Offset(w * 0.5f, h * 0.68f))
        } else {
            drawArc(
                color = pink,
                startAngle = 15f,
                sweepAngle = 150f,
                useCenter = false,
                topLeft = Offset(w * 0.43f, h * 0.62f),
                size = Size(w * 0.14f, h * 0.07f),
                style = Stroke(width = 5f, cap = StrokeCap.Round)
            )
        }

        val dot = if (connected) Color(0xFF4ADE80) else Color(0xFFF87171)
        drawCircle(dot.copy(alpha = 0.35f), radius = 14f * glow, center = Offset(w * 0.9f, h * 0.08f))
        drawCircle(dot, radius = 6f, center = Offset(w * 0.9f, h * 0.08f))
    }
}
