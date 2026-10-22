package app.gamenative.ui.screen.wow

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WoWConnectionDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun appLogRedactsBearerHeadersAndUnquotedTokens() {
        val text = WoWConnectionDiagnostics.sanitize("Authorization: Bearer abc.private-token/123== access_token=another-secret me@example.invalid")
        listOf("abc.private-token", "another-secret", "me@example.invalid").forEach { assertFalse(text.contains(it)) }
    }

    @Test fun preservesRoutingAndResultWhileRemovingAuthenticationData() {
        val text = WoWConnectionDiagnostics.connectionText(
            "launcherPortal=\"eu.actual.battle.net\" loginPortal=\"eu.actual.battle.net:1119\" usedToken=\"false\" " +
                "result=\"Status has no name. (14400)\" gotCredentials=\"true\" account=\"1234\" " +
                "token=\"private-token\" email=\"me@example.invalid\" password=secret"
        )
        assertTrue(text.contains("eu.actual.battle.net:1119"))
        assertTrue(text.contains("usedToken=\"false\""))
        assertTrue(text.contains("gotCredentials=\"true\""))
        assertTrue(text.contains("(14400)"))
        listOf("private-token", "me@example.invalid", "1234", "secret").forEach { assertFalse(text.contains(it)) }
    }

    @Test fun missingAndLargeLogsAreHandledAndOnlySelectedProductIsRead() {
        val root = temporary.newFolder()
        assertTrue(WoWConnectionDiagnostics.read(root, WoWProducts.STRESS).contains("No Connection.log"))
        File(root, "_forever_/Logs/Connection.log").apply {
            parentFile!!.mkdirs()
            writeText("old-marker\n" + "x".repeat(200_000) + "\nhost=\"us.actual.battle.net\" usedToken=\"true\"\n")
        }
        val text = WoWConnectionDiagnostics.read(root, WoWProducts.STRESS)
        assertTrue(text.contains("us.actual.battle.net"))
        assertFalse(text.contains("old-marker"))
        assertTrue(text.length < 140_000)
        assertTrue(WoWConnectionDiagnostics.read(root, WoWProducts.BETA).contains("No Connection.log"))
    }
}
