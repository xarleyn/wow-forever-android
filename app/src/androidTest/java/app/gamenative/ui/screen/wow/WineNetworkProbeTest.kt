package app.gamenative.ui.screen.wow

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in device diagnostic. Capture the launch environment in files/tmp/network-probe-env.json
 * before running it. Instrumentation restarts the target, so do this only at the login screen.
 * Running Wine through adb run-as against an app-owned wineserver is not equivalent: SELinux
 * can reject the transferred TCP descriptors, producing a misleading Winsock error 10022.
 */
@RunWith(AndroidJUnit4::class)
class WineNetworkProbeTest {
    @Test
    fun publicHttpAndHttpsThroughWineWinHttp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val temp = File(context.filesDir, "tmp")
        val environmentFile = File(temp, "network-probe-env.json")
        assertTrue("Capture the active Wine environment first", environmentFile.isFile)
        val json = JSONObject(environmentFile.readText())
        val environment = json.keys().asSequence().associateWith { json.getString(it) }.toMutableMap()
        assertTrue(environment.getValue("WINEPREFIX").startsWith(context.filesDir.path + "/"))
        environment["WINEDEBUG"] = "-all,+winhttp,+winsock,+mshtml"
        val wineBin = environment.getValue("PATH").substringBefore(':')
        val wine = "$wineBin/wine"
        val script = File(temp, "wine-network-probe.js").apply {
            writeText(
                """
                var urls = ["http://example.com/", "https://example.com/", "https://account.battle.net/"];
                var failures = 0;
                for (var i = 0; i < urls.length; i++) {
                    try {
                        var req = new ActiveXObject("WinHttp.WinHttpRequest.5.1");
                        req.SetTimeouts(4000, 4000, 6000, 6000);
                        req.Open("HEAD", urls[i], false);
                        req.Send();
                        WScript.Echo("PROBE WINHTTP " + urls[i] + " STATUS=" + req.Status);
                    } catch (e) {
                        failures++;
                        WScript.Echo("PROBE WINHTTP " + urls[i] + " ERROR=" + e.number + " " + e.description);
                    }
                }
                WScript.Quit(failures ? 1 : 0);
                """.trimIndent(),
            )
        }
        val output = File(temp, "network-probe-app-context.log")
        val diagnostics = StringBuilder()

        fun execute(executable: String, arguments: List<String>): Pair<Int, String> {
            val process = ProcessBuilder(listOf("/system/bin/linker64", executable) + arguments).apply {
                environment().clear()
                environment().putAll(environment)
                directory(File(environment.getValue("WINEPREFIX")))
                redirectErrorStream(true)
            }.start()
            val text = StringBuilder()
            val reader = thread(name = "network-probe-output") {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { text.append(it).append('\n') }
                }
            }
            val finished = process.waitFor(45, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(5_000)
            check(finished) { "Wine network probe timed out" }
            return process.exitValue() to text.toString()
        }

        try {
            diagnostics.append("APP_SECURITY_CONTEXT=")
                .append(File("/proc/self/attr/current").readText().trim()).append('\n')
            val (status, text) = execute(wine, listOf("cscript.exe", "//nologo", "Z:" + script.path.replace('/', '\\')))
            diagnostics.append(text)
            output.writeText(diagnostics.toString())
            assertEquals("See ${output.name} for the Windows API result", 0, status)
            assertEquals("All public endpoints must return an HTTP response", 3, Regex("PROBE WINHTTP .* STATUS=[1-5][0-9]{2}").findAll(text).count())

        } finally {
            output.writeText(diagnostics.toString())
            // Instrumentation already stopped the prior game; clean up only this diagnostic session.
            execute("$wineBin/wineserver", listOf("-k"))
        }
    }
}
