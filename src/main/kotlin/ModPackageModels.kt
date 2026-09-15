/**
 * A package reported by `pm list packages` plus the version information needed
 * when checking a manifest.  The version is deliberately nullable: ADB can
 * report an installed package without a useful version name.
 */
data class InstalledQuestApp(
    val packageName: String,
    val versionName: String? = null,
    val versionCode: Long? = null,
    val displayName: String? = null,
    val apkPath: String? = null,
    val thirdParty: Boolean = true
) {
    val packageId: String
        get() = packageName
}

enum class ModPackageType {
    BONELAB_NATIVE_CONTENT,
    BONELAB_CODE_MOD,
    QMOD,
    GORILLA_TAG_VIRTUAL_STUMP,
    NFVR_MANIFEST,
    ANDROID_DATA_LAYOUT,
    ANDROID_OBB_LAYOUT,
    KNOWN_GAME_PROFILE,
    GENERIC_DATA,
    UNKNOWN;

    companion object {
        val VIRTUAL_STUMP: ModPackageType
            get() = GORILLA_TAG_VIRTUAL_STUMP
        val NFVR: ModPackageType
            get() = NFVR_MANIFEST
    }
}

enum class ModInstallStrategy {
    DECLARATIVE_COPY,
    PROFILE_COPY,
    BONELAB_CONTENT_COPY,
    ANDROID_DATA_COPY,
    ANDROID_OBB_COPY,
    MOD_IO_MANAGED,
    GENERIC_EXISTING_DIRECTORY_COPY,
    NONE
}

/**
 * Strategy selection is intentionally separate from the copy implementation.
 * The resolver exposes the evidence that caused a strategy to win, rather
 * than silently falling through a collection of game-specific heuristics.
 */
enum class ModResolutionStrategy {
    NFVR_MANIFEST,
    QMOD,
    ANDROID_FILESYSTEM_LAYOUT,
    KNOWN_MOD_LOADER_PACKAGE,
    KNOWN_GAME_PROFILE,
    EXISTING_GAME_MOD_DIRECTORY,
    BUILT_IN_CONTENT_TYPE,
    UNKNOWN
}

enum class ModEvidenceLevel {
    AUTHORITATIVE,
    OPEN_SOURCE_PROJECT,
    COMMUNITY_VERIFIED,
    HEURISTIC
}

data class ModStrategyEvidence(
    val strategy: ModResolutionStrategy,
    val level: ModEvidenceLevel,
    val confidence: Int,
    val evidence: List<String> = emptyList()
) {
    init {
        require(confidence in 0..100) { "confidence must be between 0 and 100" }
    }
}

data class ModStrategyDecision(
    val selected: ModResolutionStrategy,
    val confidence: Int,
    val evidence: List<ModStrategyEvidence> = emptyList()
) {
    init {
        require(confidence in 0..100) { "confidence must be between 0 and 100" }
    }
}

/**
 * The result is deliberately separate from [ModPackageType].  A package can
 * be understood (for example, a code mod or built-in game content) without
 * being directly writable by NFVR.
 */
enum class ModInstallOutcome {
    DIRECT_INSTALL_READY,
    REQUIRES_MOD_LOADER,
    APK_PATCH_REQUIRED,
    BUILT_IN_GAME_CONTENT,
    UNSUPPORTED,
    UNSAFE_ARCHIVE
}

/**
 * A package can be installable by NFVR, or intentionally handed to a
 * browser-based service.  Keeping this separate from installability prevents
 * an external workflow from being presented as a broken install plan.
 */
enum class ModWorkflowKind {
    DIRECT_INSTALL,
    SUPPORTED_EXTERNAL_WORKFLOW,
    UNSUPPORTED
}

enum class ModProgressKind {
    SCAN,
    ANALYZE,
    INSTALL,
    EXTERNAL
}

data class ModExternalWorkflow(
    val sourceUrl: String?,
    val actionUrl: String,
    val guidance: String,
    val browserOnly: Boolean = true
)

