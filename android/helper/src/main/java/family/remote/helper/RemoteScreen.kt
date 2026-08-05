package family.remote.helper

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import family.remote.protocol.ControlCommand
import family.remote.protocol.RendezvousClient
import org.webrtc.SurfaceViewRenderer

@Composable fun RemoteScreen(client: RendezvousClient, expiresAt: Long, ended: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val engine = remember(client) { HelperRtcEngine(context, client) }
    var text by remember { mutableStateOf("") }
    DisposableEffect(engine) { engine.start(); onDispose { engine.close() } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Remote support")
            Button(onClick = ended, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("End") }
        }
        Text("Temporary session · ends automatically")
        AndroidView(factory = { SurfaceViewRenderer(it).also(engine::attachRenderer) }, modifier = Modifier.weight(1f).fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button({ engine.send(ControlCommand.GlobalAction(engine.next(), ControlCommand.Action.BACK)) }) { Text("Back") }
            Button({ engine.send(ControlCommand.GlobalAction(engine.next(), ControlCommand.Action.HOME)) }) { Text("Home") }
            Button({ engine.send(ControlCommand.GlobalAction(engine.next(), ControlCommand.Action.RECENTS)) }) { Text("Recents") }
        }
        Row {
            OutlinedTextField(text, { text = it }, modifier = Modifier.weight(1f))
            Button({ engine.send(ControlCommand.SetText(engine.next(), text)); text = "" }) { Text("Send") }
        }
    }
}
