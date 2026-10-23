package app.gamenative.ui.screen.wow

import android.content.Context
import app.gamenative.utils.Net
import com.winlator.container.Container
import com.winlator.container.ContainerFiles
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import com.winlator.core.TarCompressorUtils
import com.winlator.core.FexRuntimeFiles
import okhttp3.Request

enum class WoWLaunchMode { DIRECT, BATTLE_NET }

internal data class BattleNetLaunchTarget(val executable: String, val installer: Boolean)

/** Official desktop authentication; never reads saved passwords or constructs a launch token. */
internal object BattleNetLauncher {
    const val CONTAINER_ID = "wow_battlenet"
    const val INSTALLER_URL = "https://downloader.battle.net/download/getInstaller?os=win&installer=Battle.net-Setup.exe"
    const val INSTALLER_NAME = "Battle.net-Setup.exe"
    private const val MAX_INSTALLER_BYTES = 64L * 1024 * 1024
    const val FEX_VERSION = "2609"
    private const val FEX_SHA256 = "64f5538b841f533a7c9f07446c9db0650a754059966080c91340e3fd281a29d7"
    private val launcherPaths = listOf(
        "Program Files (x86)/Battle.net/Battle.net Launcher.exe",
        "Program Files/Battle.net/Battle.net Launcher.exe",
        "Program Files (x86)/Battle.net/Battle.net.exe",
        "Program Files/Battle.net/Battle.net.exe",
    )

    fun loadMode(context: Context): WoWLaunchMode = runCatching {
        WoWLaunchMode.valueOf(context.getSharedPreferences("wow_launcher", Context.MODE_PRIVATE)
            .getString("launch_mode", WoWLaunchMode.DIRECT.name)!!)
    }.getOrDefault(WoWLaunchMode.DIRECT)

    fun saveMode(context: Context, mode: WoWLaunchMode) {
        check(context.getSharedPreferences("wow_launcher", Context.MODE_PRIVATE).edit()
            .putString("launch_mode", mode.name).commit()) { "Couldn't save launch mode" }
    }

    fun toolsDirectory(context: Context) = File(context.filesDir, "battlenet")

    // ProcessHelper splits Unix argv itself and preserves quote characters. Escape spaces using
    // its supported syntax, leaving Wine to construct the Windows command line with real quotes.
    fun commandArgument(path: String): String = path.replace(" ", "\\ ")

    internal fun emulatorFilesPresent(directory: File): Boolean =
        FexRuntimeFiles.isPackageValid(directory)

