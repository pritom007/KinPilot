package family.remote.helper

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.viewinterop.AndroidView
import family.remote.protocol.ControlCommand
import family.remote.protocol.RendezvousClient
import org.webrtc.SurfaceViewRenderer

@Composable fun RemoteScreen(client: RendezvousClient, expiresAt: Long, ended: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val engine = remember(client) { HelperRtcEngine(context, client) }
    var text by remember { mutableStateOf("") }
    DisposableEffect(engine) { engine.start(); onDispose { engine.close() } }
    BackHandler {
        engine.send(ControlCommand.GlobalAction(engine.next(), ControlCommand.Action.BACK))
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Remote support")
            Button(onClick = ended, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("End") }
        }
        Text("Temporary session · ends automatically")
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { SurfaceViewRenderer(it).also(engine::attachRenderer) }, modifier = Modifier.fillMaxSize())
            RemoteTouchLayer(
                onTap = { x, y -> engine.send(ControlCommand.Tap(engine.next(), x, y)) },
                onLongPress = { x, y -> engine.send(ControlCommand.LongPress(engine.next(), x, y)) },
                onSwipe = { fromX, fromY, toX, toY, durationMs ->
                    engine.send(ControlCommand.Swipe(engine.next(), fromX, fromY, toX, toY, durationMs))
                }
            )
        }
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

@Composable
private fun RemoteTouchLayer(
    onTap: (Float, Float) -> Unit,
    onLongPress: (Float, Float) -> Unit,
    onSwipe: (Float, Float, Float, Float, Long) -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    val pointerId = down.id
                    val startedAt = System.currentTimeMillis()
                    var last = start
                    var moved = false
                    var longPressSent = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                        if (change.positionChanged() && change.positionChange().getDistance() > 0f) {
                            last = change.position
                            if ((last - start).getDistance() > viewConfiguration.touchSlop) moved = true
                            change.consume()
                        }

                        val elapsed = System.currentTimeMillis() - startedAt
                        if (!moved && !longPressSent && elapsed >= viewConfiguration.longPressTimeoutMillis) {
                            val (x, y) = normalized(start)
                            onLongPress(x, y)
                            longPressSent = true
                        }

                        if (change.changedToUp()) {
                            if (!longPressSent) {
                                val (startX, startY) = normalized(start)
                                val (endX, endY) = normalized(change.position)
                                if (moved) onSwipe(startX, startY, endX, endY, elapsed.coerceIn(50L, 2_000L))
                                else onTap(startX, startY)
                            }
                            change.consume()
                            break
                        }
                    }
                }
            }
    )
}

private fun androidx.compose.ui.input.pointer.PointerInputScope.normalized(offset: Offset): Pair<Float, Float> =
    (offset.x / size.width).coerceIn(0f, 1f) to (offset.y / size.height).coerceIn(0f, 1f)
