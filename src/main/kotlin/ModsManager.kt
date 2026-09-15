import java.io.*
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.security.MessageDigest
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

data class GameInfo(
    val name: String,
    val packageName: String,
    val modPath: String,
    val notes: String
)

private val LEGACY_SUPPORTED_GAMES = listOf(
    GameInfo(
        name = "BONELAB",
        packageName = "com.StressLevelZero.BONELAB",
        modPath = "/sdcard/Android/data/com.StressLevelZero.BONELAB/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Beat Saber",
        packageName = "com.beatgames.beatsaber",
        modPath = "/sdcard/ModData/com.beatgames.beatsaber/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Contractors",
        packageName = "com.CavemanStudio.Contractors",
        modPath = "/sdcard/Android/data/com.CavemanStudio.Contractors/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Pavlov Shack",
        packageName = "com.vankrupt.pavlovshack",
        modPath = "/sdcard/Android/data/com.vankrupt.pavlovshack/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Into the Radius",
        packageName = "CMGames.IntotheRadius",
        modPath = "/sdcard/Android/data/CMGames.IntotheRadius/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "The Walking Dead: Saints & Sinners",
        packageName = "com.skydancedev.saintsandsinners",
        modPath = "/sdcard/Android/data/com.skydancedev.saintsandsinners/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "BONEWORKS",
        packageName = "com.StressLevelZero.BONEWORKS",
        modPath = "/sdcard/Android/data/com.StressLevelZero.BONEWORKS/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "GORN",
        packageName = "com.FreeLives.GORN",
        modPath = "/sdcard/Android/data/com.FreeLives.GORN/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Job Simulator",
        packageName = "com.OwlchemyLabs.JobSimulator",
        modPath = "/sdcard/Android/data/com.OwlchemyLabs.JobSimulator/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Vacation Simulator",
        packageName = "com.OwlchemyLabs.VacationSimulator",
        modPath = "/sdcard/Android/data/com.OwlchemyLabs.VacationSimulator/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Hard Bullet",
        packageName = "com.Carbon.Studio.HardBullet",
        modPath = "/sdcard/Android/data/com.Carbon.Studio.HardBullet/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Hellsplit: Arena",
        packageName = "com.Ruthless.HellsplitArena",
        modPath = "/sdcard/Android/data/com.Ruthless.HellsplitArena/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Ancient Dungeon",
        packageName = "com.Ermir.AncientDungeon",
        modPath = "/sdcard/Android/data/com.Ermir.AncientDungeon/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Swordsman VR",
        packageName = "com.DigitalMiracleGames.SwordsmanVR",
        modPath = "/sdcard/Android/data/com.DigitalMiracleGames.SwordsmanVR/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Onward",
        packageName = "com.DownpourInteractive.Onward",
        modPath = "/sdcard/Android/data/com.DownpourInteractive.Onward/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Arizona Sunshine",
        packageName = "com.Virtuamix.ArizonaSunshine",
        modPath = "/sdcard/Android/data/com.Virtuamix.ArizonaSunshine/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Zero Caliber VR",
        packageName = "com.EmptyClipStudios.ZeroCaliber",
        modPath = "/sdcard/Android/data/com.EmptyClipStudios.ZeroCaliber/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Compound",
        packageName = "com.FrownTown.Compound",
        modPath = "/sdcard/Android/data/com.FrownTown.Compound/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Boneworks: Experimental Branch",
        packageName = "com.StressLevelZero.BONEWORKS.Experimental",
        modPath = "/sdcard/Android/data/com.StressLevelZero.BONEWORKS.Experimental/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Half-Life: Alyx",
        packageName = "com.Valve.HalfLifeAlyx",
        modPath = "/sdcard/Android/data/com.Valve.HalfLifeAlyx/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Skyrim VR",
        packageName = "com.Bethesda.SkyrimVR",
        modPath = "/sdcard/Android/data/com.Bethesda.SkyrimVR/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Fallout 4 VR",
        packageName = "com.Bethesda.Fallout4VR",
        modPath = "/sdcard/Android/data/com.Bethesda.Fallout4VR/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Assetto Corsa",
        packageName = "com.Kunos.AssettoCorsa",
        modPath = "/sdcard/Android/data/com.Kunos.AssettoCorsa/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Project Cars 2",
        packageName = "com.SlightlyMadStudios.ProjectCars2",
        modPath = "/sdcard/Android/data/com.SlightlyMadStudios.ProjectCars2/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Risk of Rain 2 VR",
        packageName = "com.HopooGames.RiskOfRain2VR",
        modPath = "/sdcard/Android/data/com.HopooGames.RiskOfRain2VR/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Valheim VR",
        packageName = "com.IronGate.ValheimVR",
        modPath = "/sdcard/Android/data/com.IronGate.ValheimVR/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "Lethal Company VR",
        packageName = "com.ZeekerssGames.LethalCompanyVR",
        modPath = "/sdcard/Android/data/com.ZeekerssGames.LethalCompanyVR/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    ),
    GameInfo(
        name = "No Man's Sky",
        packageName = "com.HelloGames.NoMansSky",
        modPath = "/sdcard/Android/data/com.HelloGames.NoMansSky/files/Mods",
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    )
)

/**
 * Only profiles with documented destinations are advertised as built-in
 * games.  The historical list above is retained for source compatibility
 * but is never exposed or used for destination selection.
 */
private val SUPPORTED_GAMES = GameModProfileRegistry.profiles.map { profile ->
    GameInfo(
        name = profile.displayName,
        packageName = profile.packageId,
        modPath = profile.destination,
        notes = "قد يتطلب تشغيل اللعبة مرة واحدة لإنشاء مجلد المودات"
    )
}

data class ModInstallResult(
    val success: Boolean,
    val message: String,
    val extractedFiles: List<String> = emptyList(),
    val extractedRoot: File? = null
)

class ModsManager(
    private val adbClient: AdbClient,
    private val loaderDetector: ModLoaderDetector = AdbModLoaderDetector(adbClient),
    private val apkPatcher: ApkModLoaderPatcher = NoOpApkModLoaderPatcher
) {
    companion object {
        private const val MAX_ENTRY_BYTES = ModPackageAnalyzer.MAX_ENTRY_BYTES
    }
    
    fun getSupportedGames(): List<GameInfo> = SUPPORTED_GAMES

    /**
     * Analyze a package before any extraction or ADB operation.  Kept as a
     * small facade so existing UI callers can adopt the conservative package
     * engine without coupling themselves to its implementation.
     */
    fun analyzeModPackage(
        zipFile: File,
        installedApp: InstalledQuestApp? = null
    ): ModPackageAnalysis = ModPackageAnalyzer().analyze(zipFile, installedApp)

    /**
     * Device-aware analysis for callers that already selected a headset.  A
     * loader result is captured in the plan, but execution still repeats the
     * read-only check before the first write.
     */
    suspend fun analyzeModPackage(
        serial: String,
        zipFile: File,
        installedApp: InstalledQuestApp
    ): ModPackageAnalysis = withContext(Dispatchers.IO) {
        val detection = loaderDetector.detect(serial, installedApp)
        val discovery = discoverModDirectoriesInternal(serial, installedApp, detection)
        val analysis = ModPackageAnalyzer().analyze(
            zipFile,
            installedApp,
            detection,
            discovery
        )
        analysis.copy(installPlan = analysis.installPlan.bindToDevice(serial))
    }

    /**
     * Read-only selected-package inspection. Every probe is package scoped,
     * serial scoped, and uses `test -d`; this method never creates a folder.
     */
    suspend fun discoverModDirectories(
        serial: String,
        installedApp: InstalledQuestApp
    ): ModDirectoryDiscovery = withContext(Dispatchers.IO) {
        val detection = runCatching {
            loaderDetector.detect(serial, installedApp)
        }.getOrNull()
        discoverModDirectoriesInternal(serial, installedApp, detection)
    }

    private suspend fun discoverModDirectoriesInternal(
        serial: String,
        installedApp: InstalledQuestApp,
        loaderDetection: ModLoaderDetection?
    ): ModDirectoryDiscovery {
        val profile = GameModProfileRegistry.findByPackageId(installedApp.packageName)
        val paths = linkedMapOf<String, String>()
        fun add(path: String, source: String) {
            if (path.isNotBlank() &&
                AndroidPathValidator.isSafe(path) &&
                (profile?.authoritativePaths?.contains(path) == true ||
                    ModDestinationPolicy.isPackageBound(path, installedApp.packageName))
            ) {
                paths.putIfAbsent(path, source)
            }
        }
        profile?.authoritativePaths?.forEach { add(it, "documented profile path") }
        add("/sdcard/Android/data/${installedApp.packageName}/files/Mods", "known package Mods path")
        add("/sdcard/Android/data/${installedApp.packageName}/files/mods", "known package mods path")
        add("/sdcard/Android/data/${installedApp.packageName}/files/Plugins", "known package Plugins path")
        add("/sdcard/Android/data/${installedApp.packageName}/files/plugins", "known package plugins path")
        add("/sdcard/ModData/${installedApp.packageName}", "known ModData package path")
        val candidates = paths.map { (path, source) ->
            ModDirectoryCandidate(
                packageId = installedApp.packageName,
                path = path,
                exists = runCatching {
                    adbClient.shell(serial, "test", "-d", path).exit == 0
                }.getOrDefault(false),
                source = source,
                evidenceLevel = profile?.evidenceLevel ?: ModEvidenceLevel.OPEN_SOURCE_PROJECT
            )
        }
        return ModDirectoryDiscovery(
            serial = serial,
            packageId = installedApp.packageName,
            appVersion = installedApp.versionName,
            candidates = candidates,
            loaderDetection = loaderDetection,
            profile = profile,
            diagnostics = listOf(
                "readOnly=true",
                "package=${installedApp.packageName}",
                "candidateCount=${candidates.size}"
            )
        )
    }

    suspend fun scanInstalledQuestApps(
        serial: String,
        showAll: Boolean = false,
        onProgress: (InstalledAppsScanProgress) -> Unit = {}
    ): List<InstalledQuestApp> = withContext(Dispatchers.IO) {
        fun emit(progress: InstalledAppsScanProgress) {
            // A UI observer must never be able to abort the ADB scan.
            runCatching { onProgress(progress) }
        }

        val listed = runInterruptible(Dispatchers.IO) {
            adbClient.shell(serial, "pm", "list", "packages", "-3", "-f")
        }
        if (listed.exit != 0) {
            emit(InstalledAppsScanProgress(emptyList(), 0, 0, completed = true, error = listed.err))
            return@withContext emptyList()
        }

        val packages = listed.out.lineSequence()
            .mapNotNull(::parsePackageListLine)
            .distinctBy { it.first }
            .filter { showAll || shouldIncludeQuestPackage(it.first) }
            .take(250)
            .toList()
        val total = packages.size
        var processed = 0
        val discovered = mutableListOf<InstalledQuestApp>()
        emit(InstalledAppsScanProgress(emptyList(), 0, total))

        for ((packageId, apkPath) in packages) {
            currentCoroutineContext().ensureActive()
            val details = runInterruptible(Dispatchers.IO) {
                adbClient.shell(serial, "dumpsys", "package", packageId)
            }
            val versionName = findPackageValue(details.out, "versionName")
            val versionCode = Regex("""versionCode=(\d+)""")
                .find(details.out)?.groupValues?.getOrNull(1)?.toLongOrNull()
            val profileName = GameModProfileRegistry.findByPackageId(packageId)?.displayName
            val label = findBestLabel(details.out) ?: profileName ?: packageId.substringAfterLast('.')
            discovered += InstalledQuestApp(
                packageName = packageId,
                versionName = versionName,
                versionCode = versionCode,
                displayName = label,
                apkPath = apkPath,
                thirdParty = true
            )
            processed++
            emit(InstalledAppsScanProgress(discovered.toList(), processed, total))
        }

        val sorted = discovered.sortedWith(
            compareBy<InstalledQuestApp, String>(String.CASE_INSENSITIVE_ORDER) {
                it.displayName ?: it.packageName
            }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.packageName }
        )
        emit(InstalledAppsScanProgress(sorted, total, total, completed = true))
        sorted
    }

    suspend fun executeInstallPlan(
        serial: String,
        zipFile: File,
        plan: ModInstallPlan,
        onProgress: (ModExecutionProgress) -> Unit = {}
    ): ModInstallResult {
        if (!plan.installable || plan.hasBlockingPreconditions || plan.mappings.isEmpty()) {
            return ModInstallResult(false, "خطة التثبيت غير صالحة أو تحتوي متطلبات غير مستوفاة.")
        }
        val reviewedApp = plan.reviewedApp
            ?: return ModInstallResult(false, "الخطة لا تحتوي حالة التطبيق التي تمت مراجعتها؛ أعد تحليل الحزمة.")
        val reviewedIdentity = plan.archiveIdentity
            ?: return ModInstallResult(false, "الخطة لا تحتوي هوية الأرشيف التي تمت مراجعتها؛ أعد تحليل الحزمة.")
        val operationBinding = plan.operationBinding
            ?: return ModInstallResult(false, "الخطة غير مرتبطة بجهاز محدد؛ أعد تحليل الحزمة وربطها قبل النقل.")
        val expectedPlanId = modAnalysisPlanId(serial, reviewedApp, reviewedIdentity.sha256)
        if (!modOperationBindingMatches(
                operationBinding,
                serial,
                reviewedApp,
                reviewedIdentity.sha256,
                expectedPlanId
            ) ||
            operationBinding.analysisPlanId != expectedPlanId
        ) {
            return ModInstallResult(false, "تغير ربط الجهاز أو الخطة؛ أعد تحليل الحزمة قبل النقل.")
        }
        val currentApp = scanInstalledQuestApps(serial)
            .firstOrNull { it.packageName == reviewedApp.packageName }
            ?: return ModInstallResult(false, "التطبيق المستهدف لم يعد مثبتًا؛ أعد تحليل الحزمة.")
        if (!sameAppState(reviewedApp, currentApp)) {
            return ModInstallResult(false, "تغيرت حالة التطبيق المستهدف منذ التحليل؛ أعد تحليل الحزمة.")
        }
        val loaderDetection = runCatching {
            loaderDetector.detect(serial, currentApp)
        }.getOrNull()
        val patchAssessment = runCatching {
            apkPatcher.assess(serial, currentApp)
        }.getOrNull()
        if (patchAssessment?.required == true) {
            return ModInstallResult(
                false,
                "تحتاج اللعبة إلى تصحيح APK قبل تثبيت هذا النوع من المودات؛ لم يتم تنفيذ أي تصحيح."
            )
        }
        val discovery = if (
            plan.strategy == ModInstallStrategy.GENERIC_EXISTING_DIRECTORY_COPY &&
            plan.confirmation != null
        ) {
            val inspected = discoverModDirectoriesInternal(serial, currentApp, loaderDetection)
            val destination = plan.confirmation.destination
            if (!AndroidPathValidator.isSafe(destination) ||
                plan.destinationRoot != destination ||
                plan.confirmation.token != modInstallConfirmationToken(
                    reviewedIdentity.sha256,
                    currentApp.packageName,
                    destination
                )
            ) {
                return ModInstallResult(
                    false,
                    "تغيرت وجهة التأكيد أو رمزها؛ أعد فحص المجلد قبل النقل."
                )
            }
            val exact = inspected.existingCandidates.firstOrNull {
                it.packageId == currentApp.packageName &&
                    it.path == destination
            } ?: return ModInstallResult(
                false,
                "لم تعد الوجهة المؤكدة موجودة بعد الفحص القراءة فقط؛ أعد التحليل."
            )
            inspected.copy(
                candidates = listOf(exact)
            )
        } else {
            null
        }
        val freshAnalysis = ModPackageAnalyzer().analyze(
            zipFile,
            currentApp,
            loaderDetection,
            discovery
        )
        var freshPlan = runCatching { freshAnalysis.installPlan.bindToDevice(serial) }
            .getOrElse { freshAnalysis.installPlan }
        if (plan.confirmation != null && !plan.requiresExplicitConfirmation) {
            freshPlan = freshPlan.confirmDestination(plan.confirmation.token)
        }
        if (freshPlan.outcome == ModInstallOutcome.REQUIRES_MOD_LOADER ||
            freshPlan.outcome == ModInstallOutcome.APK_PATCH_REQUIRED
        ) {
            return ModInstallResult(
                false,
                freshAnalysis.message.ifBlank {
                    if (freshPlan.outcome == ModInstallOutcome.APK_PATCH_REQUIRED) {
                        "تحتاج هذه الحزمة إلى تصحيح APK؛ لم يتم تنفيذ أي تصحيح."
                    } else {
                        "تحتاج هذه الحزمة إلى تجهيز محمّل المود قبل التثبيت."
                    }
                }
            )
        }
        if (!freshPlan.installable ||
            freshPlan.archiveIdentity != reviewedIdentity ||
            !sameSecurityProjection(plan, freshPlan)
        ) {
            return ModInstallResult(false, "تغير الأرشيف أو خطة التثبيت منذ التحليل؛ أعد التحليل قبل النقل.")
        }
        var executionPlan = freshPlan.copy(
            operationBinding = operationBinding,
            analysisPlanId = operationBinding.analysisPlanId
        )
        val profile = executionProfile(executionPlan)
            ?: return ModInstallResult(false, "لا يوجد ملف تعريف موثوق للعبة المستهدفة.")
        if (executionPlan.destinationRoot != profile.destination) {
            return ModInstallResult(false, "وجهة الخطة لا تطابق ملف تعريف اللعبة الموثوق.")
        }

        val tempRoot = Files.createTempDirectory("NFVR_ModPlan_").toFile()
        var copiedBytes = 0L
        var copiedFiles = 0
        try {
            onProgress(ModExecutionProgress(ModInstallPhase.EXTRACTING, null, "استخراج الملفات المطلوبة بأمان"))
            if (!archiveIdentityMatches(zipFile, reviewedIdentity)) {
                return ModInstallResult(false, "تغير الأرشيف أثناء التثبيت؛ تم إيقاف النقل.")
            }
            val extracted = extractPlannedFiles(zipFile, tempRoot, executionPlan)
            onProgress(ModExecutionProgress(ModInstallPhase.VALIDATING, null, "التحقق من سلامة الملفات والخطة"))
            validateExtractedFiles(extracted, executionPlan)
            if (!archiveIdentityMatches(zipFile, reviewedIdentity)) {
                return ModInstallResult(false, "تغير الأرشيف بعد التحقق؛ تم إيقاف النقل.")
            }
            val appBeforeTransfer = scanInstalledQuestApps(serial)
                .firstOrNull { it.packageName == reviewedApp.packageName }
            if (appBeforeTransfer == null || !sameAppState(reviewedApp, appBeforeTransfer)) {
                return ModInstallResult(false, "تغيرت حالة التطبيق قبل النقل؛ أعد تحليل الحزمة.")
            }
            executionPlan.loaderRequirement?.let { requirement ->
                val beforeWriteDetection = runCatching {
                    loaderDetector.detect(serial, appBeforeTransfer)
                }.getOrNull()
                if (beforeWriteDetection == null ||
                    beforeWriteDetection.packageId != appBeforeTransfer.packageName ||
                    !beforeWriteDetection.hasAny(requirement.requested)
                ) {
                    return ModInstallResult(
                        false,
                        "تعذر التحقق من محمّل المود المطلوب قبل النقل؛ أعد تحليل الحزمة."
                    )
                }
            }

            for (mapping in executionPlan.mappings) {
                val destination = mapping.destinationPath
                if (!isApprovedDestination(destination, profile, executionPlan)) {
                    return ModInstallResult(false, "تم رفض وجهة غير معتمدة: $destination")
                }
                val parent = destination.substringBeforeLast('/', profile.destination)
                val mkdir = createModPath(serial, parent)
                if (mkdir.exit != 0) return ModInstallResult(false, "فشل تجهيز وجهة المود: ${mkdir.err}")

                val source = extracted.getValue(mapping.sourcePath)
                onProgress(
                    ModExecutionProgress(
                        ModInstallPhase.TRANSFERRING,
                        executionPlan.progress(copiedBytes, copiedFiles).fraction.coerceAtMost(0.94),
                        "نقل ${source.name}"
                    )
                )
                val pushed = adbClient.pushWithProgress(serial, source, destination) { current, _ ->
                    val fraction = executionPlan.progress(copiedBytes + current, copiedFiles).fraction.coerceAtMost(0.94)
                    onProgress(ModExecutionProgress(ModInstallPhase.TRANSFERRING, fraction, "نقل ${source.name}"))
                }
                if (pushed.exit != 0) {
                    return ModInstallResult(false, "فشل نقل ${source.name}: ${pushed.err.ifBlank { pushed.out }}")
                }
                copiedBytes += mapping.sizeBytes
                copiedFiles++
            }

            onProgress(ModExecutionProgress(ModInstallPhase.VERIFYING, 0.95, "التحقق من الملفات على النظارة"))
            val verificationErrors = executionPlan.mappings.mapNotNull { mapping ->
                verifyRemoteFile(serial, mapping)
            }
            if (verificationErrors.isNotEmpty()) {
                return ModInstallResult(false, verificationErrors.joinToString("\n"))
            }
            val appAfterVerification = scanInstalledQuestApps(serial)
                .firstOrNull { it.packageName == reviewedApp.packageName }
            if (appAfterVerification == null || !sameAppState(reviewedApp, appAfterVerification)) {
                return ModInstallResult(false, "تغير اتصال النظارة أو إصدار اللعبة أثناء التحقق؛ لم يتم اعتماد النجاح.")
            }

            onProgress(ModExecutionProgress(ModInstallPhase.COMPLETED, 1.0, "اكتمل التثبيت والتحقق"))
            ModInstallHistory.record(executionPlan, true, "verified")
            return ModInstallResult(true, "تم تثبيت المود والتحقق من الملفات بنجاح.")
        } catch (e: Exception) {
            ModInstallHistory.record(executionPlan, false, e.message ?: "error")
            DiagnosticLogger.error("فشل تنفيذ خطة تثبيت المود", e)
            return ModInstallResult(false, "فشل التثبيت: ${e.message ?: "خطأ غير معروف"}")
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun parsePackageListLine(line: String): Pair<String, String?>? {
        val value = line.trim().removePrefix("package:")
        if (value.isBlank()) return null
        val separator = value.lastIndexOf('=')
        return if (separator > 0) {
            val apk = value.substring(0, separator).trim().ifBlank { null }
            val packageId = value.substring(separator + 1).trim()
            packageId.takeIf(::isValidPackageId)?.let { it to apk }
        } else {
            value.takeIf(::isValidPackageId)?.let { it to null }
        }
    }

    private fun isValidPackageId(value: String): Boolean =
        value.length in 3..200 && value.matches(Regex("""[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+"""))

    private fun findPackageValue(output: String, key: String): String? =
        Regex("""(?m)^\s*${Regex.escape(key)}=([^\s]+)""")
            .find(output)?.groupValues?.getOrNull(1)?.takeIf { it != "null" }

    private fun findBestLabel(output: String): String? {
        val patterns = listOf(
            Regex("""nonLocalizedLabel=([^,\n}]+)"""),
            Regex("""labelRes=0x0\s+nonLocalizedLabel=([^,\n}]+)""")
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(output)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        }
    }

    private fun extractPlannedFiles(
        zipFile: File,
        tempRoot: File,
        plan: ModInstallPlan
    ): Map<String, File> {
        val root = tempRoot.canonicalFile.toPath()
        val files = linkedMapOf<String, File>()
        ZipFile(zipFile).use { zip ->
            for (mapping in plan.mappings) {
                val entry = findNormalizedEntry(zip, mapping.sourcePath)
                    ?: error("الملف ${mapping.sourcePath} غير موجود داخل الحزمة.")
                require(!entry.isDirectory) { "لا يمكن تثبيت مجلد كملف: ${mapping.sourcePath}" }
                val outputPath = root.resolve(mapping.sourcePath).normalize()
                require(outputPath.startsWith(root)) { "تم رفض مسار استخراج غير آمن." }
                val output = outputPath.toFile()
                output.parentFile?.mkdirs()
                var bytes = 0L
                zip.getInputStream(entry).use { input ->
                    output.outputStream().use { target ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            bytes += read
                            require(bytes <= MAX_ENTRY_BYTES) { "ملف مود أكبر من الحد المسموح." }
                            target.write(buffer, 0, read)
                        }
                    }
                }
                require(bytes == mapping.sizeBytes) { "حجم الملف المستخرج لا يطابق خطة التثبيت." }
                files[mapping.sourcePath] = output
            }
        }
        return files
    }

    private fun validateExtractedFiles(files: Map<String, File>, plan: ModInstallPlan) {
        for (mapping in plan.mappings) {
            val file = files.getValue(mapping.sourcePath)
            require(file.isFile && file.length() == mapping.sizeBytes) { "فشل التحقق من ${mapping.sourcePath}." }
            mapping.sha256?.let { expected ->
                require(sha256(file).equals(expected, ignoreCase = true)) {
                    "بصمة SHA-256 لا تطابق الملف ${mapping.sourcePath}."
                }
            }
        }
    }

    private fun isApprovedDestination(
        path: String,
        profile: GameModProfile,
        plan: ModInstallPlan
    ): Boolean {
        if (!AndroidPathValidator.isSafe(path)) return false
        if (plan.packageType == ModPackageType.ANDROID_DATA_LAYOUT ||
            plan.packageType == ModPackageType.ANDROID_OBB_LAYOUT
        ) {
            val target = plan.targetPackageId ?: return false
            return ModDestinationPolicy.isPackageBound(path, target)
        }
        if (plan.packageType == ModPackageType.QMOD ||
            plan.packageType == ModPackageType.NFVR_MANIFEST
        ) {
            val target = plan.targetPackageId ?: return false
            return ModDestinationPolicy.isPackageBound(path, target)
        }
        return path == profile.destination || path.startsWith("${profile.destination}/")
    }

    private fun executionProfile(plan: ModInstallPlan): GameModProfile? {
        val target = plan.targetPackageId ?: return null
        if (plan.strategy == ModInstallStrategy.GENERIC_EXISTING_DIRECTORY_COPY) {
            val root = plan.destinationRoot ?: return null
            return GameModProfile(
                packageId = target,
                displayName = "Confirmed existing mod directory",
                destination = root,
                authoritativePaths = setOf(root),
                evidenceLevel = ModEvidenceLevel.OPEN_SOURCE_PROJECT
            )
        }
        GameModProfileRegistry.findByPackageId(target)?.let { return it }
        val root = plan.destinationRoot ?: return null
        return when (plan.packageType) {
            ModPackageType.ANDROID_DATA_LAYOUT,
            ModPackageType.ANDROID_OBB_LAYOUT ->
                GameModProfile(target, "Android package layout", root)
            ModPackageType.QMOD,
            ModPackageType.NFVR_MANIFEST ->
                GameModProfile(target, "Manifest package", root)
            else -> null
        }
    }

    private fun findNormalizedEntry(zip: ZipFile, normalizedName: String): ZipEntry? {
        val direct = zip.getEntry(normalizedName)
        if (direct != null) return direct
        val iterator = zip.entries()
        while (iterator.hasMoreElements()) {
            val candidate = iterator.nextElement()
            if (runCatching { ModArchivePath.normalize(candidate.name) }.getOrNull() == normalizedName) {
                return candidate
            }
        }
        return null
    }

    private suspend fun verifyRemoteFile(serial: String, mapping: ModFileMapping): String? {
        val exists = adbClient.shell(serial, "test", "-f", mapping.destinationPath)
        if (exists.exit != 0) return "فشل التحقق: الملف غير موجود في ${mapping.destinationPath}"
        val size = adbClient.shell(serial, "stat", "-c", "%s", mapping.destinationPath)
        val actual = size.out.trim().lineSequence().lastOrNull()?.toLongOrNull()
        return if (actual == mapping.sizeBytes) null
        else "فشل التحقق من حجم ${mapping.destinationPath}: المتوقع ${mapping.sizeBytes} والفعلي ${actual ?: "غير معروف"}"
    }

    private fun sha256(file: File): String {
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

    private fun sameAppState(expected: InstalledQuestApp, actual: InstalledQuestApp): Boolean =
        expected.packageName == actual.packageName &&
            expected.versionName == actual.versionName &&
            expected.versionCode == actual.versionCode

    private fun sameSecurityProjection(expected: ModInstallPlan, actual: ModInstallPlan): Boolean =
        expected.installable == actual.installable &&
            expected.outcome == actual.outcome &&
            expected.packageType == actual.packageType &&
            expected.targetPackageId == actual.targetPackageId &&
            expected.destinationRoot == actual.destinationRoot &&
            expected.strategy == actual.strategy &&
            expected.mappings == actual.mappings &&
            ((expected.reviewedApp == null && actual.reviewedApp == null) ||
                (expected.reviewedApp != null && actual.reviewedApp != null &&
                    sameAppState(expected.reviewedApp, actual.reviewedApp))) &&
            expected.loaderRequirement?.requested == actual.loaderRequirement?.requested &&
            expected.loaderRequirement?.status == actual.loaderRequirement?.status &&
            expected.resolution == actual.resolution &&
            expected.dependencies == actual.dependencies &&
            expected.optionalDependencies == actual.optionalDependencies &&
            expected.patchRequirement == actual.patchRequirement &&
            expected.confirmation == actual.confirmation &&
            expected.preconditions.map { it.code to it.satisfied } ==
                actual.preconditions.map { it.code to it.satisfied } &&
            expected.diagnostics == actual.diagnostics

    private fun archiveIdentityMatches(file: File, identity: ModArchiveIdentity): Boolean =
        runCatching {
            file.canonicalPath == identity.canonicalPath &&
                file.length() == identity.sizeBytes &&
                file.lastModified() == identity.lastModifiedMillis &&
                sha256(file).equals(identity.sha256, ignoreCase = true)
        }.getOrDefault(false)
    
    suspend fun getInstalledGamePackages(serial: String): List<String> {
        val result = adbClient.shell(serial, "pm", "list", "packages", "-3")
        if (result.exit != 0) return emptyList()
        
        return result.out.lines()
            .map { it.replace("package:", "").trim() }
            .filter { it.isNotBlank() }
    }
    
    suspend fun testPathExists(serial: String, path: String): Boolean {
        if (!AndroidPathValidator.isSafe(path)) return false
        val result = adbClient.shell(serial, "test", "-d", path)
        return result.exit == 0
    }
    
    suspend fun createModPath(serial: String, path: String): CmdResult {
        if (!AndroidPathValidator.isSafe(path)) {
            return CmdResult(1, "", "unsafe destination path")
        }
        return adbClient.shell(serial, "mkdir", "-p", path)
    }
    
    suspend fun getGameModPath(serial: String, gameInfo: GameInfo): String? {
        val installedPackages = getInstalledGamePackages(serial)
        if (!installedPackages.contains(gameInfo.packageName)) {
            return null
        }
        
        return gameInfo.modPath.takeIf(AndroidPathValidator::isSafe)
    }
    
enum class ModInstallPhase {
    ANALYZING, EXTRACTING, VALIDATING, PREPARING, TRANSFERRING, VERIFYING, COMPLETED, FAILED
}

data class ModExecutionProgress(
    val phase: ModInstallPhase,
    val fraction: Double?,
    val message: String,
    val kind: ModProgressKind = ModProgressKind.INSTALL
)

private object ModInstallHistory {
    private const val MAX_HISTORY_BYTES = 500_000L

    fun record(plan: ModInstallPlan, success: Boolean, result: String) {
        runCatching {
            val file = File(UserDataPaths.root, "logs/mod-install-history.jsonl")
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() >= MAX_HISTORY_BYTES) {
                File(file.parentFile, "${file.name}.1").also { previous ->
                    previous.delete()
                    file.renameTo(previous)
                }
            }
            val line = JSONObject()
                .put("timestamp", java.time.Instant.now().toString())
                .put("targetPackage", plan.targetPackageId)
                .put("modType", plan.packageType.name)
                .put("result", if (success) "success" else "failure")
                .put("details", result.take(500))
                .put("appVersion", AppInfo.version)
                .toString()
            file.appendText("$line\n")
        }
    }
}
    
    fun getModZipInfo(zipFile: File): Pair<Boolean, String> {
        val analysis = ModPackageAnalyzer().analyze(zipFile)
        val info = buildString {
            appendLine("${analysis.packageType}: ${analysis.message}")
            analysis.entries.take(10).forEach { appendLine("  - $it") }
            if (analysis.entries.size > 10) {
                appendLine("  ... و ${analysis.entries.size - 10} ملفات أخرى")
            }
        }
        return analysis.recognized to info
    }
}