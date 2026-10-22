package app.gamenative.ui.screen.wow

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WoWGameConfigTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun stressUsesEachSelectedRegionAndCorrectProduct() {
        BattleNetRegion.entries.forEach { region ->
            val root = temporary.newFolder(region.name)
            ensureGameConfig(root, "1280x720", WoWProducts.STRESS, region)
            val text = File(root, "_forever_/WTF/Config.wtf").readText()
            assertTrue(text.contains("SET portal \"${region.name}\""))
            assertTrue(text.contains("SET agentUID \"wowf\""))
            assertFalse(File(root, "_classic_beta_").exists())
        }
    }

    @Test fun existingConfigSwitchesRegionPreservingSettingsAndAccountFiles() {
        val root = temporary.newFolder()
        val config = File(root, "_forever_/WTF/Config.wtf").apply {
            parentFile!!.mkdirs()
            writeText("SET portal \"test\"\r\nset PORTAL \"CN\"\r\nSET agentUID \"wrong\"\r\nSET gxApi \"Metal\"\r\nSET graphicsQuality \"7\"\r\nSET textLocale \"deDE\"\r\nSET gxFullscreenResolution \"960x540\"\r\n# user comment\r\n")
        }
        val account = File(root, "_forever_/WTF/Account/123#1/settings.wtf").apply {
            parentFile!!.mkdirs(); writeText("keep me")
        }
        ensureGameConfig(root, "1280x720", WoWProducts.STRESS, BattleNetRegion.EU)
        val eu = config.readText()
        assertEquals(1, Regex("(?im)^SET portal ").findAll(eu).count())
        assertTrue(eu.contains("SET portal \"EU\""))
        assertTrue(eu.contains("SET gxApi \"D3D11\""))
        assertTrue(eu.contains("SET graphicsQuality \"7\"\r\n"))
        assertTrue(eu.contains("SET textLocale \"deDE\"\r\n"))
        assertTrue(eu.contains("SET gxFullscreenResolution \"960x540\"\r\n"))
        assertTrue(eu.contains("# user comment\r\n"))
        applyAccountPortal(root, WoWProducts.STRESS, BattleNetRegion.US)
        assertEquals(eu.replace("SET portal \"EU\"", "SET portal \"US\""), config.readText())
        assertEquals("keep me", account.readText())
        val stamp = config.lastModified()
        applyAccountPortal(root, WoWProducts.STRESS, BattleNetRegion.US)
        assertEquals(stamp, config.lastModified())
    }

    @Test fun betaRetainsTestPortalRegardlessOfAccountRegion() {
        val root = temporary.newFolder()
        ensureGameConfig(root, "1280x720", WoWProducts.BETA, BattleNetRegion.EU)
        applyAccountPortal(root, WoWProducts.BETA, BattleNetRegion.US)
        val config = File(root, "_classic_beta_/WTF/Config.wtf").readText()
        assertTrue(config.contains("SET portal \"test\""))
        assertTrue(config.contains("SET agentUID \"wow_classic_beta\""))
    }

    @Test fun stressDoesNotGuessRegionOrModifyFilesWhenUnconfirmed() {
        val root = temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            ensureGameConfig(root, "1280x720", WoWProducts.STRESS, null)
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun regionSelectionBeforeFirstLaunchDoesNotCreatePartialConfig() {
        val root = temporary.newFolder()
        applyAccountPortal(root, WoWProducts.STRESS, BattleNetRegion.EU)
        assertFalse(File(root, "_forever_/WTF/Config.wtf").exists())
    }
}
