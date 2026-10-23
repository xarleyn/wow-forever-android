package app.gamenative.ui.screen.wow

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.winlator.sysvshm.SysVSharedMemory
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedMemoryConcurrencyTest {
    @Test fun deletingSegmentsWhileX11DetachesDoesNotExposeSparseArrayTombstones() {
        assumeTrue("Disposable emulator only", Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val memory = SysVSharedMemory()
        val workers = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val unrelatedBuffer = ByteBuffer.allocateDirect(1)
        try {
            val deleting = workers.submit {
                start.await()
                repeat(2000) {
                    val id = memory.get(4096)
                    assertTrue("Shared memory allocation", id > 0)
                    memory.delete(id)
                }
            }
            val detaching = workers.submit {
                start.await()
                repeat(20000) { memory.detach(unrelatedBuffer) }
            }
            start.countDown()
            deleting.get(20, TimeUnit.SECONDS)
            detaching.get(20, TimeUnit.SECONDS)
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS))
            memory.deleteAll()
        }
    }
}
