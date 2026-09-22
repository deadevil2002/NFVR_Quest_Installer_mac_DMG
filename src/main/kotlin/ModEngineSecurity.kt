import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.text.Normalizer
import java.util.Locale

/**
 * ZIP entry normalisation shared by analysis and extraction.  A path such as
 * `Author.Mod/../Author.Mod/file` is safe because its resolved destination is
 * still inside the extraction root; a path escaping that root is rejected.
 */
object ModArchivePath {
    const val MAX_ENTRY_NAME_BYTES = 1_000

    fun normalize(raw: String, extractionRoot: Path? = null): String {
        require(raw.isNotBlank()) { "empty entry name" }
        require(!raw.contains('\u0000')) { "NUL byte in entry name" }
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= MAX_ENTRY_NAME_BYTES) {
            "entry name exceeds the safe limit"
        }

        val slashPath = raw.replace('\\', '/')
        require(!slashPath.startsWith("/")) { "absolute entry path" }
        require(!slashPath.startsWith("//")) { "UNC entry path" }
        require(!Regex("^[A-Za-z]:($|/)").containsMatchIn(slashPath)) {
            "drive-qualified entry path"
        }

        val root = extractionRoot ?: java.nio.file.Paths.get(".").toAbsolutePath().normalize()
        val resolved = root.resolve(slashPath).normalize()
        require(resolved.startsWith(root)) { "entry escapes extraction root" }

        val relative = root.relativize(resolved).toString().replace('\\', '/')
        // A root-only directory marker (for example "./") has no payload. It
        // is safe and represented as an empty normalised name.
        if (relative.isBlank()) return ""
        require(relative.split('/').none { it.isBlank() }) { "empty path component" }
        return relative
    }

    fun collisionKey(path: String): String =
        Normalizer.normalize(path.replace('\\', '/'), Normalizer.Form.NFC)
            .split('/')
            .filter { it.isNotBlank() && it != "." }
            .map { it.trimEnd(' ', '.') }
            .joinToString("/")
            .lowercase(Locale.ROOT)
}

fun isSafeModRelativePath(value: String): Boolean =
    runCatching {
        val normalized = ModArchivePath.normalize(value)
        normalized.isNotBlank() &&
            normalized.split('/').none { it.isBlank() || it == "." || it == ".." } &&
            normalized.none { it == '\u0000' || it == ';' || it == '|' || it == '`' }
    }.getOrDefault(false)

/**
 * Quotes one remote path for `adb shell` transport.  adb joins shell args
 * with spaces and the device re-parses them, so an unquoted path is shell
 * code: `$vars` expand, spaces split, `;|&`` etc. execute.  Single-quoting
 * makes every path arrive as one literal word (embedded quotes use the
 * POSIX `'\''` idiom).  Only remote PATHS are quoted; flags, package IDs,
 * and other safe-charset tokens stay bare.
 */
fun shellQuoteRemotePath(path: String): String =
    "'" + path.replace("'", "'\\''") + "'"

/** Inverse of [shellQuoteRemotePath] for fakes and diagnostics. */
fun shellUnquoteRemotePath(quoted: String): String {
    if (quoted.length >= 2 && quoted.startsWith("'") && quoted.endsWith("'")) {
        return quoted.substring(1, quoted.length - 1).replace("'\\''", "'")
    }
    return quoted
}

/**
 * The only package-bound Quest roots accepted by analysis and execution.
 * Keeping this policy shared prevents a plan from being approved by one
 * phase and rejected/expanded by another.
 */
object ModDestinationPolicy {
    fun packageRoots(packageId: String): List<String> = listOf(
        "/sdcard/Android/data/$packageId/",
        "/sdcard/Android/obb/$packageId/",
        "/sdcard/ModData/$packageId/"
    )

    fun isPackageBound(path: String, packageId: String): Boolean =
        packageRoots(packageId).any(path::startsWith)

    fun loaderRoots(packageId: String, loader: ModLoaderKind?): List<String> =
        when (loader) {
            ModLoaderKind.QUEST_LOADER -> listOf(
                "/sdcard/Android/data/$packageId/files/mods/",
                "/sdcard/Android/data/$packageId/files/libs/"
            )
            ModLoaderKind.SCOTLAND2 -> listOf(
                "/sdcard/ModData/$packageId/Modloader/early_mods/",
                "/sdcard/ModData/$packageId/Modloader/mods/",
                "/sdcard/ModData/$packageId/Modloader/libs/"
            )
            else -> emptyList()
        }

    fun isLoaderRoot(path: String, packageId: String, loader: ModLoaderKind?): Boolean =
        loaderRoots(packageId, loader).any(path::startsWith)
}
