import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

internal sealed interface IsolatedPickerOutput {
    data object Cancelled : IsolatedPickerOutput
    data class Selected(val path: String) : IsolatedPickerOutput
    data class Invalid(val diagnostic: String) : IsolatedPickerOutput
}

internal fun parseIsolatedPickerOutput(output: String): IsolatedPickerOutput {
    val lines = output.lineSequence()
        .map(String::trim)
        .map { it.removePrefix("\uFEFF") }
        .filter(String::isNotBlank)
        .toList()
    return when {
        lines.isEmpty() -> IsolatedPickerOutput.Cancelled
        lines.size != 1 -> IsolatedPickerOutput.Invalid("expected one selected path")
        '\u0000' in lines.single() -> IsolatedPickerOutput.Invalid("selected path contained NUL")
        else -> IsolatedPickerOutput.Selected(lines.single())
    }
}

internal fun pickerSelectionError(mode: DesktopChooserMode, file: File): String? =
    when (mode) {
        DesktopChooserMode.DIRECTORY ->
            if (file.isDirectory && file.canRead()) null else "selected path is not a readable directory"
        DesktopChooserMode.FILES ->
            if (file.isFile && file.canRead() && file.name.endsWith(".zip", ignoreCase = true)) {
                null
            } else {
                "selected path is not a readable ZIP file"
            }
    }

internal fun trustedWindowsPowerShellExecutable(systemRoot: String? = System.getenv("SystemRoot")): File? {
    val root = systemRoot?.trim().orEmpty()
    if (root.isBlank()) return null
    val rootFile = File(root)
    val windowsAbsolute = root.matches(Regex("""^[A-Za-z]:[\\/].*"""))
    if (!rootFile.isAbsolute && !windowsAbsolute) return null
    return File(rootFile, "System32/WindowsPowerShell/v1.0/powershell.exe")
}

internal enum class PickerWaitOutcome {
    WAITING,
    TIMED_OUT,
    CANCELLED
}

internal fun pickerWaitOutcome(remainingNanos: Long, interrupted: Boolean): PickerWaitOutcome =
    when {
        interrupted -> PickerWaitOutcome.CANCELLED
        remainingNanos <= 0L -> PickerWaitOutcome.TIMED_OUT
        else -> PickerWaitOutcome.WAITING
    }

/**
 * Windows-only desktop pickers.
 *
 * The dialog lives in a short-lived PowerShell STA process rather than in the
 * Compose/AWT process.  Besides giving us a real directory picker, this keeps
 * the native modal window and its focus tree completely isolated from
 * Compose.  User paths are environment values; they are never interpolated
 * into the PowerShell source passed to -Command.
 */
object WindowsIsolatedPicker {
    private const val PROCESS_TIMEOUT_MILLIS = 120_000L
    private const val MAX_OUTPUT_CHARS = 32_000
    private const val INITIAL_PATH_ENV = "NFVR_PICKER_INITIAL_PATH"
    private const val TITLE_ENV = "NFVR_PICKER_TITLE"
    private val gate = DesktopChooserGate()

    private val folderScript = """
        ${'$'}ErrorActionPreference = 'Stop'
        [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
        [Console]::InputEncoding = [System.Text.Encoding]::UTF8
        Add-Type -AssemblyName System.Windows.Forms
        ${'$'}dialog = New-Object System.Windows.Forms.FolderBrowserDialog
        ${'$'}dialog.Description = ${'$'}env:NFVR_PICKER_TITLE
        ${'$'}initial = ${'$'}env:NFVR_PICKER_INITIAL_PATH
        if (${'$'}initial -and [System.IO.Directory]::Exists(${'$'}initial)) {
            ${'$'}dialog.SelectedPath = ${'$'}initial
        }
        try {
            if (${'$'}dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
                ${'$'}path = ${'$'}dialog.SelectedPath
                if ([System.IO.Directory]::Exists(${'$'}path)) {
                    [Console]::Out.WriteLine(${'$'}path)
                }
            }
        } finally {
            ${'$'}dialog.Dispose()
        }
    """.trimIndent()

