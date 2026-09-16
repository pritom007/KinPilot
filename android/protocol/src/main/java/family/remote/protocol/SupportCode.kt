package family.remote.protocol

import java.net.URI
import java.util.Locale

/** Only accept our support links or a complete code, never an arbitrary scanned URL. */
object SupportCode {
    fun parse(input: String): String? {
        val value = input.trim()
        val raw = if (value.startsWith("https://")) {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (uri.host != "kinpilot.netlify.app" || uri.path != "/join" || uri.userInfo != null) return null
            uri.fragment ?: return null
        } else value
        val compact = raw.replace("-", "").replace(" ", "").uppercase(Locale.ROOT)
        return compact.takeIf { it.matches(Regex("[A-Z0-9]{12}")) }
    }
}