    fun ensureEmulator(context: Context, onStatus: (String) -> Unit) {
        val destination = File(ContentsManager.getContentTypeDir(context, ContentProfile.ContentType.CONTENT_TYPE_FEXCORE), FEX_VERSION)
        if (emulatorFilesPresent(destination)) return
        onStatus("Installing FEX 2609 for the official x86 launcher...")
        val directory = toolsDirectory(context).apply { mkdirs() }
        val archive = File(directory, "FEXCore-$FEX_VERSION.wcp")
        if (!validFexArchive(archive)) {
            val temporary = File(directory, archive.name + ".part")
            try {
                Net.http.newCall(Request.Builder().url("https://downloads.gamenative.app/FEXCore-$FEX_VERSION.wcp").build()).execute().use { response ->
                    check(response.isSuccessful) { "FEX download failed: HTTP ${response.code}" }
                    val body = response.body ?: error("Empty FEX download")
                    body.byteStream().use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                check(total <= 16 * 1024 * 1024) { "FEX archive exceeds size limit" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                check(validFexArchive(temporary)) { "FEX archive checksum mismatch" }
                temporary.copyTo(archive, overwrite = true)
            } finally { temporary.delete() }
        }
        check(destination.isDirectory || destination.mkdirs()) { "Couldn't create FEX directory" }
        // This checksum-pinned upstream WCP is an XZ-compressed tar (FD 37 7A 58 5A 00).
        check(TarCompressorUtils.extract(TarCompressorUtils.Type.XZ, archive, destination)) { "Couldn't extract FEX" }
        check(emulatorFilesPresent(destination)) { "FEX installation is incomplete" }
    }

    private fun validFexArchive(file: File): Boolean {
        if (!file.isFile) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == FEX_SHA256
    }

    fun useGameFiles(context: Context): Boolean =
        context.getSharedPreferences("wow_launcher", Context.MODE_PRIVATE).getBoolean("battlenet_game_files", false)

    fun useGameFiles(context: Context, enabled: Boolean) {
        check(context.getSharedPreferences("wow_launcher", Context.MODE_PRIVATE).edit()
            .putBoolean("battlenet_game_files", enabled).commit()) { "Couldn't save game access preference" }
    }

    fun installedTarget(container: Container?): BattleNetLaunchTarget? {
        if (container == null) return null
        return installedTarget { relative -> ContainerFiles.resolve(container, "drive_c/$relative") }
    }

    internal fun installedTarget(resolve: (String) -> File?): BattleNetLaunchTarget? =
        launcherPaths.firstOrNull { resolve(it)?.isFile == true }?.let {
            BattleNetLaunchTarget("C:\\${it.replace('/', '\\')}", false)
        }

    fun prepare(context: Context, container: Container?, onStatus: (String) -> Unit): BattleNetLaunchTarget {
        // A small Win32 probe exercises the same activation contexts inside Wine before launch.
        val probe = File(toolsDirectory(context).apply { mkdirs() }, "BattleNetProbe.exe")
        val temporaryProbe = File(probe.parentFile, "BattleNetProbe.exe.part")
        try {
            context.assets.open("battlenet/BattleNetProbe.exe").use { input -> temporaryProbe.outputStream().use { input.copyTo(it) } }
            check(com.winlator.core.PeDependencies.read(temporaryProbe).machine == 0x14c) { "Invalid loader probe" }
            java.nio.file.Files.move(temporaryProbe.toPath(), probe.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            // Diagnostic failure must not prevent opening an already installed official client.
            probe.delete()
            android.util.Log.w("BattleNetProbe", "Couldn't stage optional loader probe", e)
        } finally { temporaryProbe.delete() }
        installedTarget(container)?.let { return it }
        onStatus("Downloading official Battle.net installer...")
        val directory = toolsDirectory(context)
        check(directory.isDirectory || directory.mkdirs()) { "Couldn't create Battle.net tools directory" }
        val installer = File(directory, INSTALLER_NAME)
        if (!isPortableExecutable(installer)) {
            val temporary = File(directory, "$INSTALLER_NAME.part")
            try {
                val client = Net.http.newBuilder().connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(45, TimeUnit.SECONDS).followSslRedirects(false).build()
                client.newCall(Request.Builder().url(INSTALLER_URL).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Battle.net download failed: HTTP ${response.code}")
                    check(response.request.url.isHttps) { "Battle.net download requires HTTPS" }
                    val host = response.request.url.host
                    check(host == "battle.net" || host.endsWith(".battle.net")) { "Unexpected installer download host" }
                    val body = response.body ?: throw IOException("Empty Battle.net download")
                    body.byteStream().use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var count = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                count += read
                                check(count <= MAX_INSTALLER_BYTES) { "Battle.net installer exceeds size limit" }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
                check(isPortableExecutable(temporary)) { "The download is not a Windows installer" }
                check(temporary.renameTo(installer)) { "Couldn't save Battle.net installer" }
            } finally {
                temporary.delete()
            }
        }
        return BattleNetLaunchTarget("B:\\$INSTALLER_NAME", true)
    }

    private fun isPortableExecutable(file: File): Boolean = file.isFile && file.length() > 64 * 1024 &&
        file.inputStream().use { it.read() == 'M'.code && it.read() == 'Z'.code }
}
