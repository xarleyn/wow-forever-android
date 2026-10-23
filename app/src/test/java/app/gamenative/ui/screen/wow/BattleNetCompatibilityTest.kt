package app.gamenative.ui.screen.wow

import android.app.Application
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BattleNetCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun changesOnlyBrowserSettingAndPreservesRememberedLoginAcrossRepeatedStarts() {
        val upper = temporary.newFolder("upper")
        val config = File(upper, BattleNetCompatibility.configPath).apply {
            parentFile!!.mkdirs()
            writeText("""{"Client":{"AutoLogin":"true","SavedAccountNames":"fixture-secret","HardwareAcceleration":"true"},"Other":{"keep":42}}""")
        }
        val credentials = File(config.parentFile, "CachedData.db").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val expected = JSONObject(config.readText()).apply { getJSONObject("Client").put("HardwareAcceleration", "false") }
        val result = BattleNetCompatibility.prepare(upper, null)
        assertEquals(expected.toString(), JSONObject(config.readText()).toString())
        assertTrue(result.contains("AutoLogin=true"))
        assertFalse(result.contains("fixture-secret"))
        val modified = config.lastModified()
        BattleNetCompatibility.prepare(upper, null)
        assertEquals(modified, config.lastModified())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), credentials.readBytes())
        assertFalse(BattleNetCompatibility.diagnostics(upper, null).contains("fixture-secret"))
    }

    @Test fun invalidSettingsAreNeverResetAndLowerConfigurationIsNotEdited() {
        val upper = temporary.newFolder("upper"); val lower = temporary.newFolder("lower")
        val source = File(lower, BattleNetCompatibility.configPath).apply {
            parentFile!!.mkdirs(); writeText("""{"Client":{"AutoLogin":"false"},"Other":"keep"}""")
        }
        val original = source.readText()
        BattleNetCompatibility.prepare(upper, lower)
        assertEquals(original, source.readText())
        val target = File(upper, BattleNetCompatibility.configPath)
        assertEquals("false", JSONObject(target.readText()).getJSONObject("Client").getString("AutoLogin"))
        target.writeText("broken settings")
        assertThrows(Exception::class.java) { BattleNetCompatibility.prepare(upper, lower) }
        assertEquals("broken settings", target.readText())
    }

    @Test fun browserDiagnosticsExportOnlyCategoriesAndCodesWithoutPayloads() {
        val text = """
            [123:ERROR:gpu_init.cc(42)] secret-token user@example.org https://account.example/?code=secret
            [123:ERROR:ssl_client_socket_impl.cc(42)] handshake failed; returned -1, SSL error code 1, net_error -100
            [123:WARNING:os_crypt_win.cc(42)] password=secret-token
            [123:INFO:cef_logging.h(42)] authorization=secret-token
        """.trimIndent()
        val report = BattleNetCompatibility.summarizeCef(text)
        assertTrue(report.contains("GPU initialization"))
        assertTrue(report.contains("net_error -100"))
        assertTrue(report.contains("Windows credential encryption"))
        assertFalse(report.contains("secret"))
        assertFalse(report.contains("example"))
    }
}
