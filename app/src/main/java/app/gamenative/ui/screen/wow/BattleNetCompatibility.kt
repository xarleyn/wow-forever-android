package app.gamenative.ui.screen.wow

import com.winlator.container.Container
import com.winlator.container.ContainerFiles
import com.winlator.xenvironment.ImageFs
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** Only browser rendering is managed here; saved accounts and authentication stay client-owned. */
object BattleNetCompatibility {
    private val user = "drive_c/users/${ImageFs.USER}/AppData"
    internal val configPath = "$user/Roaming/Battle.net/Battle.net.config"

    @JvmStatic fun prepare(container: Container): String = prepare(ContainerFiles.upperDir(container), ContainerFiles.lowerDir(container))

    internal fun prepare(upper: File, lower: File?): String {
        val existing = ContainerFiles.resolve(upper, lower, configPath)
        // Never replace an unreadable, oversized or invalid configuration with defaults.
        val config = if (existing == null) JSONObject() else {
            check(existing.isFile && existing.length() <= 1024 * 1024) { "Battle.net config is unreadable or oversized" }
            JSONObject(existing.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        }
        val client = if (!config.has("Client")) JSONObject().also { config.put("Client", it) }
            else requireNotNull(config.optJSONObject("Client")) { "Unexpected Battle.net Client settings" }
        if (client.opt("HardwareAcceleration") != "false") {
            client.put("HardwareAcceleration", "false")
            val target = File(upper, configPath)
            Files.createDirectories(target.parentFile!!.toPath())
            val temporary = File.createTempFile("bnet-config-", ".part", target.parentFile)
            try {
                temporary.writeText(config.toString(4), Charsets.UTF_8)
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { temporary.delete() }
        }
        return "Battle.net browser HardwareAcceleration=false; AutoLogin=" + booleanSetting(client, "AutoLogin")
    }

    private fun booleanSetting(json: JSONObject, key: String): String = when (json.opt(key)?.toString()) {
        "true" -> "true"
        "false" -> "false"
        else -> "unset"
    }

    internal fun diagnostics(container: Container): String = diagnostics(ContainerFiles.upperDir(container), ContainerFiles.lowerDir(container))

    internal fun diagnostics(upper: File, lower: File?): String = buildString {
        appendLine("=== Battle.net browser and session diagnostics ===")
        appendLine("Session metadata only; account/cookie/token contents are never exported. Log timestamps may precede this launch.")
        val config = ContainerFiles.resolve(upper, lower, configPath)
        if (config != null && config.isFile && config.length() <= 1024 * 1024) {
            val client = runCatching { JSONObject(config.readText().removePrefix("\uFEFF")).optJSONObject("Client") }.getOrNull()
            appendLine("Config bytes=${config.length()} modified=${config.lastModified()} HardwareAcceleration=${client?.let { booleanSetting(it, "HardwareAcceleration") } ?: "unreadable"} AutoLogin=${client?.let { booleanSetting(it, "AutoLogin") } ?: "unreadable"}")
        } else appendLine("Config absent or unreadable")
        for (relative in listOf("$user/Roaming/Battle.net", "$user/Local/Battle.net/Account", "$user/Local/Battle.net/BrowserCaches")) {
            val dir = ContainerFiles.resolve(upper, lower, relative)
            appendLine("$relative present=${dir?.isDirectory == true} writable=${dir?.canWrite() == true}")
        }
        // Only Chromium native error categories and numeric result codes, never arbitrary
        // console messages, URLs, login payloads or a full client configuration.
        val logs = ContainerFiles.resolve(upper, lower, "$user/Local/Battle.net/Logs")
        val latest = logs?.listFiles { f -> f.isFile && !Files.isSymbolicLink(f.toPath()) && f.name.matches(Regex("libcef-[0-9T.]+\\.log")) }
            ?.sortedByDescending { it.lastModified() }?.take(3).orEmpty()
        if (latest.isEmpty()) appendLine("No Chromium log files yet")
        for (file in latest) {
            appendLine("${file.name} bytes=${file.length()} modified=${file.lastModified()}")
            appendLine(summarizeCef(tail(file, 64 * 1024)))
        }
    }

    internal fun summarizeCef(text: String): String {
        val known = mapOf("gpu_init.cc" to "GPU initialization", "gpu_process_host.cc" to "GPU process",
            "gpu_channel_manager.cc" to "GPU channel", "gl_surface_egl.cc" to "EGL surface",
            "ssl_client_socket_impl.cc" to "TLS handshake", "cert_verify_proc.cc" to "Certificate verification",
            "os_crypt_win.cc" to "Windows credential encryption", "webauthn" to "WebAuthn",
            "backend_impl.cc" to "Browser cache", "entry_impl.cc" to "Browser cache entry",
            "render_process_host_impl.cc" to "Renderer process")
        val events = text.lineSequence().mapNotNull { line ->
            if (!line.contains(":ERROR:") && !line.contains(":WARNING:")) return@mapNotNull null
            val category = known.entries.firstOrNull { line.contains(it.key, ignoreCase = true) }?.value ?: "Other Chromium error"
            val codes = Regex("(?i)(?:net_error|error code|exit_code|error_code|HRESULT|returned)\\s*[:=]?\\s*(-?[0-9]+|0x[0-9a-f]+)\\b")
                .findAll(line).map { it.value }.take(4).joinToString(", ")
            category + if (codes.isEmpty()) "" else " ($codes)"
        }.groupingBy { it }.eachCount()
        return events.entries.joinToString("\n") { "${it.key}: ${it.value} occurrence(s)" }.ifEmpty { "No error/warning entries in last 64 KiB" }
    }

    private fun tail(file: File, limit: Int): String = RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        val offset = (length - limit).coerceAtLeast(0)
        input.seek(offset)
        val bytes = ByteArray((length - offset).toInt()).also { input.readFully(it) }
        val text = bytes.toString(Charsets.UTF_8)
        if (offset > 0) text.substringAfter('\n', "") else text
    }
}
