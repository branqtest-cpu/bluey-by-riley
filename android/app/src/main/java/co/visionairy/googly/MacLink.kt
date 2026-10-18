package co.visionairy.googly

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class FaceState(
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val mood: String = "listening",
    val talk: Float = 0f,
)

class MacLink(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preferences = context.getSharedPreferences("bluey", Context.MODE_PRIVATE)
    private val _macs = MutableStateFlow<List<String>>(emptyList())
    val macs = _macs.asStateFlow()
    private val _connectedMac = MutableStateFlow<String?>(null)
    val connectedMac = _connectedMac.asStateFlow()
    private val _face = MutableStateFlow(FaceState())
    val face = _face.asStateFlow()
    private val pending = ConcurrentHashMap<String, (JSONObject?) -> Unit>()
    private val knownServices = ConcurrentHashMap<String, NsdServiceInfo>()
    @Volatile private var connectionGeneration: Int = 0
    @Volatile private var socket: Socket? = null
    @Volatile private var writer: BufferedWriter? = null
    @Volatile private var desiredMac: String? = preferences.getString("preferredMac", null)
    @Volatile private var discovery: NsdManager.DiscoveryListener? = null

    var onCommand: ((String) -> Unit)? = null

    fun start() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "Bonjour service resolution failed: $errorCode")
                    }
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        knownServices[info.serviceName] = info
                        _macs.value = knownServices.keys.toList().sorted()
                        val selected = desiredMac?.let(knownServices::get) ?: info
                        if (_connectedMac.value != selected.serviceName) connect(selected)
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                knownServices.remove(serviceInfo.serviceName)
                _macs.value = knownServices.keys.toList().sorted()
                if (_connectedMac.value == serviceInfo.serviceName) {
                    disconnect()
                    knownServices.values.firstOrNull()?.let(::connect)
                }
            }
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovery = null
                Log.e(TAG, "Bonjour discovery failed: $errorCode")
                nsd.stopServiceDiscovery(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                nsd.stopServiceDiscovery(this)
            }
        }
        discovery = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        discovery?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (_: IllegalArgumentException) {
                // Discovery may already have stopped after an NSD error.
            }
        }
        discovery = null
        disconnect()
        scope.cancel()
    }

    fun choose(name: String) {
        desiredMac = name
        preferences.edit().putString("preferredMac", name).apply()
        knownServices[name]?.let(::connect)
    }

    fun request(command: String, fields: JSONObject = JSONObject(), done: (JSONObject?) -> Unit) {
        val id = UUID.randomUUID().toString()
        pending[id] = done
        val packet = JSONObject(fields.toString())
            .put("command", command)
            .put("callID", id)
        if (!send(packet)) pending.remove(id)?.invoke(null)
    }

    fun send(packet: JSONObject): Boolean = synchronized(this) {
        val output = writer ?: return false
        return try {
            output.write(packet.toString())
            output.newLine()
            output.flush()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun connect(info: NsdServiceInfo) {
        // Start a new connection generation so stale attempts won't auto-reconnect.
        val myGeneration = synchronized(this) { ++connectionGeneration }
        if (_connectedMac.value == info.serviceName) return
        disconnect()
        val host = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull() else info.host
        val port = info.port
        if (host == null || port <= 0) return
        scope.launch {
            var connection: Socket? = null
            try {
                val current = Socket()
                connection = current
                current.tcpNoDelay = true
                current.connect(InetSocketAddress(host, port), 5000)
                socket = current
                writer = BufferedWriter(OutputStreamWriter(current.getOutputStream(), Charsets.UTF_8))
                _connectedMac.value = info.serviceName
                send(JSONObject().put("hello", Build.MODEL.ifBlank { "Android phone" }))
                val reader = BufferedReader(InputStreamReader(current.getInputStream(), Charsets.UTF_8))
                while (!current.isClosed) {
                    val line = reader.readLine() ?: break
                    if (line.length > MAX_PACKET_SIZE) throw IllegalStateException("Mac sent an oversized packet")
                    handle(JSONObject(line))
                }
            } catch (error: Exception) {
                Log.e(TAG, "Mac connection failed", error)
            } finally {
                if (socket === connection) disconnect()
                // Only schedule a reconnect if this coroutine's generation is still current.
                if (scope.coroutineContext[kotlinx.coroutines.Job]?.isActive == true) scope.launch {
                    kotlinx.coroutines.delay(RECONNECT_DELAY_MS)
                    if (myGeneration == connectionGeneration) {
                        knownServices[info.serviceName]?.let(::connect)
                    }
                }
            }
        }
    }

    private fun handle(packet: JSONObject) {
        packet.optJSONObject("face")?.let { face ->
            _face.value = FaceState(
                gazeX = face.optDouble("gazeX", 0.0).toFloat(),
                gazeY = face.optDouble("gazeY", 0.0).toFloat(),
                mood = face.optString("mood", "listening"),
                talk = face.optDouble("talk", 0.0).toFloat(),
            )
        }
        packet.optString("hello").takeIf(String::isNotBlank)?.let { _connectedMac.value = it }
        val command = packet.optString("command")
        val callId = packet.optString("callID")
        if (callId.isNotBlank()) pending.remove(callId)?.invoke(packet)
        else if (command.isNotBlank()) main.post { onCommand?.invoke(command) }
    }

    @Synchronized
    private fun disconnect() {
        // Invalidate pending reconnection attempts from older connection generations.
        synchronized(this) { connectionGeneration++ }
        try {
            socket?.close()
        } catch (_: Exception) {
            // Closing a failed socket is best-effort.
        }
        socket = null
        writer = null
        _connectedMac.value = null
        pending.values.forEach { it(null) }
        pending.clear()
    }

    companion object {
        private const val SERVICE_TYPE = "_googly._tcp."
        private const val MAX_PACKET_SIZE = 1_048_576
        private const val RECONNECT_DELAY_MS = 2_000L
        private const val TAG = "Bluey"
    }
}
