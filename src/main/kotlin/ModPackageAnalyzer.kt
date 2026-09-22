import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

/**
 * Reads package metadata and builds a copy plan without extracting anything.
 * The copy operation remains the responsibility of a later, explicitly
 * authorized installer.
 */
class ModPackageAnalyzer(
    private val profileRegistry: GameModProfileRegistry = GameModProfileRegistry
) {
    companion object {
        const val BONELAB_PACKAGE_ID = "com.StressLevelZero.BONELAB"
        const val BLADE_AND_SORCERY_PACKAGE_ID = "com.Warpfrog.BladeAndSorcery"
        const val NOMAD_PACKAGE_ID = BLADE_AND_SORCERY_PACKAGE_ID
        const val GORILLA_TAG_PACKAGE_ID = "com.AnotherAxiom.GorillaTag"
        const val PAVLOV_PACKAGE_ID = "com.vankrupt.pavlov"
        const val MAX_ZIP_ENTRIES = 5_000
        const val MAX_ENTRY_NAME_BYTES = 1_000
        const val MAX_METADATA_BYTES = 1L * 1024L * 1024L
        /**
         * Maximum size of one standard-ZIP entry (0xFFFFFFFF bytes).
         * java.util.zip cannot address anything larger without ZIP64, which
         * NFVR rejects outright; inspection, extraction, transfer, and
         * verification all stream with Long sizes, so no smaller arbitrary
         * per-entry cap is technically justified.  Real Quest game assets
         * (for example Pavlov mod.io .pak files near 600MB) are legitimate.
         */
        const val MAX_ENTRY_BYTES = 0xFFFFFFFFL
        /**
         * Archive-bomb guard based on evidence instead of an arbitrary total.
         * Rejects only when expanded output is BOTH large (above
         * [EXPANSION_FLOOR_BYTES]) and disproportionate to the compressed
         * input (above [MAX_EXPANSION_RATIO]).  Legitimate pre-compressed
         * game content has a ratio near 1 and is never affected.
         */
        const val EXPANSION_FLOOR_BYTES = 512L * 1024L * 1024L
        const val MAX_EXPANSION_RATIO = 64.0
        private val ROOT_METADATA_NAMES = setOf("mod.json", "nfvr-mod.json", "qmod.json", "package.json")
        private val ROOT_MANIFEST_NAMES = ROOT_METADATA_NAMES
        private val CONTENT_METADATA_BASENAMES = setOf(
            "manifest.json", "module.json", "catalog.json", "pallet.json"
        )
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private val QMOD_METADATA_KEYS = setOf(
            "_QPVersion", "name", "id", "author", "version", "description", "coverImage",
            "porter", "isLibrary"
        )
        private val QMOD_IMPLEMENTED_KEYS = setOf(
            "packageId", "packageVersion", "modloader", "modFiles", "lateModFiles",
            "libraryFiles", "fileCopies", "copyExtensions", "dependencies",
            "porter"
        )
        private val QMOD_UNIMPLEMENTED_KEYS = setOf(
            "actions", "hooks", "postInstall",
            "patcher", "patching", "apkPatching", "requiresPatching",
            "requiredModLoader", "loader", "commands", "scripts", "shell",
            "powershell", "exec", "executable"
        )
        private val NFVR_ALLOWED_KEYS = setOf(
            "schemaVersion", "name", "version", "author", "targetPackageId",
            "supportedGameVersions", "minGameVersion", "maxGameVersion",
            "modType", "requiredModLoader", "files", "copies", "commands"
        )
        private val DANGEROUS_PAYLOAD_EXTENSIONS = setOf(
            ".apk", ".exe", ".com", ".bat", ".cmd", ".ps1", ".psm1",
            ".vbs", ".js", ".jse", ".jar", ".msi", ".scr", ".sh",
            ".py", ".pyc", ".rb", ".php", ".lua", ".class", ".dex",
            ".elf", ".wasm"
        )
        private val NATIVE_PAYLOAD_EXTENSIONS = setOf(".dll", ".so")
        private val PACKAGE_ID = Regex("""[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+""")
        private val PC_ONLY_ROOTS = setOf(
            "pc", "windows", "standalonewindows", "standalonewindows64",
            "standalone", "win64", "desktop"
        )
        private val QUEST_ROOTS = setOf(
            "android", "quest", "quest2", "quest3", "android64", "arm64"
        )
        private val SUPPORTED_QMOD_SCHEMA_VERSIONS = setOf(
            "0.1.0", "0.1.1", "0.1.2", "1.0.0", "1.1.0", "1.2.0"
        )
        private val SEMVER = Regex(
            """^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?$"""
        )
        private val QMOD_EXTENSION = Regex("""^[A-Za-z0-9][A-Za-z0-9_-]*$""")
    }

    fun analyze(
        zipFile: File,
        installedApp: InstalledQuestApp? = null,
        loaderDetection: ModLoaderDetection? = null,
        directoryDiscovery: ModDirectoryDiscovery? = null
    ): ModPackageAnalysis {
        var inspectedArchive: ArchiveMetadata? = null
        return try {
            val archive = inspectArchive(zipFile)
            inspectedArchive = archive
            val rootManifestCandidates = ROOT_MANIFEST_NAMES.filter(archive.metadata::containsKey)
            if (rootManifestCandidates.size > 1) {
                throw ModPackageException(
                    "ZIP contains multiple conflicting root manifests: ${rootManifestCandidates.joinToString()}."
                )
            }
            val rootMod = archive.metadata["mod.json"]
            val rootPackage = archive.metadata["package.json"]
            val rootNfvr = archive.metadata["nfvr-mod.json"]
            if (rootMod != null && archive.traversalEntries.isNotEmpty()) {
                throw ModPackageException(
                    "QMOD ZIP entries may not contain traversal components: " +
                        archive.traversalEntries.joinToString(", ")
                )
            }

            when {
                rootMod != null -> analyzeQmod(archive, rootMod, installedApp, loaderDetection)
                rootPackage != null && isVirtualStump(rootPackage) ->
                    analyzeVirtualStump(archive, rootPackage, installedApp)
                rootNfvr != null -> analyzeNfvr(archive, rootNfvr, installedApp, loaderDetection)
                isAndroidDataLayout(archive) ->
                    analyzeAndroidLayout(archive, installedApp, obb = false)
                isAndroidObbLayout(archive) ->
                    analyzeAndroidLayout(archive, installedApp, obb = true)
                isNomadPayload(archive, installedApp) ->
                    analyzeNomadPayload(archive, installedApp, loaderDetection, directoryDiscovery)
                isBonelabPayload(archive, installedApp) ->
                    analyzeBonelabPayload(archive, installedApp, loaderDetection, directoryDiscovery)
                isPavlovPayload(zipFile, archive) ->
                    analyzePavlovPayload(zipFile, archive, installedApp)
                isKnownGameProfilePayload(archive, installedApp) ->
                    analyzeKnownGameProfile(archive, installedApp, loaderDetection)
                directoryDiscovery != null && directoryDiscovery.existingCandidates.isNotEmpty() ->
                    analyzeExistingDirectoryProposal(
                        archive,
                        installedApp,
                        directoryDiscovery
                    )
                archive.entries.any(::looksLikeGenericModPayload) -> analyzeGeneric(archive, installedApp)
                else -> unknown(archive, installedApp)
            }
        } catch (e: ModPackageException) {
            e.diagnosticEntry?.let { entry ->
                DiagnosticLogger.error("ZIP rejected entry '$entry': ${e.message.orEmpty()}")
            }
            failed(
                e.message ?: "Package metadata is invalid.",
                e.diagnosticEntry,
                installedApp,
                inspectedArchive?.identity
            )
        } catch (e: Exception) {
            failed(
                "Unable to inspect ZIP metadata: ${e.message ?: "invalid archive"}.",
                installedApp = installedApp,
                archiveIdentity = inspectedArchive?.identity
            )
        }
    }

    /** More explicit spelling for callers that distinguish analysis from inspection. */
    fun inspect(
        zipFile: File,
        installedApp: InstalledQuestApp? = null,
        loaderDetection: ModLoaderDetection? = null,
        directoryDiscovery: ModDirectoryDiscovery? = null
    ): ModPackageAnalysis = analyze(zipFile, installedApp, loaderDetection, directoryDiscovery)

    private fun inspectArchive(zipFile: File): ArchiveMetadata {
        require(zipFile.isFile) { "Selected mod archive does not exist." }
        val normalizedTree = try {
            QuestModArchiveNormalizer.normalize(zipFile)
        } catch (e: QuestModArchiveNormalizer.UnsafeArchiveException) {
            throw ModPackageException(e.message ?: "ZIP archive is unsafe.")
        }
        val metadata = linkedMapOf<String, JSONObject>()
        val entries = mutableListOf<String>()
        val sizes = linkedMapOf<String, Long>()
        val hashes = linkedMapOf<String, String>()
        val prefixes = linkedMapOf<String, ByteArray>()
        val directories = linkedSetOf<String>()
        val traversalEntries = mutableListOf<String>()
        val collisionKeys = mutableSetOf<String>()
        var compressedBytes = 0L

        ZipFile(zipFile).use { zip ->
            rejectZip64OrUnsafeLinks(zipFile, zip)
            val iterator = zip.entries()
            var count = 0
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                count++
                if (count > MAX_ZIP_ENTRIES) {
                    throw ModPackageException("ZIP contains more than $MAX_ZIP_ENTRIES entries.")
                }
                if (hasTraversalComponent(entry.name)) traversalEntries += entry.name
                val safeName = validateEntryName(entry.name)
                // "./" is a valid directory marker but has no installable
                // path of its own.  Keep inspecting the rest of the archive.
                if (safeName.isBlank()) continue
                if (!collisionKeys.add(collisionKey(safeName))) {
                    throw ModPackageException(
                        "ZIP contains a normalized path collision at '$safeName'.",
                        entry.name
                    )
                }
                entries += safeName
                sizes[safeName] = entry.size.coerceAtLeast(0L)
                if (entry.isDirectory) directories += safeName
                if (entry.size > MAX_ENTRY_BYTES) {
                    throw ModPackageException("ZIP entry '$safeName' exceeds the safe size limit.")
                }
                // Compressed sizes feed the expansion-ratio bomb guard in
                // the streaming pass below.  Unknown sizes (-1) contribute
                // nothing; the guard is skipped when no compressed total
                // is known rather than guessing.
                if (!entry.isDirectory && entry.compressedSize > 0L) {
                    compressedBytes += entry.compressedSize
                }
                if (safeName in ROOT_METADATA_NAMES && entry.name != safeName) {
                    throw ModPackageException(
                        "ZIP manifest '$safeName' must use its exact root entry name.",
                        entry.name
                    )
                }
                if (!entry.isDirectory && entry.name == safeName && safeName in ROOT_METADATA_NAMES) {
                    if (metadata.containsKey(safeName)) {
                        throw ModPackageException("ZIP contains duplicate metadata entry '$safeName'.")
                    }
                    val content = readBoundedMetadata(zip, entry, safeName)
                    val json = try {
                        JSONObject(content)
                    } catch (e: Exception) {
                        throw ModPackageException("Metadata entry '$safeName' is not valid JSON.")
                    }
                    metadata[safeName] = json
                }
                // Game content manifests are not all rooted at a standard
                // NFVR/QMOD filename.  Parse only bounded, well-known JSON
                // basenames; arbitrary archive filenames remain data, not
                // evidence of a game or an installation destination.
                if (!entry.isDirectory &&
                    isContentMetadataName(safeName) &&
                    safeName !in metadata
                ) {
                    val content = runCatching {
                        readBoundedMetadata(zip, entry, safeName)
                    }.getOrNull()
                    val json = content?.let { runCatching { JSONObject(it) }.getOrNull() }
                    if (json != null) metadata[safeName] = json
                }
            }
            var actualBytes = 0L
            for (entry in zip.entries().asSequence()) {
                if (entry.isDirectory) continue
                val safeName = validateEntryName(entry.name)
                if (safeName.isBlank()) continue
                val digest = MessageDigest.getInstance("SHA-256")
                var bytes = 0L
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytes += read
                        actualBytes += read
                        if (bytes > MAX_ENTRY_BYTES) {
                            throw ModPackageException("ZIP entry '$safeName' expands beyond the safe size limit.")
                        }
                        if (expansionAbuse(actualBytes, compressedBytes)) {
                            throw ModPackageException(
                                "ZIP expansion ratio exceeds the safe archive-bomb limit " +
                                    "(${actualBytes} bytes from ${compressedBytes} compressed bytes)."
                            )
                        }
                        digest.update(buffer, 0, read)
                        if ((prefixes[safeName]?.size ?: 0) < 4_096) {
                            prefixes[safeName] = digestPrefix(prefixes[safeName], buffer, read)
                        }
                    }
                }
                sizes[safeName] = bytes
                hashes[safeName] = digest.digest().joinToString("") { "%02x".format(it) }
            }
        }
        val identity = ModArchiveIdentity(
            canonicalPath = zipFile.canonicalPath,
            sizeBytes = zipFile.length(),
            lastModifiedMillis = zipFile.lastModified(),
            sha256 = sha256File(zipFile)
        )
        return ArchiveMetadata(
            entries,
            metadata,
            sizes,
            hashes,
            directories,
            prefixes,
            identity,
            traversalEntries,
            normalizedTree
        )
    }

    private fun isContentMetadataName(path: String): Boolean {
        val basename = path.substringAfterLast('/').lowercase(Locale.ROOT)
        return basename in CONTENT_METADATA_BASENAMES ||
            basename.endsWith(".pallet.json") ||
            (basename.startsWith("catalog_") && basename.endsWith(".json")) ||
            basename.endsWith(".catalog.json")
    }

    private fun digestPrefix(existing: ByteArray?, buffer: ByteArray, read: Int): ByteArray {
        val old = existing ?: ByteArray(0)
        val remaining = (4_096 - old.size).coerceAtLeast(0)
        if (remaining == 0) return old
        return old + buffer.copyOfRange(0, minOf(read, remaining))
    }

    private fun readBoundedMetadata(zip: ZipFile, entry: java.util.zip.ZipEntry, name: String): String {
        val out = java.io.ByteArrayOutputStream()
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size().toLong() + read > MAX_METADATA_BYTES) {
                    throw ModPackageException("Metadata entry '$name' exceeds the safe inspection limit.")
                }
                out.write(buffer, 0, read)
            }
        }
        val bytes = out.toByteArray()
        if (bytes.size >= 3 &&
            bytes[0] == 0xef.toByte() &&
            bytes[1] == 0xbb.toByte() &&
            bytes[2] == 0xbf.toByte()
        ) {
            throw ModPackageException("Metadata entry '$name' must be UTF-8 JSON without a BOM.")
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: Exception) {
            throw ModPackageException("Metadata entry '$name' is not valid UTF-8.")
        }
    }

    /**
     * QMOD is defined as a regular PKWARE ZIP, not ZIP64, and archive entries
     * must be ordinary files/directories rather than filesystem links.  The
     * JDK ZIP API intentionally hides the central-directory attributes, so
     * inspect those bounded headers before accepting the archive.
     */
    private fun rejectZip64OrUnsafeLinks(zipFile: File, zip: ZipFile) {
        val iterator = zip.entries()
        var count = 0
        while (iterator.hasMoreElements()) {
            val entry = iterator.nextElement()
            count++
            if (count > MAX_ZIP_ENTRIES) {
                throw ModPackageException("ZIP contains more than $MAX_ZIP_ENTRIES entries.")
            }
            if (hasZip64Extra(entry.extra)) {
                throw ModPackageException("ZIP64 archives are not supported.")
            }
        }
        RandomAccessFile(zipFile, "r").use { file ->
            val tailLength = minOf(file.length(), 65_557L).toInt()
            val tail = ByteArray(tailLength)
            file.seek(file.length() - tailLength)
            file.readFully(tail)
            val eocd = findEndOfCentralDirectory(tail)
            if (eocd < 0) throw ModPackageException("ZIP end-of-central-directory record is missing.")
            // A ZIP64 locator or record immediately preceding the EOCD is
            // enough to reject the format; do not scan arbitrary payload bytes.
            val hasZip64Locator = eocd >= 20 &&
                readUnsignedInt(tail, eocd - 20) == 0x07064b50L
            val hasZip64Record = eocd >= 76 &&
                readUnsignedInt(tail, eocd - 76) == 0x06064b50L
            if (hasZip64Locator || hasZip64Record) {
                throw ModPackageException("ZIP64 archives are not supported.")
            }
            val totalEntries = readUnsignedShort(tail, eocd + 10)
            val centralOffset = readUnsignedInt(tail, eocd + 16)
            if (totalEntries == 0xffff || centralOffset == 0xffffffffL) {
                throw ModPackageException("ZIP64 archives are not supported.")
            }
            if (centralOffset >= file.length()) {
                throw ModPackageException("ZIP central directory is outside the archive.")
            }
            file.seek(centralOffset)
            repeat(totalEntries) {
                val header = ByteArray(46)
                file.readFully(header)
                if (readUnsignedInt(header, 0) != 0x02014b50L) {
                    throw ModPackageException("ZIP central directory is malformed.")
                }
                val versionMadeBy = readUnsignedShort(header, 4)
                val externalAttributes = readUnsignedInt(header, 38)
                val nameLength = readUnsignedShort(header, 28)
                val extraLength = readUnsignedShort(header, 30)
                val commentLength = readUnsignedShort(header, 32)
                if ((versionMadeBy ushr 8) == 3 &&
                    (externalAttributes ushr 16 and 0xf000L) == 0xa000L
                ) {
                    throw ModPackageException("ZIP symbolic links are not supported.")
                }
                file.skipBytes(nameLength + extraLength + commentLength)
            }
        }
    }

    private fun hasZip64Extra(extra: ByteArray?): Boolean {
        if (extra == null) return false
        var offset = 0
        while (offset + 4 <= extra.size) {
            val id = (extra[offset].toInt() and 0xff) or
                ((extra[offset + 1].toInt() and 0xff) shl 8)
            val length = (extra[offset + 2].toInt() and 0xff) or
                ((extra[offset + 3].toInt() and 0xff) shl 8)
            if (offset + 4 + length > extra.size) return true
            if (id == 0x0001) return true
            offset += 4 + length
        }
        return offset != extra.size
    }

    private fun hasTraversalComponent(raw: String): Boolean =
        raw.replace('\\', '/').split('/').any { it == "." || it == ".." }

    private fun findEndOfCentralDirectory(bytes: ByteArray): Int {
        for (index in bytes.size - 4 downTo 0) {
            if (readUnsignedInt(bytes, index) == 0x06054b50L &&
                index + 22 <= bytes.size &&
                index + 22 + readUnsignedShort(bytes, index + 20) == bytes.size
            ) {
                return index
            }
        }
        return -1
    }

    private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readUnsignedInt(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)

    private fun analyzeQmod(
        archive: ArchiveMetadata,
        json: JSONObject,
        installedApp: InstalledQuestApp?,
        loaderDetection: ModLoaderDetection?
    ): ModPackageAnalysis {
        val target = json.optString("packageId", "").trim().ifBlank { null }
        val profile = target?.let(profileRegistry::findByPackageId)
        val preconditions = mutableListOf<ModInstallPrecondition>()
        if (json.has("packageId") && json.opt("packageId") !is String) {
            preconditions += blocked(
                "INVALID_PACKAGE_ID",
                "QMOD packageId must be a JSON string."
            )
        }
        validateQmodSchema(json, preconditions)
        if (target == null) {
            preconditions += blocked("TARGET_PACKAGE_MISSING", "QMOD does not declare a target packageId.")
        } else if (!PACKAGE_ID.matches(target)) {
            preconditions += blocked("INVALID_PACKAGE_ID", "QMOD packageId must be a valid Android package identifier.")
        } else if (installedApp == null) {
            preconditions += blocked("TARGET_APP_REQUIRED", "Select the installed Quest app before installing this QMOD.")
        } else if (installedApp.packageName != target) {
            preconditions += blocked(
                "TARGET_PACKAGE_MISMATCH",
                "QMOD targets '$target', but the selected app is '${installedApp.packageName}'."
            )
        } else {
            preconditions += satisfied("TARGET_PACKAGE_MATCH", "QMOD target package matches the selected app.")
        }
        // Gorilla Tag has no current authoritative Quest loader/path contract.
        // Do not let its conservative classification become a guessed write:
        // An explicit loader declaration, or a canonical loader-root copy,
        // must be paired with authenticated QuestPatcher evidence before a
        // Gorilla destination is usable.
        val loaderValue = json.optString("modloader", "").trim()
        val isGorillaTag = target == GORILLA_TAG_PACKAGE_ID
        val hasCanonicalGorillaCopy = isGorillaTag && hasCanonicalGorillaLoaderCopy(json)
        if (isGorillaTag && loaderValue.isBlank() && !hasCanonicalGorillaCopy) {
            preconditions += blocked(
                "MOD_LOADER_REQUIRED",
                "Gorilla Tag Quest compatibility is not established; this QMOD must declare a loader or use an authenticated QuestLoader destination with read-only loader evidence."
            )
        }
        val loader = ModLoaderKind.parse(loaderValue)
        if (loaderValue.isNotEmpty() &&
            (loader == null || loader !in setOf(ModLoaderKind.QUEST_LOADER, ModLoaderKind.SCOTLAND2))
        ) {
            preconditions += blocked(
                "UNSUPPORTED_MOD_LOADER",
                "QMOD requires unsupported modloader '$loaderValue'."
            )
        }
        if (isGorillaTag && loader != null && loader != ModLoaderKind.QUEST_LOADER) {
            preconditions += blocked(
                "GORILLA_LOADER_COMPATIBILITY_UNCONFIRMED",
                "Scotland2 is not an established Gorilla Tag Quest compatibility contract; NFVR will not authorize its destination."
            )
        }
        // QMOD's schema default is QuestLoader even when a legacy manifest
        // omitted `_QPVersion` and `modloader`.  Never let the old spelling
        // bypass loader evidence or install into the profile's generic Mods
        // directory.
        val containsLoaderFiles = listOf("modFiles", "lateModFiles", "libraryFiles")
            .any { json.optJSONArray(it)?.length()?.let { length -> length > 0 } == true }
        val effectiveLoader = loader ?: if (containsLoaderFiles) ModLoaderKind.QUEST_LOADER else null
        val gorillaCopyNeedsQuestLoader = isGorillaTag &&
            effectiveLoader == null &&
            hasCanonicalGorillaCopy
        if (profile == null && target != null && effectiveLoader == null) {
            // Explicit loader-relative fields derive their standardized root
            // from target+loader; unregistered packages remain limited to
            // profile-bound fileCopies.
            val hasRelativeFiles = listOf("modFiles", "lateModFiles", "libraryFiles")
                .any { json.optJSONArray(it)?.length()?.let { length -> length > 0 } == true }
            if (hasRelativeFiles) {
                preconditions += blocked("UNKNOWN_GAME_PROFILE", "No vetted destination is registered for '$target'.")
            }
        }
        val requiresLoader = when {
            effectiveLoader != null -> setOf(effectiveLoader)
            gorillaCopyNeedsQuestLoader -> setOf(ModLoaderKind.QUEST_LOADER)
            else -> emptySet()
        }
        val loaderRequirement = loaderRequirement(
            requiresLoader,
            loaderDetection,
            preconditions,
            enforceWhenUnknown = requiresLoader.isNotEmpty(),
            targetPackageId = target
        )
        if (json.optJSONArray("lateModFiles")?.length()?.let { it > 0 } == true &&
            loader != ModLoaderKind.SCOTLAND2
        ) {
            preconditions += blocked(
                "UNSUPPORTED_LATE_MOD_FILES",
                "QMOD lateModFiles is supported only for Scotland2."
            )
        }
        checkQmodPackageVersion(json, installedApp, preconditions)
        if (json.has("dependencies") &&
            (json.opt("dependencies") == null || json.opt("dependencies") == JSONObject.NULL)
        ) {
            preconditions += blocked(
                "INVALID_DEPENDENCY",
                "QMOD dependencies must be a non-empty array or dependency object."
            )
        }
        val declaredDependencies = parseDependencies(
            json.opt("dependencies"),
            optional = false,
            preconditions = preconditions
        )
        val dependencies = declaredDependencies.filterNot { it.optional }
        val dependencyOptionals = declaredDependencies.filter { it.optional }
        if (dependencies.isNotEmpty()) {
            preconditions += blocked(
                "DEPENDENCIES_REQUIREMENT",
                "This QMOD declares dependencies; NFVR will not download or install dependencies automatically."
            )
        }
        if (dependencyOptionals.isNotEmpty()) {
            preconditions += blocked(
                "OPTIONAL_DEPENDENCIES_UNVERIFIED",
                "Optional QMOD dependencies cannot be verified because NFVR has no installed-mod inventory or range resolver."
            )
        }
        val patchRequirement = if (hasPatchingRequirement(json)) {
            preconditions += blocked(
                "APK_PATCH_REQUIRED",
                "This QMOD requires APK/game patching; NFVR will not patch the installed application."
            )
            ModPatchRequirement(
                required = true,
                reason = "QMOD declares an APK/game patching requirement."
            )
        } else null
        addStrictQmodSchemaPreconditions(json, preconditions)
        // Native QMOD payloads are checked below against the declared loader,
        // ARM64 ELF header, and canonical loader root.  Applying the generic
        // deny rule here as well would reject the valid, explicitly mapped
        // QuestLoader/Scotland2 library case.
        addDangerousPayloadPrecondition(archive, preconditions)

        val mappings = parseQmodMappings(
            archive,
            json,
            profile,
            target,
            effectiveLoader,
            preconditions
        )
        if (mappings.isEmpty()) {
            preconditions += blocked("NO_COPY_MAPPINGS", "QMOD contains no safe declarative files to copy.")
        }
        addQmodNativePayloadPreconditions(
            archive,
            json,
            mappings,
            effectiveLoader,
            target,
            preconditions
        )
        val plan = plan(
            type = ModPackageType.QMOD,
            target = target,
            profile = profile,
            mappings = mappings,
            strategy = ModInstallStrategy.DECLARATIVE_COPY,
            preconditions = preconditions,
            archiveIdentity = archive.identity,
            reviewedApp = installedApp,
            loaderRequirement = loaderRequirement,
            dependencies = dependencies,
            optionalDependencies = dependencyOptionals,
            patchRequirement = patchRequirement,
            resolution = resolution(
                ModResolutionStrategy.QMOD,
                100,
                "QMOD mod.json is an explicit declarative package manifest."
            )
        )
        return result(
            type = ModPackageType.QMOD,
            message = if (plan.installable) "Recognized QMOD with a safe copy plan." else blockingMessage(plan),
            plan = plan,
            archive = archive,
            metadata = json.toMap()
        )
    }

    private fun parseQmodMappings(
        archive: ArchiveMetadata,
        json: JSONObject,
        profile: GameModProfile?,
        target: String?,
        loader: ModLoaderKind?,
        preconditions: MutableList<ModInstallPrecondition>
    ): List<ModFileMapping> {
        val hasLoaderFields = listOf("modFiles", "lateModFiles", "libraryFiles")
            .any { json.optJSONArray(it)?.length()?.let { length -> length > 0 } == true }
        if (profile == null && json.opt("fileCopies") == null && !hasLoaderFields) return emptyList()
        val mappings = mutableListOf<ModFileMapping>()
        val copies = json.opt("fileCopies")
        if (copies != null && copies != JSONObject.NULL) {
            try {
                when (copies) {
                    is JSONArray -> {
                        for (i in 0 until copies.length()) {
                            val item = copies.getJSONObject(i)
                            val allowed = setOf("name", "destination")
                            if (item.keys().asSequence().any { it !in allowed } ||
                                !item.has("destination") ||
                                !item.has("name") ||
                                item.opt("name") !is String ||
                                item.opt("destination") !is String
                            ) {
                                preconditions += blocked(
                                    "INVALID_FILE_COPIES",
                                    "QMOD fileCopies requires string name and destination fields only."
                                )
                                continue
                            }
                            val source = item.getString("name")
                            val destination = item.getString("destination")
                            if (target == GORILLA_TAG_PACKAGE_ID &&
                                !isGorillaQuestLoaderDestination(destination)
                            ) {
                                preconditions += blocked(
                                    "GORILLA_QMOD_DESTINATION_UNAUTHORIZED",
                                    "Gorilla Tag QMOD fileCopies may target only authenticated QuestLoader mods or libs roots."
                                )
                                continue
                            }
                            addQmodMapping(
                                archive, profile, mappings, target, source, destination,
                                null, preconditions
                            )
                        }
                    }
                    is JSONObject -> preconditions += blocked(
                        "INVALID_FILE_COPIES",
                        "QMOD fileCopies must use the canonical array of name/destination records."
                    )
                    else -> preconditions += blocked("INVALID_FILE_COPIES", "QMOD fileCopies must be an array or object.")
                }
            } catch (e: Exception) {
                preconditions += blocked("INVALID_FILE_COPIES", "QMOD contains an invalid fileCopies mapping.")
            }
        }

        val listedFields = listOf("modFiles", "lateModFiles", "libraryFiles")
        for (field in listedFields) {
            if (!json.has(field)) continue
            val list = json.optJSONArray(field)
            if (list == null) {
                preconditions += blocked(
                    "INVALID_QMOD_MAPPING",
                    "QMOD $field must be an array of non-empty string source paths."
                )
                continue
            }
            for (i in 0 until list.length()) {
                val raw = list.opt(i)
                if (raw !is String || raw.trim().isEmpty()) {
                    preconditions += blocked(
                        "INVALID_QMOD_MAPPING",
                        "QMOD $field must contain only non-empty string source paths."
                    )
                } else {
                    val source = raw.trim()
                    val destinationRoot = qmodDestination(profile, target, loader, field)
                    if (destinationRoot == null) {
                        preconditions += blocked(
                            "DESTINATION_UNAVAILABLE",
                            "No safe destination is known for QMOD field '$field'."
                        )
                    } else {
                        val destinationName = source.substringAfterLast('/')
                        addMapping(
                            archive,
                            profile,
                            mappings,
                            source,
                            "${destinationRoot.trimEnd('/')}/$destinationName",
                            null,
                            preconditions,
                            allowFullDestination = true,
                            targetPackageId = target
                        )
                    }
                }
            }
        }
        parseQmodCopyExtensions(json, preconditions)
        // Never infer a destination or copy every archive entry.  QMODs must
        // explicitly declare modFiles, lateModFiles, libraryFiles, or
        // fileCopies.
        return mappings.distinctBy { it.sourcePath to it.destinationPath }
    }

    /**
     * Validation follows the published QuestPatcher schema versions rather
     * than treating every JSON object named mod.json as a QMOD.  Archives
     * without _QPVersion are retained as a deliberately limited legacy
     * compatibility path because older QuestLoader packages used that form.
     */
    private fun validateQmodSchema(
        json: JSONObject,
        preconditions: MutableList<ModInstallPrecondition>
    ) {
        val rawVersion = json.opt("_QPVersion")
        if (rawVersion == null || rawVersion == JSONObject.NULL) {
            preconditions += satisfied(
                "LEGACY_QMOD_FORMAT",
                "QMOD omits _QPVersion; legacy QuestLoader compatibility rules apply."
            )
        } else if (rawVersion !is String ||
            rawVersion.trim() !in SUPPORTED_QMOD_SCHEMA_VERSIONS
        ) {
            preconditions += blocked(
                "UNSUPPORTED_QMOD_VERSION",
                "QMOD _QPVersion must be one of ${SUPPORTED_QMOD_SCHEMA_VERSIONS.joinToString()}."
            )
            return
        } else {
            val required = listOf("name", "id", "author", "version")
            val missing = required.filter { key ->
                val value = json.opt(key)
                value !is String || value.trim().isEmpty()
            }
            if (missing.isNotEmpty()) {
                preconditions += blocked(
                    "MISSING_QMOD_FIELDS",
                    "QMOD ${rawVersion.trim()} is missing required fields: ${missing.joinToString()}."
                )
            }
            val version = json.optString("version", "").trim()
            if (version.isNotEmpty() && !SEMVER.matches(version)) {
                preconditions += blocked(
                    "INVALID_QMOD_VERSION",
                    "QMOD version must be a valid semantic version."
                )
            }
        }
        // Keep the legacy field-presence compatibility path, but never allow
        // malformed identity values through it.  Modern QMOD schemas require
        // all four values; every schema still treats id as a non-whitespace
        // identifier and version as semantic when supplied.
        val id = json.opt("id")
        if (id != null && id != JSONObject.NULL &&
            (id !is String || id.trim().isEmpty() || id.any { it.isWhitespace() })
        ) {
            preconditions += blocked(
                "INVALID_QMOD_ID",
                "QMOD id must be a non-empty identifier without whitespace."
            )
        }
        listOf("name", "author").forEach { key ->
            val value = json.opt(key)
            if (value != null && value != JSONObject.NULL &&
                (value !is String || value.trim().isEmpty())
            ) {
                preconditions += blocked(
                    "INVALID_QMOD_FIELD",
                    "QMOD $key must be a non-empty string."
                )
            }
        }
        if (json.has("version") && json.opt("version") !is String) {
            preconditions += blocked("INVALID_QMOD_VERSION", "QMOD version must be a semantic-version string.")
        }
        if (json.has("modloader") && json.opt("modloader") !is String) {
            preconditions += blocked("INVALID_QMOD_LOADER", "QMOD modloader must be a string.")
        }
        listOf("description", "coverImage", "porter").forEach { key ->
            if (json.has(key) && json.opt(key) !is String) {
                preconditions += blocked(
                    "INVALID_QMOD_FIELD",
                    "QMOD $key must be a string."
                )
            }
        }
        if (json.has("isLibrary") && json.opt("isLibrary") !is Boolean) {
            preconditions += blocked(
                "INVALID_QMOD_FIELD",
                "QMOD isLibrary must be a boolean."
            )
        }
        val contentFields = listOf(
            "modFiles", "lateModFiles", "libraryFiles", "dependencies", "fileCopies"
        )
        if (contentFields.none { json.has(it) }) {
            preconditions += blocked(
                "NO_QMOD_CONTENT_FIELDS",
                "QMOD must declare at least one of modFiles, lateModFiles, libraryFiles, dependencies, or fileCopies."
            )
        }
    }

    private fun parseQmodCopyExtensions(
        json: JSONObject,
        preconditions: MutableList<ModInstallPrecondition>
    ) {
        when (val raw = json.opt("copyExtensions")) {
            null, JSONObject.NULL -> return
            is JSONArray -> {
                if (raw.length() > 0) {
                    preconditions += blocked(
                        "COPY_EXTENSIONS_REGISTRATION_UNSUPPORTED",
                        "QMOD copyExtensions registers a QuestPatcher file destination; NFVR cannot persist that registration."
                    )
                }
                for (i in 0 until raw.length()) {
                    val item = raw.optJSONObject(i)
                    if (item == null ||
                        item.keys().asSequence().any { it !in setOf("extension", "destination") } ||
                        item.opt("extension") !is String ||
                        item.opt("destination") !is String ||
                        item.optString("extension").isBlank() ||
                        item.optString("destination").isBlank()
                    ) {
                        preconditions += blocked(
                            "INVALID_COPY_EXTENSIONS",
                            "QMOD copyExtensions requires extension and destination."
                        )
                        continue
                    }
                    val extension = item.getString("extension").trim()
                    val destination = item.getString("destination").trim().trimEnd('/')
                    if (!QMOD_EXTENSION.matches(extension) ||
                        extension.startsWith(".") ||
                        !AndroidPathValidator.isSafe(destination)
                    ) {
                        preconditions += blocked(
                            "INVALID_COPY_EXTENSIONS",
                            "QMOD copyExtensions requires a safe extension and destination."
                        )
                    }
                }
            }
            else -> preconditions += blocked(
                "INVALID_COPY_EXTENSIONS",
                "QMOD copyExtensions must be the canonical array of extension/destination records."
            )
        }
    }

    private fun parseDependencies(
        raw: Any?,
        optional: Boolean,
        preconditions: MutableList<ModInstallPrecondition>
    ): List<ModPackageDependency> {
        if (raw == null || raw == JSONObject.NULL) return emptyList()
        val result = mutableListOf<ModPackageDependency>()

        fun add(value: Any?, fallbackId: String? = null) {
            when (value) {
                is JSONObject -> {
                    val allowed = setOf("id", "version", "downloadIfMissing", "required")
                    if (value.keys().asSequence().any { it !in allowed }) {
                        preconditions += blocked(
                            "INVALID_DEPENDENCY",
                            "QMOD dependency objects contain unsupported fields."
                        )
                    }
                    val idValue = value.opt("id")
                    val versionValue = value.opt("version")
                    val downloadValue = value.opt("downloadIfMissing")
                    val id = (idValue as? String)
                        ?.trim()
                        .orEmpty()
                        .ifBlank { fallbackId.orEmpty() }
                    val version = (versionValue as? String)
                        ?.trim()
                        ?.ifBlank { null }
                    val sourceUrl = (downloadValue as? String)
                        ?.trim()
                        ?.ifBlank { null }
                    val requiredValue = if (value.has("required")) {
                        value.opt("required") as? Boolean
                    } else {
                        true
                    }
                    if (requiredValue == null) {
                        preconditions += blocked(
                            "INVALID_DEPENDENCY",
                            "QMOD dependency required must be a boolean."
                        )
                    } else if ((value.has("id") && idValue !is String) ||
                        (value.has("version") && versionValue !is String) ||
                        (value.has("downloadIfMissing") && downloadValue !is String) ||
                        id.isBlank() ||
                        id.any { it.isWhitespace() } ||
                        version.isNullOrBlank() ||
                        !isSafeQmodVersionRange(version)
                    ) {
                        preconditions += blocked(
                            "INVALID_DEPENDENCY",
                            "QMOD dependency entries must declare string id and a valid version range."
                        )
                    } else {
                        val isOptional = optional || !requiredValue
                        result += ModPackageDependency(
                            id = id,
                            version = version,
                            optional = isOptional,
                            sourceUrl = sourceUrl,
                            required = !isOptional
                        )
                    }
                }
                is String -> {
                    val id = fallbackId.orEmpty().trim()
                    val version = value.trim()
                    if (id.isBlank() || !isSafeQmodVersionRange(version)) {
                        preconditions += blocked(
                            "INVALID_DEPENDENCY",
                            "QMOD dependency map entries must use a non-empty ID and version range."
                        )
                    } else {
                        result += ModPackageDependency(
                            id = id,
                            version = version,
                            optional = optional,
                            required = !optional
                        )
                    }
                }
                else -> preconditions += blocked(
                    "INVALID_DEPENDENCY",
                    "QMOD dependency entries must be objects or ID-to-range map values."
                )
            }
        }

        when (raw) {
            is JSONArray -> for (index in 0 until raw.length()) add(raw.opt(index))
            is JSONObject -> {
                if (raw.has("id") || raw.has("version") || raw.has("required") ||
                    raw.has("downloadIfMissing")
                ) {
                    add(raw)
                } else {
                    for (key in raw.keys()) add(raw.opt(key), key)
                }
            }
            is String -> preconditions += blocked(
                "INVALID_DEPENDENCY",
                "QMOD dependencies must be an array or dependency object, not a bare string."
            )
            else -> preconditions += blocked(
                "INVALID_DEPENDENCY",
                "QMOD dependencies must be an array or dependency object."
            )
        }
        if ((raw is JSONArray && raw.length() == 0) ||
            (raw is JSONObject && raw.length() == 0)
        ) {
            preconditions += blocked(
                "INVALID_DEPENDENCY",
                "QMOD dependencies must not be an empty array or object."
            )
        }
        val duplicateIds = result.groupBy { it.id }.filterValues { values ->
            values.size > 1
        }.keys
        if (duplicateIds.isNotEmpty()) {
            preconditions += blocked(
                "DUPLICATE_DEPENDENCY",
                "QMOD dependency IDs must be unique: ${duplicateIds.joinToString()}."
            )
        }
        return result.distinctBy { it.id to it.version }
    }

    private fun addQmodMapping(
        archive: ArchiveMetadata,
        profile: GameModProfile?,
        mappings: MutableList<ModFileMapping>,
        target: String?,
        source: String,
        destination: String,
        sha256: String?,
        preconditions: MutableList<ModInstallPrecondition>
    ) {
        addMapping(
            archive,
            profile,
            mappings,
            source,
            destination,
            sha256,
            preconditions,
            allowFullDestination = true,
            targetPackageId = target
        )
    }

    private fun qmodDestination(
        profile: GameModProfile?,
        target: String?,
        loader: ModLoaderKind?,
        field: String
    ): String? {
        if (target == null || !PACKAGE_ID.matches(target)) return null
        if (loader == null) return profile?.destination
        val data = "/sdcard/Android/data/$target/files"
        return when (loader) {
            ModLoaderKind.QUEST_LOADER -> when (field) {
                "libraryFiles" -> "$data/libs"
                else -> "$data/mods"
            }
            ModLoaderKind.SCOTLAND2 -> when (field) {
                "libraryFiles" -> "/sdcard/ModData/$target/Modloader/libs"
                "lateModFiles" -> "/sdcard/ModData/$target/Modloader/mods"
                else -> "/sdcard/ModData/$target/Modloader/early_mods"
            }
            ModLoaderKind.LEMON_LOADER, ModLoaderKind.MELON_LOADER ->
                "$data/Mods"
        }
    }

    private fun isGorillaQuestLoaderDestination(
        destination: String,
        target: String = GORILLA_TAG_PACKAGE_ID
    ): Boolean {
        val normalized = destination.trim().replace('\\', '/').trimEnd('/')
        val mods = "/sdcard/Android/data/$target/files/mods"
        val libs = "/sdcard/Android/data/$target/files/libs"
        return AndroidPathValidator.isSafe(normalized) &&
            (normalized == mods || normalized.startsWith("$mods/") ||
                normalized == libs || normalized.startsWith("$libs/"))
    }

    private fun hasCanonicalGorillaLoaderCopy(json: JSONObject): Boolean {
        val copies = json.optJSONArray("fileCopies") ?: return false
        return (0 until copies.length()).any { index ->
            val item = copies.optJSONObject(index)
            val destination = item?.opt("destination") as? String ?: return@any false
            isGorillaQuestLoaderDestination(destination)
        }
    }

    private fun androidLayoutPath(entry: String, root: String): String? {
        val normalized = entry.trimStart('/')
        val prefixes = listOf(
            "$root/",
            "sdcard/$root/",
            "storage/emulated/0/$root/"
        )
        return prefixes.firstNotNullOfOrNull { prefix ->
            normalized.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.let {
                "$root/$it"
            }
        }
    }

    private fun isAndroidDataLayout(archive: ArchiveMetadata): Boolean =
        archive.entries.any { androidLayoutPath(it, "Android/data") != null }

    private fun isAndroidObbLayout(archive: ArchiveMetadata): Boolean =
        archive.entries.any { androidLayoutPath(it, "Android/obb") != null }

    private fun analyzeAndroidLayout(
        archive: ArchiveMetadata,
        installedApp: InstalledQuestApp?,
        obb: Boolean
    ): ModPackageAnalysis {
        val dataPackages = archive.entries
            .mapNotNull { androidLayoutPath(it, "Android/data") }
            .mapNotNull { it.removePrefix("Android/data/").substringBefore('/').takeIf(PACKAGE_ID::matches) }
            .toSet()
        val obbPackages = archive.entries
            .mapNotNull { androidLayoutPath(it, "Android/obb") }
            .mapNotNull { it.removePrefix("Android/obb/").substringBefore('/').takeIf(PACKAGE_ID::matches) }
            .toSet()
        val packages = dataPackages + obbPackages
        val target = packages.singleOrNull()
        val preconditions = mutableListOf<ModInstallPrecondition>()
        if (packages.isEmpty()) {
            preconditions += blocked("PACKAGE_ID_MISSING", "Android layout does not contain a valid package ID.")
        } else if (packages.size > 1) {
            preconditions += blocked(
                "MULTIPLE_TARGET_PACKAGES",
                "Android layout contains multiple package IDs; NFVR will not cross-install them."
            )
        } else if (installedApp == null) {
            preconditions += blocked("TARGET_APP_REQUIRED", "Select the installed Quest app before installing this Android layout.")
        } else if (installedApp.packageName != target) {
            preconditions += blocked(
                "TARGET_PACKAGE_MISMATCH",
                "Android layout targets '$target', but the selected app is '${installedApp.packageName}'."
            )
        } else {
            preconditions += satisfied("TARGET_PACKAGE_MATCH", "Android layout package matches the selected app.")
        }

        val hasData = dataPackages.isNotEmpty()
        val hasObb = obbPackages.isNotEmpty()
        val type = if (hasData && hasObb) {
            // A combined archive is safe when both roots bind to the same
            // selected package; preserve both mappings rather than silently
            // dropping one half of the package.
            ModPackageType.ANDROID_DATA_LAYOUT
        } else if (obb || hasObb) {
            ModPackageType.ANDROID_OBB_LAYOUT
        } else {
            ModPackageType.ANDROID_DATA_LAYOUT
        }
        addDangerousPayloadPrecondition(archive, preconditions)
        addDefaultDeniedNativePayloadPrecondition(archive, preconditions)
        val mappings = mutableListOf<ModFileMapping>()
        if (target != null && packages.size == 1) {
            val dataProfile = GameModProfile(
                target,
                "Android data",
                "/sdcard/Android/data/$target"
            )
            val obbProfile = GameModProfile(
                target,
                "Android OBB",
                "/sdcard/Android/obb/$target"
            )
            for (entry in archive.entries) {
                if (entry in archive.directories) continue
                val dataEntry = androidLayoutPath(entry, "Android/data")
                val obbEntry = androidLayoutPath(entry, "Android/obb")
                when {
                    dataEntry?.startsWith("Android/data/$target/") == true ->
                        addMapping(
                            archive, dataProfile, mappings,
                            entry, dataEntry.removePrefix("Android/data/$target/"),
                            null, preconditions,
                            allowFullDestination = false
                        )
                    obbEntry?.startsWith("Android/obb/$target/") == true ->
                        addMapping(
                            archive, obbProfile, mappings,
                            entry, obbEntry.removePrefix("Android/obb/$target/"),
                            null, preconditions,
                            allowFullDestination = false
                        )
                }
            }
        }
        if (mappings.isEmpty()) {
            preconditions += blocked("NO_COPY_MAPPINGS", "Android layout contains no files to copy.")
        }
        val destinationProfile = target?.let {
            if (hasData) GameModProfile(it, "Android data", "/sdcard/Android/data/$it")
            else GameModProfile(it, "Android OBB", "/sdcard/Android/obb/$it")
        }
        val plan = plan(
            type = type,
            target = target,
            profile = destinationProfile,
            mappings = mappings,
            strategy = if (hasObb && !hasData) ModInstallStrategy.ANDROID_OBB_COPY
            else ModInstallStrategy.ANDROID_DATA_COPY,
            preconditions = preconditions,
            archiveIdentity = archive.identity,
            reviewedApp = installedApp
        )
        return result(
            type,
            if (plan.installable) "Recognized package-bound Android layout." else blockingMessage(plan),
            plan,
            archive,
            metadata = mapOf(
                "dataPackages" to dataPackages,
                "obbPackages" to obbPackages
            )
        )
    }

    private fun contentDocuments(archive: ArchiveMetadata): List<Pair<String, JSONObject>> =
        archive.metadata.entries.filter { (path, _) ->
            isContentMetadataName(path)
        }.map { it.key to it.value }

    private fun metadataPackageId(json: JSONObject): String? =
        listOf("targetPackageId", "packageId", "package", "gamePackage", "applicationId")
            .firstNotNullOfOrNull { key ->
                json.optString(key, "").trim().takeIf { it.isNotBlank() }
            }

    private fun metadataIndicatesNomad(path: String, json: JSONObject): Boolean {
        val values = listOf(
            json.optString("game", ""),
            json.optString("title", ""),
            json.optString("targetGame", ""),
            json.optString("gameName", "")
        ).joinToString(" ").lowercase(Locale.ROOT)
        return metadataPackageId(json) == BLADE_AND_SORCERY_PACKAGE_ID ||
            values.contains("blade") && values.contains("sorcery") ||
            values.contains("nomad")
    }

    private fun isNomadDocumentEvidence(path: String, json: JSONObject): Boolean {
        val keys = json.keys().asSequence().map { it.lowercase(Locale.ROOT) }.toSet()
        val hasMeaningfulModFields =
            keys.any { it in setOf("name", "title", "id", "guid", "module") } &&
                keys.any {
            it in setOf(
                "version", "gameversion", "versionname", "assets", "bundles",
                "catalog", "files", "modules", "addressables"
            )
        }
        val hasThunderRoadType = listOf(
            json.optString("type", ""),
            json.optString("game", ""),
            json.optString("engine", "")
        ).any { it.equals("thunderroad", ignoreCase = true) }
        val hasGameVersion = keys.contains("gameversion")
        // A generic Name+Version sidecar is not Nomad evidence. Require the
        // actual ThunderRoad schema marker or a manifest GameVersion field.
        return hasMeaningfulModFields && (hasThunderRoadType || hasGameVersion)
    }

    /**
     * Nomad's built-in managed-mod format can contain an intentionally empty
     * manifest alongside a managed assembly and symbols.  A DLL by itself is
     * not evidence of a loader, so require the manifest/assembly shape and
     * reject native/desktop executable payloads before treating it as content.
     */
    private fun isNomadManagedAssemblyEvidence(
        archive: ArchiveMetadata,
        path: String
    ): Boolean {
        if (path.substringAfterLast('/').lowercase(Locale.ROOT) != "manifest.json") {
            return false
        }
        val root = path.substringBeforeLast('/', "")
        val siblings = archive.entries.filter { entry ->
            entry !in archive.directories &&
                (root.isBlank() || entry.startsWith("$root/"))
        }
        val hasManagedAssembly = siblings.any {
            it.lowercase(Locale.ROOT).endsWith(".dll")
        }
        val hasNativeOrDesktopExecutable = siblings.any {
            val lower = it.lowercase(Locale.ROOT)
            lower.endsWith(".so") || lower.endsWith(".exe")
        }
        return hasManagedAssembly && !hasNativeOrDesktopExecutable
    }

    private fun isNomadPayload(
        archive: ArchiveMetadata,
        installedApp: InstalledQuestApp?
    ): Boolean {
        val documents = contentDocuments(archive)
        if (documents.isEmpty()) return false
        val explicitNomad = documents.any { (path, json) -> metadataIndicatesNomad(path, json) }
        val selectedNomad = installedApp?.packageName == BLADE_AND_SORCERY_PACKAGE_ID
        if (!explicitNomad && !selectedNomad) return false
        val managedAssemblyPayload = documents.any { (path, _) ->
            isNomadManagedAssemblyEvidence(archive, path)
        }

        // A Windows/PC export must not become a Nomad package merely because
        // it contains a generic manifest.json.  Quest content has either an
        // explicit Android/Quest branch or a mod folder with bundle/catalog
        // payload; desktop binaries are a hard conservative stop.
        val roots = archive.entries.mapNotNull {
            it.substringBefore('/').takeIf(String::isNotBlank)
        }.map(String::lowercase).toSet()
        val hasQuestBranch = roots.any { it in QUEST_ROOTS } ||
            archive.entries.any {
                it.startsWith("Android/data/$BLADE_AND_SORCERY_PACKAGE_ID/")
            }
        val hasPcPayload = roots.any { it in PC_ONLY_ROOTS } ||
            (!managedAssemblyPayload && archive.entries.any(::isPcOnlyFile)) ||
            (!managedAssemblyPayload &&
                archive.entries.any { it.lowercase(Locale.ROOT).endsWith(".dll") })
        if (hasPcPayload && !hasQuestBranch) return false

        val evidence = documents.any { (path, json) ->
            isNomadDocumentEvidence(path, json) ||
                isNomadManagedAssemblyEvidence(archive, path)
        }
        val payload = archive.entries.any { entry ->
            entry !in archive.directories &&
                (entry.lowercase(Locale.ROOT).endsWith(".assetbundle") ||
                    entry.lowercase(Locale.ROOT).endsWith(".bundle") ||
                    entry.lowercase(Locale.ROOT).endsWith(".manifest")) &&
                entry.contains('/')
        }
        // App selection is not content evidence. A malformed or generic
        // sidecar must not become a Nomad mod merely because Nomad is
        // selected in the UI.
        return (payload || managedAssemblyPayload) && evidence
    }

    private fun analyzeNomadPayload(
        archive: ArchiveMetadata,
        installedApp: InstalledQuestApp?,
        loaderDetection: ModLoaderDetection?,
        directoryDiscovery: ModDirectoryDiscovery?
    ): ModPackageAnalysis {
        val profile = profileRegistry.findByPackageId(BLADE_AND_SORCERY_PACKAGE_ID)
            ?: GameModProfile(
                BLADE_AND_SORCERY_PACKAGE_ID,
                "Blade & Sorcery: Nomad",
                "/sdcard/Android/data/$BLADE_AND_SORCERY_PACKAGE_ID/files/Mods"
            )
        val preconditions = mutableListOf<ModInstallPrecondition>()
        checkTarget(BLADE_AND_SORCERY_PACKAGE_ID, installedApp, "Blade & Sorcery: Nomad", preconditions)
        requireExistingDedicatedDestination(
            profile,
            directoryDiscovery,
            preconditions,
            "Nomad"
        )

        // Unlike a generic profile proposal, Nomad's completed probe gives
        // us an exact app fixture.  Do not install a structurally valid mod
        // into a stale game build.
        // Ordinary data/content copies are authorized by the selected app,
        // verified content destination, supported version fixture, and safe
        // archive structure.  Exact APK hashes remain a preparation/patching
        // gate, not an unnecessary blocker for copying content.
        profile.contentCompatibilityIssues(installedApp).forEach { code ->
            if (preconditions.none { it.code == code }) {
                preconditions += blocked(
                    code,
                    when (code) {
                        "GAME_VERSION_UNSUPPORTED" ->
                            "Installed Nomad version is outside the verified content profile."
                        "GAME_VERSION_CODE_UNSUPPORTED" ->
                            "Installed Nomad version code is outside the verified content profile."
                        else -> "Selected app does not match the verified Nomad content profile."
                    }
                )
            }
        }
        val documents = contentDocuments(archive)
        val declaredPackages = documents.mapNotNull { (_, json) -> metadataPackageId(json) }.toSet()
        if (declaredPackages.any { it != BLADE_AND_SORCERY_PACKAGE_ID }) {
            preconditions += blocked(
                "TARGET_PACKAGE_MISMATCH",
                "Nomad content metadata targets a different Android package."
            )
        }
        val declaredVersion = documents.firstNotNullOfOrNull { (_, json) ->
            json.keys().asSequence()
                .firstOrNull { key ->
                    key.equals("gameVersion", true) ||
                        key.equals("targetGameVersion", true) ||
                        key.equals("versionName", true)
                }
                ?.let { key -> json.optString(key, "").trim().takeIf(String::isNotBlank) }
        }
        val managedAssemblyPayload = documents.any { (path, _) ->
            isNomadManagedAssemblyEvidence(archive, path)
        }
        addDangerousPayloadPrecondition(archive, preconditions)
        if (!managedAssemblyPayload) {
            addDefaultDeniedNativePayloadPrecondition(archive, preconditions)
        }
        if (managedAssemblyPayload) {
            // The current Nomad runtime's GameVersion comparison is not
            // publicly specified. Do not turn an opaque 1.0.0.0 manifest
            // value into a false exact-match failure against app version 1.0.7.
            val compatibility = NomadCompatibilityEvaluator.evaluate(
                declaredVersion,
                installedApp?.versionName
            )
            when (compatibility.state) {
                NomadCompatibilityState.INCOMPATIBLE -> preconditions += blocked(
                    "NOMAD_GAME_VERSION_INCOMPATIBLE",
                    "Nomad manifest GameVersion '${declaredVersion ?: "missing"}' belongs to a different compatibility family than installed version '${installedApp?.versionName ?: "missing"}'."
                )
                NomadCompatibilityState.WARNING -> preconditions += satisfied(
                    "NOMAD_GAME_VERSION_REVIEW_WARNING",
                    compatibility.reason
                )
                NomadCompatibilityState.EXACT -> preconditions += satisfied(
                    "NOMAD_GAME_VERSION_EXACT",
                    compatibility.reason
                )
                NomadCompatibilityState.COMPATIBLE_FAMILY -> preconditions += satisfied(
                    "NOMAD_GAME_VERSION_COMPATIBILITY_WARNING",
                    "Release evidence places this Nomad mod in the installed major/minor family, " +
                        "but the current runtime's GameVersion semantics are not publicly specified."
                )
            }
        } else if (declaredVersion != null) {
            val compatibility = NomadCompatibilityEvaluator.evaluate(
                declaredVersion,
                installedApp?.versionName
            )
            when (compatibility.state) {
                NomadCompatibilityState.INCOMPATIBLE -> preconditions += blocked(
                    "GAME_VERSION_UNSUPPORTED",
                    "Nomad content targets incompatible game-version family '${declaredVersion}'."
                )
                NomadCompatibilityState.COMPATIBLE_FAMILY -> preconditions += satisfied(
                    "NOMAD_GAME_VERSION_COMPATIBILITY_WARNING",
                    "Release evidence places this Nomad mod in the installed major/minor family, " +
                        "but the current runtime's GameVersion semantics are not publicly specified."
                )
                else -> Unit
            }
        }
        val mappings = mutableListOf<ModFileMapping>()
        val files = archive.entries.filterNot { it in archive.directories }
        val afterMods = files.map { entry ->
            if (entry.startsWith("Mods/", true) || entry.startsWith("mods/", true)) {
                entry.substringAfter('/')
            } else entry
        }
        val meaningfulDocuments = documents.filter { (path, json) ->
            isNomadDocumentEvidence(path, json) ||
                isNomadManagedAssemblyEvidence(archive, path)
        }
        val manifestRoots = meaningfulDocuments.mapNotNull { (path, _) ->
            val normalized = if (path.startsWith("Mods/", true) ||
                path.startsWith("mods/", true)
            ) path.substringAfter('/') else path
            normalized.substringBefore('/').takeIf(String::isNotBlank)
        }.distinct()
        val roots = afterMods.mapNotNull {
            it.substringBefore('/').takeIf(String::isNotBlank)
        }.distinct()
        val modRoot = manifestRoots.singleOrNull()
        if (manifestRoots.size != 1) {
            preconditions += blocked(
                "MOD_FOLDER_REQUIRED",
                "Nomad content must contain one meaningful manifest inside one mod folder."
            )
        } else if (roots.any { it != modRoot }) {
            preconditions += blocked(
                "UNRELATED_ARCHIVE_CONTENT",
                "Archive contains files outside the evidenced Nomad mod folder."
            )
        }
        if (modRoot == null || !isSafeModRelativePath(modRoot)) {
            preconditions += blocked(
                "MOD_FOLDER_REQUIRED",
                "Nomad content must contain one safe mod folder beneath the archive wrapper."
            )
        } else {
            files.forEachIndexed { index, entry ->
                val normalized = afterMods[index]
                val relative = when {
                    normalized.startsWith("$modRoot/") ->
                        normalized.removePrefix("$modRoot/")
                    normalized == modRoot -> ""
                    else -> normalized
                }
                if (relative.isBlank()) return@forEachIndexed
                if (!normalized.startsWith("$modRoot/") && normalized != modRoot) {
                    return@forEachIndexed
                }
                // Some distributors wrap a mod folder in itself.  Keep one
                // copy of the actual folder name, never Mods/MyMod/MyMod.
                val corrected = if (relative.startsWith("$modRoot/")) {
                    relative.removePrefix("$modRoot/")
                } else relative
                val destination = "${profile.destination.trimEnd('/')}/$modRoot/$corrected"
                addMapping(
                    archive,
                    profile,
                    mappings,
                    entry,
                    destination,
                    null,
                    preconditions,
                    allowFullDestination = true,
                    targetPackageId = BLADE_AND_SORCERY_PACKAGE_ID
                )
            }
        }
        if (mappings.isEmpty()) {
            preconditions += blocked("NO_COPY_MAPPINGS", "Nomad content contains no safe files to install.")
        }
        val plan = plan(
            ModPackageType.KNOWN_GAME_PROFILE,
            BLADE_AND_SORCERY_PACKAGE_ID,
            profile,
            mappings,
            ModInstallStrategy.PROFILE_COPY,
            preconditions,
            archive.identity,
            installedApp,
            loaderRequirement = null,
            resolution = resolution(
                ModResolutionStrategy.KNOWN_GAME_PROFILE,
                100,
                "Verified Nomad manifest/module/catalog content profile."
            )
        )
        return result(
            ModPackageType.KNOWN_GAME_PROFILE,
            if (plan.installable) {
                "Recognized Blade & Sorcery: Nomad content mod."
            } else blockingMessage(plan),
            plan,
            archive,
            metadata = mapOf("platform" to "Quest content", "profile" to profile.packageId)
        )
    }

    private fun isBonelabPayload(
        archive: ArchiveMetadata,
        installedApp: InstalledQuestApp?
    ): Boolean {
        val roots = archive.entries.mapNotNull { it.substringBefore('/').takeIf { root -> root.isNotBlank() } }.toSet()
        if (roots.isEmpty()) return false
        if (installedApp?.packageName == BONELAB_PACKAGE_ID &&
            (archive.entries.any(::looksLikeCodeModPayload) ||
                archive.entries.any { it.lowercase(Locale.ROOT).endsWith(".so") })
        ) return true
        val contentEvidence = contentDocuments(archive).any { (path, json) ->
            isBonelabContentDocument(path, json)
        }
        val contentPayload = archive.entries.any { path ->
            val lower = path.lowercase(Locale.ROOT)
            (lower.endsWith(".pallet") ||
                lower.endsWith(".marrow") ||
                lower.endsWith(".assetbundle") ||
                lower.endsWith(".bundle") ||
                lower.endsWith(".asset")) && lower.contains('/')
        }
        // App selection is not content evidence. A pallet basename or an
        // arbitrary bundle cannot authorize the BONELAB destination.
        return contentPayload && contentEvidence
    }

    private fun isBonelabContentDocument(path: String, json: JSONObject): Boolean {
        val basename = path.substringAfterLast('/').lowercase(Locale.ROOT)
        val root = json.optJSONObject("root")
        val objects = json.optJSONObject("objects")
        val rootRef = root?.opt("ref")?.toString()?.takeIf { it.isNotBlank() }
        val rootObject = rootRef?.let { objects?.optJSONObject(it) }
        val isRootScopedPallet =
            basename.endsWith(".pallet.json") &&
                root?.optString("type", "").equals("pallet#0", true) &&
                rootObject?.optString("barcode", "").orEmpty().isNotBlank() &&
                rootObject?.optJSONArray("crates") != null &&
                rootObject?.optString("sdkVersion", "").orEmpty().isNotBlank()
        val internalIds = json.optJSONArray("m_InternalIds")
        val isPalletAddressablesCatalog = internalIds != null &&
            (0 until internalIds.length()).any { index ->
                internalIds.optString(index).startsWith("PALLET_BARCODE:", true)
            } &&
            (0 until internalIds.length()).any { index ->
                internalIds.optString(index).lowercase(Locale.ROOT).contains(".bundle")
            }
        if (isRootScopedPallet || isPalletAddressablesCatalog) return true
        val keys = json.keys().asSequence().map { it.lowercase(Locale.ROOT) }.toSet()
        val hasIdentity = keys.any {
            it in setOf("name", "title", "id", "barcode", "pallet", "author")
        }
        val hasContentSemantics = keys.any {
            it in setOf(
                "version", "gameversion", "sdkversion", "marrowversion",
                "assetbundle", "assets", "files", "dependencies", "platform",
                "crates"
            )
        }
        val hasGameSignal = keys.any {
            it in setOf(
                "packageid", "gameversion", "sdkversion", "marrowversion",
                "pallet", "barcode", "marrow", "assetbundle", "crates"
            )
        }
        return hasIdentity && hasContentSemantics && hasGameSignal
    }

    private fun analyzeBonelabPayload(
        archive: ArchiveMetadata,
        installedApp: InstalledQuestApp?,
        loaderDetection: ModLoaderDetection?,
        directoryDiscovery: ModDirectoryDiscovery?
    ): ModPackageAnalysis {
        val profile = profileRegistry.findByPackageId(BONELAB_PACKAGE_ID)
            ?: GameModProfile(BONELAB_PACKAGE_ID, "BONELAB", "/sdcard/Android/data/$BONELAB_PACKAGE_ID/files/Mods")
        val preconditions = mutableListOf<ModInstallPrecondition>()
        checkTarget(BONELAB_PACKAGE_ID, installedApp, "BONELAB", preconditions)
        requireExistingDedicatedDestination(
            profile,
            directoryDiscovery,
            preconditions,
            "BONELAB"
        )
        val contentDocuments = contentDocuments(archive)
        val declaredPackages = contentDocuments.mapNotNull { (_, json) -> metadataPackageId(json) }.toSet()
        if (declaredPackages.any { it != BONELAB_PACKAGE_ID }) {
            preconditions += blocked(
                "TARGET_PACKAGE_MISMATCH",
                "BONELAB content metadata targets a different Android package."
            )
        }
        // Direct BONELAB readiness is bound to the completed probe fixture,
        // even when an archive omits package/version fields.  An archive
        // cannot downgrade this check by presenting itself as unversioned.
        // APK identity is intentionally not a prerequisite for ordinary
        // content installation.  Patching/native preparation keeps its own
        // strict hash gate elsewhere.
        profile.contentCompatibilityIssues(installedApp).forEach { code ->
            if (preconditions.none { it.code == code }) {
                preconditions += blocked(
                    code,
                    "Selected BONELAB app does not match the verified content profile."
                )
            }
        }
        val declaredVersion = contentDocuments.firstNotNullOfOrNull { (_, json) ->
            listOf("gameVersion", "targetGameVersion", "versionName")
                .firstNotNullOfOrNull { key ->
                    json.optString(key, "").trim().takeIf(String::isNotBlank)
                }
        }
        if (declaredVersion != null && installedApp?.versionName != declaredVersion) {
            preconditions += blocked(
                "GAME_VERSION_UNSUPPORTED",
                "BONELAB content targets game version '$declaredVersion', but the installed version is '${installedApp?.versionName}'."
            )
        }
        val roots = archive.entries
            .mapNotNull { it.substringBefore('/').takeIf(String::isNotBlank) }
            .toMutableSet()
        val pcRoots = roots.filter { it.lowercase(Locale.ROOT) in PC_ONLY_ROOTS }
        val questRoots = roots.filter { it.lowercase(Locale.ROOT) in QUEST_ROOTS }
        val questWrapper = questRoots.firstOrNull()
        val androidPrefix = archive.entries.firstOrNull { entry ->
            entry.startsWith("Android/data/$BONELAB_PACKAGE_ID/files/Mods/")
        }?.substringBeforeLast("/Mods/")?.plus("/Mods")
        val platformPrefix = androidPrefix ?: questWrapper
        val selectedEntries = archive.entries.filter {
            platformPrefix == null || it.startsWith("$platformPrefix/")
        }
        // Choose the Quest/Android subset before classifying code or applying
        // payload policy. A desktop DLL/EXE/SO in a mixed archive must not
        // poison an otherwise valid Quest native-content branch.
        val codeMod = selectedEntries.any(::looksLikeCodeModPayload) ||
            selectedEntries.any { it.lowercase(Locale.ROOT).endsWith(".so") }
        fun relativeEntry(entry: String): String {
            val platformRelative = platformPrefix?.let {
                entry.removePrefix("$it/")
            } ?: entry
            return stripBonelabWrappers(platformRelative)
        }
        val meaningfulManifestRoots = contentDocuments(archive)
            .filter { (path, json) -> isBonelabContentDocument(path, json) }
            .map { (path, _) -> relativeEntry(path).substringBefore('/') }
            .filter(String::isNotBlank)
            .distinct()
        val codeRoot = selectedEntries
            .asSequence()
            .map(::relativeEntry)
            .mapNotNull { it.substringBefore('/').takeIf(String::isNotBlank) }
            .firstOrNull()
        val contentRoot = meaningfulManifestRoots.singleOrNull()
        val evidencedRoot = contentRoot ?: if (codeMod) codeRoot else null
        val selectedRoots = selectedEntries.asSequence()
            .filterNot { it in archive.directories }
            .map(::relativeEntry)
            .mapNotNull { it.substringBefore('/').takeIf(String::isNotBlank) }
            .distinct()
            .toList()
        if (!codeMod && meaningfulManifestRoots.size != 1) {
            preconditions += blocked(
                "MOD_FOLDER_REQUIRED",
                "BONELAB content must contain one meaningful manifest inside one mod folder."
            )
        }
        if (evidencedRoot != null && selectedRoots.any { it != evidencedRoot }) {
            preconditions += blocked(
                "UNRELATED_ARCHIVE_CONTENT",
                "Archive contains files outside the evidenced BONELAB mod folder."
            )
        }
        val loaderRequirement = if (codeMod) {
            loaderRequirement(
                setOf(ModLoaderKind.LEMON_LOADER, ModLoaderKind.MELON_LOADER),
                loaderDetection,
                preconditions,
                enforceWhenUnknown = true,
                targetPackageId = BONELAB_PACKAGE_ID
            )
        } else null
        val type = if (codeMod) ModPackageType.BONELAB_CODE_MOD else ModPackageType.BONELAB_NATIVE_CONTENT
        if (codeMod) {
            addDangerousPayloadPrecondition(
                archive,
                preconditions,
                allowCodeDll = true,
                payloadEntries = selectedEntries
            )
            addBonelabCodePayloadPreconditions(archive, preconditions, selectedEntries)
        } else {
            addDangerousPayloadPrecondition(
                archive,
                preconditions,
                payloadEntries = selectedEntries
            )
            addDefaultDeniedNativePayloadPrecondition(archive, preconditions, selectedEntries)
        }

        val hasQuestBranch = platformPrefix != null
        if (pcRoots.isNotEmpty() && !hasQuestBranch) {
            preconditions += blocked(
                "PC_ONLY_PAYLOAD",
                "هذه حزمة BONELAB مخصصة للكمبيوتر وليست محتوى Quest قابلًا للتثبيت."
            )
        }
        if (selectedEntries.any { isPcOnlyFile(it) }) {
            preconditions += blocked(
                "PC_ONLY_PAYLOAD",
                "هذه الحزمة تحتوي ملفات BONELAB مخصصة للكمبيوتر فقط."
            )
        }
        val mappings = mutableListOf<ModFileMapping>()
        if (roots.isEmpty()) {
            preconditions += blocked("MOD_FOLDER_REQUIRED", "BONELAB pallet content must contain a complete mod folder.")
        } else if (archive.entries.any { it.substringBefore('/').isBlank() }) {
            preconditions += blocked("MOD_FOLDER_REQUIRED", "BONELAB content files must be inside a mod folder.")
        } else {
            for (entry in archive.entries) {
                if (entry in archive.directories) continue
                if (platformPrefix != null && !entry.startsWith("$platformPrefix/")) {
                    // A mixed PC/Quest archive is handled by the Quest/Android
                    // branch only; never copy the desktop half silently.
                    continue
                }
                val relative = relativeEntry(entry)
                if (relative.isBlank() || !relative.contains('/')) {
                    preconditions += blocked("MOD_FOLDER_REQUIRED", "BONELAB content files must be inside a complete mod folder.")
                    continue
                }
                if (evidencedRoot != null &&
                    relative != evidencedRoot &&
                    !relative.startsWith("$evidencedRoot/")
                ) {
                    continue
                }
                val deNestedRelative = if (
                    evidencedRoot != null &&
                    relative.startsWith("$evidencedRoot/$evidencedRoot/")
                ) {
                    relative.removePrefix("$evidencedRoot/")
                } else relative
                val destination = "${profile.destination}/${deNestedRelative}"
                addMapping(
                    archive,
                    profile,
                    mappings,
                    entry,
                    destination,
                    null,
                    preconditions,
                    allowFullDestination = true,
                    targetPackageId = BONELAB_PACKAGE_ID
                )
            }
        }
        if (mappings.isEmpty()) {
            preconditions += blocked("NO_COPY_MAPPINGS", "BONELAB content contains no safe files to install.")
        }
        val plan = plan(
            type,
            BONELAB_PACKAGE_ID,
            profile,
            mappings,
            if (codeMod) ModInstallStrategy.PROFILE_COPY else ModInstallStrategy.BONELAB_CONTENT_COPY,
            preconditions,
            archive.identity,
            installedApp,
            loaderRequirement
        )
        return result(
            type,
            if (plan.installable) {
                if (codeMod) "Recognized BONELAB code mod with a verified loader."
                else "Recognized BONELAB native Marrow content."
            } else blockingMessage(plan),
            plan,
            archive,
            metadata = mapOf("platform" to if (codeMod) "Quest loader code" else "Quest native content")
        )
    }

    private fun stripBonelabWrappers(raw: String): String {
        var value = raw
        while (value.substringBefore('/').equals("Mods", ignoreCase = true)) {
            value = value.substringAfter('/', "")
            if (value.isBlank()) return ""
        }
        return value
    }

    private fun checkTarget(
        target: String,
        installedApp: InstalledQuestApp?,
        name: String,
        preconditions: MutableList<ModInstallPrecondition>
    ) {
        when {
            installedApp == null ->
                preconditions += blocked("TARGET_APP_REQUIRED", "Select the installed $name app before installing this package.")
            installedApp.packageName != target ->
                preconditions += blocked(
                    "TARGET_PACKAGE_MISMATCH",
                    "Package targets '$target', but the selected app is '${installedApp.packageName}'."
                )
            else -> preconditions += satisfied("TARGET_PACKAGE_MATCH", "$name package matches the selected app.")
        }
    }

    private fun isKnownGameProfilePayload(
        archive: ArchiveMetadata,
        installedApp: InstalledQuestApp?
    ): Boolean {
        val profile = installedApp?.let { profileRegistry.findByPackageId(it.packageName) }
            ?: return false
        // BONELAB and Nomad have dedicated, schema-driven classifiers.
        // Never let the generic known-directory fallback authorize either
        // game when their content evidence was ambiguous.
        if (profile.packageId == BONELAB_PACKAGE_ID ||
            profile.packageId == BLADE_AND_SORCERY_PACKAGE_ID
        ) return false
        return archive.entries.any { entry ->
            profile.knownContentDirectories.any { root ->
                entry == root || entry.startsWith("$root/")
            }
        }
    }

    private fun analyzeKnownGameProfile(
        archive: ArchiveMetadata,
        installedApp: InstalledQuestApp?,
        loaderDetection: ModLoaderDetection?
    ): ModPackageAnalysis {
        val target = installedApp?.packageName
        val profile = target?.let(profileRegistry::findByPackageId)
        val preconditions = mutableListOf<ModInstallPrecondition>()
        if (target == null || profile == null) {
            preconditions += blocked("TARGET_APP_REQUIRED", "Select a supported installed game before installing this package.")
        } else {
            preconditions += satisfied("TARGET_PACKAGE_MATCH", "Known game profile matches the selected app.")
        }
        val loaderRequirement = profile?.let {
            loaderRequirement(
                it.loaderRequirements,
                loaderDetection,
                preconditions,
                enforceWhenUnknown = it.loaderRequirements.isNotEmpty(),
                targetPackageId = target
            )
        }
        addDangerousPayloadPrecondition(archive, preconditions)
        addDefaultDeniedNativePayloadPrecondition(archive, preconditions)
        val mappings = mutableListOf<ModFileMapping>()
        if (profile != null) {
            for (entry in archive.entries) {
                if (entry in archive.directories) continue
                val root = profile.knownContentDirectories.firstOrNull {
                    entry.startsWith("$it/")
                } ?: continue
                val relative = entry.removePrefix("$root/")
                if (relative.isBlank()) continue
                addMapping(
                    archive,
                    profile,
                    mappings,
                    entry,
                    "${profile.destination}/$relative",
                    null,
                    preconditions,
                    allowFullDestination = true,
                    targetPackageId = target
                )
            }
        }
        if (mappings.isEmpty()) {
            preconditions += blocked("NO_COPY_MAPPINGS", "Known profile package contains no safe content files.")
        }
        val plan = plan(
            ModPackageType.KNOWN_GAME_PROFILE,
            target,
            profile,
            mappings,
            ModInstallStrategy.PROFILE_COPY,
            preconditions,
            archive.identity,
            installedApp,
            loaderRequirement
        )
        return result(
            ModPackageType.KNOWN_GAME_PROFILE,
            if (plan.installable) "Recognized package using the vetted game profile '${profile?.displayName}'."
            else blockingMessage(plan),
            plan,
            archive
        )
    }

    private fun analyzeExistingDirectoryProposal(
        archive: ArchiveMetadata,
        installedApp: InstalledQuestApp?,
        discovery: ModDirectoryDiscovery
    ): ModPackageAnalysis {
        val target = installedApp?.packageName
        // Directory existence is not a Gorilla Tag compatibility contract.
        // Its classification-only profile must not become writable merely
        // because discovery found a guessed Mods/plugins/ModData candidate.
        // Pavlov is classification-only too: it exposes no sdcard mod
        // folder, so a discovered directory must never authorize it either.
        if (target == GORILLA_TAG_PACKAGE_ID || target == PAVLOV_PACKAGE_ID) {
            val gameName = if (target == PAVLOV_PACKAGE_ID) "Pavlov" else "Gorilla Tag"
            val code = if (target == PAVLOV_PACKAGE_ID) {
                "PAVLOV_DESTINATION_UNAUTHORIZED"
            } else {
                "GORILLA_TAG_DESTINATION_UNAUTHORIZED"
            }
            val detail = if (target == PAVLOV_PACKAGE_ID) {
                "Pavlov Quest exposes no authoritative mod directory; UGC is mounted by the game's own mod.io runtime."
            } else {
                "Gorilla Tag Quest has no authoritative generic directory contract; only an exact QMOD with authenticated QuestLoader evidence may use a canonical loader destination."
            }
            val preconditions = listOf(blocked(code, detail))
            val plan = ModInstallPlan(
                outcome = ModInstallOutcome.APK_PATCH_REQUIRED,
                packageType = ModPackageType.GENERIC_DATA,
                targetPackageId = target,
                strategy = ModInstallStrategy.NONE,
                archiveIdentity = archive.identity,
                reviewedApp = installedApp,
                preconditions = preconditions,
                patchRequirement = ModPatchRequirement(
                    required = true,
                    reason = "Generic $gameName Quest archives cannot be authorized from directory discovery."
                ),
                resolution = resolution(
                    ModResolutionStrategy.UNKNOWN,
                    0,
                    "$gameName classification-only profile rejects generic directory routing."
                )
            )
            return result(
                ModPackageType.GENERIC_DATA,
                if (target == PAVLOV_PACKAGE_ID) {
                    "Pavlov Quest generic archives cannot be installed from discovered directories; UGC is managed by the game's mod.io runtime."
                } else {
                    "Gorilla Tag Quest generic archives cannot be installed from discovered directories; use an exact QMOD with authenticated QuestLoader evidence."
                },
                plan,
                archive
            )
        }
        val candidate = discovery.existingCandidates.firstOrNull {
            it.packageId == target && it.readOnly && AndroidPathValidator.isSafe(it.path)
        }
        val preconditions = mutableListOf<ModInstallPrecondition>()
        if (target == null) {
            preconditions += blocked(
                "TARGET_APP_REQUIRED",
                "Select the installed Quest app before proposing an existing mod directory."
            )
        }
        if (discovery.serial.isBlank() || target != discovery.packageId) {
            preconditions += blocked(
                "DISCOVERY_BINDING_MISMATCH",
                "The read-only directory discovery belongs to a different selected package."
            )
        }
        if (candidate == null) {
            preconditions += blocked(
                "NO_EXISTING_MOD_DIRECTORY",
                "No existing mod directory was found for the selected package."
            )
        } else {
            preconditions += satisfied(
                "EXISTING_MOD_DIRECTORY_FOUND",
                "An existing mod directory was found by read-only inspection."
            )
            preconditions += blocked(
                "EXPLICIT_CONFIRMATION_REQUIRED",
                "The proposed existing mod directory must be shown and explicitly confirmed before writing."
            )
        }
        addDangerousPayloadPrecondition(archive, preconditions)
        addDefaultDeniedNativePayloadPrecondition(archive, preconditions)
        val mappings = mutableListOf<ModFileMapping>()
        if (candidate != null) {
            val profile = GameModProfile(
                packageId = target!!,
                displayName = "Existing mod directory",
                destination = candidate.path,
                knownContentDirectories = emptySet(),
                authoritativePaths = setOf(candidate.path),
                evidenceLevel = candidate.evidenceLevel
            )
            val wrapper = archive.entries
                .mapNotNull { it.substringBefore('/').takeIf(String::isNotBlank) }
                .groupingBy { it }
                .eachCount()
                .entries
                .singleOrNull()?.key
            for (entry in archive.entries) {
                if (entry in archive.directories) continue
                val relative = if (wrapper != null && entry.startsWith("$wrapper/")) {
                    entry.removePrefix("$wrapper/")
                } else {
                    entry
                }
                if (relative.isBlank()) continue
                addMapping(
                    archive,
                    profile,
                    mappings,
                    entry,
                    "${candidate.path.trimEnd('/')}/$relative",
                    null,
                    preconditions,
                    allowFullDestination = true,
                    targetPackageId = target
                )
            }
        }
        if (mappings.isEmpty()) {
            preconditions += blocked(
                "NO_COPY_MAPPINGS",
                "The archive contains no safe files for the existing mod directory."
            )
        }
        val confirmation = candidate?.let {
            ModInstallConfirmation(
                token = modInstallConfirmationToken(
                    archive.identity?.sha256.orEmpty(),
                    target.orEmpty(),
                    it.path
                ),
                destination = it.path,
                reason = "Directory existence is evidence only; destination was inferred from a read-only scan."
            )
        }
        val profile = candidate?.let {
            GameModProfile(
                packageId = target!!,
                displayName = "Existing mod directory",
                destination = it.path,
                evidenceLevel = it.evidenceLevel
            )
        }
        val plan = plan(
            type = ModPackageType.GENERIC_DATA,
            target = target,
            profile = profile,
            mappings = mappings,
            strategy = ModInstallStrategy.GENERIC_EXISTING_DIRECTORY_COPY,
            preconditions = preconditions,
            archiveIdentity = archive.identity,
            reviewedApp = installedApp,
            confirmation = confirmation,
            resolution = resolution(
                ModResolutionStrategy.EXISTING_GAME_MOD_DIRECTORY,
                55,
                "Read-only discovery found an existing package-scoped directory; explicit confirmation is required."
            )
        )
        return result(
            ModPackageType.GENERIC_DATA,
            if (candidate == null) blockingMessage(plan)
            else "A generic existing mod directory was proposed; confirm the displayed destination before installing.",
            plan,
            archive,
            metadata = mapOf(
                "candidateDestination" to candidate?.path,
                "confirmationRequired" to (candidate != null)
            )
        )
    }

    private fun looksLikeCodeModPayload(path: String): Boolean =
        path.lowercase(Locale.ROOT).endsWith(".dll") ||
            path.lowercase(Locale.ROOT).contains("/melonloader/") ||
            path.lowercase(Locale.ROOT).contains("/lemonloader/")

    private fun isPcOnlyFile(path: String): Boolean {
        val lower = path.lowercase(Locale.ROOT)
        return lower.endsWith(".exe") || lower.endsWith(".pdb") || lower.endsWith(".lib")
    }

    private fun analyzeVirtualStump(
        archive: ArchiveMetadata,
        json: JSONObject,
        installedApp: InstalledQuestApp?
    ): ModPackageAnalysis {
        val plan = ModInstallPlan(
            outcome = ModInstallOutcome.BUILT_IN_GAME_CONTENT,
            packageType = ModPackageType.GORILLA_TAG_VIRTUAL_STUMP,
            strategy = ModInstallStrategy.NONE,
            targetPackageId = installedApp?.packageName,
            archiveIdentity = archive.identity,
            reviewedApp = installedApp,
            preconditions = listOf(
                satisfied(
                    "BUILT_IN_GAME_CONTENT",
                    "Virtual Stump content is imported by Gorilla Tag's built-in custom-map workflow; NFVR has no safe direct destination or importer contract."
                ),
                blocked(
                    "BUILT_IN_IMPORTER_UNVERIFIED",
                    "The archive is recognized as built-in Virtual Stump content, but NFVR has no confirmed implementation of Gorilla Tag's importer workflow."
                ),
                // Kept as a stable compatibility code for existing callers;
                // it no longer implies a browser or external URL workflow.
                satisfied("MOD_IO_MANAGED", "The package is managed by the game's built-in content system.")
            ),
            resolution = resolution(
                ModResolutionStrategy.BUILT_IN_CONTENT_TYPE,
                100,
                "Manifest identifies content managed by the game's own custom-content system."
            )
        )
        return result(
            ModPackageType.GORILLA_TAG_VIRTUAL_STUMP,
            "Recognized Gorilla Tag Virtual Stump content; use the game's built-in custom-map importer because no safe direct Quest copy workflow is verified.",
            plan,
            archive,
            json.toMap()
        )
    }

    /**
     * Pavlov Shack UGC is Unreal mod.io content: a small metadata.json
     * (EngineVersion + ModType) next to cooked .pak chunk files such as
     * UGC<modId>pakchunk0-Android_ASTC.pak.  Classification is purely
     * content-based so renamed archives classify identically; the outer
     * file name is never evidence.
     */
    private fun isPavlovPayload(zipFile: File, archive: ArchiveMetadata): Boolean {
        val pakEntries = archive.entries.filter { entry ->
            entry !in archive.directories &&
                entry.lowercase(Locale.ROOT).endsWith(".pak")
        }
        if (pakEntries.isEmpty()) return false
        val metadataNames = archive.entries.filter { entry ->
            entry !in archive.directories &&
                entry.substringAfterLast('/').equals("metadata.json", ignoreCase = true)
        }
        return metadataNames.any { name -> readPavlovMetadata(zipFile, name) != null }
    }

    private fun readPavlovMetadata(zipFile: File, entryName: String): JSONObject? {
        return runCatching {
            ZipFile(zipFile).use { zip ->
                val zipEntry = zip.getEntry(entryName) ?: return@runCatching null
                if (zipEntry.isDirectory || zipEntry.size > 64L * 1024L) return@runCatching null
                val out = java.io.ByteArrayOutputStream()
                zip.getInputStream(zipEntry).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (out.size().toLong() + read > 64L * 1024L) return@runCatching null
                        out.write(buffer, 0, read)
                    }
                }
                val json = JSONObject(
                    StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(out.toByteArray()))
                        .toString()
                )
                if (isPavlovMetadataDocument(json)) json else null
            }
        }.getOrNull()
    }

    private fun isPavlovMetadataDocument(json: JSONObject): Boolean {
        val engine = json.opt("EngineVersion")
        if (engine !is String || engine.isBlank()) return false
        return when (val modType = json.opt("ModType")) {
            is Number -> true
            is String -> modType.isNotBlank()
            else -> false
        }
    }

    private fun pavlovModId(archive: ArchiveMetadata): String? {
        val pattern = Regex("""UGC(\d+)""", RegexOption.IGNORE_CASE)
        return archive.entries
            .filter { it !in archive.directories && it.lowercase(Locale.ROOT).endsWith(".pak") }
            .firstNotNullOfOrNull { entry ->
                pattern.find(entry.substringAfterLast('/'))?.groupValues?.getOrNull(1)
            }
    }

    /**
     * Pavlov UGC is mounted by the game's own mod.io runtime from its
     * subscription directory; Pavlov exposes no sdcard mod folder and no
     * public local-import contract on Quest.  NFVR therefore recognizes
     * the format but authorizes no destination: recognition without a
     * writable root can never become an install plan.
     */
    private fun analyzePavlovPayload(
        zipFile: File,
        archive: ArchiveMetadata,
        installedApp: InstalledQuestApp?
    ): ModPackageAnalysis {
        val preconditions = mutableListOf<ModInstallPrecondition>()
        checkTarget(PAVLOV_PACKAGE_ID, installedApp, "Pavlov", preconditions)
        preconditions += satisfied(
            "PAVLOV_MOD_IO_MANAGED",
            "Pavlov UGC content is mounted by the game's own mod.io runtime."
        )
        preconditions += blocked(
            "PAVLOV_IMPORTER_UNVERIFIED",
            "The archive is recognized as Pavlov mod.io UGC, but NFVR has no confirmed local import contract for Pavlov on Quest."
        )
        val plan = ModInstallPlan(
            outcome = ModInstallOutcome.BUILT_IN_GAME_CONTENT,
            packageType = ModPackageType.PAVLOV_UGC_CONTENT,
            strategy = ModInstallStrategy.NONE,
            targetPackageId = PAVLOV_PACKAGE_ID,
            archiveIdentity = archive.identity,
            reviewedApp = installedApp,
            preconditions = preconditions,
            resolution = resolution(
                ModResolutionStrategy.BUILT_IN_CONTENT_TYPE,
                100,
                "Manifest identifies content managed by the game's own mod.io UGC system."
            )
        )
        return result(
            ModPackageType.PAVLOV_UGC_CONTENT,
            "Recognized Pavlov mod.io UGC content; the game imports its own subscriptions and NFVR has no safe direct Quest copy workflow.",
            plan,
            archive,
            mapOf(
                "modId" to pavlovModId(archive),
                "platform" to "Quest mod.io UGC"
            )
        )
    }

    private fun analyzeGeneric(
        archive: ArchiveMetadata,
        installedApp: InstalledQuestApp? = null
    ): ModPackageAnalysis {
        val preconditions = mutableListOf<ModInstallPrecondition>()
        addDefaultDeniedNativePayloadPrecondition(archive, preconditions)
        val gorillaNative = installedApp?.packageName == GORILLA_TAG_PACKAGE_ID &&
            archive.entries.any { it.lowercase(Locale.ROOT).endsWith(".so") ||
                looksLikeCodeModPayload(it) }
        if (gorillaNative) {
            preconditions += blocked(
                "APK_PATCH_REQUIRED",
                "Gorilla Tag native/code mods require a reviewed APK patch or compatible loader; NFVR will not guess a Quest destination."
            )
        }
        preconditions += blocked(
            "DESTINATION_UNDECLARED",
            "Generic mod data has no declarative destination; NFVR will not guess where to copy it."
        )
        val patchRequirement = if (gorillaNative) ModPatchRequirement(
            required = true,
            reason = "No authoritative Gorilla Tag Quest native/code-mod installation contract is available."
        ) else null
        val plan = ModInstallPlan(
            packageType = ModPackageType.GENERIC_DATA,
            outcome = if (gorillaNative) ModInstallOutcome.APK_PATCH_REQUIRED else ModInstallOutcome.UNSUPPORTED,
            strategy = ModInstallStrategy.NONE,
            targetPackageId = installedApp?.packageName,
            archiveIdentity = archive.identity,
            reviewedApp = installedApp,
            preconditions = preconditions,
            patchRequirement = patchRequirement,
            resolution = resolution(
                ModResolutionStrategy.UNKNOWN,
                0,
                "No package-bound manifest, Android layout, loader package, profile, or discovered directory matched."
            )
        )
        return result(
            ModPackageType.GENERIC_DATA,
            "Recognized generic mod data, but no safe installation destination was declared.",
            plan,
            archive
        )
    }

    private fun analyzeNfvr(
        archive: ArchiveMetadata,
        json: JSONObject,
        installedApp: InstalledQuestApp?,
        loaderDetection: ModLoaderDetection?
    ): ModPackageAnalysis {
        val preconditions = mutableListOf<ModInstallPrecondition>()
        val schema = json.opt("schemaVersion")
        if (schema !is Number || schema.toInt() != 1) {
            return result(
                ModPackageType.NFVR_MANIFEST,
                "NFVR manifest must use schemaVersion 1.",
                ModInstallPlan(
                    packageType = ModPackageType.NFVR_MANIFEST,
                    archiveIdentity = archive.identity,
                    reviewedApp = installedApp,
                    preconditions = listOf(blocked("UNSUPPORTED_SCHEMA", "Only nfvr-mod.json schemaVersion 1 is supported."))
                ),
                archive,
                json.toMap()
            )
        }

        val unknownFields = json.keys().asSequence().filterNot(NFVR_ALLOWED_KEYS::contains).toList()
        if (unknownFields.isNotEmpty()) {
            preconditions += blocked(
                "UNKNOWN_MANIFEST_FIELDS",
                "NFVR manifest contains unsupported fields: ${unknownFields.joinToString()}."
            )
        }
        if (json.has("commands")) {
            preconditions += blocked("COMMANDS_NOT_ALLOWED", "NFVR manifests may contain declarative copies only; commands are forbidden.")
        }
        val target = json.optString("targetPackageId", "").trim().ifBlank { null }
        if (target == null) {
            preconditions += blocked("TARGET_PACKAGE_MISSING", "NFVR manifest must declare targetPackageId.")
        } else if (installedApp == null) {
            preconditions += blocked("TARGET_APP_REQUIRED", "Select the installed Quest app before installing this manifest.")
        } else if (installedApp.packageName != target) {
            preconditions += blocked(
                "TARGET_PACKAGE_MISMATCH",
                "Manifest targets '$target', but the selected app is '${installedApp.packageName}'."
            )
        } else {
            preconditions += satisfied("TARGET_PACKAGE_MATCH", "Manifest target package matches the selected app.")
        }

        val profile = target?.let(profileRegistry::findByPackageId)
        if (profile == null && target != null) {
            preconditions += blocked("UNKNOWN_GAME_PROFILE", "No vetted destination is registered for '$target'.")
        }
        checkVersionConstraints(json, installedApp, preconditions)
        val loaderValue = json.optString("requiredModLoader", "").trim()
        val loader = ModLoaderKind.parse(loaderValue)
        if (loaderValue.isNotEmpty() &&
            (loader == null || loader !in setOf(ModLoaderKind.QUEST_LOADER, ModLoaderKind.SCOTLAND2))
        ) {
            preconditions += blocked("UNSUPPORTED_MOD_LOADER", "Unsupported required modloader '$loaderValue'.")
        }
        val loaderRequirement = loaderRequirement(
            if (loader == null) emptySet() else setOf(loader),
            loaderDetection,
            preconditions,
            enforceWhenUnknown = loader != null,
            targetPackageId = target
        )
        val patchRequirement = if (json.optString("modType", "").equals("apk-patch", true)) {
            preconditions += blocked(
                "APK_PATCH_REQUIRED",
                "NFVR manifest requests APK patching; NFVR will not patch the installed application."
            )
            ModPatchRequirement(
                required = true,
                reason = "NFVR manifest modType=apk-patch."
            )
        } else null
        addDangerousManifestPreconditions(json, preconditions, NFVR_ALLOWED_KEYS)
        addDangerousPayloadPrecondition(archive, preconditions)

        val mappings = parseNfvrMappings(archive, json, profile, preconditions)
        if (mappings.isEmpty()) {
            preconditions += blocked("NO_COPY_MAPPINGS", "NFVR manifest must contain at least one safe file copy.")
        }
        addNfvrNativePayloadPreconditions(
            archive,
            mappings,
            loader,
            target,
            preconditions
        )
        val plan = plan(
            ModPackageType.NFVR_MANIFEST, target, profile, mappings,
            ModInstallStrategy.DECLARATIVE_COPY, preconditions,
            archive.identity,
            installedApp,
            loaderRequirement,
            patchRequirement = patchRequirement,
            resolution = resolution(
                ModResolutionStrategy.NFVR_MANIFEST,
                100,
                "nfvr-mod.json is an explicit NFVR declarative manifest."
            )
        )
        return result(
            ModPackageType.NFVR_MANIFEST,
            if (plan.installable) "Recognized NFVR manifest with a safe copy plan." else blockingMessage(plan),
            plan,
            archive,
            json.toMap()
        )
    }

    private fun parseNfvrMappings(
        archive: ArchiveMetadata,
        json: JSONObject,
        profile: GameModProfile?,
        preconditions: MutableList<ModInstallPrecondition>
    ): List<ModFileMapping> {
        if (profile == null) return emptyList()
        val files = json.optJSONArray("files")
        val copies = json.optJSONArray("copies")
        if (files != null && copies != null) {
            preconditions += blocked("DUPLICATE_COPY_FIELDS", "NFVR manifest must use either files or copies, not both.")
            return emptyList()
        }
        val list = files ?: copies ?: return emptyList()
        val mappings = mutableListOf<ModFileMapping>()
        for (i in 0 until list.length()) {
            val item = list.opt(i)
            if (item !is JSONObject) {
                preconditions += blocked("INVALID_COPY_MAPPING", "Every NFVR file mapping must be an object.")
                continue
            }
            try {
                val source = item.getString("source")
                val destination = item.getString("destination")
                val sha = item.optString("sha256", "").trim().ifBlank { null }
                if (item.keys().asSequence().any { it !in setOf("source", "destination", "sha256") }) {
                    preconditions += blocked("INVALID_COPY_MAPPING", "NFVR file mappings may contain only source, destination, and sha256.")
                    continue
                }
                if (sha != null && !SHA256.matches(sha)) {
                    preconditions += blocked("INVALID_SHA256", "sha256 must contain exactly 64 hexadecimal characters.")
                    continue
                }
                addMapping(archive, profile, mappings, source, destination, sha, preconditions)
            } catch (e: Exception) {
                preconditions += blocked("INVALID_COPY_MAPPING", "NFVR contains an incomplete file mapping.")
            }
        }
        return mappings.distinctBy { it.sourcePath to it.destinationPath }
    }

    private fun addMapping(
        archive: ArchiveMetadata,
        profile: GameModProfile?,
        mappings: MutableList<ModFileMapping>,
        source: String,
        destination: String,
        sha256: String?,
        preconditions: MutableList<ModInstallPrecondition>,
        allowFullDestination: Boolean = false,
        targetPackageId: String? = null
    ) {
        val sourcePath = safeRelativePath(source)
        val normalizedDestination = destination.trim().replace('\\', '/')
        val destinationPath = safeRelativePath(destination)
        val explicitFullDestination = profile?.let {
            normalizedDestination == it.destination ||
                normalizedDestination.startsWith("${it.destination}/")
        } == true ||
            (allowFullDestination && isApprovedManifestDestination(normalizedDestination, targetPackageId))
        if (sourcePath == null || (destinationPath == null && !explicitFullDestination)) {
            preconditions += blocked("UNSAFE_PATH", "File mappings may not contain absolute, traversal, or unsafe paths.")
            return
        }
        val entry = archive.entries.firstOrNull { it == sourcePath }
        if (entry == null) {
            preconditions += blocked("MISSING_SOURCE", "Manifest mapping refers to '$sourcePath', which is not in the ZIP.")
            return
        }
        if (sourcePath in archive.directories) {
            preconditions += blocked("SOURCE_IS_DIRECTORY", "Manifest mapping refers to a directory, not a file: '$sourcePath'.")
            return
        }
        val fullDestination = if (explicitFullDestination) {
            normalizedDestination
        } else {
            val profileDestination = profile?.destination
            if (profileDestination == null) {
                preconditions += blocked(
                    "DESTINATION_UNAVAILABLE",
                    "A relative destination requires a vetted game profile."
                )
                return
            }
            "${profileDestination.trimEnd('/')}/${destinationPath!!}"
        }
        if (!AndroidPathValidator.isSafe(fullDestination) ||
            (!fullDestinationCandidate(fullDestination, profile?.destination) &&
                !(allowFullDestination && isApprovedManifestDestination(fullDestination, targetPackageId)))
        ) {
            preconditions += blocked("UNSAFE_DESTINATION", "Destination '$destination' is outside the vetted game mod directory.")
            return
        }
        val size = archive.sizes[sourcePath]
        val actualSha256 = archive.hashes[sourcePath]
        if (size == null || actualSha256 == null) {
            preconditions += blocked("SOURCE_HASH_UNAVAILABLE", "Could not hash mapped source '$sourcePath'.")
            return
        }
        if (sha256 != null && !sha256.equals(actualSha256, ignoreCase = true)) {
            preconditions += blocked("SHA256_MISMATCH", "Declared sha256 does not match '$sourcePath'.")
            return
        }
        val sourceCollision = collisionKey(sourcePath)
        val destinationCollision = collisionKey(fullDestination)
        val sourceCollisionEntry = mappings.firstOrNull {
            collisionKey(it.sourcePath) == sourceCollision
        }
        val destinationCollisionEntry = mappings.firstOrNull {
            collisionKey(it.destinationPath) == destinationCollision
        }
        if (destinationCollisionEntry != null ||
            (sourceCollisionEntry != null &&
                (sourceCollisionEntry.sha256 != actualSha256 ||
                    sourceCollisionEntry.sizeBytes != size))
        ) {
            val collision = destinationCollisionEntry ?: sourceCollisionEntry!!
            val same = collisionKey(collision.sourcePath) == sourceCollision &&
                collisionKey(collision.destinationPath) == destinationCollision
            preconditions += blocked(
                if (same) "DUPLICATE_MAPPING" else "CONFLICTING_MAPPING",
                "Mappings contain duplicate or conflicting source/destination paths."
            )
            return
        }
        mappings += ModFileMapping(sourcePath, fullDestination, actualSha256, size)
    }

    private fun fullDestinationCandidate(destination: String, profileDestination: String?): Boolean =
        profileDestination != null &&
            (destination == profileDestination || destination.startsWith("${profileDestination.trimEnd('/')}/"))

    private fun isApprovedManifestDestination(destination: String, targetPackageId: String?): Boolean {
        val target = targetPackageId?.takeIf { PACKAGE_ID.matches(it) } ?: return false
        return ModDestinationPolicy.isPackageBound(destination, target) &&
            !destination.contains("/../") &&
            AndroidPathValidator.isSafe(destination)
    }

    private fun checkVersionConstraints(
        json: JSONObject,
        app: InstalledQuestApp?,
        preconditions: MutableList<ModInstallPrecondition>
    ) {
        val version = app?.versionName
        val min = json.optString("minGameVersion", "").trim().ifBlank { null }
        val max = json.optString("maxGameVersion", "").trim().ifBlank { null }
        val supported = json.optJSONArray("supportedGameVersions")
        if ((min != null || max != null || supported != null) && version == null) {
            preconditions += blocked("GAME_VERSION_REQUIRED", "Manifest has game version constraints but the installed version is unavailable.")
            return
        }
        if (version != null && min != null && compareVersions(version, min) < 0) {
            preconditions += blocked("GAME_VERSION_UNSUPPORTED", "Installed game version '$version' is below minimum '$min'.")
        }
        if (version != null && max != null && compareVersions(version, max) > 0) {
            preconditions += blocked("GAME_VERSION_UNSUPPORTED", "Installed game version '$version' is above maximum '$max'.")
        }
        if (version != null && supported != null && (0 until supported.length()).none {
                compareVersions(version, supported.optString(it)) == 0
            }) {
            preconditions += blocked("GAME_VERSION_UNSUPPORTED", "Installed game version '$version' is not listed as supported.")
        }
    }

    private fun plan(
        type: ModPackageType,
        target: String?,
        profile: GameModProfile?,
        mappings: List<ModFileMapping>,
        strategy: ModInstallStrategy,
        preconditions: List<ModInstallPrecondition>,
        archiveIdentity: ModArchiveIdentity? = null,
        reviewedApp: InstalledQuestApp? = null,
        loaderRequirement: ModLoaderRequirement? = null,
        dependencies: List<ModPackageDependency> = emptyList(),
        optionalDependencies: List<ModPackageDependency> = emptyList(),
        patchRequirement: ModPatchRequirement? = null,
        confirmation: ModInstallConfirmation? = null,
        resolution: ModStrategyDecision = resolutionFor(type)
    ): ModInstallPlan {
        val installable = mappings.isNotEmpty() && preconditions.none { !it.satisfied }
        val unsupportedLoader = preconditions.any { it.code == "UNSUPPORTED_MOD_LOADER" }
        val requiresLoader = preconditions.any {
            !it.satisfied && (
                it.code == "MOD_LOADER_NOT_DETECTED" ||
                    it.code == "MOD_LOADER_UNKNOWN" ||
                    it.code == "MOD_LOADER_REQUIRED"
                )
        }
        val hardPayloadBlock = preconditions.any {
            !it.satisfied && it.code in setOf(
                "DANGEROUS_PAYLOAD",
                "NATIVE_PAYLOAD_REQUIRES_LOADER_ROOT",
                "INVALID_MANAGED_CODE",
                "PC_ONLY_PAYLOAD",
                "MIXED_PLATFORM_PAYLOAD",
                "UNSUPPORTED_LATE_MOD_FILES"
            )
        }
        val outcome = when {
            type == ModPackageType.GORILLA_TAG_VIRTUAL_STUMP ->
                ModInstallOutcome.BUILT_IN_GAME_CONTENT
            type == ModPackageType.UNKNOWN ->
                ModInstallOutcome.UNSUPPORTED
            type == ModPackageType.GENERIC_DATA &&
                confirmation == null ->
                ModInstallOutcome.UNSUPPORTED
            patchRequirement?.required == true ->
                ModInstallOutcome.APK_PATCH_REQUIRED
            unsupportedLoader -> ModInstallOutcome.UNSUPPORTED
            // A managed/native code archive is still a meaningful code-mod
            // classification when its loader is absent.  Preserve the
            // loader-required outcome for the UI; once a loader is detected,
            // invalid binaries remain blocked by the hard payload checks.
            hardPayloadBlock && !(
                type == ModPackageType.BONELAB_CODE_MOD &&
                    requiresLoader &&
                    preconditions.none {
                        it.code == "NATIVE_PAYLOAD_REQUIRES_LOADER_ROOT"
                    }
                ) ->
                ModInstallOutcome.UNSUPPORTED
            requiresLoader -> ModInstallOutcome.REQUIRES_MOD_LOADER
            installable -> ModInstallOutcome.DIRECT_INSTALL_READY
            else -> ModInstallOutcome.UNSUPPORTED
        }
        return ModInstallPlan(
        installable = installable,
        outcome = outcome,
        packageType = type,
        targetPackageId = target,
        destinationRoot = profile?.destination ?: inferDestinationRoot(target, mappings),
        mappings = mappings,
        preconditions = preconditions,
        strategy = strategy,
        archiveIdentity = archiveIdentity,
        reviewedApp = reviewedApp,
            loaderRequirement = loaderRequirement,
            dependencies = dependencies,
            optionalDependencies = optionalDependencies,
            patchRequirement = patchRequirement,
            confirmation = confirmation,
            resolution = resolution,
            diagnostics = preconditions.filterNot { it.satisfied }.map { it.message }
        )
    }

    private fun requireExistingDedicatedDestination(
        profile: GameModProfile,
        discovery: ModDirectoryDiscovery?,
        preconditions: MutableList<ModInstallPrecondition>,
        gameName: String
    ) {
        /*
         * Offline analysis has no device evidence to inspect.  Leave that
         * preview honest but unresolved; device-aware analysis must provide a
         * discovery snapshot and is blocked unless the exact authoritative
         * root was observed there.
         */
        if (discovery == null) return
        val verified = discovery.existingCandidates.any {
            it.packageId == profile.packageId &&
                it.path == profile.destination &&
                it.readOnly
        } == true
        if (!verified) {
            preconditions += blocked(
                "MOD_DESTINATION_REQUIRED",
                "$gameName content requires the verified existing directory " +
                    "${profile.destination}; NFVR will not create the approved base."
            )
        }
    }

    private fun resolution(
        strategy: ModResolutionStrategy,
        confidence: Int,
        evidence: String
    ): ModStrategyDecision = ModStrategyDecision(
        selected = strategy,
        confidence = confidence,
        evidence = listOf(
            ModStrategyEvidence(
                strategy = strategy,
                level = when (strategy) {
                    ModResolutionStrategy.NFVR_MANIFEST,
                    ModResolutionStrategy.QMOD,
                    ModResolutionStrategy.ANDROID_FILESYSTEM_LAYOUT ->
                        ModEvidenceLevel.AUTHORITATIVE
                    ModResolutionStrategy.KNOWN_GAME_PROFILE ->
                        ModEvidenceLevel.OPEN_SOURCE_PROJECT
                    ModResolutionStrategy.BUILT_IN_CONTENT_TYPE ->
                        ModEvidenceLevel.AUTHORITATIVE
                    ModResolutionStrategy.EXISTING_GAME_MOD_DIRECTORY,
                    ModResolutionStrategy.KNOWN_MOD_LOADER_PACKAGE ->
                        ModEvidenceLevel.COMMUNITY_VERIFIED
                    ModResolutionStrategy.UNKNOWN ->
                        ModEvidenceLevel.HEURISTIC
                },
                confidence = confidence,
                evidence = listOf(evidence)
            )
        )
    )

    private fun resolutionFor(type: ModPackageType): ModStrategyDecision {
        val strategy = when (type) {
            ModPackageType.NFVR_MANIFEST -> ModResolutionStrategy.NFVR_MANIFEST
            ModPackageType.QMOD -> ModResolutionStrategy.QMOD
            ModPackageType.ANDROID_DATA_LAYOUT,
            ModPackageType.ANDROID_OBB_LAYOUT -> ModResolutionStrategy.ANDROID_FILESYSTEM_LAYOUT
            ModPackageType.BONELAB_NATIVE_CONTENT,
            ModPackageType.BONELAB_CODE_MOD,
            ModPackageType.KNOWN_GAME_PROFILE -> ModResolutionStrategy.KNOWN_GAME_PROFILE
            ModPackageType.GORILLA_TAG_VIRTUAL_STUMP,
            ModPackageType.PAVLOV_UGC_CONTENT -> ModResolutionStrategy.BUILT_IN_CONTENT_TYPE
            ModPackageType.GENERIC_DATA -> ModResolutionStrategy.EXISTING_GAME_MOD_DIRECTORY
            ModPackageType.UNKNOWN -> ModResolutionStrategy.UNKNOWN
        }
        return resolution(strategy, if (strategy == ModResolutionStrategy.UNKNOWN) 0 else 70, "recognized package structure")
    }

    private fun inferDestinationRoot(
        target: String?,
        mappings: List<ModFileMapping>
    ): String? {
        val packageId = target?.takeIf(PACKAGE_ID::matches) ?: return null
        val roots = listOf(
            "/sdcard/Android/data/$packageId",
            "/sdcard/Android/obb/$packageId",
            "/sdcard/ModData/$packageId"
        )
        val matchingRoots = mappings.asSequence()
            .mapNotNull { mapping -> roots.firstOrNull { mapping.destinationPath == it || mapping.destinationPath.startsWith("$it/") } }
            .distinct()
            .toList()
        return when {
            matchingRoots.size == 1 -> matchingRoots.single()
            matchingRoots.size > 1 -> "/sdcard/"
            else -> null
        }
    }

    private fun loaderRequirement(
        required: Set<ModLoaderKind>,
        detection: ModLoaderDetection?,
        preconditions: MutableList<ModInstallPrecondition>,
        enforceWhenUnknown: Boolean,
        targetPackageId: String? = null
    ): ModLoaderRequirement? {
        if (required.isEmpty()) return null
        val requirement = ModLoaderRequirement(
            requested = required,
            detection = detection,
            targetPackageId = targetPackageId
        )
        when (requirement.status) {
            ModLoaderStatus.DETECTED ->
                preconditions += satisfied("MOD_LOADER_DETECTED", requirement.message.ifBlank {
                    detection?.summary(required) ?: "Required modloader detected."
                })
            ModLoaderStatus.NOT_DETECTED ->
                preconditions += blocked("MOD_LOADER_NOT_DETECTED", requirement.message.ifBlank {
                    detection?.summary(required) ?: "Required modloader was not detected."
                })
            ModLoaderStatus.UNKNOWN -> if (enforceWhenUnknown) {
                preconditions += blocked("MOD_LOADER_UNKNOWN", requirement.message.ifBlank {
                    detection?.summary(required) ?: "Required modloader could not be verified."
                })
            }
        }
        return requirement
    }

    private fun checkQmodPackageVersion(
        json: JSONObject,
        app: InstalledQuestApp?,
        preconditions: MutableList<ModInstallPrecondition>
    ) {
        if (!json.has("packageVersion")) return
        if (json.opt("packageVersion") !is String) {
            preconditions += blocked(
                "INVALID_PACKAGE_VERSION",
                "QMOD packageVersion must be a JSON string."
            )
            return
        }
        val requested = json.optString("packageVersion", "").trim()
        if (requested.isBlank()) {
            preconditions += blocked("INVALID_PACKAGE_VERSION", "QMOD packageVersion must be a non-empty version.")
        } else if (app?.versionName == null) {
            preconditions += blocked("GAME_VERSION_REQUIRED", "QMOD declares packageVersion but the installed version is unavailable.")
        } else if (app.versionName.trim() != requested) {
            preconditions += blocked(
                "GAME_VERSION_UNSUPPORTED",
                "QMOD targets game version '$requested', but the installed version is '${app.versionName}'."
            )
        }
    }

    private fun isVirtualStump(json: JSONObject): Boolean =
        json.optString("packageId", "").equals(GORILLA_TAG_VIRTUAL_STUMP_PACKAGE_ID, true) ||
            json.optString("packageType", "").equals(GORILLA_TAG_VIRTUAL_STUMP_PACKAGE_ID, true) ||
            listOf("pcFileName", "androidFileName", "customMapSupportVersion", "initialScenes", "availableGameModes")
                .all(json::has)

    private fun hasPatchingRequirement(json: JSONObject): Boolean =
        listOf("patcher", "patching", "apkPatching", "requiresPatching")
            .any { key ->
                val value = json.opt(key)
                value != null && value != JSONObject.NULL &&
                    when (value) {
                        is Boolean -> value
                        is String -> value.isNotBlank() && !value.equals("false", true)
                        else -> true
                    }
            }

    private fun hasEntries(value: Any): Boolean = when (value) {
        is JSONArray -> value.length() > 0
        is JSONObject -> value.length() > 0
        is String -> value.isNotBlank()
        else -> true
    }

    private fun addDangerousManifestPreconditions(
        json: JSONObject,
        preconditions: MutableList<ModInstallPrecondition>,
        allowedKeys: Set<String>
    ) {
        val dangerous = json.keys().asSequence().filter { key ->
            val lower = key.lowercase(Locale.ROOT)
            val relevant = listOf(
                "command", "script", "shell", "powershell", "exec", "executable",
                "patch", "loader", "dependency"
            ).any(lower::contains)
            relevant && key !in allowedKeys
        }.toList()
        if (dangerous.isNotEmpty()) {
            preconditions += blocked(
                "DANGEROUS_MANIFEST_KEY",
                "Manifest contains unsupported command, script, loader, patching, dependency, or executable keys: ${dangerous.joinToString()}."
            )
        }
    }

    private fun addStrictQmodSchemaPreconditions(
        json: JSONObject,
        preconditions: MutableList<ModInstallPrecondition>
    ) {
        val allowed = QMOD_METADATA_KEYS + QMOD_IMPLEMENTED_KEYS + QMOD_UNIMPLEMENTED_KEYS
        val unknown = json.keys().asSequence().filterNot(allowed::contains).toList()
        if (unknown.isNotEmpty()) {
            preconditions += blocked(
                "UNKNOWN_QMOD_FIELD",
                "QMOD mod.json contains unsupported top-level fields: ${unknown.joinToString()}."
            )
        }
        val unimplemented = json.keys().asSequence().filter(QMOD_UNIMPLEMENTED_KEYS::contains).toList()
        if (unimplemented.isNotEmpty()) {
            preconditions += blocked(
                "UNSUPPORTED_QMOD_FIELD",
                "QMOD uses unsupported fields that NFVR will not execute or configure: ${unimplemented.joinToString()}."
            )
        }
    }

    private fun addDangerousPayloadPrecondition(
        archive: ArchiveMetadata,
        preconditions: MutableList<ModInstallPrecondition>,
        allowCodeDll: Boolean = false,
        payloadEntries: Collection<String> = archive.entries
    ) {
        val dangerous = payloadEntries.filter { path ->
            DANGEROUS_PAYLOAD_EXTENSIONS.any { path.lowercase(Locale.ROOT).endsWith(it) } &&
                !(allowCodeDll && path.lowercase(Locale.ROOT).endsWith(".dll"))
        }
        if (dangerous.isNotEmpty()) {
            preconditions += blocked(
                "DANGEROUS_PAYLOAD",
                "Package contains executable or script payloads that NFVR will not install: ${dangerous.take(5).joinToString()}."
            )
        }
    }

    private fun addDefaultDeniedNativePayloadPrecondition(
        archive: ArchiveMetadata,
        preconditions: MutableList<ModInstallPrecondition>,
        payloadEntries: Collection<String> = archive.entries
    ) {
        val native = payloadEntries.filter { path ->
            NATIVE_PAYLOAD_EXTENSIONS.any {
                path.lowercase(Locale.ROOT).endsWith(it)
            }
        }
        if (native.isNotEmpty()) {
            preconditions += blocked(
                "NATIVE_PAYLOAD_REQUIRES_LOADER_ROOT",
                "DLL/SO payloads are denied unless a validated loader mapping and architecture proof are present: ${native.take(5).joinToString()}."
            )
        }
    }

    private fun addBonelabCodePayloadPreconditions(
        archive: ArchiveMetadata,
        preconditions: MutableList<ModInstallPrecondition>,
        payloadEntries: Collection<String> = archive.entries
    ) {
        val dlls = payloadEntries.filter {
            it.lowercase(Locale.ROOT).endsWith(".dll")
        }
        if (dlls.any { !isManagedPeCli(archive, it) }) {
            preconditions += blocked(
                "INVALID_MANAGED_CODE",
                "BONELAB code mods must be validated managed PE/CLI assemblies."
            )
        }
        if (payloadEntries.any {
                it.lowercase(Locale.ROOT).endsWith(".so") &&
                    !isArm64Elf(archive, it)
            }) {
            preconditions += blocked(
                "NATIVE_PAYLOAD_REQUIRES_LOADER_ROOT",
                "BONELAB code plans require validated ARM64 native SO payloads."
            )
        }
    }

    private fun addQmodNativePayloadPreconditions(
        archive: ArchiveMetadata,
        json: JSONObject,
        mappings: List<ModFileMapping>,
        loader: ModLoaderKind?,
        target: String?,
        preconditions: MutableList<ModInstallPrecondition>
    ) {
        val nativeSources = archive.entries.filter {
            NATIVE_PAYLOAD_EXTENSIONS.any { extension ->
                it.lowercase(Locale.ROOT).endsWith(extension)
            }
        }
        if (nativeSources.isEmpty()) return
        val allowedLoader = loader == ModLoaderKind.QUEST_LOADER ||
            loader == ModLoaderKind.SCOTLAND2
        val loaderSources = listOf("modFiles", "lateModFiles", "libraryFiles")
            .flatMap { field ->
                (json.optJSONArray(field)?.let { array ->
                    (0 until array.length()).mapNotNull { array.optString(it).trim().ifBlank { null } }
                } ?: emptyList())
            }
            .mapNotNull { safeRelativePath(it) }
            .toSet()
        val allValid = allowedLoader &&
            target != null &&
            nativeSources.all { source ->
                val mapping = mappings.firstOrNull { it.sourcePath == source } ?: return@all false
                val lower = source.lowercase(Locale.ROOT)
                source in loaderSources &&
                    lower.endsWith(".so") &&
                    isArm64Elf(archive, source) &&
                    isLoaderRoot(mapping.destinationPath, target, loader)
            }
        if (!allValid) {
            preconditions += blocked(
                "NATIVE_PAYLOAD_REQUIRES_LOADER_ROOT",
                "QMOD DLL/SO payloads require an explicit QuestLoader/Scotland2 root and validated ARM64 ELF files."
            )
        }
    }

    private fun addNfvrNativePayloadPreconditions(
        archive: ArchiveMetadata,
        mappings: List<ModFileMapping>,
        loader: ModLoaderKind?,
        target: String?,
        preconditions: MutableList<ModInstallPrecondition>
    ) {
        val nativeSources = archive.entries.filter {
            NATIVE_PAYLOAD_EXTENSIONS.any { extension ->
                it.lowercase(Locale.ROOT).endsWith(extension)
            }
        }
        if (nativeSources.isEmpty()) return
        val allValid = loader != null &&
            target != null &&
            nativeSources.all { source ->
                val mapping = mappings.firstOrNull { it.sourcePath == source } ?: return@all false
                source.lowercase(Locale.ROOT).endsWith(".so") &&
                    isArm64Elf(archive, source) &&
                    isLoaderRoot(mapping.destinationPath, target, loader)
            }
        if (!allValid) {
            preconditions += blocked(
                "NATIVE_PAYLOAD_REQUIRES_LOADER_ROOT",
                "NFVR DLL/SO payloads require a declared supported loader, loader root, and validated ARM64 ELF file."
            )
        }
    }

    private fun isLoaderRoot(path: String, target: String, loader: ModLoaderKind?): Boolean {
        return ModDestinationPolicy.isLoaderRoot(path, target, loader)
    }

    private fun isArm64Elf(archive: ArchiveMetadata, source: String): Boolean {
        val bytes = archive.prefixes[source] ?: return false
        return bytes.size >= 20 &&
            bytes[0] == 0x7f.toByte() &&
            bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() &&
            bytes[4].toInt() == 2 &&
            bytes[5].toInt() == 1 &&
            (bytes[18].toInt() and 0xff) == 0xb7 &&
            bytes[19].toInt() == 0
    }

    private fun isManagedPeCli(archive: ArchiveMetadata, source: String): Boolean {
        val bytes = archive.prefixes[source] ?: return false
        if (bytes.size < 64 || bytes[0] != 'M'.code.toByte() || bytes[1] != 'Z'.code.toByte()) return false
        val peOffset = littleEndianInt(bytes, 0x3c)
        if (peOffset < 0 || peOffset + 24 > bytes.size) return false
        if (bytes[peOffset] != 'P'.code.toByte() ||
            bytes[peOffset + 1] != 'E'.code.toByte() ||
            bytes[peOffset + 2] != 0.toByte() ||
            bytes[peOffset + 3] != 0.toByte()
        ) return false
        val optional = peOffset + 24
        if (optional + 2 > bytes.size) return false
        val magic = littleEndianShort(bytes, optional)
        val directory = when (magic) {
            0x10b -> optional + 96
            0x20b -> optional + 112
            else -> return false
        }
        val cliDirectory = directory + (14 * 8)
        return cliDirectory + 8 <= bytes.size &&
            littleEndianInt(bytes, cliDirectory) != 0 &&
            littleEndianInt(bytes, cliDirectory + 4) != 0
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun collisionKey(path: String): String = ModArchivePath.collisionKey(path)

    private fun unknown(
        archive: ArchiveMetadata,
        installedApp: InstalledQuestApp? = null
    ): ModPackageAnalysis {
        val extensions = archive.entries.asSequence()
            .filterNot { it in archive.directories }
            .map { it.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf(String::isNotBlank) }
            .filterNotNull()
            .distinct()
            .sorted()
            .take(20)
            .toList()
        val topLevel = archive.entries
            .mapNotNull { it.substringBefore('/').takeIf(String::isNotBlank) }
            .distinct()
            .sorted()
            .take(20)
        val diagnostics = buildList {
            add("selectedPackage=${installedApp?.packageName ?: "none"}")
            add("topLevel=${topLevel.joinToString(",").ifBlank { "none" }}")
            add("extensions=${extensions.joinToString(",").ifBlank { "none" }}")
            add("manifests=${archive.metadata.keys.sorted().joinToString(",").ifBlank { "none" }}")
            add("loaderEvidence=not authenticated during offline archive analysis")
            add("candidateDestinationEvidence=none")
        }
        val plan = ModInstallPlan(
            packageType = ModPackageType.UNKNOWN,
            targetPackageId = installedApp?.packageName,
            archiveIdentity = archive.identity,
            reviewedApp = installedApp,
            preconditions = listOf(blocked("UNKNOWN_FORMAT", "This mod format is not recognized or its destination cannot be determined safely.")),
            diagnostics = diagnostics,
            resolution = resolution(
                ModResolutionStrategy.UNKNOWN,
                0,
                "No safe strategy matched the archive."
            )
        )
        return result(
            ModPackageType.UNKNOWN,
            "This mod format is not recognized safely. Diagnostic classification is available for support.",
            plan,
            archive,
            metadata = mapOf("diagnostics" to diagnostics)
        )
    }

    private fun failed(
        message: String,
        diagnosticEntry: String? = null,
        installedApp: InstalledQuestApp? = null,
        archiveIdentity: ModArchiveIdentity? = null
    ): ModPackageAnalysis =
        ModPackageAnalysis(
            ModPackageType.UNKNOWN,
            recognized = false,
            message = message,
            compatibility = ModCompatibility(false, listOf(message)),
            installPlan = ModInstallPlan(
                outcome = ModInstallOutcome.UNSAFE_ARCHIVE,
                targetPackageId = installedApp?.packageName,
                archiveIdentity = archiveIdentity,
                reviewedApp = installedApp,
                preconditions = listOf(
                    blocked(
                        "UNSAFE_ARCHIVE",
                        if (diagnosticEntry == null) message else "$message (entry '$diagnosticEntry')"
                    )
                ),
                diagnostics = listOfNotNull(diagnosticEntry)
            ),
            diagnostics = listOfNotNull(diagnosticEntry),
            archiveTree = null
        )

    private fun applyArchiveStructure(
        plan: ModInstallPlan,
        tree: QuestModArchiveTree?
    ): ModInstallPlan {
        if (tree == null) return plan
        val preconditions = plan.preconditions.toMutableList()
        if (tree.hasNestedArchives &&
            preconditions.none { it.code == "NESTED_ARCHIVE_REQUIRES_REVIEW" }
        ) {
            preconditions += blocked(
                "NESTED_ARCHIVE_REQUIRES_REVIEW",
                "Archive contains nested ZIP content; NFVR will not flatten or install nested archives automatically."
            )
        }
        val discoveredDependencies = archiveDependencies(tree)
        if (tree.dependencyPaths.isNotEmpty() &&
            preconditions.none { it.code == "ARCHIVE_DEPENDENCIES_DISCOVERED" }
        ) {
            preconditions += satisfied(
                "ARCHIVE_DEPENDENCIES_DISCOVERED",
                "Archive dependency metadata was discovered; NFVR will not download or execute dependency instructions automatically."
            )
        }
        val dependencies = (plan.dependencies + discoveredDependencies)
            .distinctBy { it.id.lowercase(Locale.ROOT) to it.version.orEmpty() }
        val installable = plan.mappings.isNotEmpty() &&
            preconditions.none { !it.satisfied }
        val outcome = if (plan.outcome == ModInstallOutcome.DIRECT_INSTALL_READY &&
            !installable
        ) {
            ModInstallOutcome.UNSUPPORTED
        } else {
            plan.outcome
        }
        return plan.copy(
            installable = installable,
            outcome = outcome,
            preconditions = preconditions,
            dependencies = dependencies,
            diagnostics = (plan.diagnostics +
                preconditions.filterNot { it.satisfied }.map { it.message }).distinct()
        )
    }

    private fun archiveDependencies(tree: QuestModArchiveTree): List<ModPackageDependency> {
        val result = mutableListOf<ModPackageDependency>()
        tree.metadata.values.forEach { json ->
            val key = json.keys().asSequence().firstOrNull {
                it.equals("dependencies", true) || it.equals("dependency", true)
            } ?: return@forEach
            val value = json.opt(key)
            when (value) {
                is JSONArray -> {
                    for (index in 0 until value.length()) {
                        addArchiveDependency(result, value.opt(index), null)
                    }
                }
                is JSONObject -> {
                    value.keys().forEach { id ->
                        addArchiveDependency(result, value.opt(id), id)
                    }
                }
                else -> addArchiveDependency(result, value, null)
            }
        }
        if (result.isEmpty()) {
            tree.dependencyPaths.forEach { path ->
                result += ModPackageDependency(
                    id = path,
                    downloadRequired = false,
                    optional = true
                )
            }
        }
        return result.distinctBy { it.id.lowercase(Locale.ROOT) to it.version.orEmpty() }
    }

    private fun addArchiveDependency(
        result: MutableList<ModPackageDependency>,
        value: Any?,
        fallbackId: String?
    ) {
        val dependency = when (value) {
            is JSONObject -> {
                val id = listOf("id", "packageId", "name", "modId")
                    .firstNotNullOfOrNull { key ->
                        value.optString(key, "").trim().takeIf(String::isNotBlank)
                    } ?: fallbackId
                id?.let {
                    ModPackageDependency(
                        id = it,
                        version = value.optString("version", "").trim().takeIf(String::isNotBlank),
                        optional = value.optBoolean("optional", false),
                        downloadRequired = false
                    )
                }
            }
            else -> value?.toString()?.trim()?.takeIf(String::isNotBlank)?.let {
                ModPackageDependency(id = it, downloadRequired = false, optional = true)
            }
        }
        if (dependency != null) result += dependency
    }

    private fun result(
        type: ModPackageType,
        message: String,
        plan: ModInstallPlan,
        archive: ArchiveMetadata,
        metadata: Map<String, Any?> = emptyMap(),
        externalWorkflow: ModExternalWorkflow? = null
    ): ModPackageAnalysis {
        // Every classified result carries the selected app identity.  Keeping
        // this normalization at the result boundary protects less common
        // analyzer branches from accidentally dropping it.
        val normalizedPlan = applyArchiveStructure(
            plan.copy(
                targetPackageId = plan.targetPackageId ?: plan.reviewedApp?.packageName
            ),
            archive.normalizedTree
        )
        return ModPackageAnalysis(
        packageType = type,
        recognized = type != ModPackageType.UNKNOWN,
        message = message,
        compatibility = if (externalWorkflow != null) {
            ModCompatibility(true)
        } else {
            ModCompatibility(normalizedPlan.installable, normalizedPlan.preconditions.filterNot { it.satisfied }.map { it.message })
        },
        installPlan = normalizedPlan,
        metadata = metadata,
        entries = archive.entries,
        externalWorkflow = externalWorkflow,
            diagnostics = normalizedPlan.diagnostics.distinct(),
            archiveTree = archive.normalizedTree
    )
    }

    private fun blockingMessage(plan: ModInstallPlan): String =
        plan.preconditions.firstOrNull { !it.satisfied }?.message
            ?: "Package is recognized but no safe installation plan is available."

    private fun blocked(code: String, message: String) = ModInstallPrecondition(code, message, false)
    private fun satisfied(code: String, message: String) = ModInstallPrecondition(code, message, true)

    private fun validateEntryName(name: String): String {
        return try {
            ModArchivePath.normalize(name)
        } catch (e: IllegalArgumentException) {
            throw ModPackageException(
                "ZIP rejected entry '$name': ${e.message ?: "unsafe path"}.",
                name
            )
        }
    }

    private fun safeRelativePath(value: String): String? {
        val normalized = runCatching { ModArchivePath.normalize(value.trim()) }.getOrNull() ?: return null
        if (normalized.isBlank()) return null
        if (normalized.split('/').any { it.isBlank() || it == "." || it == ".." }) return null
        if (normalized.any { it == '\u0000' || it == ';' || it == '|' || it == '`' }) return null
        return normalized
    }

    /**
     * QMOD dependency versions are ranges rather than arbitrary download
     * directives.  Keep this intentionally conservative: accept the common
     * SemVer operators/wildcards and reject URLs, control characters, and
     * operator-only values without attempting to resolve a range locally.
     */
    private fun isSafeQmodVersionRange(value: String?): Boolean {
        val range = value?.trim().orEmpty()
        if (range.isEmpty() || range.length > 200) return false
        if (range.any { it.isISOControl() || it == '/' || it == '\\' || it == ':' }) return false
        if (range.contains("..")) return false
        if (range.none { it.isDigit() || it == '*' || it == 'x' || it == 'X' }) return false
        val allowed = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ.*+<>=~^| -"
        return range.all { it in allowed }
    }

    private fun looksLikeGenericModPayload(path: String): Boolean {
        val lower = path.lowercase()
        return lower.endsWith(".so") ||
            lower.endsWith(".dll") ||
            lower.endsWith(".assetbundle") ||
            lower.endsWith(".bundle")
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.trim().split(Regex("[.-]"))
        val b = right.trim().split(Regex("[.-]"))
        for (i in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrNull(i) ?: "0"
            val bv = b.getOrNull(i) ?: "0"
            val an = av.toLongOrNull()
            val bn = bv.toLongOrNull()
            val comparison = if (an != null && bn != null) an.compareTo(bn) else av.compareTo(bv)
            if (comparison != 0) return comparison
        }
        return 0
    }

    private data class ArchiveMetadata(
        val entries: List<String>,
        val metadata: Map<String, JSONObject>,
        val sizes: Map<String, Long> = emptyMap(),
        val hashes: Map<String, String> = emptyMap(),
        val directories: Set<String> = emptySet(),
        val prefixes: Map<String, ByteArray> = emptyMap(),
        val identity: ModArchiveIdentity? = null,
        val traversalEntries: List<String> = emptyList(),
        val normalizedTree: QuestModArchiveTree? = null
    )

    private class ModPackageException(
        message: String,
        val diagnosticEntry: String? = null
    ) : Exception(message)

}

