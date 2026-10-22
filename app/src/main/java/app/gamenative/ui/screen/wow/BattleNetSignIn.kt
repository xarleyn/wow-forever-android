package app.gamenative.ui.screen.wow

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.gamenative.Crypto
import app.gamenative.ui.theme.WowGold
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object BattleNetSignIn {
    // Deliberately not a data class: generated toString must never disclose the password.
    class Login(val email: String, val password: String)

    internal fun store(context: Context) = BattleNetAccountStore(
        prefs(context),
        encrypt = { Base64.encodeToString(Crypto.encrypt(it.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) },
        decrypt = { String(Crypto.decrypt(Base64.decode(it, Base64.NO_WRAP)), Charsets.UTF_8) },
    )

    private var loginWatcher: Job? = null
    private var loginGeneration = 0L

    private fun flavorDir(gameRoot: File): String = WowClientDownloader.profileForGameRoot(gameRoot).flavorDir

    fun writeLoginFile(gameRoot: File, login: Login) {
        require(listOf(login.email, login.password).none { '\n' in it || '\r' in it || '\u0000' in it })
        val file = File(File(gameRoot, flavorDir(gameRoot)), "login.txt")
        file.parentFile?.mkdirs()
        file.writeText("${login.email}\n${login.password}\n")
    }

    @Synchronized
    fun removeLoginFile(gameRoot: File): Boolean {
        loginGeneration++
        loginWatcher?.cancel()
        loginWatcher = null
        // Remove stale credentials from either supported product, including after a product switch.
        return WoWProducts.supported.map { profile -> runCatching {
            val file = File(File(gameRoot, profile.flavorDir), "login.txt")
            if (file.exists()) {
                file.writeBytes(ByteArray(file.length().coerceAtMost(4096).toInt()))
                check(file.delete()) { "Couldn't remove saved game login" }
            }
        }.isSuccess }.all { it }
    }

    @Synchronized
    fun startLoginWatcher(gameRoot: File) {
        loginWatcher?.cancel()
        val generation = ++loginGeneration
        loginWatcher = CoroutineScope(Dispatchers.IO).launch {
            val hotfixLog = File(File(gameRoot, flavorDir(gameRoot)), "Logs/Hotfix.log")
            val initialLength = hotfixLog.length()
            val initialModified = hotfixLog.lastModified()
            val deadline = System.currentTimeMillis() + 180_000L
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(1000L)
                if (hotfixLog.exists() && hotfixLog.lastModified() != initialModified && hotfixLog.length() > initialLength + 50_000L) break
            }
            synchronized(this@BattleNetSignIn) {
                // An old launch must never erase the next selected profile's login.txt.
                if (loginGeneration == generation) removeLoginFile(gameRoot)
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "battle_net_login"
}

@Composable
fun BattleNetAccountCard(
    accounts: List<BattleNetAccount>,
    active: BattleNetAccount?,
    enabled: Boolean,
    onSelect: (BattleNetAccount) -> Unit,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(0.9f),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2E), contentColor = Color(0xFFE2E8F0)),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, Color(0xFF2A3C54)),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("BATTLE.NET ACCOUNT", color = WowGold)
            Box {
                OutlinedButton(
                    onClick = { expanded = true }, enabled = enabled && accounts.isNotEmpty(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = WowGold),
                ) {
                    Text(active?.displayName ?: "Select a saved account")
                }
                DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                    accounts.forEach { account ->
                        DropdownMenuItem(
                            text = { Text(account.displayName) },
                            onClick = { expanded = false; onSelect(account) },
                        )
                    }
                }
            }
            Text(active?.region?.let { "WoW region: ${it.title} (${it.name})" }
                ?: "Confirm your WoW account region to enable auto-launch.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAdd, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = WowGold)) { Text("Add") }
                TextButton(onClick = onEdit, enabled = enabled && active != null, colors = ButtonDefaults.textButtonColors(contentColor = WowGold)) { Text("Edit") }
                TextButton(onClick = onRemove, enabled = enabled && active != null, colors = ButtonDefaults.textButtonColors(contentColor = WowGold)) { Text("Remove") }
            }
        }
    }
}

@Composable
fun BattleNetCredentialDialog(
    onDismiss: () -> Unit,
    initial: BattleNetAccount? = null,
    initialLogin: BattleNetSignIn.Login? = null,
    onConfirm: (String, BattleNetRegion, BattleNetSignIn.Login) -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label ?: "WoW1") }
    var region by remember { mutableStateOf(initial?.region) }
    var email by remember { mutableStateOf(initialLogin?.email ?: "") }
    var password by remember { mutableStateOf(initialLogin?.password ?: "") }
    val emailFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        if (initial != null) return@LaunchedEffect
        delay(DIALOG_SETTLE_MS)
        emailFocus.requestFocus()
        keyboard?.show()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Battle.net login") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Choose your WoW region. You can save EU and US profiles for the same Battle.net login. Password saved encrypted on this device.")
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Profile name (e.g. WoW1)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    BattleNetRegion.entries.forEach { item ->
                        FilterChip(selected = region == item, onClick = { region = item }, label = { Text(item.name) })
                    }
                }
                Text(region?.title ?: "Choose a region to continue")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.focusRequester(emailFocus),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && email.isNotBlank() && password.isNotEmpty() && region != null &&
                    listOf(label, email, password).none { '\n' in it || '\r' in it || '\u0000' in it },
                onClick = { region?.let { onConfirm(label.trim(), it, BattleNetSignIn.Login(email.trim(), password)) } },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val DIALOG_SETTLE_MS = 300L
