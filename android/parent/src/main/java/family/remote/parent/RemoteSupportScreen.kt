package family.remote.parent

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import family.remote.parent.rtc.HelperRtcEngine
import family.remote.parent.rtc.VoiceAudioRoute
import family.remote.protocol.ControlCommand
import family.remote.protocol.RendezvousClient
import kotlinx.coroutines.delay
import org.webrtc.SurfaceViewRenderer

@Composable
fun RemoteSupportScreen(client: RendezvousClient, expiresAt: Long, onEnd: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val engine = remember(client) { HelperRtcEngine(context.applicationContext, client) }
    var text by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf("Connecting securely…") }
    var controlReady by remember { mutableStateOf(false) }
    var remaining by remember { mutableLongStateOf((expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)) }
    val audioRoute = remember { VoiceAudioRoute(context.applicationContext) }
    var voiceJoined by remember { mutableStateOf(false) }
    var voiceMuted by remember { mutableStateOf(true) }
    var remoteVoice by remember { mutableStateOf("Other person has not joined voice") }
    var route by remember { mutableStateOf("Speaker") }
    var voiceFeedback by remember { mutableStateOf("Voice is optional and starts only when you join.") }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && engine.joinVoice()) { audioRoute.begin(); voiceJoined = true; voiceMuted = false; route = audioRoute.label; voiceFeedback = "Voice connected" }
        else { engine.reportVoicePermissionFailure(false); voiceFeedback = "Microphone permission is required for voice" }
    }

    DisposableEffect(engine) {
        engine.onControlStatus = { ready, reason ->
            controlReady = ready
            feedback = if (ready) "Remote control is ready"
            else family.remote.protocol.controlStatusMessage(reason)
        }
        engine.onControlResult = { result ->
            feedback = if (result.accepted) "Action completed"
            else if (result.reason == "accessibility_unavailable") {
                controlReady = false
                "Screen viewing only · ask them to enable KinPilot in Accessibility settings"
            } else "Action unavailable · ${result.reason ?: "unknown error"}"
        }
        engine.onRemoteVoiceState = { state ->
            remoteVoice = when { !state.joined -> "Other person has not joined voice"; state.muted -> "Other person is muted"; else -> "Other person is speaking-enabled" }
        }
        engine.start()
        onDispose { audioRoute.end(); engine.onControlStatus = null; engine.onControlResult = null; engine.onRemoteVoiceState = null; engine.close() }
    }
    LaunchedEffect(expiresAt) {
        while (remaining > 0L) {
            delay(1_000)
            remaining = (expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
        }
        if (remaining == 0L) onEnd()
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Helping remotely", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Private session · ${formatRemaining(remaining)} remaining",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            FilledTonalButton(onClick = onEnd, colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )) { Text("End") }
        }

        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF101614),
            tonalElevation = 2.dp
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AndroidView(
                    factory = { SurfaceViewRenderer(it).also(engine::attachRenderer) },
                    modifier = Modifier.fillMaxSize()
                )
                Text(
                    feedback,
                    modifier = Modifier.align(Alignment.TopCenter)
                        .padding(12.dp)
                        .background(Color(0xB0000000), RoundedCornerShape(20.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        Text("Tap, hold, or swipe directly on the shared screen.", style = MaterialTheme.typography.bodySmall)

        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .65f)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Voice", style = MaterialTheme.typography.titleMedium)
                Text("$voiceFeedback · $remoteVoice", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!voiceJoined) Button(onClick = {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            if (engine.joinVoice()) { audioRoute.begin(); voiceJoined = true; voiceMuted = false; route = audioRoute.label; voiceFeedback = "Voice connected" }
                        } else microphone.launch(Manifest.permission.RECORD_AUDIO)
                    }) { Text("Join voice") }
                    else {
                        FilledTonalButton(onClick = { voiceMuted = !voiceMuted; engine.setVoiceMuted(voiceMuted) }) { Text(if (voiceMuted) "Unmute" else "Mute") }
                        FilledTonalButton(onClick = { route = audioRoute.cycle() }) { Text(route) }
                        OutlinedButton(onClick = { engine.leaveVoice(); audioRoute.end(); voiceJoined = false; voiceMuted = true; voiceFeedback = "You left voice" }) { Text("Leave") }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(
                modifier = Modifier.weight(1f),
                enabled = controlReady,
                onClick = { feedback = send(engine, ControlCommand.Action.BACK) },
                label = { Text("Back") }
            )
            AssistChip(
                modifier = Modifier.weight(1f),
                enabled = controlReady,
                onClick = { feedback = send(engine, ControlCommand.Action.HOME) },
                label = { Text("Home") }
            )
            AssistChip(
                modifier = Modifier.weight(1f),
                enabled = controlReady,
                onClick = { feedback = send(engine, ControlCommand.Action.RECENTS) },
                label = { Text("Recents") }
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(2_000) },
                enabled = controlReady,
                modifier = Modifier.weight(1f),
                label = { Text("Type in focused field") },
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(enabled = controlReady && text.isNotBlank(), onClick = {
                feedback = if (engine.send(ControlCommand.SetText(engine.next(), text))) "Text requested" else "Control connection unavailable"
                if (feedback == "Text requested") text = ""
            }) { Text("Send") }
        }
        Text(
            "Protected and password fields reject remote typing. Nothing is recorded.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun send(engine: HelperRtcEngine, action: ControlCommand.Action): String =
    if (engine.send(ControlCommand.GlobalAction(engine.next(), action))) "Action requested" else "Control connection unavailable"

private fun formatRemaining(milliseconds: Long): String {
    val totalMinutes = milliseconds / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes.coerceAtLeast(1)}m"
}
