package family.remote.parent

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Release metadata is public; no device IDs, screen contents or accounts are sent. */
internal object AppUpdates {
    data class Release(val version: String, val download: String)
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    fun latest(): Release? {
        fun get(url: String) = http.newCall(Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json").build()).execute().use {
            check(it.isSuccessful) { "Update service unavailable" }
            it.body?.string() ?: error("Empty response")
        }
        val releases = JSONArray(get("https://api.github.com/repos/pritom007/KinPilot/releases?per_page=10"))
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft")) continue
            val assets = release.getJSONArray("assets")
            var manifest: String? = null
            var apk: String? = null
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val url = asset.optString("browser_download_url")
                if (!url.startsWith("https://github.com/pritom007/KinPilot/releases/download/")) continue
                when (asset.optString("name")) {
                    "update.json" -> manifest = url
                    "KinPilot.apk" -> apk = url
                }
            }
            if (manifest != null && apk != null) {
                val metadata = JSONObject(get(manifest))
                if (metadata.getLong("versionCode") > BuildConfig.VERSION_CODE) {
                    return Release(metadata.getString("versionName"), apk)
                }
            }
        }
        return null
    }
}

@Composable
internal fun UpdateCard() {
    val context = LocalContext.current
    var release by remember { mutableStateOf<AppUpdates.Release?>(null) }
    var status by remember { mutableStateOf("Checking for updates…") }
    var check by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(check) {
        while (true) {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching { AppUpdates.latest() } }
            result.onSuccess {
                release = it
                status = if (it == null) "You're up to date" else "Version ${it.version} is ready"
            }.onFailure { status = "Couldn't check. Tap to retry." }
            busy = false
            delay(15 * 60 * 1000L)
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("KinPilot ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleSmall)
            Text(status, style = MaterialTheme.typography.bodyMedium)
            val available = release
            if (available != null) {
                Text("Download the update, then open it and approve installation. If Android says App not installed, remove a pre-v0.0.7 debug build once and reinstall the latest release.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(available.download))) }) {
                    Text("Download update")
                }
            } else TextButton(enabled = !busy, onClick = { check++ }) { Text(if (busy) "Checking…" else "Check for updates") }
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text("Update notifications") }
        }
    }
}
