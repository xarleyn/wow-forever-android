package app.gamenative.ui.screen.wow

import java.io.File
import java.io.RandomAccessFile

internal object WoWConnectionDiagnostics {
    private val safeFields = setOf(
        "launcherPortal", "loginPortal", "host", "port", "usedToken", "result", "code", "gotCredentials",
        "platform", "locale", "position", "numGameAccounts", "subRegionCount", "loginType",
        "authenticatorRequired", "legalRequired", "hasCaptcha", "hasPayload", "usedSSO",
    )
    private val quotedField = Regex("(\\b[\\w]+)=(\"[^\"]*\")")
    private val sensitiveField = Regex(
        "(?i)(\\b[\\w]*(?:password|passwd|token|credential|authorization|email|account)[\\w]*\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;]+)",
    )
    private val email = Regex("[A-Za-z0-9.!#%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*")

    fun sanitize(text: String): String {
        val redacted = sensitiveField.replace(bearer.replace(text, "Bearer [redacted]")) { match ->
            val key = match.groupValues[1].substringBefore('=').substringBefore(':').trim()
            if (key in safeFields) match.value else "${match.groupValues[1]}[redacted]"
        }
        return email.replace(redacted, "[email redacted]")
    }

    fun connectionText(text: String): String = sanitize(quotedField.replace(text) { match ->
        if (match.groupValues[1] in safeFields) match.value else "${match.groupValues[1]}=\"[redacted]\""
    })

    fun read(root: File, profile: WoWProductProfile): String {
        val relativePath = "${profile.flavorDir}/Logs/Connection.log"
        val file = File(root, relativePath)
        val heading = "=== $relativePath (last 128 KiB / 400 lines, redacted) ==="
        if (!file.isFile) return "$heading\nNo Connection.log yet. Launch the game, then return here and refresh."
        return try {
            val tail = RandomAccessFile(file, "r").use { input ->
                val offset = (input.length() - 128 * 1024).coerceAtLeast(0)
                input.seek(offset)
                val bytes = ByteArray((input.length() - offset).toInt())
                input.readFully(bytes)
                val text = bytes.toString(Charsets.UTF_8)
                if (offset > 0) text.substringAfter('\n', "") else text
            }
            val lines = tail.lines().takeLast(400).joinToString("\n")
            "$heading\n${connectionText(lines)}\n\n" +
                "Check launcherPortal/loginPortal against the selected region. usedToken=false means password login. " +
                "14400 alone does not identify the cause; a correct portal does not guarantee server acceptance. " +
                "Logs may include previous launches; compare the latest timestamps."
        } catch (_: Exception) {
            "$heading\nCannot read Connection.log. Check game folder and storage permission."
        }
    }
}
