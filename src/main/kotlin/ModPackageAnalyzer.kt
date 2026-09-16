import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
        const val GORILLA_TAG_PACKAGE_ID = "com.AnotherAxiom.GorillaTag"
        const val MAX_ZIP_ENTRIES = 5_000
        const val MAX_ENTRY_NAME_BYTES = 1_000
        const val MAX_METADATA_BYTES = 1L * 1024L * 1024L
        const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
        const val MAX_TOTAL_DECLARED_BYTES = 2L * 1024L * 1024L * 1024L
        private val ROOT_METADATA_NAMES = setOf("mod.json", "nfvr-mod.json", "qmod.json", "package.json")
        private val ROOT_MANIFEST_NAMES = ROOT_METADATA_NAMES
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
        return try {
            val archive = inspectArchive(zipFile)
            val rootManifestCandidates = ROOT_MANIFEST_NAMES.filter(archive.metadata::containsKey)
            if (rootManifestCandidates.size > 1) {
                throw ModPackageException(
                    "ZIP contains multiple conflicting root manifests: ${rootManifestCandidates.joinToString()}."
                )
            }
            val rootMod = archive.metadata["mod.json"]
            val rootPackage = archive.metadata["package.json"]
            val rootNfvr = archive.metadata["nfvr-mod.json"]

            when {
                rootMod != null -> analyzeQmod(archive, rootMod, installedApp, loaderDetection)
                rootPackage != null && isVirtualStump(rootPackage) ->
                    analyzeVirtualStump(archive, rootPackage)
                rootNfvr != null -> analyzeNfvr(archive, rootNfvr, installedApp, loaderDetection)
                isAndroidDataLayout(archive) ->
                    analyzeAndroidLayout(archive, installedApp, obb = false)
                isAndroidObbLayout(archive) ->
                    analyzeAndroidLayout(archive, installedApp, obb = true)
                isBonelabPayload(archive) ->
                    analyzeBonelabPayload(archive, installedApp, loaderDetection)
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
            failed(e.message ?: "Package metadata is invalid.", e.diagnosticEntry)
        } catch (e: Exception) {
            failed("Unable to inspect ZIP metadata: ${e.message ?: "invalid archive"}.")
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
        val metadata = linkedMapOf<String, JSONObject>()
        val entries = mutableListOf<String>()
        val sizes = linkedMapOf<String, Long>()
        val hashes = linkedMapOf<String, String>()
        val prefixes = linkedMapOf<String, ByteArray>()
        val directories = linkedSetOf<String>()
        val collisionKeys = mutableSetOf<String>()
        var declaredBytes = 0L

        ZipFile(zipFile).use { zip ->
            val iterator = zip.entries()
            var count = 0
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                count++
                if (count > MAX_ZIP_ENTRIES) {
                    throw ModPackageException("ZIP contains more than $MAX_ZIP_ENTRIES entries.")
                }
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
                if (entry.size >= 0L) {
                    declaredBytes += entry.size
                    if (declaredBytes > MAX_TOTAL_DECLARED_BYTES) {
                        throw ModPackageException("ZIP declares more data than the safe archive limit.")
                    }
                }
                if (!entry.isDirectory && safeName in ROOT_METADATA_NAMES) {
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
                        if (bytes > MAX_ENTRY_BYTES || actualBytes > MAX_TOTAL_DECLARED_BYTES) {
                            throw ModPackageException("ZIP expands beyond the safe inspection limit.")
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
        return ArchiveMetadata(entries, metadata, sizes, hashes, directories, prefixes, identity)
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
        return out.toString(StandardCharsets.UTF_8.name())
    }

    private fun analyzeQmod(
        archive: ArchiveMetadata,
        json: JSONObject,
        installedApp: InstalledQuestApp?,
        loaderDetection: ModLoaderDetection?
    ): ModPackageAnalysis {
        val target = json.optString("packageId", "").trim().ifBlank { null }
        val profile = target?.let(profileRegistry::findByPackageId)
        val preconditions = mutableListOf<ModInstallPrecondition>()
        validateQmodSchema(json, preconditions)
        if (target == null) {
            preconditions += blocked("TARGET_PACKAGE_MISSING", "QMOD does not declare a target packageId.")
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
        // an explicit loader declaration and authenticated QuestPatcher tag
        // are both required before loader-defined destinations are usable.
        val loaderValue = json.optString("modloader", "").trim()
        val isGorillaTag = target == GORILLA_TAG_PACKAGE_ID
        if (isGorillaTag && loaderValue.isBlank()) {
            preconditions += blocked(
                "MOD_LOADER_REQUIRED",
                "Gorilla Tag Quest compatibility is not established; this QMOD must declare a loader and use authenticated QuestPatcher evidence."
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
            preconditions += satisfied(
                "OPTIONAL_DEPENDENCIES_UNRESOLVED",
                "Optional QMOD dependencies were recorded for review; NFVR will not download them automatically."
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
                                !item.has("name")
                            ) {
                                preconditions += blocked(
                                    "INVALID_FILE_COPIES",
                                    "QMOD fileCopies requires canonical name and destination fields only."
                                )
                                continue
                            }
                            val source = item.optString("name", "")
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
            val list = json.optJSONArray(field) ?: continue
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
                    val id = value.optString("id")
                        .ifBlank { value.optString("modId") }
                        .ifBlank { value.optString("packageId") }
                        .ifBlank { fallbackId.orEmpty() }
                    val version = value.optString("version")
                        .ifBlank { value.optString("versionRange") }
                        .ifBlank { null }
                    val sourceUrl = value.optString("downloadIfMissing")
                        .ifBlank { value.optString("url") }
                        .ifBlank { value.optString("downloadUrl") }
                        .ifBlank { null }
                    val canonicalArrayEntry = fallbackId == null
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
                    } else if (id.isBlank() ||
                        (canonicalArrayEntry && version.isNullOrBlank())
                    ) {
                        preconditions += blocked(
                            "INVALID_DEPENDENCY",
                            "QMOD dependency entries must declare non-empty id and version."
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
                    val id = value.trim().ifBlank { fallbackId.orEmpty() }
                    if (id.isBlank() || fallbackId == null) {
                        preconditions += blocked(
                            "INVALID_DEPENDENCY",
                            "QMOD dependency entries must be objects with id and version."
                        )
                    } else {
                        result += ModPackageDependency(
                            id = id,
                            optional = optional,
                            required = !optional
                        )
                    }
                }
                else -> preconditions += blocked(
                    "INVALID_DEPENDENCY",
                    "QMOD dependencies must be objects, IDs, or an ID map."
                )
            }
        }

        when (raw) {
            is JSONArray -> for (index in 0 until raw.length()) add(raw.opt(index))
            is JSONObject -> for (key in raw.keys()) add(raw.opt(key), key)
            is String -> add(raw)
            else -> preconditions += blocked(
                "INVALID_DEPENDENCY",
                "QMOD dependencies must be an array, object, or ID."
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

    private fun isBonelabPayload(archive: ArchiveMetadata): Boolean {
        val roots = archive.entries.mapNotNull { it.substringBefore('/').takeIf { root -> root.isNotBlank() } }.toSet()
        if (roots.isEmpty()) return false
        if (archive.entries.any(::looksLikeCodeModPayload)) return true
        return archive.entries.any { path ->
            val lower = path.lowercase(Locale.ROOT)
            lower.endsWith(".pallet") ||
                lower.endsWith(".marrow") ||
                lower.endsWith(".assetbundle") ||
                lower.endsWith(".bundle") ||
                lower.endsWith(".asset") ||
                lower.endsWith("/manifest.json") ||
                lower.endsWith("/pallet.json") ||
                lower.endsWith("/catalog.json")
        }
    }

    private fun analyzeBonelabPayload(
        archive: ArchiveMetadata,
        installedApp: InstalledQuestApp?,
        loaderDetection: ModLoaderDetection?
    ): ModPackageAnalysis {
        val profile = profileRegistry.findByPackageId(BONELAB_PACKAGE_ID)
            ?: GameModProfile(BONELAB_PACKAGE_ID, "BONELAB", "/sdcard/Android/data/$BONELAB_PACKAGE_ID/files/Mods")
        val preconditions = mutableListOf<ModInstallPrecondition>()
        checkTarget(BONELAB_PACKAGE_ID, installedApp, "BONELAB", preconditions)
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
        val codeMod = selectedEntries.any(::looksLikeCodeModPayload)
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
                val platformRelative = platformPrefix?.let {
                    entry.removePrefix("$it/")
                } ?: entry
                val relative = stripBonelabWrappers(platformRelative)
                if (relative.isBlank() || !relative.contains('/')) {
                    preconditions += blocked("MOD_FOLDER_REQUIRED", "BONELAB content files must be inside a complete mod folder.")
                    continue
                }
                val destination = "${profile.destination}/${relative}"
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
        if (profile.packageId == BONELAB_PACKAGE_ID) return false
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
        if (target == GORILLA_TAG_PACKAGE_ID) {
            val preconditions = listOf(
                blocked(
                    "GORILLA_TAG_DESTINATION_UNAUTHORIZED",
                    "Gorilla Tag Quest has no authoritative generic directory contract; only an exact QMOD with authenticated QuestLoader evidence may use a canonical loader destination."
                )
            )
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
                    reason = "Generic Gorilla Tag Quest archives cannot be authorized from directory discovery."
                ),
                resolution = resolution(
                    ModResolutionStrategy.UNKNOWN,
                    0,
                    "Gorilla Tag classification-only profile rejects generic directory routing."
                )
            )
            return result(
                ModPackageType.GENERIC_DATA,
                "Gorilla Tag Quest generic archives cannot be installed from discovered directories; use an exact QMOD with authenticated QuestLoader evidence.",
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
        json: JSONObject
    ): ModPackageAnalysis {
        val plan = ModInstallPlan(
            outcome = ModInstallOutcome.BUILT_IN_GAME_CONTENT,
            packageType = ModPackageType.GORILLA_TAG_VIRTUAL_STUMP,
            strategy = ModInstallStrategy.NONE,
            archiveIdentity = archive.identity,
            preconditions = listOf(
                satisfied(
                    "BUILT_IN_GAME_CONTENT",
                    "Virtual Stump content is managed by Gorilla Tag's built-in custom-content system; NFVR has no safe direct destination."
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
            "Recognized Gorilla Tag Virtual Stump content; manage it through the game's built-in custom-content system.",
            plan,
            archive,
            json.toMap()
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
            archiveIdentity = archive.identity,
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
        val collision = mappings.firstOrNull {
            collisionKey(it.sourcePath) == sourceCollision ||
                collisionKey(it.destinationPath) == destinationCollision
        }
        if (collision != null) {
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
            hardPayloadBlock -> ModInstallOutcome.UNSUPPORTED
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
            ModPackageType.GORILLA_TAG_VIRTUAL_STUMP -> ModResolutionStrategy.BUILT_IN_CONTENT_TYPE
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
        val requested = json.optString("packageVersion", "").trim()
        if (requested.isBlank()) {
            preconditions += blocked("INVALID_PACKAGE_VERSION", "QMOD packageVersion must be a non-empty version.")
        } else if (app?.versionName == null) {
            preconditions += blocked("GAME_VERSION_REQUIRED", "QMOD declares packageVersion but the installed version is unavailable.")
        } else if (compareVersions(app.versionName, requested) != 0) {
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
                it.lowercase(Locale.ROOT).endsWith(".so")
            }) {
            preconditions += blocked(
                "NATIVE_PAYLOAD_REQUIRES_LOADER_ROOT",
                "BONELAB code plans do not accept unvalidated native SO payloads."
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

    private fun failed(message: String, diagnosticEntry: String? = null): ModPackageAnalysis =
        ModPackageAnalysis(
            ModPackageType.UNKNOWN,
            recognized = false,
            message = message,
            compatibility = ModCompatibility(false, listOf(message)),
            installPlan = ModInstallPlan(
                outcome = ModInstallOutcome.UNSAFE_ARCHIVE,
                archiveIdentity = null,
                preconditions = listOf(
                    blocked(
                        "UNSAFE_ARCHIVE",
                        if (diagnosticEntry == null) message else "$message (entry '$diagnosticEntry')"
                    )
                ),
                diagnostics = listOfNotNull(diagnosticEntry)
            ),
            diagnostics = listOfNotNull(diagnosticEntry)
        )

    private fun result(
        type: ModPackageType,
        message: String,
        plan: ModInstallPlan,
        archive: ArchiveMetadata,
        metadata: Map<String, Any?> = emptyMap(),
        externalWorkflow: ModExternalWorkflow? = null
    ): ModPackageAnalysis = ModPackageAnalysis(
        packageType = type,
        recognized = type != ModPackageType.UNKNOWN,
        message = message,
        compatibility = if (externalWorkflow != null) {
            ModCompatibility(true)
        } else {
            ModCompatibility(plan.installable, plan.preconditions.filterNot { it.satisfied }.map { it.message })
        },
        installPlan = plan,
        metadata = metadata,
        entries = archive.entries,
        externalWorkflow = externalWorkflow,
        diagnostics = plan.diagnostics.distinct()
    )

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
        val identity: ModArchiveIdentity? = null
    )

    private class ModPackageException(
        message: String,
        val diagnosticEntry: String? = null
    ) : Exception(message)

}

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
