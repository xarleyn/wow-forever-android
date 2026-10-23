package app.gamenative.ui.screen.wow

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.IntentCompat

@RunWith(AndroidJUnit4::class)
class WoWLogShareTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun attachment(intent: Intent): String {
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)!!
        assertEquals("content", uri.scheme)
        assertEquals(uri, intent.clipData!!.getItemAt(0).uri)
        assertNotEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
        return context.contentResolver.openInputStream(uri)!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test fun shortLogsStayInlineAndRedacted() {
        val intent = WoWLogShare.createIntent(context, "Ошибка подключения\npassword=private email=user@example.invalid")
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        assertFalse(intent.hasExtra(Intent.EXTRA_STREAM))
        assertEquals("Ошибка подключения\npassword=[redacted] email=[redacted]", intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test fun largeUnicodeLogIsAReadableCompleteFileWithoutInlinePayload() {
        val text = "Строка диагностики 🐉\n".repeat(20_000) + "END OF LOG"
        val intent = WoWLogShare.createIntent(context, text)
        assertEquals(text, attachment(intent))
        val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)!!
        assertTrue(uri.lastPathSegment!!.startsWith("wow-forever-log-"))
        assertTrue(uri.lastPathSegment!!.endsWith(".txt"))
    }

    @Test fun lineAndCharacterThresholdsBothSelectAttachments() {
        assertFalse(WoWLogShare.createIntent(context, "x".repeat(4_000)).hasExtra(Intent.EXTRA_STREAM))
        assertEquals("x".repeat(4_001), attachment(WoWLogShare.createIntent(context, "x".repeat(4_001))))
        assertFalse(WoWLogShare.createIntent(context, List(100) { "x" }.joinToString("\n")).hasExtra(Intent.EXTRA_STREAM))
        val manyLines = List(101) { "x" }.joinToString("\n")
        assertEquals(manyLines, attachment(WoWLogShare.createIntent(context, manyLines)))
    }

    @Test fun anotherShareDoesNotOverwriteThePreviousAttachmentAndFilesAreRedacted() {
        val first = WoWLogShare.createIntent(context, "x".repeat(4_001) + "\npassword=private")
        val secondText = "new snapshot\n".repeat(101)
        val second = WoWLogShare.createIntent(context, secondText)
        assertNotEquals(IntentCompat.getParcelableExtra(first, Intent.EXTRA_STREAM, Uri::class.java), IntentCompat.getParcelableExtra(second, Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals("x".repeat(4_001) + "\npassword=[redacted]", attachment(first))
        assertEquals(secondText, attachment(second))
    }
}
