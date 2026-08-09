package family.remote.helper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import family.remote.protocol.RendezvousClient

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { HelperApp() } }
    }

    @Composable private fun HelperApp() {
        var name by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var status by remember { mutableStateOf("Enter the code shown on the parent phone.") }
        var acceptedAt by remember { mutableStateOf<Long?>(null) }
        val client = remember {
            RendezvousClient(object : RendezvousClient.Listener {
                override fun onOpen() = runOnUiThread {
                    clientHolder?.join(code, name.ifBlank { "Family helper" })
                }
                override fun onWaiting() = runOnUiThread { status = "Waiting for the parent to accept…" }
                override fun onAccepted(expiresAt: Long) = runOnUiThread { acceptedAt = expiresAt }
                override fun onDeclined() = runOnUiThread { status = "The parent declined the request." }
                override fun onEnded(reason: String) = runOnUiThread { acceptedAt = null; status = "Session ended." }
                override fun onError(value: String) = runOnUiThread {
                    status = when (value) {
                        "room_not_found" -> "That code is invalid or expired."
                        "room_busy" -> "Another helper is already connected."
                        else -> "Could not connect: $value"
                    }
                }
            }).also { clientHolder = it }
        }
        DisposableEffect(client) { onDispose { client.close(); clientHolder = null } }

        acceptedAt?.let { expiry ->
            RemoteScreen(client, expiry) { client.close(); acceptedAt = null }
            return
        }
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("KinPilot Helper", style = MaterialTheme.typography.headlineMedium)
            Text("The connection is temporary and the parent must approve it on their phone.")
            OutlinedTextField(name, { name = it }, label = { Text("Your displayed name") }, singleLine = true)
            OutlinedTextField(code, { code = it.uppercase() }, label = { Text("Support code") }, singleLine = true)
            Button(enabled = code.filter(Char::isLetterOrDigit).length == 12, onClick = {
                status = "Connecting…"
                client.connect()
            }) { Text("Request access") }
            OutlinedButton(onClick = { openLatestRelease() }) { Text("Update from GitHub") }
            Text(status)
        }
    }

    companion object { @Volatile private var clientHolder: RendezvousClient? = null }

    private fun openLatestRelease() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/pritom007/KinPilot/releases/latest")))
    }
}
