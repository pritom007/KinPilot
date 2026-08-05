package family.remote.helper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { MaterialTheme { App() } } }
    @Composable private fun App() {
        val repo = remember { HelperRepository() }; var signedIn by remember { mutableStateOf(FirebaseAuth.getInstance().currentUser != null) }; var email by remember { mutableStateOf("") }; var password by remember { mutableStateOf("") }; var name by remember { mutableStateOf("") }; var code by remember { mutableStateOf("") }; var error by remember { mutableStateOf<String?>(null) }
        if (!signedIn) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("KinPilot Helper", style=MaterialTheme.typography.headlineMedium); OutlinedTextField(email,{email=it},label={Text("Email")}); OutlinedTextField(password,{password=it},label={Text("Password")}); Button({repo.signIn(email,password){it.onSuccess{signedIn=true}.onFailure{e->error=e.message}}}){Text("Sign in")}; error?.let{Text(it,color=MaterialTheme.colorScheme.error)} }; return }
        var devices by remember { mutableStateOf(emptyList<ParentDevice>()) }; var waiting by remember { mutableStateOf(false) }; var sessionId by remember { mutableStateOf<String?>(null) }
        DisposableEffect(Unit) { repo.registerDevice(android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID), android.os.Build.MODEL); onDispose { } }
        DisposableEffect(Unit) { val registration = repo.observeDevices { devices = it }; onDispose { registration.remove() } }
        sessionId?.let { RemoteScreen(it) { sessionId = null }; return }
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Family devices", style=MaterialTheme.typography.headlineMedium); Text("The parent approves every session before their screen is shared.")
            OutlinedTextField(name,{name=it},label={Text("Your displayed name")}); devices.forEach { device -> Card { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement=Arrangement.SpaceBetween) { Text(device.name); Button(enabled=!waiting,onClick={waiting=true;repo.request(device.id,name.ifBlank{"Family helper"}){result->result.onSuccess{requestId->repo.watchAccepted(requestId){sessionId=it;waiting=false}}.onFailure{e->error=e.message;waiting=false}}}){Text(if(waiting)"Waiting…" else "Request help")} } } }
            Divider(); Text("Pair another device",style=MaterialTheme.typography.titleLarge); OutlinedTextField(code,{code=it},label={Text("8-digit code")}); Button({repo.redeem(code,name.ifBlank{"Family helper"}){it.onSuccess{code=""}.onFailure{e->error=e.message}}}){Text("Pair")}; error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        }
    }
}
