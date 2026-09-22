import org.json.JSONObject

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
    val thirdParty: Boolean = true,
    /**
     * Optional hash captured by the read-only APK inspection.  Keeping this
     * on the selected-app identity lets a profile (and an eventual manager)
     * bind preparation evidence to the actual APK bytes rather than only to
     * a package/version pair.
     */
    val apkSha256: String? = null
) {
    val packageId: String
        get() = packageName
}

enum class ModPackageType {
    BONELAB_NATIVE_CONTENT,
    BONELAB_CODE_MOD,
    QMOD,
    GORILLA_TAG_VIRTUAL_STUMP,
    PAVLOV_UGC_CONTENT,
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
        val NOMAD_CONTENT: ModPackageType
            get() = KNOWN_GAME_PROFILE
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
 * The workflow is selected from the analyzed archive, not from the selected
 * game's name.  This prevents data-only content and built-in game content
 * from being routed through APK preparation merely because the game supports
 * code mods too.
 */
enum class ModWorkflowRoute {
    CONTENT_MOD,
    LOADER_CODE_MOD,
    VIRTUAL_STUMP,
    APK_PATCH_REQUIRED,
    UNSUPPORTED
}

data class ModWorkflowRouting(
    val route: ModWorkflowRoute,
    val preparationRequired: Boolean,
    val installAvailable: Boolean,
    val requiredLoader: String? = null,
    val reason: String? = null
)

fun routeModWorkflow(analysis: ModPackageAnalysis): ModWorkflowRouting {
    val plan = analysis.installPlan
    return when {
        analysis.packageType == ModPackageType.GORILLA_TAG_VIRTUAL_STUMP ||
            analysis.outcome == ModInstallOutcome.BUILT_IN_GAME_CONTENT ->
            ModWorkflowRouting(
                ModWorkflowRoute.VIRTUAL_STUMP,
                preparationRequired = false,
                installAvailable = false,
                reason = "محتوى تديره اللعبة؛ لا يوجد عقد استيراد محلي موثق."
            )
        analysis.outcome == ModInstallOutcome.APK_PATCH_REQUIRED ->
            ModWorkflowRouting(
                ModWorkflowRoute.APK_PATCH_REQUIRED,
                preparationRequired = true,
                installAvailable = false,
                reason = "تحتاج الحزمة إلى تجهيز APK قبل التثبيت."
            )
        analysis.outcome == ModInstallOutcome.REQUIRES_MOD_LOADER ->
            ModWorkflowRouting(
                ModWorkflowRoute.LOADER_CODE_MOD,
                preparationRequired = true,
                installAvailable = false,
                requiredLoader = plan.loaderRequirement
                    ?.requested
                    ?.joinToString(" أو ") { it.displayName }
                    ?.takeIf(String::isNotBlank),
                reason = "تحتاج الحزمة إلى محمّل مودات متوافق."
            )
        analysis.outcome == ModInstallOutcome.DIRECT_INSTALL_READY &&
            plan.installable ->
            ModWorkflowRouting(
                ModWorkflowRoute.CONTENT_MOD,
                preparationRequired = false,
                installAvailable = true
            )
        else ->
            ModWorkflowRouting(
                ModWorkflowRoute.UNSUPPORTED,
                preparationRequired = false,
                installAvailable = false,
                reason = customerAnalysisMessage(analysis)
            )
    }
}

fun customerPreconditionMessage(code: String): String = when {
    code == "APK_PATCH_REQUIRED" -> "تحتاج هذه الحزمة إلى تصحيح آمن لتطبيق اللعبة قبل التثبيت."
    code == "PAVLOV_IMPORTER_UNVERIFIED" ->
        "هذا محتوى Pavlov من mod.io تديره اللعبة؛ لا يملك NFVR عقد استيراد محليًا موثقًا وآمنًا."
    code == "UNSAFE_DESTINATION" ->
        "يحتوي الأرشيف ملفات بأسماء لا يمكن نقلها بأمان إلى النظارة؛ لم يتم نقل أي ملف."
    code == "NOMAD_GAME_VERSION_COMPATIBILITY_WARNING" ->
        "تدعم بيانات الإصدار الحالية هذه الحزمة ضمن عائلة اللعبة نفسها، لكن مقارنة GameVersion في وقت التشغيل غير موثقة علنًا؛ التثبيت متاح لأن جميع فحوص المحتوى والوجهة والأمان نجحت."
    code.contains("LOADER", ignoreCase = true) -> "يجب تجهيز محمّل المودات المطلوب ثم إعادة التحليل."
    code.contains("TARGET_APP_REQUIRED", ignoreCase = true) -> "اختر لعبة مثبتة على النظارة أولًا."
    code.contains("APK_SHA256_REQUIRED", ignoreCase = true) ->
        "تعذر إثبات بصمة APK المطابقة؛ أعد فحص اللعبة قبل تثبيت المحتوى."
    code.contains("STALE", ignoreCase = true) -> "تغيرت حالة الجهاز أو الحزمة؛ أعد التحليل قبل المتابعة."
    code.contains("UNSAFE", ignoreCase = true) ||
        code.contains("CORRUPT", ignoreCase = true) ||
        code.contains("MALFORMED", ignoreCase = true) ||
        code.contains("TRAVERSAL", ignoreCase = true) -> "الأرشيف غير آمن أو تالف ولا يمكن استخدامه."
    code.contains("UNSUPPORTED", ignoreCase = true) ||
        code.contains("UNKNOWN", ignoreCase = true) -> "صيغة الحزمة غير مدعومة حاليًا."
    code.contains("CONFIRM", ignoreCase = true) -> "يجب تأكيد الوجهة المقترحة قبل التثبيت."
    else -> "لا يمكن تثبيت هذه الحزمة بهذه الخطة."
}

fun customerAnalysisMessage(analysis: ModPackageAnalysis): String = when {
    analysis.installPlan.preconditions.any {
        it.code == "NOMAD_GAME_VERSION_COMPATIBILITY_WARNING"
    } ->
        customerPreconditionMessage("NOMAD_GAME_VERSION_COMPATIBILITY_WARNING")
    analysis.installPlan.preconditions.any {
        !it.satisfied && it.code == "NOMAD_GAME_VERSION_INCOMPATIBLE"
    } ->
        "إصدار Nomad لهذا المود ينتمي إلى عائلة توافق مختلفة عن إصدار اللعبة المحدد."
    analysis.installPlan.preconditions.any {
        !it.satisfied && it.code == "BUILT_IN_IMPORTER_UNVERIFIED"
    } ->
        "هذا محتوى Gorilla Tag مخصص لمستورد الخرائط المدمج؛ لا يملك NFVR حاليًا عقد استيراد موثقًا وآمنًا."
    analysis.installPlan.preconditions.any {
        !it.satisfied && it.code == "NESTED_ARCHIVE_REQUIRES_REVIEW"
    } ->
        "يحتوي الأرشيف على ZIP متداخل؛ يجب مراجعته كحزمة مستقلة ولا يقوم NFVR بفكّه أو تثبيته تلقائيًا."
    analysis.outcome == ModInstallOutcome.APK_PATCH_REQUIRED ->
        customerPreconditionMessage("APK_PATCH_REQUIRED")
    analysis.outcome == ModInstallOutcome.REQUIRES_MOD_LOADER ->
        customerPreconditionMessage("REQUIRES_MOD_LOADER")
    analysis.outcome == ModInstallOutcome.UNSAFE_ARCHIVE ->
        customerPreconditionMessage("UNSAFE_ARCHIVE")
    analysis.outcome == ModInstallOutcome.BUILT_IN_GAME_CONTENT ->
        "تم التعرف على الحزمة، لكن عقد الاستيراد الموثق والآمن غير متاح في NFVR؛ لم يتم نقل أي ملف."
    analysis.outcome == ModInstallOutcome.UNSUPPORTED ||
        analysis.packageType == ModPackageType.UNKNOWN || !analysis.recognized ->
        customerPreconditionMessage("UNSUPPORTED")
    else -> analysis.installPlan.preconditions.firstOrNull { !it.satisfied }?.let {
        customerPreconditionMessage(it.code)
    } ?: if (analysis.installable) {
        "الحزمة جاهزة للتثبيت الآمن."
    } else {
        "لا يمكن تثبيت هذه الحزمة بهذه الخطة."
    }
}

fun customerPackageTypeMessage(type: ModPackageType): String = when (type) {
    ModPackageType.QMOD -> "حزمة QMOD"
    ModPackageType.NFVR_MANIFEST -> "حزمة NFVR"
    ModPackageType.ANDROID_DATA_LAYOUT -> "تخطيط بيانات Android"
    ModPackageType.ANDROID_OBB_LAYOUT -> "تخطيط OBB لنظام Android"
    ModPackageType.BONELAB_NATIVE_CONTENT -> "محتوى BONELAB"
    ModPackageType.BONELAB_CODE_MOD -> "مود برمجي لـ BONELAB"
    ModPackageType.GORILLA_TAG_VIRTUAL_STUMP -> "محتوى Gorilla Tag تديره اللعبة"
    ModPackageType.PAVLOV_UGC_CONTENT -> "محتوى Pavlov من mod.io تديره اللعبة"
    ModPackageType.KNOWN_GAME_PROFILE -> "حزمة للعبة معروفة"
    ModPackageType.GENERIC_DATA -> "حزمة بيانات عامة"
    ModPackageType.UNKNOWN -> "صيغة غير معروفة"
}

fun customerLoaderRequirementMessage(requirement: ModLoaderRequirement): String =
    when (requirement.status) {
        ModLoaderStatus.DETECTED ->
            "تم العثور على دليل موثوق للمحمّل المطلوب على اللعبة المحددة."
        ModLoaderStatus.NOT_DETECTED ->
            "لم يتم العثور على المحمّل المطلوب على اللعبة المحددة."
        ModLoaderStatus.UNKNOWN ->
            "تعذر التحقق من حالة المحمّل المطلوب على اللعبة المحددة."
    }

enum class ModExecutionFailureKind {
    PREPARE_DESTINATION,
    TRANSFER,
    VERIFY,
    UNEXPECTED
}

fun customerModExecutionFailureMessage(kind: ModExecutionFailureKind): String = when (kind) {
    ModExecutionFailureKind.PREPARE_DESTINATION ->
        "تعذر تجهيز وجهة المود على النظارة."
    ModExecutionFailureKind.TRANSFER ->
        "تعذر نقل أحد ملفات المود إلى النظارة."
    ModExecutionFailureKind.VERIFY ->
        "تعذر التحقق من الملفات المنقولة على النظارة."
    ModExecutionFailureKind.UNEXPECTED ->
        "تعذر إكمال تثبيت المود. راجع التشخيصات ثم أعد المحاولة."
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
    packageType == ModPackageType.UNKNOWN ||
        packageType == ModPackageType.PAVLOV_UGC_CONTENT -> ModWorkflowKind.UNSUPPORTED
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
    val reviewedDeviceSerial: String? = null,
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
                gameVersionCode = app.versionCode,
                archiveSha256 = archive.sha256,
                analysisPlanId = id
            )
        )
    }

    /**
     * Binding is an execution capability, not part of displaying analysis.
     * Non-installable classifications intentionally return null.
     */
    fun tryBindToDevice(serial: String): ModInstallPlan? {
        return ModOperationBindingPolicy.bindExecutable(this, serial)
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

/**
 * Pure binding policy shared by device-aware analysis and the UI.  Analysis
 * classifications are useful without an operation binding; only a complete,
 * executable plan may be bound to a device.
 */
object ModOperationBindingPolicy {
    fun bindExecutable(plan: ModInstallPlan, serial: String): ModInstallPlan? {
        val normalizedSerial = serial.trim()
        if (!plan.installable ||
            plan.outcome != ModInstallOutcome.DIRECT_INSTALL_READY ||
            plan.mappings.isEmpty() ||
            plan.hasBlockingPreconditions ||
            plan.reviewedApp == null ||
            plan.archiveIdentity == null ||
            normalizedSerial.isEmpty() ||
            (plan.reviewedDeviceSerial != null &&
                plan.reviewedDeviceSerial != normalizedSerial) ||
            (plan.reviewedApp.versionName.isNullOrBlank() && plan.reviewedApp.versionCode == null)
        ) return null
        return runCatching { plan.bindToDevice(normalizedSerial) }.getOrNull()
    }
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

/**
 * The archive normalizer deliberately describes packaging without deciding
 * where a game-specific installer may write.  This keeps wrapper handling,
 * dependency discovery, and nested-archive policy reusable for every Quest
 * game while leaving code/content and loader decisions to the analyzer.
 */
enum class QuestModArchiveRootKind {
    MOD_ROOT,
    PACKAGING_METADATA,
    DEPENDENCY,
    NESTED_ARCHIVE,
    UNKNOWN
}

data class QuestModArchiveRoot(
    val path: String,
    val kind: QuestModArchiveRootKind,
    val fileCount: Int,
    val totalBytes: Long
)

data class QuestModArchiveTree(
    val entries: List<String> = emptyList(),
    val fileEntries: List<String> = emptyList(),
    val directoryEntries: List<String> = emptyList(),
    val wrapperRoot: String? = null,
    val roots: List<QuestModArchiveRoot> = emptyList(),
    val metadata: Map<String, JSONObject> = emptyMap(),
    val dependencyPaths: List<String> = emptyList(),
    val nestedArchivePaths: List<String> = emptyList(),
    val multipleModRoots: Boolean = false
) {
    /**
     * A wrapper is a packaging detail, never an additional destination level.
     * Callers should use this value only when their game-specific contract
     * says that the root itself is the installable mod directory.
     */
    val normalizedRoot: String?
        get() = wrapperRoot

    val metadataPaths: List<String>
        get() = metadata.keys.toList()

    val hasNestedArchives: Boolean
        get() = nestedArchivePaths.isNotEmpty()

    val hasMultipleRoots: Boolean
        get() = multipleModRoots
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
    val diagnostics: List<String> = emptyList(),
    val archiveTree: QuestModArchiveTree? = null
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
    val versionRules: Map<String, String> = emptyMap(),
    /**
     * A profile may classify a game without authorizing a destination.  This
     * is required for Gorilla Tag until an authoritative Quest contract is
     * available; only an explicit QMOD plus authenticated loader evidence can
     * then supply canonical loader destinations.
     */
    val writeAuthorized: Boolean = true,
    /** Exact app version names covered by the evidence fixture. */
    val supportedVersionNames: Set<String> = emptySet(),
    /** Exact app version codes covered by the evidence fixture. */
    val supportedVersionCodes: Set<Long> = emptySet(),
    /** SHA-256 values of APK bytes covered by the evidence fixture. */
    val supportedApkSha256: Set<String> = emptySet()
) {
    val apkSha256: String?
        get() = supportedApkSha256.singleOrNull()

    val requiresApkSha256Evidence: Boolean
        get() = supportedApkSha256.isNotEmpty()

    val supportedVersions: Set<String>
        get() = supportedVersionNames

    /**
     * A profile match is deliberately exact when a profile has fixture
     * version evidence. APK evidence is mandatory when the profile records
     * an APK fixture. Callers without APK inspection can still use
     * [supportsVersion], but cannot claim profile compatibility.
     */
    fun supportsVersion(app: InstalledQuestApp): Boolean =
        app.packageName == packageId &&
            (supportedVersionNames.isEmpty() || app.versionName in supportedVersionNames) &&
            (supportedVersionCodes.isEmpty() || app.versionCode in supportedVersionCodes)

    fun acceptsApkSha256(hash: String?): Boolean =
        supportedApkSha256.isEmpty() ||
            (!hash.isNullOrBlank() && supportedApkSha256.any { it.equals(hash.trim(), true) })

    fun matchesApkSha256(hash: String?): Boolean = acceptsApkSha256(hash)

    fun supportsApp(app: InstalledQuestApp, apkSha256: String? = app.apkSha256): Boolean =
        supportsVersion(app) &&
            acceptsApkSha256(apkSha256)

    fun matchesApp(app: InstalledQuestApp, apkSha256: String? = app.apkSha256): Boolean =
        supportsApp(app, apkSha256)

    fun matchesVersion(versionName: String?, versionCode: Long?): Boolean =
        (supportedVersionNames.isEmpty() || versionName in supportedVersionNames) &&
            (supportedVersionCodes.isEmpty() || versionCode in supportedVersionCodes)

    fun isCompatible(
        app: InstalledQuestApp?,
        apkSha256: String? = app?.apkSha256,
        requireApkEvidence: Boolean = true
    ): Boolean = compatibilityIssues(app, apkSha256, requireApkEvidence).isEmpty()

    /**
     * Data-only content copies do not mutate or reinstall the APK.  Their
     * compatibility gate is package identity, supported version evidence, and
     * the verified destination; APK hashes remain available to strict
     * preparation/patching callers.
     */
    fun isContentCompatible(app: InstalledQuestApp?): Boolean =
        contentCompatibilityIssues(app).isEmpty()

    fun contentCompatibilityIssues(app: InstalledQuestApp?): List<String> =
        compatibilityIssues(app, requireApkEvidence = false)

    /** Stable precondition codes for manager/device-aware callers. */
    fun compatibilityIssues(
        app: InstalledQuestApp?,
        apkSha256: String? = app?.apkSha256,
        requireApkEvidence: Boolean = true
    ): List<String> {
        if (app == null) return listOf("TARGET_APP_REQUIRED")
        val issues = mutableListOf<String>()
        if (app.packageName != packageId) issues += "TARGET_PACKAGE_MISMATCH"
        if (supportedVersionNames.isNotEmpty() &&
            app.versionName !in supportedVersionNames
        ) issues += "GAME_VERSION_UNSUPPORTED"
        if (supportedVersionCodes.isNotEmpty() &&
            app.versionCode !in supportedVersionCodes
        ) issues += "GAME_VERSION_CODE_UNSUPPORTED"
        if (requireApkEvidence && supportedApkSha256.isNotEmpty()) {
            if (apkSha256.isNullOrBlank()) {
                issues += "APK_SHA256_REQUIRED"
            } else if (!acceptsApkSha256(apkSha256)) {
                issues += "APK_SHA256_UNSUPPORTED"
            }
        }
        return issues
    }

    fun isRecognizedArchiveSignature(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        return recognizedArchiveSignatures.any { signature ->
            val value = signature.lowercase()
            normalized == value || normalized.endsWith("/$value") ||
                (value.startsWith(".") && normalized.endsWith(value))
        }
    }
}

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
                    if (packageId.isBlank() || (destination.isBlank() &&
                            item.optBoolean("writeAuthorized", true))) return@mapNotNull null
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
                    val versionNames = stringSet("supportedVersionNames").ifEmpty {
                        stringSet("supportedVersions")
                    }
                    val versionCodes = item.optJSONArray("supportedVersionCodes")?.let { array ->
                        (0 until array.length()).mapNotNull { index ->
                            when (val value = array.opt(index)) {
                                is Number -> value.toLong()
                                is String -> value.trim().toLongOrNull()
                                else -> null
                            }
                        }.toSet()
                    } ?: emptySet()
                    val apkHashes = buildSet {
                        stringSet("supportedApkSha256").forEach { add(it.lowercase()) }
                        item.optString("apkSha256").trim()
                            .takeIf(String::isNotBlank)?.let { add(it.lowercase()) }
                        item.optString("apkSha256Hash").trim()
                            .takeIf(String::isNotBlank)?.let { add(it.lowercase()) }
                    }
                    val versionRules = item.optJSONObject("versionRules")?.let { rules ->
                        rules.keys().asSequence().associateWith { key ->
                            rules.optString(key)
                        }
                    } ?: emptyMap()
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
                            destination.takeIf { it.isNotBlank() }?.let { setOf(it) } ?: emptySet()
                        },
                        modTypes = enumSet("modTypes").ifEmpty { supported },
                        installationStrategies = stringSet("installationStrategies")
                            .mapNotNull { runCatching { ModResolutionStrategy.valueOf(it) }.getOrNull() }
                            .toSet()
                            .ifEmpty { setOf(ModResolutionStrategy.KNOWN_GAME_PROFILE) },
                        evidenceLevel = evidence,
                        evidenceSources = stringSet("evidenceSources").toList(),
                        versionRules = versionRules,
                        writeAuthorized = item.optBoolean("writeAuthorized", true),
                        supportedVersionNames = versionNames,
                        supportedVersionCodes = versionCodes,
                        supportedApkSha256 = apkHashes
                    )
                }
            }
        }.getOrElse { emptyList() }
    }
}