import org.json.JSONObject
import java.io.File
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Reads a ZIP central directory and bounded metadata without extracting it.
 *
 * This class intentionally does not choose an install destination.  A ZIP
 * with a wrapper, several real roots, dependencies, or another ZIP inside it
 * is represented honestly so a game-specific analyzer can make (or refuse)
 * the next decision.  Nested archives are never flattened automatically.
 */
object QuestModArchiveNormalizer {
    const val MAX_ENTRIES = 5_000
    const val MAX_ENTRY_NAME_BYTES = 1_000
    const val MAX_METADATA_BYTES = 1L * 1024L * 1024L

    private val ROOT_METADATA_NAMES = setOf(
        "mod.json",
        "nfvr-mod.json",
        "qmod.json",
        "package.json",
        "manifest.json",
        "module.json",
        "catalog.json"
    )

    class UnsafeArchiveException(message: String) : Exception(message)

    fun normalize(archive: File): QuestModArchiveTree {
        if (!archive.isFile) {
            throw UnsafeArchiveException("Selected mod archive does not exist.")
        }

        val entries = mutableListOf<String>()
        val files = mutableListOf<String>()
        val directories = mutableListOf<String>()
        val sizes = linkedMapOf<String, Long>()
        val metadata = linkedMapOf<String, JSONObject>()
        val dependencyPaths = mutableListOf<String>()
        val nestedArchives = mutableListOf<String>()
        val collisionKeys = mutableSetOf<String>()

        try {
            ZipFile(archive).use { zip ->
                val iterator = zip.entries()
                while (iterator.hasMoreElements()) {
                    if (entries.size >= MAX_ENTRIES) {
                        throw UnsafeArchiveException(
                            "ZIP contains more than $MAX_ENTRIES entries."
                        )
                    }
                    val entry = iterator.nextElement()
                    val normalized = normalizeEntryName(entry.name)
                    if (normalized.isBlank()) continue
                    if (!collisionKeys.add(ModArchivePath.collisionKey(normalized))) {
                        throw UnsafeArchiveException(
                            "ZIP contains a normalized path collision at '$normalized'."
                        )
                    }
                    val size = entry.size.coerceAtLeast(0L)
                    entries += normalized
                    sizes[normalized] = size
                    if (entry.isDirectory) {
                        directories += normalized
                        continue
                    }
                    files += normalized
                    val lower = normalized.lowercase(Locale.ROOT)
                    if (lower.endsWith(".zip")) nestedArchives += normalized
                    if (isDependencyPath(normalized)) dependencyPaths += normalized

                    if (isMetadataPath(normalized) && size <= MAX_METADATA_BYTES) {
                        readJson(zip, entry, normalized)?.let { metadata[normalized] = it }
                    }
                }
            }
        } catch (e: UnsafeArchiveException) {
            throw e
        } catch (e: Exception) {
            throw UnsafeArchiveException(
                "Unable to inspect ZIP central directory: ${e.message ?: "invalid archive"}."
            )
        }

        val rootLevelFiles = files.filter { '/' !in it }
        val metadataTopLevel = rootLevelFiles
            .filter(::isMetadataPath)
            .toSet()
        val directoryRoots = files.asSequence()
            .filter { '/' in it }
            .map { it.substringBefore('/') }
            .filter(String::isNotBlank)
            .toSet()
        val hasRootPayload = rootLevelFiles.any { it !in metadataTopLevel }
        val actualRoots = directoryRoots - metadataTopLevel
        val wrapper = when {
            !hasRootPayload && actualRoots.size == 1 -> actualRoots.single()
            else -> null
        }
        val roots = buildList {
            if (hasRootPayload) {
                add(
                    QuestModArchiveRoot(
                        path = "",
                        kind = QuestModArchiveRootKind.MOD_ROOT,
                        fileCount = rootLevelFiles.count { it !in metadataTopLevel },
                        totalBytes = rootLevelFiles
                            .filter { it !in metadataTopLevel }
                            .sumOf { sizes[it] ?: 0L }
                    )
                )
            }
            addAll(directoryRoots.sorted().map { root ->
            val rootFiles = files.filter { it == root || it.startsWith("$root/") }
            val rootLower = root.lowercase(Locale.ROOT)
            val kind = when {
                root in metadataTopLevel -> QuestModArchiveRootKind.PACKAGING_METADATA
                rootFiles.any { it.lowercase(Locale.ROOT).endsWith(".zip") } ->
                    QuestModArchiveRootKind.NESTED_ARCHIVE
                rootFiles.any(::isDependencyPath) -> QuestModArchiveRootKind.DEPENDENCY
                rootLower.isNotBlank() -> QuestModArchiveRootKind.MOD_ROOT
                else -> QuestModArchiveRootKind.UNKNOWN
            }
            QuestModArchiveRoot(
                path = root,
                kind = kind,
                fileCount = rootFiles.size,
                totalBytes = rootFiles.sumOf { sizes[it] ?: 0L }
            )
            })
            if (metadataTopLevel.isNotEmpty()) {
                addAll(metadataTopLevel.sorted().map { path ->
                    QuestModArchiveRoot(
                        path = path,
                        kind = QuestModArchiveRootKind.PACKAGING_METADATA,
                        fileCount = 1,
                        totalBytes = sizes[path] ?: 0L
                    )
                })
            }
        }

        return QuestModArchiveTree(
            entries = entries.toList(),
            fileEntries = files.toList(),
            directoryEntries = directories.toList(),
            wrapperRoot = wrapper,
            roots = roots,
            metadata = metadata.toMap(),
            dependencyPaths = dependencyPaths.toList(),
            nestedArchivePaths = nestedArchives.toList(),
            multipleModRoots = actualRoots.size > 1 ||
                (actualRoots.isNotEmpty() && hasRootPayload)
        )
    }

