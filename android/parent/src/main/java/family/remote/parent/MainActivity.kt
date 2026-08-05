package family.remote.parent

import android.Manifest
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import family.remote.parent.capture.ScreenShareService
import family.remote.parent.data.IncomingRequest
import family.remote.parent.data.ParentRepository

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        family.remote.parent.capture.AppContext.value = applicationContext
        setContent { MaterialTheme { ParentApp() } }
    }

    @Composable private fun ParentApp() {
        val repository = remember { ParentRepository() }
        var signedIn by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser != null) }
        var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        if (!signedIn) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("KinPilot Parent", style = MaterialTheme.typography.headlineMedium)
                Text("Sign in with the family account created during setup.")
                OutlinedTextField(email, { email = it }, label = { Text("Email") })
                OutlinedTextField(password, { password = it }, label = { Text("Password") })
                Button(onClick = { repository.signIn(email, password) { it.onSuccess { signedIn = true }.onFailure { e -> error = e.message } } }) { Text("Sign in") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }; return
        }

        val deviceId = remember { Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) }
        DisposableEffect(deviceId) { repository.registerDevice(deviceId, android.os.Build.MODEL); onDispose { } }
        var requests by remember { mutableStateOf(emptyList<IncomingRequest>()) }
        var inviteCode by remember { mutableStateOf<String?>(null) }
        var acceptedSession by remember { mutableStateOf<String?>(null) }
        val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null && acceptedSession != null) {
                val service = Intent(this, ScreenShareService::class.java)
                    .putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(ScreenShareService.EXTRA_RESULT_DATA, data)
                    .putExtra("sessionId", acceptedSession)
                ContextCompat.startForegroundService(this, service)
            } else acceptedSession = null
        }
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        DisposableEffect(deviceId) { val listener = repository.observeRequests(deviceId) { requests = it }; onDispose { listener.remove() } }

        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Ready to help", style = MaterialTheme.typography.headlineMedium)
            Text("Only people you pair can ask for help. You approve every session, and Android asks again before your screen is shared.")
            Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Enable remote-control accessibility") }
            Button(onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow session notification") }
            OutlinedButton(onClick = { repository.createPairingInvite(deviceId) { it.onSuccess { code -> inviteCode = code }.onFailure { e -> error = e.message } } }) { Text("Create one-time pairing code") }
            inviteCode?.let { Text("Pairing code: $it\nExpires in 10 minutes.", style = MaterialTheme.typography.titleLarge) }
            Divider()
            if (requests.isEmpty()) Text("No help requests")
            requests.forEach { request -> RequestCard(request,
                decline = { repository.respond(request.id, false) {} },
                accept = { repository.respond(request.id, true) { response -> response.onSuccess { sessionId ->
                    acceptedSession = sessionId
                    val manager = getSystemService(MediaProjectionManager::class.java)
                    projection.launch(manager.createScreenCaptureIntent())
                }.onFailure { e -> error = e.message } } })
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    @Composable private fun RequestCard(request: IncomingRequest, decline: () -> Unit, accept: () -> Unit) {
        Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${request.helperName} is asking to help", style = MaterialTheme.typography.titleMedium)
            Text("Accepting will open Android's screen-sharing confirmation.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = decline) { Text("Decline") }; Button(onClick = accept) { Text("Allow help") }
            }
        } }
    }
}
