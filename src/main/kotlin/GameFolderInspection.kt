import java.io.File
import java.security.MessageDigest

enum class GameFolderKind {
    APK_ONLY,
    APK_WITH_OBB,
    SPLIT_APK,
    INVALID
}

/**
 * Immutable snapshot of one OBB selected for transfer.  `file` is canonical
 * at inspection time and `relativePath` binds it to the selected game folder.
 * The installer revalidates both before every device write.
 */
data class AllowedObbFile(
    val file: File,
    val relativePath: String,
    val packageName: String,
    val sizeBytes: Long,
    val sha256: String
) {
    val remoteDirectory: String
        get() = "/sdcard/Android/obb/$packageName"
    val remoteFileName: String
        get() = file.name
}

/**
 * Result of inspecting one user-selected game directory.  Only a single APK
 * with a readable package identity is installable.  OBB files are selected
 * by their package-bearing names, not by a directory name; metadata next to
 * valid OBBs is therefore harmless and is never transferred.
 */
data class GameFolderInspection(
    val folder: File,
    val kind: GameFolderKind,
    val apk: File? = null,
    val allowedObbFiles: List<AllowedObbFile> = emptyList(),
    val obbDirs: List<File> = emptyList(),
    val packageName: String? = null,
    val requiredBytes: Long = 0L,
    val reason: String? = null
) {
    val installable: Boolean
        get() = kind == GameFolderKind.APK_ONLY || kind == GameFolderKind.APK_WITH_OBB
}

/**
 * Performs local-only game folder detection.  It deliberately does not treat
 * every child directory as OBB: unrelated metadata and tools are ignored,
 * while ambiguous split APK input is surfaced explicitly.
 */
fun inspectGameFolder(folder: File): GameFolderInspection {
    val selectedFolder = runCatching { folder.canonicalFile }.getOrDefault(folder.absoluteFile)
    if (!selectedFolder.isDirectory || !selectedFolder.canRead()) {
        return GameFolderInspection(
            folder = selectedFolder,
            kind = GameFolderKind.INVALID,
            reason = "مجلد اللعبة غير موجود أو غير قابل للقراءة."
        )
    }

    val children = selectedFolder.listFiles()?.toList().orEmpty()
    val apks = children
        .filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    if (apks.isEmpty()) {
        return GameFolderInspection(
            folder = selectedFolder,
            kind = GameFolderKind.INVALID,
            reason = "لم يتم العثور على ملف APK في مجلد اللعبة."
        )
    }
    if (apks.size > 1) {
        return GameFolderInspection(
            folder = selectedFolder,
            kind = GameFolderKind.SPLIT_APK,
            reason = "المجلد يحتوي ${apks.size} ملفات APK؛ يبدو أنه يستخدم split APKs، ولن يتم اختيار APK عشوائي."
        )
    }

    val apk = apks.single()
    if (!apk.canRead() || apk.length() <= 0L) {
        return GameFolderInspection(
            folder = selectedFolder,
            kind = GameFolderKind.INVALID,
            apk = apk,
            reason = "ملف APK غير قابل للقراءة أو فارغ."
        )
    }
    val packageName = readApkPackageName(apk)
    if (packageName.isNullOrBlank()) {
        return GameFolderInspection(
            folder = selectedFolder,
            kind = GameFolderKind.INVALID,
            apk = apk,
            reason = "تعذر قراءة package identity من ملف APK."
        )
    }

    val allowedObbFiles = children
        .asSequence()
        .flatMap { child ->
            if (child.isDirectory) child.walkTopDown()
            else sequenceOf(child)
        }
        .filter { it.isFile && it.length() > 0L }
        .mapNotNull { file ->
            val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@mapNotNull null
            if (!isMatchingObbName(canonical.name, packageName)) return@mapNotNull null
            if (canonicalDescendant(selectedFolder, canonical) == null) return@mapNotNull null
            val relative = runCatching {
                selectedFolder.toPath().toAbsolutePath().normalize()
                    .relativize(canonical.toPath().toAbsolutePath().normalize())
                    .toString()
                    .replace(File.separatorChar, '/')
            }.getOrNull() ?: return@mapNotNull null
            val sizeBytes = canonical.length()
            val sha256 = sha256ObbFile(canonical) ?: return@mapNotNull null
            AllowedObbFile(
                file = canonical,
                relativePath = relative,
                packageName = packageName,
                sizeBytes = sizeBytes,
                sha256 = sha256
            )
        }
        .distinctBy { it.file.absolutePath }
        .sortedBy { it.relativePath }
        .toList()
    val matchingObbDirs = allowedObbFiles
        .mapNotNull { it.file.parentFile?.canonicalFile }
        .distinctBy { it.absolutePath }
    val requiredBytes = apk.length() + allowedObbFiles.sumOf { it.sizeBytes }
    val withObb = allowedObbFiles.isNotEmpty()
    return GameFolderInspection(
        folder = selectedFolder,
        kind = if (withObb) GameFolderKind.APK_WITH_OBB else GameFolderKind.APK_ONLY,
        apk = apk,
        allowedObbFiles = allowedObbFiles,
        obbDirs = matchingObbDirs,
        packageName = packageName,
        requiredBytes = requiredBytes
    )
}

