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
        const val MAX_ZIP_ENTRIES = 5_000
        const val MAX_ENTRY_NAME_BYTES = 1_000
        const val MAX_METADATA_BYTES = 1L * 1024L * 1024L
        const val MAX_ENTRY_BYTES = 512L * 1024L * 1024L
        const val MAX_TOTAL_DECLARED_BYTES = 2L * 1024L * 1024L * 1024L
        private val ROOT_METADATA_NAMES = setOf("mod.json", "nfvr-mod.json", "qmod.json", "package.json")
        private val ROOT_MANIFEST_NAMES = ROOT_METADATA_NAMES
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private val QMOD_METADATA_KEYS = setOf(
            "name", "id", "author", "version", "description", "coverImage"
        )
        private val QMOD_IMPLEMENTED_KEYS = setOf(
            "packageId", "modloader", "modFiles", "lateModFiles",
            "libraryFiles", "fileCopies", "dependencies"
        )
        private val QMOD_UNIMPLEMENTED_KEYS = setOf(
            "packageVersion", "copyExtensions", "actions", "hooks", "postInstall",
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
    }

    fun analyze(zipFile: File, installedApp: InstalledQuestApp? = null): ModPackageAnalysis {
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
                rootMod != null -> analyzeQmod(archive, rootMod, installedApp)
                rootPackage != null && isVirtualStump(rootPackage) ->
                    analyzeVirtualStump(archive, rootPackage)
                rootNfvr != null -> analyzeNfvr(archive, rootNfvr, installedApp)
                archive.entries.any(::looksLikeGenericModPayload) -> analyzeGeneric(archive)
                else -> unknown(archive)
            }
        } catch (e: ModPackageException) {
            failed(e.message ?: "Package metadata is invalid.")
        } catch (e: Exception) {
            failed("Unable to inspect ZIP metadata: ${e.message ?: "invalid archive"}.")
        }
    }

    /** More explicit spelling for callers that distinguish analysis from inspection. */
    fun inspect(zipFile: File, installedApp: InstalledQuestApp? = null): ModPackageAnalysis =
        analyze(zipFile, installedApp)

    private fun inspectArchive(zipFile: File): ArchiveMetadata {
        require(zipFile.isFile) { "Selected mod archive does not exist." }
        val metadata = linkedMapOf<String, JSONObject>()
        val entries = mutableListOf<String>()
        val sizes = linkedMapOf<String, Long>()
        val hashes = linkedMapOf<String, String>()
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
                if (!collisionKeys.add(collisionKey(safeName))) {
                    throw ModPackageException("ZIP contains duplicate entry '$safeName'.")
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
        return ArchiveMetadata(entries, metadata, sizes, hashes, directories, identity)
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
        installedApp: InstalledQuestApp?
    ): ModPackageAnalysis {
        val target = json.optString("packageId", "").trim().ifBlank { null }
        val profile = target?.let(profileRegistry::findByPackageId)
        val preconditions = mutableListOf<ModInstallPrecondition>()
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
        if (profile == null && target != null) {
            preconditions += blocked("UNKNOWN_GAME_PROFILE", "No vetted destination is registered for '$target'.")
        }

        val loader = json.optString("modloader", "").trim()
        if (loader.isNotEmpty()) {
            preconditions += blocked(
                "UNSUPPORTED_MOD_LOADER",
                "QMOD requires modloader '$loader', which NFVR does not install or configure."
            )
        }
        if (hasPatchingRequirement(json)) {
            preconditions += blocked(
                "UNSUPPORTED_PATCHING",
                "This QMOD requires APK/game patching, which NFVR will not perform."
            )
        }
        val dependencies = json.opt("dependencies")
        if (dependencies != null && dependencies != JSONObject.NULL && hasEntries(dependencies)) {
            preconditions += blocked(
                "UNSUPPORTED_DEPENDENCIES",
                "This QMOD declares dependencies; NFVR does not download or install dependencies automatically."
            )
        }
        addStrictQmodSchemaPreconditions(json, preconditions)
        addDangerousPayloadPrecondition(archive, preconditions)

        val mappings = parseQmodMappings(archive, json, profile, preconditions)
        if (mappings.isEmpty()) {
            preconditions += blocked("NO_COPY_MAPPINGS", "QMOD contains no safe declarative files to copy.")
        }
        val plan = plan(
            type = ModPackageType.QMOD,
            target = target,
            profile = profile,
            mappings = mappings,
            strategy = ModInstallStrategy.PROFILE_COPY,
            preconditions = preconditions,
            archiveIdentity = archive.identity,
            reviewedApp = installedApp
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
        preconditions: MutableList<ModInstallPrecondition>
    ): List<ModFileMapping> {
        if (profile == null) return emptyList()
        val mappings = mutableListOf<ModFileMapping>()
        val copies = json.opt("fileCopies")
        if (copies != null && copies != JSONObject.NULL) {
            try {
                when (copies) {
                    is JSONArray -> {
                        for (i in 0 until copies.length()) {
                            val item = copies.getJSONObject(i)
                            if (item.keys().asSequence().any { it !in setOf("source", "destination") }) {
                                preconditions += blocked(
                                    "DANGEROUS_MANIFEST_KEY",
                                    "QMOD fileCopies may contain only source and destination."
                                )
                                continue
                            }
                            val source = item.getString("source")
                            val destination = item.getString("destination")
                            addMapping(archive, profile, mappings, source, destination, null, preconditions)
                        }
                    }
                    is JSONObject -> {
                        for (source in copies.keys()) {
                            addMapping(
                                archive, profile, mappings, source, copies.getString(source),
                                null, preconditions
                            )
                        }
                    }
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
                    addMapping(archive, profile, mappings, source, source, null, preconditions)
                }
            }
        }
        // Never infer a destination or copy every archive entry.  QMODs must
        // explicitly declare modFiles, lateModFiles, libraryFiles, or
        // fileCopies.
        return mappings.distinctBy { it.sourcePath to it.destinationPath }
    }

    private fun analyzeVirtualStump(
        archive: ArchiveMetadata,
        json: JSONObject
    ): ModPackageAnalysis {
        val sourceUrl = virtualStumpSourceUrl(json)
        val externalWorkflow = ModExternalWorkflow(
            sourceUrl = sourceUrl,
            actionUrl = sourceUrl ?: GORILLA_TAG_MOD_IO_URL,
            guidance = if (sourceUrl != null) {
                "افتح صفحة mod.io الموثوقة في المتصفح لإدارة محتوى Gorilla Tag. لا ينفذ NFVR تثبيتًا مباشرًا لهذا النوع."
            } else {
                "افتح صفحة Gorilla Tag على mod.io في المتصفح وابحث عن المحتوى المطلوب. لا يخمّن NFVR رابطًا أو وجهة تثبيت."
            }
        )
        val plan = ModInstallPlan(
            packageType = ModPackageType.GORILLA_TAG_VIRTUAL_STUMP,
            strategy = ModInstallStrategy.MOD_IO_MANAGED,
            archiveIdentity = archive.identity,
            preconditions = listOf(
                satisfied(
                    "MOD_IO_MANAGED",
                    "Gorilla Tag Virtual Stump content is managed by Gorilla Tag/mod.io; NFVR has no safe direct destination."
                )
            )
        )
        return result(
            ModPackageType.GORILLA_TAG_VIRTUAL_STUMP,
            "Recognized Gorilla Tag Virtual Stump map/gamemode package; it is not directly installable by NFVR.",
            plan,
            archive,
            json.toMap() + mapOf(
                "externalWorkflow" to "SUPPORTED_EXTERNAL_WORKFLOW",
                "modIoUrl" to sourceUrl
            ),
            externalWorkflow = externalWorkflow
        )
    }

    private fun virtualStumpSourceUrl(json: JSONObject): String? {
        val keys = listOf("modIoUrl", "modioUrl", "modio_url", "modio", "mod.io", "sourceUrl", "website", "url")
        return keys.asSequence()
            .mapNotNull { key -> json.optString(key, "").trim().ifBlank { null } }
            .mapNotNull(::validatedHttpsModIoUrl)
            .firstOrNull()
    }

    private fun analyzeGeneric(archive: ArchiveMetadata): ModPackageAnalysis {
        val plan = ModInstallPlan(
            packageType = ModPackageType.GENERIC_DATA,
            strategy = ModInstallStrategy.NONE,
            archiveIdentity = archive.identity,
            preconditions = listOf(
                blocked(
                    "DESTINATION_UNDECLARED",
                    "Generic mod data has no declarative destination; NFVR will not guess where to copy it."
                )
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
        installedApp: InstalledQuestApp?
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
        val loader = json.optString("requiredModLoader", "").trim()
        if (loader.isNotEmpty()) {
            preconditions += blocked("UNSUPPORTED_MOD_LOADER", "NFVR does not install or configure required modloader '$loader'.")
        }
        if (json.optString("modType", "").equals("apk-patch", true)) {
            preconditions += blocked("UNSUPPORTED_PATCHING", "NFVR manifests may not request APK patching.")
        }
        addDangerousManifestPreconditions(json, preconditions, NFVR_ALLOWED_KEYS)
        addDangerousPayloadPrecondition(archive, preconditions)

        val mappings = parseNfvrMappings(archive, json, profile, preconditions)
        if (mappings.isEmpty()) {
            preconditions += blocked("NO_COPY_MAPPINGS", "NFVR manifest must contain at least one safe file copy.")
        }
        val plan = plan(
            ModPackageType.NFVR_MANIFEST, target, profile, mappings,
            ModInstallStrategy.DECLARATIVE_COPY, preconditions,
            archive.identity, installedApp
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
        profile: GameModProfile,
        mappings: MutableList<ModFileMapping>,
        source: String,
        destination: String,
        sha256: String?,
        preconditions: MutableList<ModInstallPrecondition>
    ) {
        val sourcePath = safeRelativePath(source)
        val destinationPath = safeRelativePath(destination)
        val normalizedDestination = destination.trim().replace('\\', '/')
        val explicitFullDestination = normalizedDestination == profile.destination ||
            normalizedDestination.startsWith("${profile.destination}/")
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
            "${profile.destination}/${destinationPath!!}"
        }
        if (!AndroidPathValidator.isSafe(fullDestination) ||
            !fullDestination.startsWith("${profile.destination}/")
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
        reviewedApp: InstalledQuestApp? = null
    ): ModInstallPlan = ModInstallPlan(
        installable = mappings.isNotEmpty() && preconditions.none { !it.satisfied },
        packageType = type,
        targetPackageId = target,
        destinationRoot = profile?.destination,
        mappings = mappings,
        preconditions = preconditions,
        strategy = strategy,
        archiveIdentity = archiveIdentity,
        reviewedApp = reviewedApp
    )

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
        preconditions: MutableList<ModInstallPrecondition>
    ) {
        val dangerous = archive.entries.filter { path ->
            DANGEROUS_PAYLOAD_EXTENSIONS.any { path.lowercase(Locale.ROOT).endsWith(it) }
        }
        if (dangerous.isNotEmpty()) {
            preconditions += blocked(
                "DANGEROUS_PAYLOAD",
                "Package contains executable or script payloads that NFVR will not install: ${dangerous.take(5).joinToString()}."
            )
        }
    }

    private fun collisionKey(path: String): String =
        Normalizer.normalize(path.replace('\\', '/'), Normalizer.Form.NFC)
            .split('/')
            .filter { it.isNotBlank() && it != "." }
            .map { it.trimEnd(' ', '.') }
            .joinToString("/")
            .lowercase(Locale.ROOT)

    private fun unknown(archive: ArchiveMetadata): ModPackageAnalysis {
        val plan = ModInstallPlan(
            packageType = ModPackageType.UNKNOWN,
            archiveIdentity = archive.identity,
            preconditions = listOf(blocked("UNKNOWN_FORMAT", "This mod format is not recognized or its destination cannot be determined safely."))
        )
        return result(
            ModPackageType.UNKNOWN,
            "This mod format is not recognized or its installation destination cannot be determined safely.",
            plan,
            archive
        )
    }

    private fun failed(message: String): ModPackageAnalysis =
        ModPackageAnalysis(
            ModPackageType.UNKNOWN,
            recognized = false,
            message = message,
            compatibility = ModCompatibility(false, listOf(message)),
            installPlan = ModInstallPlan(
                archiveIdentity = null,
                preconditions = listOf(blocked("INVALID_ARCHIVE", message))
            )
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
        externalWorkflow = externalWorkflow
    )

    private fun blockingMessage(plan: ModInstallPlan): String =
        plan.preconditions.firstOrNull { !it.satisfied }?.message
            ?: "Package is recognized but no safe installation plan is available."

    private fun blocked(code: String, message: String) = ModInstallPrecondition(code, message, false)
    private fun satisfied(code: String, message: String) = ModInstallPrecondition(code, message, true)

    private fun validateEntryName(name: String): String {
        require(name.isNotBlank()) { "ZIP contains an empty entry name." }
        require(!name.contains('\u0000')) { "ZIP contains a NUL entry name." }
        require(name.toByteArray(StandardCharsets.UTF_8).size <= MAX_ENTRY_NAME_BYTES) {
            "ZIP entry name exceeds the safe limit."
        }
        val normalized = name.replace('\\', '/')
        require(!normalized.startsWith("/") && !normalized.startsWith("~")) { "ZIP contains an absolute entry path." }
        require(!normalized.substringBefore('/').contains(':')) { "ZIP contains an unsafe entry path." }
        val pieces = normalized.split('/')
        require(pieces.none { it.isEmpty() || it == "." || it == ".." }) { "ZIP contains a traversal entry path." }
        return pieces.joinToString("/")
    }

    private fun safeRelativePath(value: String): String? {
        val normalized = value.trim().replace('\\', '/')
        if (normalized.isBlank() || normalized.startsWith("/") || normalized.startsWith("~")) return null
        if (normalized.substringBefore('/').contains(':')) return null
        val pieces = normalized.split('/')
        if (pieces.any { it.isBlank() || it == "." || it == ".." }) return null
        if (pieces.any { it.any { char -> char == '\u0000' || char == ';' || char == '|' || char == '`' } }) return null
        return pieces.joinToString("/")
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
        val identity: ModArchiveIdentity? = null
    )

    private class ModPackageException(message: String) : Exception(message)

}

private fun sha256File(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
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
