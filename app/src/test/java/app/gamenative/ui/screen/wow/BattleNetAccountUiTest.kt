package app.gamenative.ui.screen.wow

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BattleNetAccountUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun narrowAccountCardSelectsUsProfileWithSameEmail() {
        val eu = BattleNetAccount("eu", "WoW1", "same@example.invalid", BattleNetRegion.EU)
        val us = eu.copy(id = "us", region = BattleNetRegion.US)
        val active = mutableStateOf(eu)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Column(Modifier.fillMaxSize().background(Color(0xFF0B1220)), horizontalAlignment = Alignment.CenterHorizontally) {
                    BattleNetAccountCard(listOf(eu, us), active.value, true, { active.value = it }, {}, {}, {})
                }
            }
        }
        compose.onNodeWithText("WoW1 (EU)").assertIsDisplayed().performClick()
        compose.onNodeWithText("WoW1 (US)").assertIsDisplayed().performClick()
        compose.onNodeWithText("WoW region: Americas (US)").assertIsDisplayed()
        assertEquals(us, active.value)
        screenshot("account-card-360dp.png")
    }

    private fun screenshot(name: String) {
        val target = File("build/outputs/login-ui", name)
        target.parentFile!!.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
