package co.visionairy.googly

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.TimeUnit

class RealtimeSession(
    private val link: MacLink,
) {
    enum class State { ASLEEP, WAKING, LISTENING, ASKING, THINKING, SPEAKING }

    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State.ASLEEP)
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var recording = false
    @Volatile private var holding = false
    @Volatile private var askWhenReady = false
    @Volatile private var sleepAfterReply = false
    @Volatile private var awake = false
    private var transcript = StringBuilder()
    @Volatile private var currentState = State.ASLEEP

    var onCaption: ((String, Boolean) -> Unit)? = null

    fun wake() {
        if (awake || currentState == State.WAKING) return
        if (link.connectedMac.value == null) {
            showCaption("Open Bluey on your Mac and connect to the same Wi-Fi first.", true)
            return
        }
        awake = true
        setState(State.WAKING)
        requestToken()
    }

    fun sleep() {
        awake = false
        holding = false
        askWhenReady = false
        sleepAfterReply = false
        stopRecording()
        socket?.close(1000, "Bluey is going to sleep")
        socket = null
        setState(State.ASLEEP)
        link.send(JSONObject().put("command", "asleep"))
    }

    fun beginAsk() {
        holding = true
        showCaption("", false)
        if (!awake) wake()
        else if (socket != null) setState(State.ASKING)
    }

    fun endAsk() {
        if (!holding) return
        holding = false
        if (socket == null || currentState == State.WAKING) {
            askWhenReady = true
            return
        }
        ask()
    }

    private fun ask() {
        askWhenReady = false
        setState(State.THINKING)
        send(JSONObject().put("type", "input_audio_buffer.commit"))
        send(JSONObject().put("type", "response.create"))
    }

    fun toggle() {
        if (awake) sleep() else wake()
    }

    fun microphoneDenied() {
        showCaption("Bluey needs microphone permission to listen. Enable it in Android Settings.", true)
    }

    private fun connect(token: String) {
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=gpt-realtime-2.1")
            .header("Authorization", "Bearer $token")
            .header("OpenAI-Beta", "realtime=v1")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!awake) {
                    webSocket.close(1000, "Session ended")
                    return
                }
                if (!startRecording()) return
                if (askWhenReady) ask()
                else setState(if (holding) State.ASKING else State.LISTENING)
                link.send(JSONObject().put("command", "awake"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handle(JSONObject(text))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) reconnect()
            }

            override fun onFailure(webSocket: WebSocket, error: Throwable, response: Response?) {
                if (socket === webSocket) {
                    Log.e(TAG, "Realtime connection failed", error)
                    reconnect()
                }
            }
        })
    }

    private fun reconnect() {
        stopRecording()
        socket = null
        if (awake) retryScope.launch {
            delay(1_500)
            if (awake && socket == null) requestToken()
        }
    }

    private fun requestToken() {
        link.request("realtimeToken") { response ->
            val token = response?.optString("text")?.takeIf { it.isNotBlank() }
            if (!awake) return@request
            if (token != null) {
                connect(token)
            } else if (currentState == State.WAKING) {
                awake = false
                setState(State.ASLEEP)
                showCaption("Connect Bluey to a Mac with an OpenAI key to start.", true)
            } else {
                retryScope.launch {
                    delay(2_000)
                    if (awake && socket == null) requestToken()
                }
            }
        }
    }

    private fun startRecording(): Boolean {
        val minimum = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minimum <= 0) {
            showCaption("This phone can't start its microphone.", true)
            sleep()
            return false
        }
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum, FRAME_BYTES * 4),
        )
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            showCaption("I couldn't start the microphone. Check Bluey's microphone permission.", true)
            sleep()
            return false
        }
        recorder = audio
        try {
            audio.startRecording()
        } catch (error: IllegalStateException) {
            recorder = null
            audio.release()
            Log.e(TAG, "Could not start the microphone", error)
            showCaption("I couldn't start the microphone. Check Bluey's microphone permission.", true)
            sleep()
            return false
        }
        recording = true
        Thread({
            val samples = ShortArray(FRAME_SAMPLES)
            while (recording) {
                val count = audio.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (count == AudioRecord.ERROR_DEAD_OBJECT) break
                if (count < 0) {
                    Log.e(TAG, "Microphone read failed: $count")
                    break
                }
                if (count == 0) continue
                val raw = ByteArray(count * 2)
                for (i in 0 until count) {
                    val sample = samples[i].toInt()
                    raw[i * 2] = sample.toByte()
                    raw[i * 2 + 1] = (sample shr 8).toByte()
                }
                val encoded = Base64.getEncoder().encodeToString(raw)
                send(JSONObject().put("type", "input_audio_buffer.append").put("audio", encoded))
            }
        }, "Bluey microphone").start()
        return true
    }

    private fun stopRecording() {
        recording = false
        recorder?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
                // The recorder may not have reached RECORDSTATE_RECORDING.
            }
            it.release()
        }
        recorder = null
    }

    private fun handle(event: JSONObject) {
        when (event.optString("type")) {
            "response.created" -> transcript = StringBuilder()
            "response.output_text.delta", "response.output_audio_transcript.delta" -> {
                transcript.append(event.optString("delta"))
                setState(State.SPEAKING)
                showCaption(transcript.toString(), false)
            }
            "response.done" -> {
                val text = transcript.toString()
                if (text.isNotBlank()) showCaption(text, true)
                val output = event.optJSONObject("response")?.optJSONArray("output") ?: JSONArray()
                val calls = (0 until output.length()).mapNotNull { output.optJSONObject(it) }
                    .filter { it.optString("type") == "function_call" }
                if (calls.isNotEmpty()) {
                    transcript = StringBuilder()
                    runTools(calls)
                }
                else if (sleepAfterReply) sleep()
                else if (currentState == State.SPEAKING || currentState == State.THINKING) {
                    setState(if (holding) State.ASKING else State.LISTENING)
                }
            }
            "error" -> {
                val message = event.optJSONObject("error")?.optString("message") ?: "Something went wrong."
                showCaption(message, true)
            }
        }
    }

    private fun runTools(calls: List<JSONObject>) {
        setState(State.THINKING)
        runToolAt(calls, 0)
    }

    private fun runToolAt(calls: List<JSONObject>, index: Int) {
        if (index >= calls.size || socket == null) return
        val call = calls[index]
        val name = call.optString("name")
        val callId = call.optString("call_id")
        if (name == "go_to_sleep") sleepAfterReply = true
        val fields = JSONObject().put("tool", name).put("text", call.optString("arguments", "{}"))
        link.request("tool", fields) { result ->
            val text = result?.optString("text") ?: "The Mac didn't answer."
            send(JSONObject().put("type", "conversation.item.create")
                .put("item", JSONObject().put("type", "function_call_output").put("call_id", callId).put("output", text)))
            result?.optString("image")?.takeIf(String::isNotBlank)?.let { image ->
                send(JSONObject().put("type", "conversation.item.create")
                    .put("item", JSONObject().put("type", "message").put("role", "user")
                        .put("content", JSONArray().put(JSONObject().put("type", "input_image")
                            .put("image_url", "data:image/jpeg;base64,$image")))))
            }
            main.post {
                if (socket == null) return@post
                if (name == "web_research") {
                    text.substringAfter("\n---REPORT---\n", "").takeIf(String::isNotBlank)?.let { report ->
                        // The report text is already shown in the Mac's report card.
                        onCaption?.invoke(report, true)
                    }
                }
                runToolAt(calls, index + 1)
                if (index + 1 == calls.size) send(JSONObject().put("type", "response.create"))
            }
        }
    }

    private fun send(event: JSONObject) {
        socket?.send(event.toString())
    }

    private fun showCaption(text: String, done: Boolean) {
        main.post { onCaption?.invoke(text, done) }
        link.send(JSONObject().put("command", if (done) "captionDone" else "caption").put("text", text))
    }

    private fun setState(value: State) {
        currentState = value
        main.post { _state.value = value }
    }

    companion object {
        private const val SAMPLE_RATE = 24_000
        private const val FRAME_SAMPLES = 2_400
        private const val FRAME_BYTES = FRAME_SAMPLES * 2
        private const val TAG = "Bluey"
    }
}
