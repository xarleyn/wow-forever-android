package app.gamenative.ui.screen.wow

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class BattleNetRegion(val title: String) {
    EU("Europe"), US("Americas"), KR("Korea"), TW("Taiwan");

    companion object {
        fun parse(value: String?): BattleNetRegion? = entries.firstOrNull { it.name.equals(value, true) }
    }
}

/** A launch profile, not a server-issued WoW game-account ID. One email may have EU and US profiles. */
data class BattleNetAccount(val id: String, val label: String, val email: String, val region: BattleNetRegion?) {
    val displayName: String get() = "$label (${region?.name ?: "choose region"})"
}

/** Cipher callbacks keep Android Keystore out of storage tests. Passwords never enter account metadata. */
internal class BattleNetAccountStore(
    private val prefs: SharedPreferences,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String,
) {
    private fun records(): JSONArray {
        prefs.getString("accounts_v2", null)?.let { return JSONArray(it) }
        val email = prefs.getString("email", null) ?: return JSONArray()
        val password = prefs.getString("password", null) ?: return JSONArray()
        // Preserve the existing ciphertext. Region cannot be inferred from email or CDN branch.
        val migrated = JSONArray().put(JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("label", "Saved login")
            put("email", email)
            put("password", password)
        })
        persist(migrated, migrated.getJSONObject(0).getString("id"))
        return migrated
    }

    fun accounts(): List<BattleNetAccount> {
        val rows = records()
        return (0 until rows.length()).map { metadata(rows.getJSONObject(it)) }
    }

    fun active(): BattleNetAccount? {
        val accounts = accounts()
        val id = prefs.getString("active_account", null)
        return accounts.firstOrNull { it.id == id }
    }

    fun login(id: String): BattleNetSignIn.Login {
        val rows = records()
        val record = (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getString("id") == id }
        return BattleNetSignIn.Login(record.getString("email"), decrypt(record.getString("password")))
    }

    fun save(id: String?, label: String, region: BattleNetRegion, login: BattleNetSignIn.Login): BattleNetAccount {
        require(label.isNotBlank() && login.email.isNotBlank() && login.password.isNotEmpty())
        require(listOf(label, login.email, login.password).none { '\n' in it || '\r' in it || '\u0000' in it })
        val rows = records()
        val accountId = id ?: UUID.randomUUID().toString()
        val record = JSONObject().apply {
            put("id", accountId)
            put("label", label.trim())
            put("email", login.email.trim())
            put("region", region.name)
            put("password", encrypt(login.password))
        }
        val index = (0 until rows.length()).firstOrNull { rows.getJSONObject(it).getString("id") == accountId }
        if (id != null) require(index != null) { "Saved account no longer exists" }
        if (index == null) rows.put(record) else rows.put(index, record)
        persist(rows, accountId)
        return metadata(record)
    }

    fun select(id: String) {
        require(accounts().any { it.id == id })
        check(prefs.edit().putString("active_account", id).commit()) { "Couldn't save selected account" }
    }

    fun remove(id: String) {
        val rows = records()
        val kept = JSONArray()
        for (i in 0 until rows.length()) {
            rows.getJSONObject(i).takeUnless { it.getString("id") == id }?.let { kept.put(it) }
        }
        val activeId = prefs.getString("active_account", null)?.takeUnless { it == id }
        // Do not silently switch credentials when the selected profile is removed.
        persist(kept, activeId)
    }

    private fun persist(rows: JSONArray, activeId: String?) {
        check(prefs.edit().putString("accounts_v2", rows.toString()).putString("active_account", activeId)
            .remove("email").remove("password").commit()) { "Couldn't save Battle.net accounts" }
    }

    private fun metadata(row: JSONObject) = BattleNetAccount(
        row.getString("id"), row.getString("label"), row.getString("email"), BattleNetRegion.parse(row.optString("region")),
    )
}
