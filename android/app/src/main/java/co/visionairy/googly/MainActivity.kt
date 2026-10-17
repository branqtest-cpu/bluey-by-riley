package co.visionairy.googly

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private lateinit var link: MacLink
    private lateinit var live: RealtimeSession
    private var permissionGranted by mutableStateOf(false)
    private var pendingHold = false
    private var requestingPermissionForHold = false
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        val permissionWasForHold = requestingPermissionForHold
        requestingPermissionForHold = false
        if (granted && permissionWasForHold) {
            if (pendingHold) live.beginAsk()
        } else if (granted) live.wake()
        else live.microphoneDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        link = MacLink(applicationContext)
        live = RealtimeSession(link)
        link.onCommand = { command ->
            when (command) {
                "wake" -> wakeWithPermission()
                "sleep" -> live.sleep()
            }
        }
        permissionGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        setContent {
            MaterialTheme {
                BlueyScreen(
                    link,
                    live,
                    onWake = ::wakeWithPermission,
                    onHoldStart = ::beginAskWithPermission,
                    onHoldEnd = ::endAsk,
                )
            }
        }
        link.start()
    }

    override fun onDestroy() {
        live.sleep()
        link.stop()
        super.onDestroy()
    }

    private fun wakeWithPermission() {
        if (link.connectedMac.value == null) {
            live.wake()
            return
        }
        if (permissionGranted) live.wake()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun beginAskWithPermission() {
        if (link.connectedMac.value == null) {
            live.wake()
            return
        }
        pendingHold = true
        if (permissionGranted) live.beginAsk()
        else {
            requestingPermissionForHold = true
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun endAsk() {
        if (!pendingHold) return
        pendingHold = false
        if (!requestingPermissionForHold) live.endAsk()
    }
}

@Composable
private fun BlueyScreen(
    link: MacLink,
    live: RealtimeSession,
    onWake: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
) {
    val face by link.face.collectAsState()
    val macs by link.macs.collectAsState()
    val connected by link.connectedMac.collectAsState()
    val state by live.state.collectAsState()
    var caption by remember { mutableStateOf("") }
    var holding by remember { mutableStateOf(false) }
    live.onCaption = { text, _ -> caption = text }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        BlueyFace(
            face = face,
            state = state,
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        if (live.state.value == RealtimeSession.State.ASLEEP) onWake() else live.sleep()
                    },
                    onLongPress = {
                        holding = true
                        onHoldStart()
                    },
                    onPress = {
                        tryAwaitRelease()
                        if (holding) {
                            holding = false
                            onHoldEnd()
                        }
                    },
                )
            },
        )

        Column(
            Modifier.align(Alignment.TopStart).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = when (state) {
                    RealtimeSession.State.ASLEEP -> connected?.let { "Connected to $it" } ?: "Looking for your Mac"
                    RealtimeSession.State.WAKING -> "Waking up"
                    RealtimeSession.State.LISTENING -> "Listening · hold to ask"
                    RealtimeSession.State.ASKING -> "I'm all ears"
                    RealtimeSession.State.THINKING -> "Thinking"
                    RealtimeSession.State.SPEAKING -> "Replying"
                },
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (connected == null && macs.isEmpty()) {
                Text("Open Bluey on your Mac and connect to the same Wi-Fi.", color = Color.LightGray, fontSize = 12.sp)
            }
        }

        if (macs.size > 1) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                macs.forEach { name ->
                    Button(
                        onClick = { link.choose(name) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (connected == name) Berry else Color(0xFF292635),
                        ),
                    ) { Text(name, maxLines = 1) }
                }
            }
        }

        if (caption.isNotBlank()) {
            Text(
                text = caption,
                color = Color.White,
                fontSize = 17.sp,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(start = 32.dp, end = 32.dp, bottom = 20.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xDD1E1B29))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun BlueyFace(
    face: FaceState,
    state: RealtimeSession.State,
    modifier: Modifier = Modifier,
) {
    val colors = listOf(Color(0xFFA9BCFF), Color(0xFF6C86F5), Color(0xFF4254D6), Color(0xFF2B2F8F))
    Canvas(modifier) {
        val scale = maxOf(size.width / 844f, size.height / 390f)
        val width = 820f * scale
        val height = 700f * scale
        val left = (size.width - width) / 2
        val top = size.height - height + 44f * scale
        drawOval(
            brush = Brush.linearGradient(colors, start = androidx.compose.ui.geometry.Offset(left, top),
                end = androidx.compose.ui.geometry.Offset(left + width, top + height)),
            topLeft = androidx.compose.ui.geometry.Offset(left, top),
            size = androidx.compose.ui.geometry.Size(width, height),
        )
        val eyeY = top + 157f * scale
        val pupilOffsetX = face.gazeX.coerceIn(-1f, 1f) * 42f * scale
        val pupilOffsetY = face.gazeY.coerceIn(-1f, 1f) * 40f * scale
        for (eyeX in listOf(left + 280f * scale, left + 540f * scale)) {
            drawCircle(Color.White, 95f * scale, androidx.compose.ui.geometry.Offset(eyeX, eyeY))
            drawCircle(BerryDark, 46f * scale, androidx.compose.ui.geometry.Offset(eyeX + pupilOffsetX, eyeY + pupilOffsetY))
            drawCircle(Color.White, 15f * scale,
                androidx.compose.ui.geometry.Offset(eyeX + pupilOffsetX - 15f * scale, eyeY + pupilOffsetY - 20f * scale))
        }
        drawOval(BerryDark, androidx.compose.ui.geometry.Offset(left + 380f * scale, top + 272f * scale),
            androidx.compose.ui.geometry.Size(60f * scale, (38f + face.talk.coerceIn(0f, 1f) * 34f) * scale))
        if (state == RealtimeSession.State.LISTENING || state == RealtimeSession.State.ASKING) {
            drawRoundRect(
                color = Color(0xFF4F8BFF),
                topLeft = androidx.compose.ui.geometry.Offset(8f, 8f),
                size = androidx.compose.ui.geometry.Size(size.width - 16f, size.height - 16f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(48f),
                style = Stroke(width = if (state == RealtimeSession.State.ASKING) 10f else 5f),
            )
        }
    }
}

private val Berry = Color(0xFF6C86F5)
private val BerryDark = Color(0xFF1C1F66)
