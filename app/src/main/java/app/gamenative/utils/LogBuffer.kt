package app.gamenative.utils

import android.util.Log
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

object LogBuffer {
    private val lines = CopyOnWriteArrayList<String>()

    val snapshot: List<String> get() = lines.toList()

    fun append(line: String) {
        lines.add(line)
        while (lines.size > 2000) lines.removeAt(0)
    }

    class Tree : Timber.Tree() {
        override fun isLoggable(tag: String?, priority: Int): Boolean = true

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val prefix = when (priority) {
                Log.VERBOSE -> "V"
                Log.DEBUG -> "D"
                Log.INFO -> "I"
                Log.WARN -> "W"
                Log.ERROR -> "E"
                else -> "?"
            }
            append("$prefix/${tag ?: "Pluvia"}: $message")
            t?.let { append(Log.getStackTraceString(it)) }
        }
    }
}