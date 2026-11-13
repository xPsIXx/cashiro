package com.pennywiseai.tracker.data.diagnostic

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * S3 + S5 regression for [DiagnosticLogger].
 *
 * S3: the logcat branch used to append the RAW message and pass the RAW
 * Throwable to Log.*, while only the disk copy was scrubbed. Now logcat, the
 * in-memory buffer and the disk file all render from the single scrubbed
 * DiagnosticEntry: Bearer tokens, PATs, JSON secrets, password assignments,
 * ?token= query params and credential-bearing URLs must appear redacted in
 * EVERY sink. ShadowLog captures the real Log.* calls, so the logcat path is
 * asserted, not assumed.
 *
 * S5: log() used to `runBlocking` a synchronous file write on the caller's
 * thread (main-thread ViewModels called it per retry event). Now log() only
 * enqueues; ordering is FIFO; flush() drains deterministically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiagnosticLoggerScrubEnqueueTest {

    private lateinit var logger: DiagnosticLogger

    private val patLike = "ilp_0123456789abcdef0123456789abcdef"

    @Before
    fun setUp() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        // Start from a clean disk buffer for each test.
        DiagnosticLogger(context).let { legacy ->
            runBlocking { legacy.clear() }
        }
        File(context.cacheDir, "diagnostics").deleteRecursively()
        ShadowLog.clear()
        logger = DiagnosticLogger(context)
    }

    private fun logcatText(): String =
        ShadowLog.getLogsForTag("DiagnosticLogger").joinToString("\n") { it.msg }

    private fun diskText(): String {
        val file = logger.currentLogFile
        return if (file.exists()) file.readText() else ""
    }

    @Test
    fun secretsAreRedactedInLogcatAndOnDiskNotJustOnDisk() = runBlocking {
        logger.e(
            "FireflyClient",
            "sync failed: Authorization: Bearer $patLike for https://ff.example.org/api"
        )
        logger.w("TokenManager", "rejected pat: $patLike (personal_access_token=$patLike)")
        logger.w(
            "FireflyClient",
            "request failed",
            IllegalStateException("PUT https://user:hunterp4ssw0rd@ff.internal/ failed; body password=topsecret123")
        )
        logger.i("FireflyClient", "probe URL https://ff.example.org/?token=abc123def456ghi789j012")
        flush()

        val secrets = listOf(patLike, "hunterp4ssw0rd", "topsecret123", "abc123def456ghi789j012")
        val logcat = logcatText()
        val disk = diskText()

        assertTrue("logcat path must exist at all", logcat.isNotBlank())
        for (secret in secrets) {
            assertFalse("secret leaked to LOGCAT: $secret", logcat.contains(secret))
            assertFalse("secret leaked to DISK: $secret", disk.contains(secret))
        }
        for (entry in logger.entries.value) {
            for (secret in secrets) {
                assertFalse("secret leaked to in-memory buffer: $secret", entry.toString().contains(secret))
            }
        }
        assertTrue("redaction marker present in logcat", logcat.contains("***"))
        assertTrue("redaction marker present on disk", disk.contains("***"))
        // The throwable reached the sinks too — as scrubbed text, in both.
        assertTrue(logcat.contains("PUT https://user:***@ff.internal/ failed"))
        assertTrue(disk.contains("PUT https://user:***@ff.internal/ failed"))
    }

    @Test
    fun logReturnsImmediatelyWithoutTouchingDiskOnTheCallersThread() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val startedAt = System.nanoTime()
        repeat(20) { logger.d("EnqueueTest", "message $it") }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        // Old behavior: 20 synchronous runBlocking file writes. New behavior:
        // 20 channel offers. Even accounting for Robolectric overhead this is
        // orders of magnitude below a per-call file append.
        assertTrue("log() must not block the caller (took ${elapsedMs}ms for 20 calls)", elapsedMs < 250)

        flush()
        val file = logger.currentLogFile
        assertTrue("queued entries must land on disk after flush", file.exists())
        assertEquals(20, logger.entries.value.count { it.tag == "EnqueueTest" })
    }

    @Test
    fun enqueuePreservesCallOrderAndFlushDrainsTheQueue() = runBlocking {
        repeat(100) { logger.i("OrderTest", "line-$it") }
        flush()

        val messages = logger.entries.value.filter { it.tag == "OrderTest" }.map { it.message }
        assertEquals(100, messages.size)
        assertEquals("FIFO order must survive the queue", (0 until 100).map { "line-$it" }, messages)

        val diskLines = diskText().lines().filter { it.contains("OrderTest") }
        assertEquals("disk must hold the same order", (0 until 100).map { "line-$it" }, diskLines.map { line ->
            line.substringAfter("OrderTest: ")
        })
    }

    @Test
    fun flushIsBestEffortAndNeverThrowsAfterDrain() = runBlocking {
        logger.d("FlushTest", "a")
        withTimeout(10_000) { logger.flush() }
        withTimeout(1_000) { logger.flush() } // second flush on an empty queue
        assertEquals("a", logger.entries.value.last().message)
    }

    private suspend fun flush() = withTimeout(10_000) { logger.flush() }
}
