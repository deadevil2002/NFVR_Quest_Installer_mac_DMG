/**
 * Pure, transport-independent normalization for `pm path` output.
 *
 * The probe receives this output through more than one adapter.  Keeping the
 * parser here means that an adapter cannot accidentally turn warnings (or a
 * shell prompt) into APK inventory entries.
 */
data class QuestProbeApkPathDiagnostics(
    val rawLineCount: Int,
    val validApkLineCount: Int,
    val uniqueApkCount: Int,
    val limit: Int = QuestProbeApkPaths.DEFAULT_LIMIT,
    val zeroApkCount: Boolean = uniqueApkCount == 0
) {
    val rawCount: Int get() = rawLineCount
    val validCount: Int get() = validApkLineCount
    val uniqueCount: Int get() = uniqueApkCount
    val limitExceeded: Boolean get() = uniqueApkCount > limit
}

data class QuestProbeApkPathResult(
    val paths: List<String>,
    val diagnostics: QuestProbeApkPathDiagnostics,
    val error: String? = null
) {
    val accepted: Boolean get() = error == null
    val isEmpty: Boolean get() = paths.isEmpty()
    val rawLineCount: Int get() = diagnostics.rawLineCount
    val validApkLineCount: Int get() = diagnostics.validApkLineCount
    val uniqueApkCount: Int get() = diagnostics.uniqueApkCount
    val limit: Int get() = diagnostics.limit
}

object QuestProbeApkPaths {
    const val DEFAULT_LIMIT: Int = 128
    private val SAFE_PATH = Regex("""/data/app/[A-Za-z0-9._~+=@/-]+\.apk""")
    private val SHELL_OR_CONTROL = Regex("""[\u0000-\u001f\u007f;|&${'$'}`(){}\[\]<>"'\\*?!]""")

    /**
     * Parses raw stdout.  A valid line must have exactly one `package:`
     * prefix; no attempt is made to extract a path from surrounding noise.
     */
    fun parse(output: String, limit: Int = DEFAULT_LIMIT): QuestProbeApkPathResult =
        normalize(
            output.lineSequence().toList().let { lines ->
                if (output.endsWith("\n") || output.endsWith("\r")) lines.dropLast(1) else lines
            },
            limit,
            allowBarePaths = false
        )

    fun normalize(output: String, limit: Int = DEFAULT_LIMIT): QuestProbeApkPathResult =
        parse(output, limit)

    /**
     * Normalizes values returned by a transport adapter.  Adapters commonly
     * return already-separated paths, while a conservative adapter may still
     * return `package:` lines.  Both forms go through the same path validator.
     */
    fun normalizeDirect(
        values: List<String>,
        limit: Int = DEFAULT_LIMIT
    ): QuestProbeApkPathResult = normalize(values, limit, allowBarePaths = true)

    fun normalize(values: List<String>, limit: Int = DEFAULT_LIMIT): QuestProbeApkPathResult =
        normalizeDirect(values, limit)

    fun normalizeLines(
        lines: Iterable<String>,
        limit: Int = DEFAULT_LIMIT
    ): QuestProbeApkPathResult = normalize(lines.toList(), limit, allowBarePaths = false)

    private fun normalize(
        rawValues: List<String>,
        limit: Int,
        allowBarePaths: Boolean
    ): QuestProbeApkPathResult {
        require(limit > 0) { "APK path limit must be positive" }
        val unique = linkedSetOf<String>()
        var valid = 0
        rawValues.forEach { raw ->
            val value = raw.trim()
            val candidate = when {
                value.startsWith("package:") -> value.removePrefix("package:")
                allowBarePaths -> value
                else -> null
            }?.trim()
            if (candidate != null && isSafeApkPath(candidate)) {
                valid++
                unique += candidate
            }
        }
        val paths = unique.toList()
        val diagnostics = QuestProbeApkPathDiagnostics(
            rawLineCount = rawValues.size,
            validApkLineCount = valid,
            uniqueApkCount = paths.size,
            limit = limit
        )
        val error = when {
            paths.isEmpty() -> "No APK paths returned"
            paths.size > limit -> "APK count exceeds safe limit"
            else -> null
        }
        return QuestProbeApkPathResult(paths, diagnostics, error)
    }

    fun isSafeApkPath(path: String): Boolean {
        if (path.isBlank() || path != path.trim() || !path.endsWith(".apk") ||
            !SAFE_PATH.matches(path) || SHELL_OR_CONTROL.containsMatchIn(path)
        ) return false
        val parts = path.split('/')
        return parts.firstOrNull() == "" &&
            parts.drop(1).none { it.isEmpty() || it == "." || it == ".." }
    }
}

fun parseQuestProbeApkPaths(
    output: String,
    limit: Int = QuestProbeApkPaths.DEFAULT_LIMIT
): QuestProbeApkPathResult = QuestProbeApkPaths.parse(output, limit)

fun normalizeQuestProbeApkPaths(
    paths: List<String>,
    limit: Int = QuestProbeApkPaths.DEFAULT_LIMIT
): QuestProbeApkPathResult = QuestProbeApkPaths.normalizeDirect(paths, limit)