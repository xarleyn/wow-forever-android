package app.gamenative.ui.screen.wow

/**
 * Describes one supported World of Warcraft client identity.
 *
 * The launcher must never mix these profiles: every file path, CDN request,
 * executable name and config value has to come from the selected profile.
 */
data class WoWProductProfile(
    val productId: String,
    val flavorDir: String,
    val executableName: String,
    val displayName: String,
    /**
     * Alternative spellings of the ARM64 client binary. Blizzard ships the
     * Stress Test binary as `Wow-ARM64.exe` in the install manifest while the
     * product config advertises `Wow-arm64.exe`; both are accepted.
     */
    val executableAliases: List<String> = emptyList(),
) {
    val executableCandidates: List<String>
        get() = (listOf(executableName) + executableAliases).distinct()
}

object WoWProducts {
    val BETA = WoWProductProfile(
        productId = "wow_classic_beta",
        flavorDir = "_classic_beta_",
        executableName = "WowB-ARM64.exe",
        displayName = "Forever Beta",
        executableAliases = listOf("WowB-arm64.exe"),
    )

    val STRESS = WoWProductProfile(
        productId = "wowf",
        flavorDir = "_forever_",
        executableName = "Wow-arm64.exe",
        displayName = "Forever Stress Test",
        executableAliases = listOf("Wow-ARM64.exe"),
    )

    val supported = listOf(STRESS, BETA)

    fun find(productId: String?): WoWProductProfile? =
        supported.firstOrNull { it.productId == productId }

    fun isSupported(productId: String?): Boolean = find(productId) != null
}
