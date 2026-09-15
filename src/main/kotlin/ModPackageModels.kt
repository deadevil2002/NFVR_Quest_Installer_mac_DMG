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
    NONE
}

/**
 * The result is deliberately separate from [ModPackageType].  A package can
 * be understood (for example, a code mod or built-in game content) without
 * being directly writable by NFVR.
 */
enum class ModInstallOutcome {
    DIRECT_INSTALL_READY,
    REQUIRES_MOD_LOADER,
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
    val diagnostics: List<String> = emptyList()
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
    val loaderRequirements: Set<ModLoaderKind> = emptySet()
)

/**
 * Destinations are intentionally kept in one registry.  Adding a game here
 * is an explicit security decision; the analyzer never derives a destination
 * from a package name or from an archive entry.
 */
object GameModProfileRegistry {
    private val registeredProfiles = listOf(
        profile(
            "com.StressLevelZero.BONELAB",
            "BONELAB",
            supportedPackageTypes = setOf(
                ModPackageType.BONELAB_NATIVE_CONTENT,
                ModPackageType.BONELAB_CODE_MOD,
                ModPackageType.QMOD,
                ModPackageType.NFVR_MANIFEST
            ),
            knownContentDirectories = setOf("Mods")
        ),
        profile(
            "com.beatgames.beatsaber",
            "Beat Saber",
            "/sdcard/ModData/com.beatgames.beatsaber/Mods",
            loaderRequirements = setOf(ModLoaderKind.QUEST_LOADER, ModLoaderKind.SCOTLAND2)
        )
    )

    val profiles: List<GameModProfile>
        get() = registeredProfiles

    fun findByPackageId(packageId: String): GameModProfile? =
        registeredProfiles.firstOrNull { it.packageId == packageId }

    fun forPackage(packageId: String): GameModProfile? = findByPackageId(packageId)

    private fun profile(
        packageId: String,
        name: String,
        destination: String = "/sdcard/Android/data/$packageId/files/Mods",
        supportedPackageTypes: Set<ModPackageType> = setOf(
            ModPackageType.QMOD,
            ModPackageType.NFVR_MANIFEST
        ),
        knownContentDirectories: Set<String> = setOf("Mods"),
        loaderRequirements: Set<ModLoaderKind> = emptySet()
    ): GameModProfile =
        GameModProfile(
            packageId = packageId,
            displayName = name,
            destination = destination,
            supportedPackageTypes = supportedPackageTypes,
            knownContentDirectories = knownContentDirectories,
            recognizedArchiveSignatures = setOf("mod.json", "nfvr-mod.json"),
            loaderRequirements = loaderRequirements
        )
}