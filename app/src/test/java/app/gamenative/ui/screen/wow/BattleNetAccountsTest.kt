package app.gamenative.ui.screen.wow

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BattleNetAccountsTest {
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("account-test", Context.MODE_PRIVATE)
    private fun store() = BattleNetAccountStore(prefs, { "sealed:" + it.reversed() }, { it.removePrefix("sealed:").reversed() })
    private fun login(password: String = "secret") = BattleNetSignIn.Login("same@example.invalid", password)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun sameEmailCanHaveEuAndUsProfilesAndSelectionSurvivesReload() {
        val store = store()
        val eu = store.save(null, "WoW1", BattleNetRegion.EU, login())
        val us = store.save(null, "WoW1", BattleNetRegion.US, login("other-secret"))
        assertNotEquals(eu.id, us.id)
        assertEquals(2, store.accounts().size)
        assertEquals(us, store().active())
        store.select(eu.id)
        assertEquals(BattleNetRegion.EU, store().active()?.region)
        assertEquals("secret", store().login(eu.id).password)
        assertFalse(prefs.getString("accounts_v2", "")!!.contains("secret"))
        assertFalse(login().toString().contains("secret"))
    }

    @Test fun legacyMigrationPreservesCiphertextAndRequiresRegionConfirmation() {
        prefs.edit().putString("email", "same@example.invalid").putString("password", "sealed:terces").commit()
        val migrated = store().active()!!
        assertNull(migrated.region)
        assertFalse(prefs.contains("password"))
        assertEquals("secret", store().login(migrated.id).password)
        assertEquals(migrated.id, store().active()?.id)
        store().save(migrated.id, "WoW1", BattleNetRegion.EU, login())
        assertEquals(1, store().accounts().size)
        assertEquals(BattleNetRegion.EU, store().active()?.region)
    }

    @Test fun removingActiveDoesNotSilentlySelectAnotherAccount() {
        val first = store().save(null, "EU", BattleNetRegion.EU, login())
        val second = store().save(null, "US", BattleNetRegion.US, login())
        store().remove(second.id)
        assertNull(store().active())
        assertEquals(listOf(first), store().accounts())
    }

    @Test fun encryptionFailureAndUnreadablePasswordNeverClearSavedAccounts() {
        val account = store().save(null, "EU", BattleNetRegion.EU, login())
        val before = prefs.all
        val failing = BattleNetAccountStore(prefs, { error("unavailable") }, { error("unavailable") })
        assertThrows(IllegalStateException::class.java) { failing.login(account.id) }
        assertThrows(IllegalStateException::class.java) { failing.save(account.id, "US", BattleNetRegion.US, login()) }
        assertEquals(before, prefs.all)
    }

    @Test fun loginFileLineInjectionIsRejectedBeforeSaving() {
        assertThrows(IllegalArgumentException::class.java) {
            store().save(null, "WoW1", BattleNetRegion.EU, login("secret\nother"))
        }
        assertTrue(store().accounts().isEmpty())
    }
}
