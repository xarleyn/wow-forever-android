package app.gamenative.ui.screen.wow

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun BattleNetToolbar(modifier: Modifier = Modifier, controlsVisible: Boolean, onKeyboard: () -> Unit, onControls: () -> Unit) {
    Surface(modifier = modifier, color = Color(0xE6212633), contentColor = Color.White,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
        Row {
            TextButton(onClick = onKeyboard, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Default.Keyboard, contentDescription = null, tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text("Keyboard", color = Color.White)
            }
            TextButton(onClick = onControls, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (controlsVisible) "Hide controls" else "Controls", color = Color.White) }
        }
    }
}
