import java.io.File
import java.time.Clock

object UserDataPaths {
    val root: File
        get() = File(System.getProperty("user.home"), ".nfvr_quest_installer")
}

object DiagnosticLogger {
    private const val MAX_LOG_BYTES = 1_000_000L
    private const val MAX_RECENT_BYTES = 40_000
    private val lock = Any()
    @Volatile
    private var clock: Clock = Clock.systemUTC()

    private val logFile: File
        get() = File(UserDataPaths.root, "logs/diagnostic.log")

    fun info(message: String) = write("INFO", message)

    fun error(message: String, throwable: Throwable? = null) {
        val details = throwable?.stackTraceToString()?.let { "\n$it" }.orEmpty()
        write("ERROR", message + details)
    }

    /**
     * Diagnostic files intentionally keep an ISO Instant for support tooling;
     * this hook only makes log creation deterministic in focused tests.
     */
    internal fun setClockForTests(value: Clock) {
        clock = value
    }

    fun safeForCopy(value: String): String = safeDiagnosticText(value)

    fun recent(): String = synchronized(lock) {
        runCatching {
            val file = logFile
            if (!file.exists()) return@synchronized ""
            val text = file.readText()
            val bounded = if (text.length <= MAX_RECENT_BYTES) text else text.takeLast(MAX_RECENT_BYTES)
            safeDiagnosticText(bounded)
        }.getOrDefault("")
    }

    private fun write(level: String, message: String) = synchronized(lock) {
        runCatching {
            val file = logFile
            file.parentFile?.mkdirs()
            rotateIfNeeded(file)
            val safe = redact(message).take(80_000)
            file.appendText("${clock.instant()} [$level] $safe\n")
        }
        Unit
    }

    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_LOG_BYTES) return
        val previous = File(file.parentFile, "${file.name}.1")
        previous.delete()
        file.renameTo(previous)
    }

    private fun redact(value: String): String {
        val home = System.getProperty("user.home").orEmpty()
        val withoutHome = if (home.isBlank()) value else value.replace(home, "[USER_HOME]", ignoreCase = true)
        return safeDiagnosticText(withoutHome)
            .replace(Regex("""(?i)(license[_ -]?key\s*[=:]\s*)\S+"""), "$1[REDACTED]")
            .replace(Regex("""(?i)(authorization\s*[=:]\s*)\S+"""), "$1[REDACTED]")
            .replace(Regex("""(?i)(token\s*[=:]\s*)\S+"""), "$1[REDACTED]")
            .replace(Regex("""(?i)(السيريال\s*:\s*)\S+"""), "$1[REDACTED]")
    }
}