const val GORILLA_TAG_VIRTUAL_STUMP_PACKAGE_ID = "GORILLA_TAG_VIRTUAL_STUMP"
const val GORILLA_TAG_MOD_IO_URL = "https://mod.io/g/gorilla-tag"

/**
 * Only mod.io HTTPS pages are allowed to cross the browser boundary.  This
 * deliberately does not infer a game's slug or accept arbitrary redirect
 * URLs.
 */
fun validatedHttpsModIoUrl(raw: String?): String? {
    val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return runCatching {
        val uri = java.net.URI(value)
        val host = uri.host?.lowercase() ?: return@runCatching null
        if (uri.scheme?.lowercase() != "https" ||
            (host != "mod.io" && !host.endsWith(".mod.io")) ||
            uri.userInfo != null ||
            uri.fragment != null
        ) null else uri.toASCIIString()
    }.getOrNull()
}

fun isValidatedHttpsModIoUrl(raw: String?): Boolean = validatedHttpsModIoUrl(raw) != null

fun validateModIoUrl(raw: String?): String? = validatedHttpsModIoUrl(raw)

fun isValidModIoUrl(raw: String?): Boolean = isValidatedHttpsModIoUrl(raw)

fun classifyModWorkflow(
    packageType: ModPackageType,
    externalWorkflow: ModExternalWorkflow? = null
): ModWorkflowKind = when {
    externalWorkflow != null || packageType == ModPackageType.GORILLA_TAG_VIRTUAL_STUMP ->
        ModWorkflowKind.SUPPORTED_EXTERNAL_WORKFLOW
    packageType == ModPackageType.UNKNOWN -> ModWorkflowKind.UNSUPPORTED
    else -> ModWorkflowKind.DIRECT_INSTALL
}

fun isSupportedExternalWorkflow(packageType: ModPackageType): Boolean =
    classifyModWorkflow(packageType) == ModWorkflowKind.SUPPORTED_EXTERNAL_WORKFLOW

/**
 * Warnings can be emitted by both schema and payload validation.  Use the
 * stable code as the identity so a user sees one actionable explanation.
 */
fun deduplicateModWarnings(warnings: List<ModInstallPrecondition>): List<ModInstallPrecondition> {
    val seen = mutableSetOf<String>()
    return warnings.filter { warning ->
        val key = warning.code.trim().ifBlank { warning.message.trim() }
        seen.add(key)
    }
}

fun shouldCollapseInstalledAppList(
    selectedApp: InstalledQuestApp?,
    changeRequested: Boolean
): Boolean = selectedApp != null && !changeRequested

data class ModCompatibility(
    val compatible: Boolean,
    val reasons: List<String> = emptyList()
)

data class ModFileMapping(
    val sourcePath: String,
    val destinationPath: String,
    val sha256: String? = null,
    val sizeBytes: Long = 0L
)

data class ModArchiveIdentity(
    val canonicalPath: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val sha256: String
)

data class ModPackageDependency(
    val id: String,
    val version: String? = null,
    val downloadRequired: Boolean = true,
    val optional: Boolean = false,
    val sourceUrl: String? = null,
    val required: Boolean = !optional
)

/**
 * Patching is a capability boundary, not an installation command.  NFVR
 * records why a package needs an APK patch while leaving the actual patch
 * operation to a separately reviewed implementation.
 */
data class ModPatchRequirement(
    val required: Boolean,
    val reason: String,
    val patcherId: String? = null,
    val supported: Boolean = false
)

data class ModDirectoryCandidate(
    val packageId: String,
    val path: String,
    val exists: Boolean,
    val source: String,
    val evidenceLevel: ModEvidenceLevel = ModEvidenceLevel.OPEN_SOURCE_PROJECT,
    val readOnly: Boolean = true
)

