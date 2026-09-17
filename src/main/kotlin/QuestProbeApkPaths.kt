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
    val error: String? = null,
    /** The ordered read-only source which supplied the accepted paths. */
    val source: QuestProbeApkPathSource = QuestProbeApkPathSource.PM_PATH
) {
    val accepted: Boolean get() = error == null
    val isEmpty: Boolean get() = paths.isEmpty()
    val rawLineCount: Int get() = diagnostics.rawLineCount
    val validApkLineCount: Int get() = diagnostics.validApkLineCount
    val uniqueApkCount: Int get() = diagnostics.uniqueApkCount
    val limit: Int get() = diagnostics.limit
}

enum class QuestProbeApkPathSource {
    PM_PATH,
    CMD_PACKAGE_PATH,
    DUMPSYS_CODE_PATH,
    DUMPSYS_SOURCE_DIR
}

object QuestProbeApkPaths {
    const val DEFAULT_LIMIT: Int = 128
    private val SAFE_PATH = Regex("""/(?:data/app|system|product|vendor)/[A-Za-z0-9._~+=@/-]+\.apk""")
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

    fun normalizeDirect(
        values: List<String>,
        source: QuestProbeApkPathSource,
        limit: Int = DEFAULT_LIMIT
    ): QuestProbeApkPathResult = normalize(values, limit, allowBarePaths = true, source = source)

    fun normalize(values: List<String>, limit: Int = DEFAULT_LIMIT): QuestProbeApkPathResult =
        normalizeDirect(values, limit)

    fun normalizeLines(
        lines: Iterable<String>,
        limit: Int = DEFAULT_LIMIT
    ): QuestProbeApkPathResult = normalize(lines.toList(), limit, allowBarePaths = false)

    /**
     * Extracts only package-manager APK fields from a dumpsys package result.
     * This is intentionally not a general path search: paths are accepted only
     * from known fields and split APKs are constrained to the selected package's
     * codePath directory.
     */
    fun fromDumpsys(
        packageId: String,
        dumpsys: String,
        limit: Int = DEFAULT_LIMIT
    ): QuestProbeApkPathResult {
        require(packageId.matches(Regex("""[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+"""))) {
            "invalid package id"
        }
        val codePath = Regex("""(?m)^\s*codePath=([^\s]+)""").find(dumpsys)?.groupValues?.get(1)
            ?.takeIf(::isSafeDirectory)
        val values = mutableListOf<String>()
        val sources = mutableListOf<QuestProbeApkPathSource>()
        fun field(name: String, source: QuestProbeApkPathSource) {
            Regex("""(?m)^\s*(?:$name)\s*[:=]\s*([^\s]+)""").findAll(dumpsys).forEach { match ->
                val value = match.groupValues[1]
                if (isSafeApkPath(value) && (codePath == null || value.startsWith(codePath.trimEnd('/') + "/") || value == codePath)) {
                    values += value
                    sources += source
                }
            }
        }
        field("codePath", QuestProbeApkPathSource.DUMPSYS_CODE_PATH)
        field("resourcePath", QuestProbeApkPathSource.DUMPSYS_CODE_PATH)
        field("path", QuestProbeApkPathSource.DUMPSYS_CODE_PATH)
        field("sourceDir", QuestProbeApkPathSource.DUMPSYS_SOURCE_DIR)
        field("publicSourceDir", QuestProbeApkPathSource.DUMPSYS_SOURCE_DIR)
        Regex("""(?m)^\s*splitSourceDirs\s*[:=]\s*(.+)$""").findAll(dumpsys).forEach { match ->
            match.groupValues[1].split(',').map(String::trim).forEach { value ->
                if (isSafeApkPath(value) && (codePath == null || value.startsWith(codePath.trimEnd('/') + "/"))) {
                    values += value
                    sources += QuestProbeApkPathSource.DUMPSYS_SOURCE_DIR
                }
            }
        }
        val result = normalizeDirect(values, QuestProbeApkPathSource.DUMPSYS_CODE_PATH, limit)
        val preferred = sources.firstOrNull()
        return result.copy(source = preferred ?: QuestProbeApkPathSource.DUMPSYS_CODE_PATH)
    }

    private fun isSafeDirectory(path: String): Boolean =
        path.startsWith("/") && !SHELL_OR_CONTROL.containsMatchIn(path) &&
            path.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." }

    private fun normalize(
        rawValues: List<String>,
        limit: Int,
        allowBarePaths: Boolean,
        source: QuestProbeApkPathSource = QuestProbeApkPathSource.PM_PATH
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
        return QuestProbeApkPathResult(paths, diagnostics, error, source)
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