package com.winlator.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ProcessOutputCaptureTest {
    private class Child(private val input: InputStream, private val finished: Boolean) : Process() {
        var killed = false
        var waits = 0
        override fun getInputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int = error("Unbounded wait is forbidden")
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { waits++; return finished }
        override fun exitValue(): Int = if (finished) 7 else throw IllegalThreadStateException()
        override fun destroy() { killed = true }
        override fun destroyForcibly(): Process { killed = true; return this }
    }

    @Test fun capturesFinalOutputAndExitCodeWithoutWaitingAgain() {
        val child = Child(ByteArrayInputStream("last line without newline".toByteArray()), true)
        val output = ProcessOutputCapture.capture(child, 1, 1024)
        assertEquals("PROBE_PROCESS exit=7\nlast line without newline", output)
        assertEquals(1, child.waits)
        assertFalse(child.killed)
    }

    @Test fun capsAStreamWithoutNewlinesAndKeepsItsEnd() {
        val child = Child(ByteArrayInputStream(("x".repeat(512 * 1024) + "END").toByteArray()), true)
        val output = ProcessOutputCapture.capture(child, 1, 4096)
        assertTrue(output, output.contains("truncated"))
        assertTrue(output.endsWith("END"))
        assertTrue(output.length < 4200)
    }

    @Test fun timeoutAndInheritedPipeCannotBlockLaunchOrRequireAnotherProcessWait() {
        for (finished in listOf(false, true)) {
            val release = CountDownLatch(1)
            val stream = object : InputStream() {
                override fun read(): Int { release.await(); return -1 }
                override fun close() { release.await() }
            }
            val child = Child(stream, finished)
            val started = System.nanoTime()
            try {
                val output = ProcessOutputCapture.capture(child, 1, 4096)
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2000)
                assertEquals(!finished, child.killed)
                assertEquals(1, child.waits)
                assertTrue(output, output.contains(if (finished) "exit=7" else "timeout=1s"))
            } finally { release.countDown() }
        }
    }
}