    private val fileScript = """
        ${'$'}ErrorActionPreference = 'Stop'
        [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
        [Console]::InputEncoding = [System.Text.Encoding]::UTF8
        Add-Type -AssemblyName System.Windows.Forms
        ${'$'}dialog = New-Object System.Windows.Forms.OpenFileDialog
        ${'$'}dialog.Multiselect = ${'$'}false
        ${'$'}dialog.Filter = 'ZIP archives (*.zip)|*.zip|All files (*.*)|*.*'
        ${'$'}dialog.Title = ${'$'}env:NFVR_PICKER_TITLE
        ${'$'}initial = ${'$'}env:NFVR_PICKER_INITIAL_PATH
        if (${'$'}initial -and [System.IO.Directory]::Exists(${'$'}initial)) {
            ${'$'}dialog.InitialDirectory = ${'$'}initial
        }
        try {
            if (${'$'}dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
                ${'$'}path = ${'$'}dialog.FileName
                if ([System.IO.File]::Exists(${'$'}path)) {
                    [Console]::Out.WriteLine(${'$'}path)
                }
            }
        } finally {
            ${'$'}dialog.Dispose()
        }
    """.trimIndent()

    suspend fun chooseFolder(
        initialDirectory: File? = null,
        title: String = "اختر مجلد اللعبة"
    ): DesktopChooserResult = choose(
        mode = DesktopChooserMode.DIRECTORY,
        initialDirectory = initialDirectory,
        title = title,
        script = folderScript
    )

    suspend fun chooseZipFile(
        initialDirectory: File? = null,
        title: String = "اختر ملف المود (ZIP)"
    ): DesktopChooserResult = choose(
        mode = DesktopChooserMode.FILES,
        initialDirectory = initialDirectory,
        title = title,
        script = fileScript
    )

    private suspend fun choose(
        mode: DesktopChooserMode,
        initialDirectory: File?,
        title: String,
        script: String
    ): DesktopChooserResult {
        if (!isWindows()) {
            return DesktopChooserResult.Failed(
                "هذه النافذة الأصلية متاحة في إصدار Windows فقط.",
                "isolated-picker unsupported host=${System.getProperty("os.name").orEmpty()}"
            )
        }
        if (!gate.tryAcquire()) {
            DiagnosticLogger.info("isolated picker busy: mode=$mode")
            return DesktopChooserResult.Busy
        }
        return try {
            runInterruptible(Dispatchers.IO) {
                execute(
                    mode = mode,
                    initialDirectory = initialDirectory,
                    title = title,
                    script = script
                )
            }
        } catch (cancelled: CancellationException) {
            // Cancellation is normal while the native process is being
            // dismissed.  Do not turn it into a user-facing picker error.
            throw cancelled
        } catch (error: Throwable) {
            DiagnosticLogger.error("isolated picker failed: mode=$mode", error)
            DesktopChooserResult.Failed(
                "تعذر فتح نافذة الاختيار. حاول مرة أخرى.",
                "isolated picker ${error::class.java.simpleName}: ${error.message.orEmpty()}"
            )
        } finally {
            gate.release()
        }
    }

