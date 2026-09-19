package family.remote.parent

import android.Manifest
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.view.WindowManager
import android.widget.Toast
import android.graphics.Bitmap
import android.net.Uri
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import family.remote.parent.capture.ScreenShareService
import family.remote.parent.control.RemoteControlService
import family.remote.parent.rtc.VoiceAudioRoute
import family.remote.protocol.RendezvousClient
import family.remote.protocol.SupportCode
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

object ParentSessionState { @Volatile var client: RendezvousClient? = null }

private enum class AppPage { HOME, GET_SUPPORT, HELP_SOMEONE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        family.remote.parent.capture.AppContext.value = applicationContext
        UpdateWorker.schedule(applicationContext)
        setContent { KinPilotTheme { KinPilotApp() } }
    }

    @Composable
    private fun KinPilotApp() {
        var page by rememberSaveable { mutableStateOf(if (intent.getBooleanExtra("getHelp", false)) AppPage.GET_SUPPORT else AppPage.HOME) }
        Surface(Modifier.fillMaxSize().safeDrawingPadding(), color = MaterialTheme.colorScheme.background) {
            when (page) {
                AppPage.HOME -> HomeScreen(onGetSupport = { page = AppPage.GET_SUPPORT }, onHelp = { page = AppPage.HELP_SOMEONE })
                AppPage.GET_SUPPORT -> GetSupportScreen(onBack = { page = AppPage.HOME })
                AppPage.HELP_SOMEONE -> HelpSomeoneScreen(onBack = { page = AppPage.HOME })
            }
        }
    }

    @Composable
    private fun HomeScreen(onGetSupport: () -> Unit, onHelp: () -> Unit) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            BrandMark()
            Spacer(Modifier.height(16.dp))
            Text("KinPilot", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(
                "Help from family, wherever they are.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
            )
            RoleCard(
                title = "I need help",
                description = "Ask someone you trust to help with this phone.",
                action = "Get help with my phone",
                accent = MaterialTheme.colorScheme.primary,
                onClick = onGetSupport
            )
            Spacer(Modifier.height(16.dp))
            RoleCard(
                title = "Help someone",
                description = "Use their code or scan their QR to help with their phone.",
                action = "Help with another phone",
                accent = MaterialTheme.colorScheme.secondary,
                onClick = onHelp
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { pinHelpShortcut() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                Text("Add Get help to my home screen")
            }
            Text("Next time, open Get help directly from your phone’s home screen.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            PhoneTools()
            Spacer(Modifier.height(16.dp))
            UpdateCard()
            Spacer(Modifier.height(24.dp))
            Text(
                "No account · No recording · Sessions expire automatically",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }

    @Composable
    private fun RoleCard(title: String, description: String, action: String, accent: Color, onClick: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(Modifier.background(Brush.linearGradient(listOf(accent.copy(alpha = .14f), MaterialTheme.colorScheme.surface))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(38.dp).clip(CircleShape).background(accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                    Text(title.first().toString(), color = accent, fontWeight = FontWeight.Bold)
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text(action, textAlign = TextAlign.Center) }
            }
        }
    }

    @Composable
    private fun GetSupportScreen(onBack: () -> Unit) {
        var code by remember { mutableStateOf<String?>(null) }
        var request by remember { mutableStateOf<Pair<String, String>?>(null) }
        var status by remember { mutableStateOf("Create a private code when you are ready.") }
        var connecting by remember { mutableStateOf(false) }
        var accepted by remember { mutableStateOf(false) }
        var sharing by remember { mutableStateOf(false) }
        var controlAvailable by remember { mutableStateOf(RemoteControlService.isAvailable()) }
        var setupExpanded by rememberSaveable { mutableStateOf(false) }
        var showQr by rememberSaveable { mutableStateOf(false) }
        var codeExpiresAt by remember { mutableStateOf(0L) }
        var secondsLeft by remember { mutableStateOf(0L) }
        var approved by remember { mutableStateOf(false) }
        var helperName by remember { mutableStateOf("") }
        val voiceRoute = remember { VoiceAudioRoute(applicationContext) }
        var voiceJoined by remember { mutableStateOf(false) }
        var voiceMuted by remember { mutableStateOf(true) }
        var voiceRouteLabel by remember { mutableStateOf("Speaker") }
        var remoteVoice by remember { mutableStateOf("Your helper has not joined voice") }
        val leaveScreen: () -> Unit = {
            if (sharing) {
                status = "Screen sharing is still active. Use the notification Stop button to end it."
                moveTaskToBack(true)
            } else onBack()
        }
        BackHandler(onBack = leaveScreen)
        val controlAvailabilityListener = remember { { available: Boolean -> runOnUiThread { controlAvailable = available } } }
        val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && sharing) {
                startService(Intent(this, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_VOICE_JOIN))
                voiceRoute.begin(); voiceJoined = true; voiceMuted = false; voiceRouteLabel = voiceRoute.label
            } else if (!granted) status = "Microphone permission was not granted. Screen sharing continues without voice."
        }
        val client = remember {
            RendezvousClient(object : RendezvousClient.Listener {
                override fun onOpen() { runOnUiThread { ParentSessionState.client?.createRoom() } }
                override fun onRoomCreated(value: String, expiresAt: Long) { runOnUiThread {
                    codeExpiresAt = expiresAt; secondsLeft = ((expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                    approved = false; helperName = ""; showQr = false
                    code = value; connecting = false; status = "Send this code to someone you trust. Keep KinPilot open while you wait."
                } }
                override fun onJoinRequest(id: String, helperName: String) { runOnUiThread {
                    request = id to helperName; status = "$helperName is asking to connect."
                } }
                override fun onAccepted(expiresAt: Long) { runOnUiThread { approved = true; accepted = true; status = "Approved. Choose what Android should share." } }
                override fun onEnded(reason: String) { runOnUiThread {
                    stopService(Intent(this@MainActivity, ScreenShareService::class.java))
                    code = null; request = null; connecting = false; sharing = false; approved = false; accepted = false
                    voiceRoute.end(); voiceJoined = false; voiceMuted = true
                    status = "Help has ended. To ask again, create a new code."
                } }
                override fun onError(value: String) { runOnUiThread {
                    connecting = false
                    status = "We could not connect. Check your internet, then try again."
                } }
            })
        }
        DisposableEffect(client) {
            ParentSessionState.client = client
            family.remote.parent.capture.SessionEvents.listener = { reason -> runOnUiThread {
                client.close(); code = null; request = null; connecting = false; accepted = false; sharing = false; approved = false
                voiceRoute.end(); voiceJoined = false; voiceMuted = true
                status = "Sharing has stopped. Your helper can no longer see or use your phone."
            } }
            family.remote.parent.capture.ScreenSessionCoordinator.voiceListener = { local, remote -> runOnUiThread {
                voiceJoined = local.joined; voiceMuted = local.muted
                remote?.let { remoteVoice = if (!it.joined) "Your helper has not joined voice" else if (it.muted) "Your helper is muted" else "Your helper joined voice" }
            } }
            RemoteControlService.addAvailabilityListener(controlAvailabilityListener)
            onDispose {
                stopService(Intent(this@MainActivity, ScreenShareService::class.java))
                family.remote.parent.capture.SessionEvents.listener = null
                family.remote.parent.capture.ScreenSessionCoordinator.voiceListener = null
                voiceRoute.end()
                RemoteControlService.removeAvailabilityListener(controlAvailabilityListener)
                client.close()
                if (ParentSessionState.client === client) ParentSessionState.client = null
            }
        }

        val projection = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null && accepted) {
                ContextCompat.startForegroundService(this, Intent(this, ScreenShareService::class.java)
                    .putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                    .putExtra(ScreenShareService.EXTRA_RESULT_DATA, data)
                    .putExtra("sessionId", "ephemeral"))
                sharing = true
                status = "Your screen is being shared. Use the notification to stop anytime."
            } else if (accepted) status = "Nothing is shared yet. Tap Start sharing when you are ready."
            accepted = false
        }
        LaunchedEffect(accepted) {
            if (accepted) {
                val manager = getSystemService(MediaProjectionManager::class.java)
                projection.launch(if (Build.VERSION.SDK_INT >= 34)
                    manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                else manager.createScreenCaptureIntent())
            }
        }
        val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(client) {
            connecting = true
            status = "Preparing your private connection…"
            client.connect()
        }
        val clipboard = LocalClipboardManager.current
        LaunchedEffect(code, codeExpiresAt, approved) {
            while (code != null && !approved) {
                secondsLeft = ((codeExpiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                if (secondsLeft == 0L) {
                    client.close(); code = null; request = null; connecting = false
                    status = "This code has expired. Create a new code and send it to your helper."
                    break
                }
                delay(1000)
            }
        }
        // Keep only this foreground support screen awake; never acquire a wake lock.
        DisposableEffect(code != null || sharing) {
            if (code != null || sharing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }

        request?.let { (id, name) ->
            AlertDialog(
                onDismissRequest = { client.respond(id, false); request = null },
                title = { Text("$name wants to help") },
                text = { Text("Do you recognize this person? If you allow them, Android will ask you to share your screen. They can then see your screen and, when remote control is enabled, use your phone. You can stop at any time.") },
                confirmButton = {
                    Button(onClick = { helperName = name; client.respond(id, true); request = null }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Yes, allow help") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { client.respond(id, false); request = null }, modifier = Modifier.heightIn(min = 56.dp)) { Text("No, decline") }
                }
            )
        }

        Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenHeader("Help with my phone", if (sharing) "$helperName can see your screen." else "We’ll guide you one step at a time.", leaveScreen)

            if (!sharing) {
                Text(if (approved) "Next: share your screen" else if (code != null) "Next: send your code" else "Let’s get you connected", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (sharing) {
                Button(
                    onClick = {
                        stopService(Intent(this@MainActivity, ScreenShareService::class.java))
                        client.close(); voiceRoute.end()
                        code = null; request = null; connecting = false; accepted = false; sharing = false; approved = false
                        voiceJoined = false; voiceMuted = true; status = "Sharing stopped. Your helper can no longer see or use your phone."
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                ) { Text("Stop sharing", fontWeight = FontWeight.Bold) }
            }

            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)) {
                Text(status, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.padding(16.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }

            if (code == null && !sharing) {
                Button(
                    enabled = !connecting,
                    onClick = { client.close(); connecting = true; status = "Starting the private connection…"; client.connect() },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                ) { Text(if (connecting) "Creating code…" else "Create support code") }
            }

            if (approved && !sharing) {
                Text("Android will ask what to share. Choose your entire screen so your helper can help across apps. Nothing is shared until you approve.")
                Button(onClick = { accepted = true }, enabled = !accepted, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Start sharing") }
            }

            code?.takeIf { !sharing && !approved }?.let { value ->
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Your private help code", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 10.dp))
                        Text("Valid for ${secondsLeft / 60}:${(secondsLeft % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "Help me on KinPilot: https://kinpilot.netlify.app/join#$value")
                                }, "Share support link"))
                            }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 56.dp)) { Text("Send code to my helper") }
                        Text("Choose your helper in your messaging app, send the link, then return here. Or read the code aloud on a call.", modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { clipboard.setText(AnnotatedString(value)); Toast.makeText(this@MainActivity, "Code copied", Toast.LENGTH_SHORT).show() }) { Text("Copy code") }
                        TextButton(onClick = { showQr = !showQr }) { Text(if (showQr) "Hide QR code" else "Helper beside you? Show QR code") }
                        if (showQr) {
                            val image = remember(value) { qr("https://kinpilot.netlify.app/join#$value") }
                            Image(image.asImageBitmap(), "Ask your helper to scan this QR code", Modifier.sizeIn(maxWidth = 210.dp).fillMaxWidth().aspectRatio(1f))
                        }
                    }
                }
            }

            if (sharing) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Screen sharing is active", color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
                        Text(remoteVoice, style = MaterialTheme.typography.bodySmall)
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!voiceJoined) Button(onClick = {
                                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    startService(Intent(this@MainActivity, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_VOICE_JOIN))
                                    voiceRoute.begin(); voiceJoined = true; voiceMuted = false; voiceRouteLabel = voiceRoute.label
                                } else microphone.launch(Manifest.permission.RECORD_AUDIO)
                            }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Talk to my helper") }
                            else {
                                FilledTonalButton(onClick = {
                                    voiceMuted = !voiceMuted
                                    startService(Intent(this@MainActivity, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_VOICE_MUTE).putExtra(ScreenShareService.EXTRA_MUTED, voiceMuted))
                                }, modifier = Modifier.fillMaxWidth()) { Text(if (voiceMuted) "Turn my microphone on" else "Mute my microphone") }
                                FilledTonalButton(onClick = { voiceRouteLabel = voiceRoute.cycle() }, modifier = Modifier.fillMaxWidth()) { Text("Sound output: $voiceRouteLabel") }
                                OutlinedButton(onClick = {
                                    startService(Intent(this@MainActivity, ScreenShareService::class.java).setAction(ScreenShareService.ACTION_VOICE_LEAVE))
                                    voiceRoute.end(); voiceJoined = false; voiceMuted = true
                                }, modifier = Modifier.fillMaxWidth()) { Text("End voice only") }
                            }
                        }
                    }
                }
            }

            if (!sharing && !approved) OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (controlAvailable) "Ready for hands-on help" else "Viewing only for now", fontWeight = FontWeight.SemiBold)
                    if (!controlAvailable) {
                        Text("Your helper can see your screen after you approve, but cannot tap things for you yet.")
                        TextButton(onClick = { setupExpanded = !setupExpanded }) { Text(if (setupExpanded) "Hide setup steps" else "Help me set up control") }
                        if (setupExpanded) {
                            Text("1. Open the settings below.\n2. Choose Downloaded apps or Installed services.\n3. Choose KinPilot and turn on Use KinPilot.\n4. Come back here. We’ll check it automatically.")
                            Text("If Android blocks this: open KinPilot’s App info → ⋮ → Allow restricted settings. Only do this for the KinPilot APK you trust.")
                            Text("Enabling this service lets your approved helper read screen content and perform taps, swipes, navigation, and typing during a support session. Nothing is recorded. You can stop sharing at any time.")
                            FilledTonalButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("I understand — open settings") }
                        }
                    }
                }
            }
            if (!sharing) TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }) { Text("Allow session notifications") }
            if (code != null && !sharing) OutlinedButton(onClick = {
                stopService(Intent(this@MainActivity, ScreenShareService::class.java))
                client.close(); code = null; request = null; connecting = false; accepted = false; sharing = false; approved = false; status = "Request cancelled. Create a new code whenever you need help."
            }, modifier = Modifier.fillMaxWidth()) { Text("Cancel session") }
        }
    }

    @Composable
    private fun HelpSomeoneScreen(onBack: () -> Unit) {
        BackHandler(onBack = onBack)
        val preferences = remember { getSharedPreferences("kinpilot", MODE_PRIVATE) }
        var name by rememberSaveable { mutableStateOf(preferences.getString("helperName", "").orEmpty()) }
        var code by rememberSaveable { mutableStateOf("") }
        var status by remember { mutableStateOf("Enter the code shown on the other person's phone.") }
        var connecting by remember { mutableStateOf(false) }
        var acceptedAt by remember { mutableStateOf<Long?>(null) }
        val client = remember {
            RendezvousClient(object : RendezvousClient.Listener {
                override fun onOpen() { runOnUiThread { SupportCode.parse(code)?.let { helperClient?.join(it, name.ifBlank { "Trusted helper" }) } } }
                override fun onWaiting() { runOnUiThread { connecting = true; status = "Waiting for them to approve…" } }
                override fun onAccepted(expiresAt: Long) { runOnUiThread { connecting = false; acceptedAt = expiresAt } }
                override fun onDeclined() { runOnUiThread { connecting = false; status = "They declined this request." } }
                override fun onEnded(reason: String) { runOnUiThread { connecting = false; acceptedAt = null; status = "Session ended." } }
                override fun onError(value: String) { runOnUiThread {
                    connecting = false
                    status = if (value == "room_unavailable") "That code is invalid, used, or expired." else "Could not connect: $value"
                } }
            }).also { helperClient = it }
        }
        DisposableEffect(client) { onDispose { client.close(); if (helperClient === client) helperClient = null } }
        val clipboard = LocalClipboardManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val requestAccess: () -> Unit = {
            if (!connecting && SupportCode.parse(code) != null) {
                keyboard?.hide()
                preferences.edit().putString("helperName", name.trim()).apply()
                client.close()
                connecting = true; status = "Connecting securely…"; client.connect()
            }
        }
        val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
            result.contents?.let { contents ->
                val parsed = SupportCode.parse(contents)
                if (parsed == null) status = "This is not a KinPilot support QR. Ask them to open Get support."
                else { code = parsed; status = "QR scanned. Tap Request access to connect." }
            }
        }

        acceptedAt?.let { expiry ->
            RemoteSupportScreen(client, expiry) { client.close(); acceptedAt = null; connecting = false; status = "Session ended." }
            return
        }

        Column(Modifier.fillMaxSize().imePadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenHeader("Help someone", "They stay in control and must approve before you can see anything.", onBack)
            FilledTonalButton(enabled = !connecting, onClick = {
                scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan the QR on their KinPilot screen").setBeepEnabled(false).setOrientationLocked(false))
            }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Scan QR code", style = MaterialTheme.typography.titleMedium) }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(60) },
                enabled = !connecting,
                label = { Text("Your name") },
                placeholder = { Text("Trusted helper") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.take(128) },
                enabled = !connecting,
                label = { Text("12-character support code") },
                placeholder = { Text("ABCD-EFGH-JKLM") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                supportingText = { Text("Paste a code or shared link • ${SupportCode.parse(code)?.length ?: code.filter(Char::isLetterOrDigit).length.coerceAtMost(12)}/12") },
                trailingIcon = { TextButton(enabled = !connecting, onClick = {
                    val parsed = SupportCode.parse(clipboard.getText()?.text.orEmpty())
                    if (parsed != null) code = parsed else status = "Copy a full KinPilot code or link first."
                }) { Text("Paste") } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrect = false, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { requestAccess() })
            )
            Button(
                enabled = SupportCode.parse(code) != null && !connecting,
                onClick = requestAccess,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (connecting) "Waiting for approval…" else "Request access") }
            if (connecting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = { client.close(); connecting = false; status = "Request cancelled. You can try another code." }) { Text("Cancel request") }
            }
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(status, modifier = Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "The session ends automatically. KinPilot does not store the screen or support code.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    @Composable
    private fun PhoneTools() {
        var expanded by rememberSaveable { mutableStateOf(false) }
        OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (expanded) "Hide phone tools" else "Phone tools", style = MaterialTheme.typography.titleMedium)
                }
                if (expanded) {
                    Text("Quick access to common fixes. Use Back to return to KinPilot.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PhoneTool("Internet connection", "Check Wi-Fi or mobile data if you cannot connect.", Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                    PhoneTool("Sound and volume", "Adjust the volume if you cannot hear your helper.", Settings.ACTION_SOUND_SETTINGS)
                    PhoneTool("Text and screen size", "Make text easier to read across your whole phone.", Settings.ACTION_DISPLAY_SETTINGS)
                    PhoneTool("Battery", "Check remaining charge and battery-saving settings.", Intent.ACTION_POWER_USAGE_SUMMARY)
                }
            }
        }
    }

    @Composable
    private fun PhoneTool(title: String, description: String, action: String) {
        OutlinedButton(onClick = {
            try { startActivity(Intent(action)) }
            catch (_: android.content.ActivityNotFoundException) {
                Toast.makeText(this, "This phone does not provide that shortcut. Open your phone’s Settings app.", Toast.LENGTH_LONG).show()
            }
        }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    private fun pinHelpShortcut() {
        val manager = getSystemService(ShortcutManager::class.java)
        if (manager?.isRequestPinShortcutSupported != true) {
            Toast.makeText(this, "This launcher cannot add shortcuts. Keep the KinPilot app on your home screen instead.", Toast.LENGTH_LONG).show()
            return
        }
        val shortcut = ShortcutInfo.Builder(this, "get-help")
            .setShortLabel("Get help")
            .setLongLabel("Get help with my phone")
            .setIcon(Icon.createWithResource(this, applicationInfo.icon))
            .setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .putExtra("getHelp", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            .build()
        manager.requestPinShortcut(shortcut, null)
    }

    @Composable
    private fun ScreenHeader(title: String, subtitle: String, onBack: () -> Unit) {
        Row(verticalAlignment = Alignment.Top) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp)) { Text("Back") }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    companion object { @Volatile private var helperClient: RendezvousClient? = null }

}

@Composable
private fun BrandMark() {
    Box(
        Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text("K", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Box(Modifier.size(12.dp).align(Alignment.TopEnd).offset((-8).dp, 8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
    }
}

@Composable
private fun KinPilotTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(
        primary = Color(0xFF5954D6),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFE5E1FF),
        onPrimaryContainer = Color(0xFF201A57),
        secondary = Color(0xFF087F8C),
        secondaryContainer = Color(0xFFD9E9F7),
        tertiary = Color(0xFFF5B429),
        tertiaryContainer = Color(0xFFFFE9B0),
        background = Color(0xFFF7F6FC),
        surface = Color.White,
        surfaceVariant = Color(0xFFEAE8F3)
    )
    val dark = darkColorScheme(primary = Color(0xFFC6BFFF), secondary = Color(0xFF7BD8DE),
        background = Color(0xFF11121D), surface = Color(0xFF1B1C2C), surfaceVariant = Color(0xFF292B40))
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else scheme, content = content)
}

private fun qr(text: String): Bitmap {
    val size = 512
    val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    return Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).also { bitmap ->
        for (y in 0 until size) for (x in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
}
