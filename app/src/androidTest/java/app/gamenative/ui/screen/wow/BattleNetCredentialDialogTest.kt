package app.gamenative.ui.screen.wow

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a device: Robolectric 4.14 cannot reach idle for this platform Dialog in this project. */
@RunWith(AndroidJUnit4::class)
class BattleNetCredentialDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun migratedLoginRequiresExplicitRegionBeforeSave() {
        var savedRegion: BattleNetRegion? = null
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                BattleNetCredentialDialog(
                    onDismiss = {},
                    initial = BattleNetAccount("old", "Saved login", "same@example.invalid", null),
                    initialLogin = BattleNetSignIn.Login("same@example.invalid", "fixture-password"),
                    onConfirm = { _, region, _ -> savedRegion = region },
                )
            }
        }
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("EU").performClick()
        compose.onNodeWithText("Europe").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Save").performClick()
        assertEquals(BattleNetRegion.EU, savedRegion)
    }

}
