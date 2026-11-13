package com.pennywiseai.tracker.data.diagnostic

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory + on-disk diagnostic logger for the Firefly III integration and
 * general app behavior. Logs survive process death by being appended to a
 * rotating file in the app's cache directory. The UI can view and share the
 * current log buffer without needing ADB.
 *
 * Privacy: only diagnostic info intentionally logged by callers is stored.
 * Passwords or tokens must NEVER be passed here — and since S3 the logger no
 * longer even trusts that contract: EVERY outgoing string (logcat message AND
 * logcat stack text AND disk entry) is produced from the same scrubbed
 * [DiagnosticEntry], so a caller that interpolates a Bearer token, PAT,
 * password or credential-bearing URL is defused at the sink instead of
 * leaking through a "raw passthrough" logcat branch.
 *
 * Non-blocking (S5): [log] never touches disk, Log.*, or coroutine blocking on
 * the caller's thread. Each call builds the scrubbed entry and offers it to a
 * single-consumer [Channel] drained on Dispatchers.IO, preserving call order.
 * At most the entry currently being written is ever lost on process death;
 * [flush] drains the queue deterministically (used at shutdown seams and by
 * tests). [log] never throws, even if enqueueing fails.
 */
@Singleton
class DiagnosticLogger @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "DiagnosticLogger"
        private const val MAX_IN_MEMORY = 500
        private val TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }

    private val logDir: File
        get() = File(context.cacheDir, "diagnostics").also { it.mkdirs() }

    val currentLogFile: File
        get() = File(logDir, "pennywise_diagnostic.log")

    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val entries: StateFlow<List<DiagnosticEntry>> = _entries.asStateFlow()

    private val mutex = Mutex()

    /** FIFO queue; UNLIMITED so an offer can never block or drop. */
    private val queue = Channel<DiagnosticEntry>(Channel.UNLIMITED)

    /** Entries enqueued but not yet written by the consumer. */
    private val pending = AtomicLong(0)

    /** Internal control message: flush the queue and notify the flusher. */
    private class FlushSignal(val ack: Channel<Unit>)

    private val consumerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Single consumer: drains FIFO, so logcat + disk see entries in exact
        // call order. The first action loads last-run entries from disk —
        // startup no longer blocks on it (S5: the old code used runBlocking in
        // the constructor).
        consumerScope.launch {
            runCatching { loadFromDisk() }
            for (item in queue) {
                when (item) {
                    is FlushSignal -> {
                        pending.set(0)
                        item.ack.send(Unit)
                    }
                    is DiagnosticEntry -> writeEntry(item)
                }
            }
        }
    }

    /**
     * Enqueue one log line (S5). Returns as soon as the scrubbed entry is in
     * the queue — no file IO, no `runBlocking`, no `Log.*` on the caller's
     * dispatcher (callers include the main thread via ViewModels). Never
     * throws: a failed enqueue degrades to a direct scrubbed logcat write.
     */
    fun log(level: DiagnosticLevel, tag: String, message: String, throwable: Throwable? = null) {
        try {
            // S3: the scrubbed entry is the ONE source of truth. Logcat, the
            // in-memory buffer and the disk file all render from it, so raw
            // secrets can never take a side exit.
            val safeMessage = scrubSecrets(message)
            val safeThrowable = throwable?.stackTraceString()?.let { scrubSecrets(it) }
            val entry = DiagnosticEntry(
                timestamp = LocalDateTime.now(),
                level = level,
                tag = tag,
                message = safeMessage,
                throwable = safeThrowable
            )
            pending.incrementAndGet()
            val sent = queue.trySend(entry)
            if (!sent.isSuccess) {
                pending.decrementAndGet()
                // Degraded path: enqueueing is impossible (consumer's job
                // was cancelled). Still route through the scrubbed entry.
                runCatching { emitToLogcat(entry) }
            }
        } catch (_: Exception) {
            // log() must never take a caller down with it.
        }
    }

    fun d(tag: String, message: String) = log(DiagnosticLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(DiagnosticLevel.INFO, tag, message)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log(DiagnosticLevel.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(DiagnosticLevel.ERROR, tag, message, throwable)

    /**
     * Suspend until every entry enqueued before this call has been written to
     * logcat + disk. Best-effort shutdown seam (and test synchronization):
     * returns immediately once the queue is empty.
     */
    suspend fun flush() {
        if (pending.get() == 0L) return
        val ack = Channel<Unit>(1)
        queue.trySend(FlushSignal(ack))
        ack.receiveCatching()
    }

    /**
     * Write the current in-memory log entries to a shareable file and return it.
     */
    suspend fun exportToFile(): File = withContext(Dispatchers.IO) {
        flush()
        val exportFile = File(logDir, "pennywise_diagnostic_export_${System.currentTimeMillis()}.txt")
        mutex.withLock {
            exportFile.writer().use { writer ->
                _entries.value.forEach { entry ->
                    writer.write(entry.toString())
                    writer.write("\n")
                }
            }
        }
        exportFile
    }

    suspend fun clear() {
        flush()
        mutex.withLock {
            _entries.value = emptyList()
            currentLogFile.delete()
        }
    }

    /** Consumer-side: the only place anything is emitted, in FIFO order. */
    private suspend fun writeEntry(entry: DiagnosticEntry) {
        runCatching {
            emitToLogcat(entry)
            appendEntry(entry)
        }
        pending.decrementAndGet()
        // Flush acknowledgement is carried by the FlushSignal control message
        // itself (see init), so an entry never needs to notify a flusher.
    }

    /** S3: logcat renders the SAME scrubbed entry as disk — no raw message, no raw throwable. */
    private fun emitToLogcat(entry: DiagnosticEntry) {
        val combinedMessage = buildString {
            append("[${entry.timestamp.format(TIMESTAMP_FORMATTER)}]")
            append(" ${entry.level.name.padEnd(5)}")
            append(" ${entry.tag}: ")
            append(entry.message)
            entry.throwable?.let { append("\n$it") }
        }
        when (entry.level) {
            // The raw Throwable is deliberately NOT passed to Log.*: its
            // getMessage()/stackTrace get re-serialized into every device log
            // by the platform, which would bypass scrubbing entirely. The
            // scrubbed stack text is already part of combinedMessage.
            DiagnosticLevel.ERROR -> Log.e(TAG, combinedMessage)
            DiagnosticLevel.WARN -> Log.w(TAG, combinedMessage)
            else -> Log.i(TAG, combinedMessage)
        }
    }

    private suspend fun appendEntry(entry: DiagnosticEntry) {
        mutex.withLock {
            val updated = (_entries.value + entry).takeLast(MAX_IN_MEMORY)
            _entries.value = updated

            withContext(Dispatchers.IO) {
                try {
                    currentLogFile.appendText(entry.toString() + "\n")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write diagnostic entry to disk", e)
                }
            }
        }
    }

    private suspend fun loadFromDisk() {
        withContext(Dispatchers.IO) {
            try {
                if (!currentLogFile.exists()) return@withContext
                val lines = currentLogFile.readLines()
                if (lines.isEmpty()) return@withContext

                val parsed = lines.mapNotNull { parseLogLine(it) }
                _entries.value = parsed.takeLast(MAX_IN_MEMORY)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load diagnostic log from disk", e)
            }
        }
    }

    private fun parseLogLine(line: String): DiagnosticEntry? {
        // Expected format: [yyyy-MM-dd HH:mm:ss.SSS] LEVEL tag: message
        return try {
            if (!line.startsWith("[")) return null
            val timestampEnd = line.indexOf("]")
            if (timestampEnd == -1) return null
            val timestampStr = line.substring(1, timestampEnd)
            val timestamp = LocalDateTime.parse(timestampStr, TIMESTAMP_FORMATTER)

            val remaining = line.substring(timestampEnd + 2)
            val parts = remaining.split(" ", limit = 3)
            if (parts.size < 3) return null

            val level = DiagnosticLevel.valueOf(parts[0].trim())
            val tagWithColon = parts[1]
            val tag = if (tagWithColon.endsWith(":")) tagWithColon.dropLast(1) else tagWithColon
            val message = scrubSecrets(parts[2])

            DiagnosticEntry(timestamp = timestamp, level = level, tag = tag, message = message)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Remove common secret patterns (Bearer tokens, PATs, JSON access_token/
     * client_secret, password assignments, credential-bearing URLs) before ANY
     * output — logcat or disk — is produced (S3: one scrub path for every sink).
     */
    private fun scrubSecrets(input: String): String {
        return input
            .replace(Regex("""(?i)(Authorization\s*[:=]\s*Bearer\s+)[A-Za-z0-9_\-\.]{8,}"""), "$1***")
            .replace(Regex("""(?i)(Bearer\s+)[A-Za-z0-9_\-\.]{8,}"""), "$1***")
            .replace(Regex("""(?i)("(?:access_token|client_secret|refresh_token|api_key|apikey|auth_token)"\s*[:=]\s*")[^"]*"""), "$1***")
            .replace(Regex("""(?i)(password\s*[:=]\s*)[^\s,\"\']+"""), "$1***")
            // PATs / personal access tokens mentioned by name.
            .replace(Regex("""(?i)((?:personal_access_token|\bpat\b)\s*[:=]\s*)[A-Za-z0-9_\-\.]{12,}"""), "$1***")
            // Tokens carried as query parameters: ?token=…, &pat=…, &access_token=…
            .replace(Regex("""(?i)([?&](?:token|pat|access_token|api_key)=)[^&\s]+"""), "$1***")
            // Credentials embedded in URLs (e.g. https://user:pass@lan-host):
            // keep the username, redact the password.
            .replace(Regex("""(?i)(://[^\s/@:]+:)[^\s/@]+@"""), "$1***@")
    }

    private fun Throwable.stackTraceString(): String {
        val sw = StringWriter()
        printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}

enum class DiagnosticLevel { DEBUG, INFO, WARN, ERROR }

data class DiagnosticEntry(
    val timestamp: LocalDateTime,
    val level: DiagnosticLevel,
    val tag: String,
    val message: String,
    val throwable: String? = null
) {
    private companion object {
        private val FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    }

    override fun toString(): String {
        return buildString {
            append("[${timestamp.format(FORMATTER)}] ${level.name.padEnd(5)} $tag: $message")
            throwable?.let { append("\n$it") }
        }
    }
}
