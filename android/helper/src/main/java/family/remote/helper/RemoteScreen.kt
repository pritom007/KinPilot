package family.remote.helper

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.viewinterop.AndroidView
import family.remote.protocol.ControlCommand
import family.remote.protocol.RendezvousClient
import org.webrtc.SurfaceViewRenderer

@Composable fun RemoteScreen(client: RendezvousClient, expiresAt: Long, ended: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val engine = remember(client) { HelperRtcEngine(context, client) }
    var text by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("Checking remote control…") }
    val routeController = remember { VoiceAudioRoute(context.applicationContext) }
    var joined by remember { mutableStateOf(false) }; var muted by remember { mutableStateOf(true) }; var route by remember { mutableStateOf("Speaker") }
    var remoteVoice by remember { mutableStateOf("Other person has not joined voice") }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && engine.joinVoice()) { routeController.begin(); joined=true; muted=false; route=routeController.label }
        else engine.reportVoicePermissionFailure(false)
    }
    DisposableEffect(engine) {
        engine.onControlStatus = { available, reason ->
            ready = available
            feedback = if (available) "Remote control ready" else family.remote.protocol.controlStatusMessage(reason)
        }
        engine.onControlResult = { result -> feedback = if (result.accepted) "Action completed" else "Action unavailable: "+result.reason }
        engine.onRemoteVoiceState = { state -> remoteVoice = if (!state.joined) "Other person has not joined voice" else if (state.muted) "Other person is muted" else "Voice connected" }
        engine.start()
        onDispose { routeController.end(); engine.close() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Remote support")
            Button(onClick = ended, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("End") }
        }
        Text("Temporary session · ends automatically")
        AndroidView(factory = { SurfaceViewRenderer(it).also(engine::attachRenderer) }, modifier = Modifier.weight(1f).fillMaxWidth())
        Text(feedback)
        Text("Tap, hold, or swipe directly on the screen.")
        Text(remoteVoice)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            if (!joined) Button(onClick = {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    if (engine.joinVoice()) { routeController.begin(); joined=true; muted=false; route=routeController.label }
                } else microphone.launch(Manifest.permission.RECORD_AUDIO)
            }) { Text("Join voice") }
            else { Button(onClick={ muted=!muted; engine.setVoiceMuted(muted) }){Text(if(muted)"Unmute" else "Mute")}; Button(onClick={route=routeController.cycle()}){Text(route)}; OutlinedButton(onClick={engine.leaveVoice();routeController.end();joined=false;muted=true}){Text("Leave")} }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(enabled = ready, onClick = { engine.send(ControlCommand.GlobalAction(engine.next(), ControlCommand.Action.BACK)) }) { Text("Back") }
            Button(enabled = ready, onClick = { engine.send(ControlCommand.GlobalAction(engine.next(), ControlCommand.Action.HOME)) }) { Text("Home") }
            Button(enabled = ready, onClick = { engine.send(ControlCommand.GlobalAction(engine.next(), ControlCommand.Action.RECENTS)) }) { Text("Recents") }
        }
        Row {
            OutlinedTextField(text, { text = it }, modifier = Modifier.weight(1f))
            Button(enabled = ready, onClick = { engine.send(ControlCommand.SetText(engine.next(), text)); text = "" }) { Text("Send") }
        }
    }
}
