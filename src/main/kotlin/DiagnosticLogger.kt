import java.io.File
import java.time.Instant

object UserDataPaths {
    val root: File
        get() = File(System.getProperty("user.home"), ".nfvr_quest_installer")
}

object DiagnosticLogger {
    private const val MAX_LOG_BYTES = 1_000_000L
    private const val MAX_RECENT_BYTES = 40_000
    private val lock = Any()

    private val logFile: File
        get() = File(UserDataPaths.root, "logs/diagnostic.log")

    fun info(message: String) = write("INFO", message)

    fun error(message: String, throwable: Throwable? = null) {
        val details = throwable?.stackTraceToString()?.let { "\n$it" }.orEmpty()
        write("ERROR", message + details)
    }

    fun recent(): String = synchronized(lock) {
        runCatching {
            val file = logFile
            if (!file.exists()) return@synchronized ""
            val text = file.readText()
            if (text.length <= MAX_RECENT_BYTES) text else text.takeLast(MAX_RECENT_BYTES)
        }.getOrDefault("")
    }

    private fun write(level: String, message: String) = synchronized(lock) {
        runCatching {
            val file = logFile
            file.parentFile?.mkdirs()
            rotateIfNeeded(file)
            val safe = redact(message).take(80_000)
            file.appendText("${Instant.now()} [$level] $safe\n")
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
        return withoutHome
            .replace(Regex("""(?i)(license[_ -]?key\s*[=:]\s*)\S+"""), "$1[REDACTED]")
            .replace(Regex("""(?i)(authorization\s*[=:]\s*)\S+"""), "$1[REDACTED]")
            .replace(Regex("""(?i)(token\s*[=:]\s*)\S+"""), "$1[REDACTED]")
            .replace(Regex("""(?i)(السيريال\s*:\s*)\S+"""), "$1[REDACTED]")
    }
}