package com.pennywiseai.tracker.data.diagnostic

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory + on-disk diagnostic logger for the Firefly III integration and
 * general app behavior. Logs survive process death by being appended to a
 * rotating file in the app's cache directory. The UI can view and share the
 * current log buffer without needing ADB.
 *
 * Privacy: only diagnostic info intentionally logged by callers is stored.
 * Passwords or tokens must NEVER be passed here.
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Load previous entries from disk on a background thread so Hilt
        // construction on the main thread cannot ANR.
        scope.launch {
            loadFromDisk()
        }
    }

    fun log(level: DiagnosticLevel, tag: String, message: String, throwable: Throwable? = null) {
        val safeMessage = scrubSecrets(message)
        val safeThrowable = throwable?.stackTraceString()?.let { scrubSecrets(it) }
        val entry = DiagnosticEntry(
            timestamp = LocalDateTime.now(),
            level = level,
            tag = tag,
            message = safeMessage,
            throwable = safeThrowable
        )

        val combinedMessage = buildString {
            append("[${entry.timestamp.format(TIMESTAMP_FORMATTER)}]")
            append(" ${entry.level.name.padEnd(5)}")
            append(" $tag: ")
            append(message)
            entry.throwable?.let { append("\n$it") }
        }

        when (level) {
            DiagnosticLevel.ERROR -> Log.e(TAG, combinedMessage, throwable)
            DiagnosticLevel.WARN -> Log.w(TAG, combinedMessage, throwable)
            else -> Log.i(TAG, combinedMessage, throwable)
        }

        scope.launch {
            appendEntry(entry)
        }
    }

    fun d(tag: String, message: String) = log(DiagnosticLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(DiagnosticLevel.INFO, tag, message)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log(DiagnosticLevel.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(DiagnosticLevel.ERROR, tag, message, throwable)

    /**
     * Write the current in-memory log entries to a shareable file and return it.
     */
    suspend fun exportToFile(): File = withContext(Dispatchers.IO) {
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
        mutex.withLock {
            _entries.value = emptyList()
            currentLogFile.delete()
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
     * Remove common secret patterns (Bearer tokens, JSON access_token/client_secret)
     * before storing or sharing logs.
     */
    private fun scrubSecrets(input: String): String {
        return input
            .replace(Regex("""(?i)(Authorization\s*[:=]\s*Bearer\s+)[A-Za-z0-9_\-\.]{8,}"""), "$1***")
            .replace(Regex("""(?i)(Bearer\s+)[A-Za-z0-9_\-\.]{8,}"""), "$1***")
            .replace(Regex("""(?i)("access_token"\s*:\s*")[^\"]*"""), "$1***\"")
            .replace(Regex("""(?i)("client_secret"\s*:\s*")[^\"]*"""), "$1***\"")
            .replace(Regex("""(?i)(password\s*[:=]\s*)[^\s,\"\']+"""), "$1***")
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
