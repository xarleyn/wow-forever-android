package app.gamenative.ui.screen.wow

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

private fun configDefaults(productId: String, portal: String) = linkedMapOf(
    "portal" to "\"$portal\"",
    "agentUID" to "\"$productId\"",
    "gxApi" to "\"D3D11\"",
    "textLocale" to "\"enUS\"",
    "audioLocale" to "\"enUS\"",
    "gxMaximize" to "\"1\"",
    "graphicsQuality" to "\"0\"",
    "ResampleQuality" to "\"0\"",
    "RenderScale" to "\"1\"",
    "farclip" to "\"1200\"",
    "horizonClip" to "\"1200\"",
    "RAIDfarclip" to "\"1200\"",
    "RAIDhorizonClip" to "\"1200\"",
    "shadowMode" to "\"0\"",
    "graphicsShadowQuality" to "\"0\"",
    "raidGraphicsShadowQuality" to "\"0\"",
    "worldBaseMip" to "\"0\"",
    "RAIDworldBaseMip" to "\"0\"",
    "graphicsTextureResolution" to "\"2\"",
    "raidGraphicsTextureResolution" to "\"2\"",
    "componentTextureLevel" to "\"0\"",
    "RAIDcomponentTextureLevel" to "\"0\"",
    "entityShadowFadeScale" to "\"0\"",
    "refraction" to "\"0\"",
    "groundEffectDensity" to "\"16\"",
    "GamePadEnable" to "\"1\"",
    "InputDeviceInterfaceStyle" to "\"1\"",
)

internal fun portalFor(profile: WoWProductProfile, region: BattleNetRegion?): String =
    if (profile.productId == WoWProducts.BETA.productId) "test"
    else requireNotNull(region) { "Choose the WoW account region before launching the Stress Test" }.name

/** Update connection identity without touching account directories or unrelated user settings. */
internal fun applyAccountPortal(root: File, profile: WoWProductProfile, region: BattleNetRegion?) {
    val config = File(root, "${profile.flavorDir}/WTF/Config.wtf")
    val portal = portalFor(profile, region)
    if (config.isFile) updateConfig(config, linkedMapOf("portal" to portal, "agentUID" to profile.productId))
}

internal fun ensureGameConfig(root: File, screenSize: String, profile: WoWProductProfile, region: BattleNetRegion?) {
    val portal = portalFor(profile, region)
    val flavorDir = File(root, profile.flavorDir)
    check(flavorDir.isDirectory || flavorDir.mkdirs()) { "Couldn't create game flavor directory" }
    val flavorInfo = File(flavorDir, ".flavor.info")
    if (!flavorInfo.exists()) flavorInfo.writeText("Product Flavor!STRING:0\n${profile.productId}\n")
    val config = File(flavorDir, "WTF/Config.wtf")
    if (!config.exists()) {
        val defaults = configDefaults(profile.productId, portal) + RESOLUTION_KEYS.associateWith { "\"$screenSize\"" }
        writeConfig(config, defaults.entries.joinToString("\n") { "SET ${it.key} ${it.value}" } + "\n")
        return
    }
    val original = config.readLines()
    val managed = linkedMapOf("portal" to portal, "agentUID" to profile.productId, "gxApi" to "D3D11")
    RESOLUTION_KEYS.forEach { key ->
        val value = original.firstNotNullOfOrNull { line ->
            setting.find(line)?.takeIf { it.groupValues[1].equals(key, true) }?.groupValues?.get(2)
        }
        if (value == null || !fitsScreen(value.trim().trim('"'), screenSize)) managed[key] = screenSize
    }
    updateConfig(config, managed)
}

private val setting = Regex("^\\s*SET\\s+(\\S+)\\s+(.*?)\\s*$", RegexOption.IGNORE_CASE)

private fun updateConfig(file: File, values: Map<String, String>) {
    val original = file.readText()
    val newline = if ("\r\n" in original) "\r\n" else "\n"
    val pending = values.toMutableMap()
    val updated = original.lineSequence().toMutableList().apply {
        if (lastOrNull() == "") removeAt(lastIndex)
    }.mapNotNull { line ->
        val key = setting.find(line)?.groupValues?.get(1)
        val managedKey = values.keys.firstOrNull { it.equals(key, true) }
        if (managedKey == null) line
        else pending.remove(managedKey)?.let { "SET $managedKey \"$it\"" }
    }.toMutableList()
    pending.forEach { (key, value) -> updated.add("SET $key \"$value\"") }
    val text = updated.joinToString(newline) + newline
    if (text != original) writeConfig(file, text)
}

private fun writeConfig(file: File, text: String) {
    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Couldn't create WTF directory" }
    val temporary = File.createTempFile("Config-", ".tmp", file.parentFile)
    try {
        temporary.outputStream().use { stream ->
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        temporary.delete()
    }
}

private val RESOLUTION_KEYS = listOf("gxFullscreenResolution", "gxWindowedResolution")

internal fun fitsScreen(resolution: String, screenSize: String): Boolean {
    val (width, height) = resolution.split("x").map { it.toIntOrNull() ?: return false }.takeIf { it.size == 2 } ?: return false
    val (screenWidth, screenHeight) = screenSize.split("x").map { it.toInt() }
    return width in 1..screenWidth && height in 1..screenHeight
}