    private fun execute(
        mode: DesktopChooserMode,
        initialDirectory: File?,
        title: String,
        script: String
    ): DesktopChooserResult {
        val powershell = trustedWindowsPowerShellExecutable()
            ?.takeIf { it.isFile && it.canExecute() }
            ?.absolutePath
            ?: return DesktopChooserResult.Failed(
                "تعذر فتح نافذة الاختيار الأصلية في Windows.",
                "trusted SystemRoot Windows PowerShell was not found"
            )

        val initial = initialDirectory
            ?.takeIf { it.isDirectory }
            ?.let { runCatching { it.canonicalFile }.getOrNull() }

        val process = try {
            val initialPath = initial?.absolutePath.orEmpty()
            if ('\u0000' in initialPath || '\u0000' in title) {
                return DesktopChooserResult.Failed(
                    "تعذر فتح نافذة الاختيار. حاول مرة أخرى.",
                    "picker environment value contained NUL"
                )
            }
            ProcessBuilder(
                powershell,
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-STA",
                "-ExecutionPolicy",
                "Bypass",
                "-Command",
                script
            ).apply {
                // Paths and UI text are data supplied through the process
                // environment, not source-code interpolation.
                environment()[INITIAL_PATH_ENV] = initialPath
                environment()[TITLE_ENV] = title.take(200)
            }.start()
        } catch (error: Throwable) {
            return DesktopChooserResult.Failed(
                "تعذر تشغيل نافذة الاختيار. تحقق من إعداد Windows PowerShell.",
                "powershell start ${error::class.java.simpleName}: ${error.message.orEmpty()}"
            )
        }

        val stdout = BoundedPickerOutput(MAX_OUTPUT_CHARS)
        val stderr = BoundedPickerOutput(MAX_OUTPUT_CHARS)
        val stdoutReader = Thread({
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach(stdout::append)
            }
        }, "nfvr-picker-stdout").apply { isDaemon = true }
        val stderrReader = Thread({
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
                lines.forEach(stderr::append)
            }
        }, "nfvr-picker-stderr").apply { isDaemon = true }
        stdoutReader.start()
        stderrReader.start()

        var timedOut = false
        try {
            val deadline = System.nanoTime() +
                TimeUnit.MILLISECONDS.toNanos(PROCESS_TIMEOUT_MILLIS)
            while (process.isAlive) {
                val remaining = deadline - System.nanoTime()
                when (pickerWaitOutcome(remaining, Thread.currentThread().isInterrupted)) {
                    PickerWaitOutcome.CANCELLED ->
                        throw InterruptedException("isolated picker cancelled")
                    PickerWaitOutcome.TIMED_OUT -> {
                        timedOut = true
                        process.destroyForcibly()
                        break
                    }
                    PickerWaitOutcome.WAITING -> Unit
                }
                process.waitFor(
                    minOf(
                        100L,
                        TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1L)
                    ),
                    TimeUnit.MILLISECONDS
                )
            }
            if (process.isAlive) {
                process.destroyForcibly()
            }
            process.waitFor(5, TimeUnit.SECONDS)
            stdoutReader.join(5_000)
            stderrReader.join(5_000)
        } catch (cancelled: InterruptedException) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
            stdoutReader.join(1_000)
            stderrReader.join(1_000)
            Thread.currentThread().interrupt()
            throw cancelled
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }

        if (timedOut) {
            val diagnostics = diagnosticText("timeout", stderr.toString())
            DiagnosticLogger.error("isolated picker timeout: mode=$mode $diagnostics")
            return DesktopChooserResult.Failed(
                "انتهت مهلة نافذة الاختيار. حاول مرة أخرى.",
                "isolated picker timeout mode=$mode $diagnostics"
            )
        }

        val output = stdout.toString()
        val errorOutput = stderr.toString()
        if (process.exitValue() != 0) {
            val diagnostics = diagnosticText("exit=${process.exitValue()}", errorOutput)
            DiagnosticLogger.error("isolated picker process error: mode=$mode $diagnostics")
            return DesktopChooserResult.Failed(
                "تعذر فتح نافذة الاختيار. حاول مرة أخرى.",
                "isolated picker mode=$mode $diagnostics"
            )
        }

        val selected = when (val parsed = parseIsolatedPickerOutput(output)) {
            IsolatedPickerOutput.Cancelled -> {
                DiagnosticLogger.info("isolated picker cancelled: mode=$mode")
                return DesktopChooserResult.Cancelled
            }
            is IsolatedPickerOutput.Invalid -> {
                DiagnosticLogger.error("isolated picker invalid output: ${parsed.diagnostic}")
                return DesktopChooserResult.Failed(
                    "تعذر قراءة نتيجة نافذة الاختيار. حاول مرة أخرى.",
                    "isolated picker invalid output: ${parsed.diagnostic}"
                )
            }
            is IsolatedPickerOutput.Selected -> File(parsed.path)
        }

        val canonical = runCatching { selected.canonicalFile }.getOrNull()
        val validationError = canonical?.let { pickerSelectionError(mode, it) }
            ?: "selected path could not be canonicalized"
        if (validationError != null || canonical == null) {
            val technical = "isolated picker selected invalid ${mode.name.lowercase()} output: $validationError"
            DiagnosticLogger.error(technical)
            return DesktopChooserResult.Failed(
                when (mode) {
                    DesktopChooserMode.DIRECTORY -> "اختر مجلدًا موجودًا وقابلًا للقراءة."
                    DesktopChooserMode.FILES -> "اختر ملف ZIP موجودًا وقابلًا للقراءة."
                },
                technical
            )
        }

        DiagnosticLogger.info(
            "isolated picker selected: mode=$mode name=${canonical.name.take(160)}"
        )
        return DesktopChooserResult.Selected(canonical)
    }

    private fun diagnosticText(prefix: String, stderr: String): String {
        val home = System.getProperty("user.home").orEmpty()
        val safe = stderr
            .replace("\u0000", "")
            .replace("\r", " ")
            .replace("\n", " ")
            .let { if (home.isBlank()) it else it.replace(home, "[USER_HOME]", ignoreCase = true) }
            .take(2_000)
        return if (safe.isBlank()) prefix else "$prefix stderr=$safe"
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)

    private class BoundedPickerOutput(private val maxChars: Int) {
        private val value = StringBuilder()

        @Synchronized
        fun append(line: String) {
            if (value.length >= maxChars) return
            value.append(line.take((maxChars - value.length).coerceAtLeast(0))).append('\n')
        }

        @Synchronized
        override fun toString(): String = value.toString()
    }
}