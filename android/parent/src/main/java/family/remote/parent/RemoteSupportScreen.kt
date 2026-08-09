package family.remote.parent

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import family.remote.parent.rtc.HelperRtcEngine
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

    DisposableEffect(engine) {
        engine.onControlStatus = { ready, _ ->
            controlReady = ready
            feedback = if (ready) "Remote control is ready"
            else "Screen viewing only · ask them to enable KinPilot in Accessibility settings"
        }
        engine.onControlResult = { result ->
            feedback = if (result.accepted) "Action completed"
            else if (result.reason == "accessibility_unavailable") {
                controlReady = false
                "Screen viewing only · ask them to enable KinPilot in Accessibility settings"
            } else "Action unavailable · ${result.reason ?: "unknown error"}"
        }
        engine.start()
        onDispose { engine.onControlStatus = null; engine.onControlResult = null; engine.close() }
    }
    LaunchedEffect(expiresAt) {
        while (remaining > 0L) {
            delay(1_000)
            remaining = (expiresAt - System.currentTimeMillis()).coerceAtLeast(0L)
        }
        if (remaining == 0L) onEnd()
    }
    BackHandler(enabled = controlReady) {
        feedback = send(engine, ControlCommand.Action.BACK)
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
                RemoteTouchLayer(
                    enabled = controlReady,
                    onTap = { x, y ->
                        feedback = if (engine.send(ControlCommand.Tap(engine.next(), x, y))) "Tap requested" else "Control connection unavailable"
                    },
                    onLongPress = { x, y ->
                        feedback = if (engine.send(ControlCommand.LongPress(engine.next(), x, y))) "Long press requested" else "Control connection unavailable"
                    },
                    onSwipe = { fromX, fromY, toX, toY, durationMs ->
                        feedback = if (engine.send(ControlCommand.Swipe(engine.next(), fromX, fromY, toX, toY, durationMs))) "Swipe requested" else "Control connection unavailable"
                    }
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

@Composable
private fun RemoteTouchLayer(
    enabled: Boolean,
    onTap: (Float, Float) -> Unit,
    onLongPress: (Float, Float) -> Unit,
    onSwipe: (Float, Float, Float, Float, Long) -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val pointerId = down.id
                    val start = down.position
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

private fun formatRemaining(milliseconds: Long): String {
    val totalMinutes = milliseconds / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes.coerceAtLeast(1)}m"
}