/**
 * Archive-bomb guard based on evidence, not arbitrary totals.  Only fires
 * when expanded output is BOTH large (above the floor, so small archives
 * are never affected) and disproportionate to the compressed input.
 * Unknown compressed totals (no evidence) never trigger it.
 */
internal fun expansionAbuse(actualTotalBytes: Long, compressedTotalBytes: Long): Boolean {
    if (actualTotalBytes <= ModPackageAnalyzer.EXPANSION_FLOOR_BYTES) return false
    if (compressedTotalBytes <= 0L) return false
    return actualTotalBytes.toDouble() > compressedTotalBytes.toDouble() *
        ModPackageAnalyzer.MAX_EXPANSION_RATIO
}

/** A single declared entry size is valid while inside the ZIP format range. */
internal fun entrySizeAllowed(declaredSize: Long): Boolean =
    declaredSize >= 0L && declaredSize <= ModPackageAnalyzer.MAX_ENTRY_BYTES

private fun JSONObject.toMap(): Map<String, Any?> =
    keys().asSequence().associateWith { key -> toKotlinValue(get(key)) }

private fun toKotlinValue(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONObject -> value.toMap()
    is JSONArray -> (0 until value.length()).map { toKotlinValue(value.get(it)) }
    else -> value
}

fun analyzeModPackage(
    zipFile: File,
    installedApp: InstalledQuestApp? = null
): ModPackageAnalysis = ModPackageAnalyzer().analyze(zipFile, installedApp)
