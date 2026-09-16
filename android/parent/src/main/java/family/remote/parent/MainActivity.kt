package family.remote.parent

import android.Manifest
import android.content.Intent
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
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import family.remote.parent.capture.ScreenShareService
import family.remote.parent.control.RemoteControlService
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
        var page by rememberSaveable { mutableStateOf(AppPage.HOME) }
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
                "A little closer. A lot more helpful.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
            )
            RoleCard(
                title = "Get support",
                description = "Let someone you trust lend a hand. You stay in control.",
                action = "Create support code",
                accent = MaterialTheme.colorScheme.primary,
                onClick = onGetSupport
            )
            Spacer(Modifier.height(16.dp))
            RoleCard(
                title = "Help someone",
                description = "Scan their QR or paste a code. Connect in moments.",
                action = "Enter a code",
                accent = MaterialTheme.colorScheme.secondary,
                onClick = onHelp
            )
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
                Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(action) }
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
        val leaveScreen: () -> Unit = {
            if (sharing) {
                status = "Screen sharing is still active. Use the notification Stop button to end it."
                moveTaskToBack(true)
            } else onBack()
        }
        BackHandler(onBack = leaveScreen)
        val controlAvailabilityListener = remember { { available: Boolean -> runOnUiThread { controlAvailable = available } } }
        val client = remember {
            RendezvousClient(object : RendezvousClient.Listener {
                override fun onOpen() { runOnUiThread { ParentSessionState.client?.createRoom() } }
                override fun onRoomCreated(value: String, expiresAt: Long) { runOnUiThread {
                    code = value; connecting = false; status = "Code ready · expires in 10 minutes"
                } }
                override fun onJoinRequest(id: String, helperName: String) { runOnUiThread {
                    request = id to helperName; status = "$helperName is asking to connect."
                } }
                override fun onAccepted(expiresAt: Long) { runOnUiThread { accepted = true; status = "Approved. Choose what Android should share." } }
                override fun onEnded(reason: String) { runOnUiThread {
                    stopService(Intent(this@MainActivity, ScreenShareService::class.java))
                    code = null; request = null; connecting = false; sharing = false; status = "Session ended."
                } }
                override fun onError(value: String) { runOnUiThread {
                    connecting = false
                    status = if (value == "service_unavailable") "Could not reach the support service. Try again." else "Could not continue: $value"
                } }
            })
        }
        DisposableEffect(client) {
            ParentSessionState.client = client
            family.remote.parent.capture.SessionEvents.listener = { reason -> runOnUiThread {
                client.close(); code = null; request = null; connecting = false; accepted = false; sharing = false
                status = "Session ended: $reason"
            } }
            RemoteControlService.addAvailabilityListener(controlAvailabilityListener)
            onDispose {
                stopService(Intent(this@MainActivity, ScreenShareService::class.java))
                family.remote.parent.capture.SessionEvents.listener = null
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
            } else if (accepted) status = "Screen sharing was not started."
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

        Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ScreenHeader("Get support", "You approve every helper and Android always asks before sharing.", leaveScreen)

            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f)) {
                Text(status, modifier = Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (controlAvailable) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (controlAvailable) "Remote control is ready" else "Enable remote control",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (controlAvailable) "Your helper can use tap, swipe, and navigation after you approve screen sharing."
                        else "Screen sharing works without this, but your helper cannot tap, swipe, or go Back until KinPilot is enabled in Android Accessibility settings."
                    )
                    if (!controlAvailable) {
                        Text("Choose Downloaded apps (or Installed services) → KinPilot → Use KinPilot. If Android blocks this sideloaded app, open App info → ⋮ → Allow restricted settings, then return here. Only do this for the KinPilot APK you trust.")
                        Text("Enabling this service lets your approved helper read screen content and perform taps, swipes, navigation, and typing during a support session. Nothing is recorded. You can stop sharing at any time.")
                        FilledTonalButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                            Text("Open Accessibility settings")
                        }
                    }
                }
            }

            if (code == null) {
                Button(
                    enabled = !connecting,
                    onClick = { connecting = true; status = "Starting the private connection…"; client.connect() },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(if (connecting) "Creating code…" else "Create support code") }
            }

            code?.let { value ->
                val image = remember(value) { qr("https://kinpilot.netlify.app/join#$value") }
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("ONE-TIME SUPPORT CODE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 10.dp))
                        Image(image.asImageBitmap(), "QR support code", Modifier.size(210.dp))
                        Text("Share this QR or code with one trusted helper.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(value)) }) { Text("Copy code") }
                            TextButton(onClick = {
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "Help me on KinPilot: https://kinpilot.netlify.app/join#$value")
                                }, "Share support link"))
                            }) { Text("Share link") }
                    }
                }
            }

            request?.let { (id, name) ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Connection request", style = MaterialTheme.typography.labelLarge)
                        Text("$name wants to help", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("Only accept if you recognize this person.")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { client.respond(id, false); request = null }, modifier = Modifier.weight(1f)) { Text("Decline") }
                            Button(onClick = { client.respond(id, true); request = null }, modifier = Modifier.weight(1f)) { Text("Accept") }
                        }
                    }
                }
            }

            if (sharing) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Text("Screen sharing is active", Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    modifier = Modifier.weight(1f)
                ) { Text(if (controlAvailable) "Control settings" else "Enable control") }
                FilledTonalButton(onClick = { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.weight(1f)) { Text("Notifications") }
            }
            if (code != null) OutlinedButton(onClick = {
                stopService(Intent(this@MainActivity, ScreenShareService::class.java))
                client.close(); code = null; request = null; connecting = false; status = "Session cancelled."
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

    private fun openLatestRelease() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/pritom007/KinPilot/releases")))
    }
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