    fun isMetadataPath(path: String): Boolean {
        val basename = path.substringAfterLast('/').lowercase(Locale.ROOT)
        return basename in ROOT_METADATA_NAMES ||
            basename.endsWith(".pallet.json") ||
            (basename.startsWith("catalog_") && basename.endsWith(".json")) ||
            basename.endsWith(".catalog.json") ||
            isDependencyPath(path)
    }

    private fun isDependencyPath(path: String): Boolean {
        val basename = path.substringAfterLast('/').lowercase(Locale.ROOT)
        return basename == "dependencies.json" ||
            basename == "dependency.json" ||
            basename.endsWith(".deps.json") ||
            path.lowercase(Locale.ROOT).contains("/dependencies/")
    }

    private fun normalizeEntryName(raw: String): String {
        if (raw.isBlank()) throw UnsafeArchiveException("ZIP contains an empty entry name.")
        if (raw.toByteArray(StandardCharsets.UTF_8).size > MAX_ENTRY_NAME_BYTES) {
            throw UnsafeArchiveException("ZIP entry name exceeds the safe limit.")
        }
        val slashPath = raw.replace('\\', '/')
        if (slashPath.startsWith("/") ||
            slashPath.startsWith("//") ||
            Regex("^[A-Za-z]:($|/)").containsMatchIn(slashPath)
        ) {
            throw UnsafeArchiveException("ZIP entry '$raw' is an absolute path.")
        }
        if (slashPath.split('/').any { it == "." || it == ".." }) {
            throw UnsafeArchiveException("ZIP entry '$raw' contains traversal components.")
        }
        return runCatching { ModArchivePath.normalize(slashPath) }.getOrElse {
            throw UnsafeArchiveException("ZIP entry '$raw' is unsafe: ${it.message}.")
        }
    }

    private fun readJson(zip: ZipFile, entry: java.util.zip.ZipEntry, path: String): JSONObject? {
        return runCatching {
            val bytes = zip.getInputStream(entry).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (output.size().toLong() + read > MAX_METADATA_BYTES) return@runCatching null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
            if (bytes.size >= 3 &&
                bytes[0] == 0xef.toByte() &&
                bytes[1] == 0xbb.toByte() &&
                bytes[2] == 0xbf.toByte()
            ) return@runCatching null
            val text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
            JSONObject(text)
        }.getOrNull()
    }
}