data class ModDirectoryDiscovery(
    val serial: String,
    val packageId: String,
    val appVersion: String? = null,
    val candidates: List<ModDirectoryCandidate> = emptyList(),
    val loaderDetection: ModLoaderDetection? = null,
    val profile: GameModProfile? = null,
    val diagnostics: List<String> = emptyList()
) {
    val existingCandidates: List<ModDirectoryCandidate>
        get() = candidates.filter { it.exists }
}

data class ModInstallConfirmation(
    val token: String,
    val destination: String,
    val reason: String,
    val required: Boolean = true
)

fun modInstallConfirmationToken(
    archiveSha256: String,
    packageId: String,
    destination: String
): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest("$archiveSha256\u001f$packageId\u001f$destination".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

data class ModInstallPrecondition(
    val code: String,
    val message: String,
    val satisfied: Boolean = false
)

/**
 * A loader check is always represented in the plan, even when it could not be
 * performed during offline analysis.  The manager performs the same check
 * again immediately before the first write.
 */
data class ModLoaderRequirement(
    val requested: Set<ModLoaderKind>,
    val detection: ModLoaderDetection? = null,
    val message: String = "",
    val targetPackageId: String? = null
) {
    val satisfied: Boolean
        get() = detection != null &&
            (targetPackageId == null || detection.packageId == targetPackageId) &&
            detection.hasAny(requested)

    val status: ModLoaderStatus
        get() = when {
            detection == null -> ModLoaderStatus.UNKNOWN
            targetPackageId != null && detection.packageId != targetPackageId -> ModLoaderStatus.UNKNOWN
            else -> detection.statusFor(requested)
        }
}

/**
 * This is a plan, not an instruction to execute.  In particular, no shell
 * command or executable action can be represented by this model.
 */
data class ModInstallPlan(
    val installable: Boolean = false,
    val outcome: ModInstallOutcome = ModInstallOutcome.UNSUPPORTED,
    val packageType: ModPackageType = ModPackageType.UNKNOWN,
    val targetPackageId: String? = null,
    val destinationRoot: String? = null,
    val mappings: List<ModFileMapping> = emptyList(),
    val preconditions: List<ModInstallPrecondition> = emptyList(),
    val strategy: ModInstallStrategy = ModInstallStrategy.NONE,
    val totalBytes: Long = mappings.sumOf { it.sizeBytes },
    val totalFiles: Int = mappings.size,
    val archiveIdentity: ModArchiveIdentity? = null,
    val reviewedApp: InstalledQuestApp? = null,
    val loaderRequirement: ModLoaderRequirement? = null,
    val diagnostics: List<String> = emptyList(),
    val resolution: ModStrategyDecision = ModStrategyDecision(
        ModResolutionStrategy.UNKNOWN,
        confidence = 0
    ),
    val dependencies: List<ModPackageDependency> = emptyList(),
    val optionalDependencies: List<ModPackageDependency> = emptyList(),
    val patchRequirement: ModPatchRequirement? = null,
    val confirmation: ModInstallConfirmation? = null,
    val analysisPlanId: String? = null,
    val operationBinding: ModOperationBinding? = null
) {
    val fileMappings: List<ModFileMapping>
        get() = mappings

    val archiveSha256: String?
        get() = archiveIdentity?.sha256

    val reviewedAppPackageName: String?
        get() = reviewedApp?.packageName

    val reviewedAppVersionName: String?
        get() = reviewedApp?.versionName

    val hasBlockingPreconditions: Boolean
        get() = preconditions.any { !it.satisfied }

    val requiresExplicitConfirmation: Boolean
        get() = confirmation?.required == true &&
            preconditions.any { !it.satisfied && it.code == "EXPLICIT_CONFIRMATION_REQUIRED" }

    /**
     * Bind an immutable analysis to the selected headset.  The helper only
     * creates a binding; it never performs I/O or makes an unbound plan
     * installable.
     */
    fun bindToDevice(serial: String): ModInstallPlan {
        val normalizedSerial = serial.trim()
        require(normalizedSerial.isNotEmpty()) { "device serial is required" }
        val app = reviewedApp ?: error("an installed app is required before device binding")
        val archive = archiveIdentity ?: error("an archive identity is required before device binding")
        val id = modAnalysisPlanId(normalizedSerial, app, archive.sha256)
        return copy(
            analysisPlanId = id,
            operationBinding = ModOperationBinding(
                deviceSerial = normalizedSerial,
                packageId = app.packageName,
                gameVersion = app.versionName,
                archiveSha256 = archive.sha256,
                analysisPlanId = id
            )
        )
    }

    /**
     * Generic existing-directory proposals must be explicitly accepted by the
     * caller that showed the destination to the user.
     */
    fun confirmDestination(token: String): ModInstallPlan {
        if (confirmation?.token != token) return this
        val updated = preconditions.map { precondition ->
            if (precondition.code == "EXPLICIT_CONFIRMATION_REQUIRED") {
                precondition.copy(satisfied = true)
            } else {
                precondition
            }
        }
        val ready = mappings.isNotEmpty() && updated.none { !it.satisfied }
        return copy(
            installable = ready,
            outcome = if (ready) ModInstallOutcome.DIRECT_INSTALL_READY else outcome,
            preconditions = updated
        )
    }

    fun progress(copiedBytes: Long, copiedFiles: Int): ModInstallProgress =
        ModInstallProgress(copiedBytes, totalBytes, copiedFiles, totalFiles)
}

data class ModInstallProgress(
    val copiedBytes: Long,
    val totalBytes: Long,
    val copiedFiles: Int,
    val totalFiles: Int
) {
    val fraction: Double
        get() = when {
            totalBytes > 0L -> (copiedBytes.toDouble() / totalBytes).coerceIn(0.0, 1.0)
            totalFiles > 0 -> (copiedFiles.toDouble() / totalFiles).coerceIn(0.0, 1.0)
            else -> 0.0
        }

    val percent: Int
        get() = (fraction * 100.0).toInt()
}

data class ModPackageAnalysis(
    val packageType: ModPackageType,
    val recognized: Boolean,
    val message: String,
    val compatibility: ModCompatibility,
    val installPlan: ModInstallPlan,
    val metadata: Map<String, Any?> = emptyMap(),
    val entries: List<String> = emptyList(),
    val externalWorkflow: ModExternalWorkflow? = null,
    val diagnostics: List<String> = emptyList()
) {
    val installable: Boolean
        get() = installPlan.installable

    /** Alias useful to callers that prefer the shorter term. */
    val plan: ModInstallPlan
        get() = installPlan

    val workflow: ModWorkflowKind
        get() = classifyModWorkflow(packageType, externalWorkflow)

    val isExternalWorkflow: Boolean
        get() = externalWorkflow != null

    val outcome: ModInstallOutcome
        get() = installPlan.outcome

    val requiresModLoader: Boolean
        get() = outcome == ModInstallOutcome.REQUIRES_MOD_LOADER

    val isBuiltInGameContent: Boolean
        get() = outcome == ModInstallOutcome.BUILT_IN_GAME_CONTENT

    val externalSourceUrl: String?
        get() = externalWorkflow?.sourceUrl

    val externalActionUrl: String?
        get() = externalWorkflow?.actionUrl

    val strategy: ModResolutionStrategy
        get() = installPlan.resolution.selected

    val confidence: Int
        get() = installPlan.resolution.confidence
}

data class GameModProfile(
    val packageId: String,
    val displayName: String,
    val destination: String,
    val supportedPackageTypes: Set<ModPackageType> = setOf(
        ModPackageType.QMOD,
        ModPackageType.NFVR_MANIFEST
    ),
    val minimumGameVersion: String? = null,
    val maximumGameVersion: String? = null,
    val knownContentDirectories: Set<String> = emptySet(),
    val recognizedArchiveSignatures: Set<String> = emptySet(),
    val loaderRequirements: Set<ModLoaderKind> = emptySet(),
    val engine: String = "Unknown",
    val authoritativePaths: Set<String> = emptySet(),
    val modTypes: Set<ModPackageType> = supportedPackageTypes,
    val installationStrategies: Set<ModResolutionStrategy> = setOf(
        ModResolutionStrategy.KNOWN_GAME_PROFILE
    ),
    val evidenceLevel: ModEvidenceLevel = ModEvidenceLevel.HEURISTIC,
    val evidenceSources: List<String> = emptyList(),
    val versionRules: Map<String, String> = emptyMap()
)

/**
 * Destinations are intentionally kept in one registry.  Adding a game here
 * is an explicit security decision; the analyzer never derives a destination
 * from a package name or from an archive entry.
 */
object GameModProfileRegistry {
    private val registeredProfiles: List<GameModProfile> = loadProfiles()

    val profiles: List<GameModProfile>
        get() = registeredProfiles

    fun findByPackageId(packageId: String): GameModProfile? =
        registeredProfiles.firstOrNull { it.packageId == packageId }

    fun forPackage(packageId: String): GameModProfile? = findByPackageId(packageId)

    private fun loadProfiles(): List<GameModProfile> {
        val stream = GameModProfileRegistry::class.java.classLoader
            ?.getResourceAsStream("mod-profiles.json")
            ?: return emptyList()
        return runCatching {
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                val root = org.json.JSONArray(reader.readText())
                (0 until root.length()).mapNotNull { index ->
                    val item = root.optJSONObject(index) ?: return@mapNotNull null
                    val packageId = item.optString("packageId").trim()
                    val destination = item.optString("destination").trim()
                    if (packageId.isBlank() || destination.isBlank()) return@mapNotNull null
                    fun stringSet(key: String): Set<String> =
                        item.optJSONArray(key)?.let { array ->
                            (0 until array.length()).mapNotNull {
                                array.optString(it).trim().takeIf(String::isNotBlank)
                            }.toSet()
                        } ?: emptySet()
                    fun enumSet(key: String): Set<ModPackageType> =
                        stringSet(key).mapNotNull { value ->
                            runCatching { ModPackageType.valueOf(value) }.getOrNull()
                        }.toSet()
                    val supported = enumSet("supportedPackageTypes").ifEmpty {
                        setOf(ModPackageType.QMOD, ModPackageType.NFVR_MANIFEST)
                    }
                    val loaders = stringSet("loaderRequirements").mapNotNull {
                        runCatching { ModLoaderKind.valueOf(it) }.getOrNull()
                    }.toSet()
                    val evidence = runCatching {
                        ModEvidenceLevel.valueOf(item.optString("evidenceLevel"))
                    }.getOrDefault(ModEvidenceLevel.HEURISTIC)
                    GameModProfile(
                        packageId = packageId,
                        displayName = item.optString("displayName", packageId),
                        destination = destination,
                        supportedPackageTypes = supported,
                        minimumGameVersion = item.optString("minimumGameVersion").ifBlank { null },
                        maximumGameVersion = item.optString("maximumGameVersion").ifBlank { null },
                        knownContentDirectories = stringSet("knownContentDirectories"),
                        recognizedArchiveSignatures = stringSet("recognizedArchiveSignatures"),
                        loaderRequirements = loaders,
                        engine = item.optString("engine", "Unknown"),
                        authoritativePaths = stringSet("authoritativePaths").ifEmpty {
                            setOf(destination)
                        },
                        modTypes = enumSet("modTypes").ifEmpty { supported },
                        installationStrategies = stringSet("installationStrategies")
                            .mapNotNull { runCatching { ModResolutionStrategy.valueOf(it) }.getOrNull() }
                            .toSet()
                            .ifEmpty { setOf(ModResolutionStrategy.KNOWN_GAME_PROFILE) },
                        evidenceLevel = evidence,
                        evidenceSources = stringSet("evidenceSources").toList(),
                        versionRules = emptyMap()
                    )
                }
            }
        }.getOrElse { emptyList() }
    }
}