package app.gamenative.ui.screen.wow

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object WoWLogShare {
    private const val MAX_INLINE_CHARS = 4_000
    private const val MAX_INLINE_LINES = 100

    /** Called on an IO dispatcher; share a snapshot so refreshes cannot change the attachment. */
    fun createIntent(context: Context, logs: String): Intent {
        val text = WoWConnectionDiagnostics.sanitize(logs)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "WoW Forever logs")
            if (text.length <= MAX_INLINE_CHARS && text.count { it == '\n' } < MAX_INLINE_LINES) {
                putExtra(Intent.EXTRA_TEXT, text)
            } else {
                val directory = File(context.cacheDir, "log_shares")
                check(directory.isDirectory || directory.mkdirs()) { "Couldn't create log export directory" }
                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
                // A separate file per share keeps attachments already handed to another app valid.
                val file = File.createTempFile("wow-forever-log-$timestamp-", ".txt", directory)
                file.writeText(text, Charsets.UTF_8)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, "WoW Forever logs", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