internal fun isMatchingObbName(name: String, packageName: String): Boolean =
    Regex(
        "^(main|patch)\\.[0-9]+\\.${Regex.escape(packageName)}\\.obb$",
        RegexOption.IGNORE_CASE
    ).matches(name)

internal fun canonicalDescendant(root: File, candidate: File): File? {
    val rootPath = runCatching {
        root.canonicalFile.toPath().toAbsolutePath().normalize()
    }.getOrNull() ?: return null
    val candidatePath = runCatching {
        candidate.canonicalFile.toPath().toAbsolutePath().normalize()
    }.getOrNull() ?: return null
    return candidatePath
        .takeIf { it != rootPath && it.startsWith(rootPath) }
        ?.toFile()
}

internal fun sha256ObbFile(file: File): String? = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}.getOrNull()

/**
 * Revalidates an immutable allowlist item against the folder immediately
 * before an ADB write.  A changed, moved, replaced, or escaped file is an
 * installation error rather than an item to silently skip.
 */
fun revalidateAllowedObbFile(
    folder: File,
    allowed: AllowedObbFile
): File? {
    val root = runCatching { folder.canonicalFile }.getOrNull() ?: return null
    val current = runCatching { allowed.file.canonicalFile }.getOrNull() ?: return null
    if (canonicalDescendant(root, current) == null) return null
    if (!current.isFile || !current.canRead() || current.length() <= 0L) return null
    if (current.name != allowed.file.name ||
        !isMatchingObbName(current.name, allowed.packageName) ||
        current.length() != allowed.sizeBytes
    ) return null
    val rootPath = root.toPath().toAbsolutePath().normalize()
    val currentPath = current.toPath().toAbsolutePath().normalize()
    if (!currentPath.startsWith(rootPath)) return null
    val relative = runCatching {
        rootPath.relativize(currentPath).toString().replace(File.separatorChar, '/')
    }.getOrNull()
        ?: return null
    if (relative != allowed.relativePath) return null
    if (sha256ObbFile(current) != allowed.sha256) return null
    return current
}

fun validateAllowedObbFiles(
    folder: File,
    allowedFiles: List<AllowedObbFile>,
    packageName: String? = allowedFiles.firstOrNull()?.packageName
): List<String> {
    val invalidAllowlistItems = allowedFiles.mapNotNull { allowed ->
        if (revalidateAllowedObbFile(folder, allowed) == null) allowed.relativePath else null
    }
    val root = runCatching { folder.canonicalFile }.getOrNull()
    val escapedMatching = mutableListOf<String>()
    val currentMatching = if (root == null || packageName.isNullOrBlank()) {
        emptySet()
    } else {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        root.walkTopDown()
            .filter { it.isFile && it.length() > 0L && isMatchingObbName(it.name, packageName) }
            .mapNotNull { file ->
                val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@mapNotNull null
                val canonicalPath = canonical.toPath().toAbsolutePath().normalize()
                if (!canonicalPath.startsWith(rootPath)) {
                    escapedMatching += file.name
                    return@mapNotNull null
                }
                runCatching {
                    rootPath.relativize(canonicalPath).toString().replace(File.separatorChar, '/')
                }.getOrNull()
            }
            .toSet()
    }
    val allowedPaths = allowedFiles.mapTo(mutableSetOf()) { it.relativePath }
    return invalidAllowlistItems +
        escapedMatching.map { "ملف OBB مطابق يهرب خارج مجلد اللعبة: $it" } +
        currentMatching
            .filterNot { it in allowedPaths }
            .map { "ملف OBB مطابق جديد لم يكن ضمن الفحص: $it" }
}