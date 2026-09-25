import java.io.*
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.security.MessageDigest
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

/**
 * APK identity is collected only for the selected package. A normal package
 * scan deliberately remains cheap and does not pull every installed APK.
 */
fun interface QuestApkEvidenceRefresher {
    suspend fun refresh(
        serial: String,
        app: InstalledQuestApp,
        onProgress: (String) -> Unit,
        cancelled: () -> Boolean
    ): InstalledQuestApp
}

class RestrictedQuestApkEvidenceRefresher(
    private val adbClient: AdbClient
) : QuestApkEvidenceRefresher {
    override suspend fun refresh(
        serial: String,
        app: InstalledQuestApp,
        onProgress: (String) -> Unit,
        cancelled: () -> Boolean
    ): InstalledQuestApp {
        onProgress("جارٍ التحقق من بصمة APK للعبة المحددة…")
        val report = withContext(Dispatchers.IO) {
            QuestPreparationProbe(AdbClient.AdbRestrictedQuestTransport(adbClient)).probe(
                targetGame = app.packageName,
                onGame = { game -> onProgress("فحص APK: ${game.displayName}") },
                cancelled = cancelled
            )
        }
        require(!report.cancelled && !report.stale) {
            "تغير جهاز Quest أو أُلغي فحص APK."
        }
        require(report.device.serial == serial) {
            "تغير جهاز Quest أثناء فحص APK."
        }
        val game = report.games.singleOrNull { it.packageId == app.packageName }
            ?: error("تعذر العثور على اللعبة المحددة أثناء فحص APK.")
        require(game.versionName == app.versionName && game.versionCode == app.versionCode) {
            "تغير إصدار اللعبة أثناء فحص APK."
        }
        val base = game.apks.firstOrNull { it.splitName == null }
            ?: error("تعذر العثور على APK الأساسي للعبة المحددة.")
        val hash = base.sha256?.trim().orEmpty()
        require(hash.length == 64) {
            "تعذر التحقق من بصمة APK للعبة المحددة."
        }
        val profile = GameModProfileRegistry.findByPackageId(app.packageName)
        require(profile == null || profile.acceptsApkSha256(hash)) {
            "بصمة APK لا تطابق النسخة الموثقة للعبة."
        }
        return app.copy(apkSha256 = hash.lowercase())
    }
}

class ModsManager(
    private val adbClient: AdbClient,
    private val loaderDetector: ModLoaderDetector = AdbModLoaderDetector(adbClient),
    private val apkPatcher: ApkModLoaderPatcher = NoOpApkModLoaderPatcher,
    private val apkEvidenceRefresher: QuestApkEvidenceRefresher =
        RestrictedQuestApkEvidenceRefresher(adbClient)
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
        installedApp: InstalledQuestApp,
        onProgress: (String) -> Unit = {}
    ): ModPackageAnalysis = withContext(Dispatchers.IO) {
        /*
         * Content analysis is deliberately independent from APK acquisition.
         * A verified package/version, destination discovery, archive identity,
         * and the loader check (when the package declares one) are sufficient
         * for data-only content.  APK evidence remains a second pass reserved
         * for patching and native-loader plans.
         */
        val detection = loaderDetector.detect(serial, installedApp)
        val discovery = discoverModDirectoriesInternal(serial, installedApp, detection)
        var reviewedApp = installedApp
        var analysis = ModPackageAnalyzer().analyze(
            zipFile,
            reviewedApp,
            detection,
            discovery
        )
        if (requiresStrictApkEvidence(analysis.installPlan)) {
            reviewedApp = refreshApkEvidenceIfRequired(serial, reviewedApp, onProgress)
            analysis = ModPackageAnalyzer().analyze(
                zipFile,
                reviewedApp,
                detection,
                discovery
            )
        }
        // Classification must remain displayable without an executable
        // operation binding (for example Gorilla APK patch requirements).
        val deviceAwarePlan = analysis.installPlan.copy(reviewedDeviceSerial = serial)
        val boundPlan = deviceAwarePlan.tryBindToDevice(serial)
        // Installed-version decision: read-only, never blocks analysis.
        // A missing/unreadable install simply yields no assessment and the
        // normal first-install path applies.
        val assessment = runCatching {
            assessInstalledMod(serial, analysis)
        }.getOrNull()
        val nomadAssessment = runCatching {
            assessInstalledNomadMod(serial, analysis)
        }.getOrNull()
        return@withContext if (boundPlan == null) {
            analysis.copy(
                installPlan = deviceAwarePlan,
                installedModAssessment = assessment,
                nomadAssessment = nomadAssessment
            )
        } else {
            analysis.copy(
                installPlan = boundPlan,
                installedModAssessment = assessment,
                nomadAssessment = nomadAssessment
            )
        }
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
        add("/sdcard/ModData/${installedApp.packageName}/Mods", "Scotland2 Mods path")
        add("/sdcard/ModData/${installedApp.packageName}/Modloader", "Scotland2 Modloader root")
        add("/sdcard/ModData/${installedApp.packageName}/Modloader/early_mods", "Scotland2 early_mods path")
        add("/sdcard/ModData/${installedApp.packageName}/Modloader/mods", "Scotland2 mods path")
        add("/sdcard/ModData/${installedApp.packageName}/Modloader/libs", "Scotland2 libs path")
        add("/sdcard/ModData/${installedApp.packageName}/Packages", "Scotland2 QMOD packages path")
        add("/sdcard/ModData/${installedApp.packageName}/Configs", "Scotland2 configs path")
        val candidates = paths.map { (path, source) ->
            ModDirectoryCandidate(
                packageId = installedApp.packageName,
                path = path,
                exists = runCatching {
                    adbClient.shell(serial, "test", "-d", shellQuoteRemotePath(path)).exit == 0
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
            ),
            beatSaberInventory = collectBeatSaberInventory(serial, installedApp),
            pavlovRunAsFunctional = probePavlovRunAs(serial, installedApp)
        )
    }

    /**
     * Pavlov only, strictly read-only: reports whether `run-as` functions
     * for the installed package (debuggable build).  Never a write probe
     * and never an install gate; shell writes were proven denied on
     * device even where run-as reads work.
     */
    private fun probePavlovRunAs(serial: String, installedApp: InstalledQuestApp): Boolean? {
        if (installedApp.packageName != ModPackageAnalyzer.PAVLOV_PACKAGE_ID) return null
        return runCatching {
            adbClient.shell(serial, "run-as", installedApp.packageName, "ls").exit == 0
        }.getOrDefault(false)
    }

    /**
     * Compares an analyzed BONELAB archive against the installed mod
     * folder, if any.  Null when inapplicable (other games, no evidenced
     * pallet identity, unreadable state).  Every device read is bounded
     * and read-only; the installed pallet is pulled byte-exact so its
     * hash is comparable.
     */
    suspend fun assessInstalledMod(
        serial: String,
        analysis: ModPackageAnalysis
    ): InstalledModAssessment? {
        if (analysis.packageType != ModPackageType.BONELAB_NATIVE_CONTENT) return null
        val archiveIdentity = analysis.bonelabIdentity ?: return null
        val root = archiveIdentity.modRoot?.trim()?.takeIf { it.isNotBlank() && !it.contains('/') }
            ?: return null
        val profile = GameModProfileRegistry.findByPackageId(ModPackageAnalyzer.BONELAB_PACKAGE_ID)
            ?: return null
        val base = profile.destination.trimEnd('/').takeIf {
            it.isNotBlank() && AndroidPathValidator.isSafe(it)
        } ?: return null
        return assessInstalledModWithIdentity(serial, archiveIdentity, base, root)
    }

    private suspend fun assessInstalledModWithIdentity(
        serial: String,
        archiveIdentity: BonelabPalletIdentity,
        base: String,
        modRoot: String
    ): InstalledModAssessment? {
        val modDir = "$base/$modRoot"
        if (adbClient.shell(serial, "test", "-d", shellQuoteRemotePath(modDir)).exit != 0) {
            return InstalledModAssessment(
                relation = InstalledModRelation.ABSENT,
                modRoot = modRoot,
                archiveIdentity = archiveIdentity,
                installedIdentity = null
            )
        }
        val installedIdentity = readInstalledPalletIdentity(serial, base, modRoot)
        val fileCount = runCatching {
            val count = adbClient.shell(
                serial, "find", shellQuoteRemotePath(modDir), "-type", "f", "|", "wc", "-l"
            )
            if (count.exit != 0) null
            else count.out.trim().toLongOrNull()?.takeIf { it >= 0L }?.toInt()
        }.getOrNull()
        return InstalledModAssessment(
            relation = decideInstalledModRelation(archiveIdentity, installedIdentity),
            modRoot = modRoot,
            archiveIdentity = archiveIdentity,
            installedIdentity = installedIdentity,
            installedFileCount = fileCount
        )
    }

    /**
     * Compares an analyzed Nomad archive against installed mod folders.
     * Null when inapplicable (other games, no evidenced manifest
     * identity).  Identity is proven by manifest Name (+Author when both
     * sides declare one); the folder is located by scanning installed
     * manifests, never by trusting the archive's folder name.  Every
     * device read is bounded and read-only.
     */
    suspend fun assessInstalledNomadMod(
        serial: String,
        analysis: ModPackageAnalysis
    ): NomadInstalledAssessment? {
        if (analysis.installPlan.reviewedApp?.packageName != ModPackageAnalyzer.NOMAD_PACKAGE_ID &&
            analysis.installPlan.targetPackageId != ModPackageAnalyzer.NOMAD_PACKAGE_ID
        ) {
            return null
        }
        val archiveIdentity = analysis.nomadIdentity ?: return null
        val profile = GameModProfileRegistry.findByPackageId(ModPackageAnalyzer.NOMAD_PACKAGE_ID)
            ?: return null
        val base = profile.destination.trimEnd('/').takeIf {
            it.isNotBlank() && AndroidPathValidator.isSafe(it)
        } ?: return null
        if (adbClient.shell(serial, "test", "-d", shellQuoteRemotePath(base)).exit != 0) {
            return null
        }
        val folders = runCatching {
            val listed = adbClient.shell(serial, "ls", shellQuoteRemotePath(base))
            if (listed.exit != 0) return null
            listed.out.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.contains('/') && !it.startsWith(".") }
                .toList()
        }.getOrNull() ?: return null
        for (folder in folders) {
            val manifestPath = "$base/$folder/manifest.json"
            if (adbClient.shell(serial, "test", "-f", shellQuoteRemotePath(manifestPath)).exit != 0) {
                continue
            }
            val installed = readInstalledNomadIdentity(serial, manifestPath, folder) ?: continue
            if (!installed.name.equals(archiveIdentity.name, ignoreCase = false)) continue
            val archiveAuthor = archiveIdentity.author?.trim()?.ifBlank { null }
            val installedAuthor = installed.author?.trim()?.ifBlank { null }
            if (archiveAuthor != null && installedAuthor != null &&
                !archiveAuthor.equals(installedAuthor, ignoreCase = true)
            ) {
                return NomadInstalledAssessment(
                    relation = InstalledModRelation.IDENTITY_MISMATCH,
                    modRoot = folder,
                    archiveIdentity = archiveIdentity,
                    installedIdentity = installed
                )
            }
            val fileCount = runCatching {
                val count = adbClient.shell(
                    serial, "find", shellQuoteRemotePath("$base/$folder"),
                    "-type", "f", "|", "wc", "-l"
                )
                if (count.exit != 0) null
                else count.out.trim().toLongOrNull()?.takeIf { it >= 0L }?.toInt()
            }.getOrNull()
            return NomadInstalledAssessment(
                relation = decideNomadInstalledRelation(archiveIdentity, installed),
                modRoot = folder,
                archiveIdentity = archiveIdentity,
                installedIdentity = installed,
                installedFileCount = fileCount
            )
        }
        return NomadInstalledAssessment(
            relation = InstalledModRelation.ABSENT,
            modRoot = archiveIdentity.modRoot.orEmpty(),
            archiveIdentity = archiveIdentity,
            installedIdentity = null
        )
    }

    private suspend fun readInstalledNomadIdentity(
        serial: String,
        manifestPath: String,
        modFolder: String
    ): NomadModIdentity? {
        if (!AndroidPathValidator.isSafe(manifestPath)) return null
        val size = adbClient.shell(serial, "stat", "-c", "%s", shellQuoteRemotePath(manifestPath))
        if (size.exit != 0) return null
        val bytes = size.out.trim().toLongOrNull() ?: return null
        if (bytes <= 0L || bytes > 64L * 1024L) return null
        val local = runCatching {
            File.createTempFile("nfvr-installed-nomad-", ".json").also { it.deleteOnExit() }
        }.getOrNull() ?: return null
        try {
            val pulled = adbClient.pullReadOnly(serial, manifestPath, local)
            if (pulled.exit != 0 || !local.isFile || local.length() != bytes) return null
            val json = runCatching {
                JSONObject(local.readText(Charsets.UTF_8))
            }.getOrNull() ?: return null
            val name = json.optString("Name", "").trim().ifBlank { null } ?: return null
            return NomadModIdentity(
                name = name,
                author = json.optString("Author", "").trim().ifBlank { null },
                modVersion = json.optString("ModVersion", "").trim().ifBlank { null },
                gameVersion = json.optString("GameVersion", "").trim().ifBlank { null },
                modRoot = modFolder
            )
        } finally {
            runCatching { local.delete() }
        }
    }

    private suspend fun readInstalledPalletIdentity(
        serial: String,
        base: String,
        modRoot: String
    ): BonelabPalletIdentity? {
        val modDir = "$base/$modRoot"
        val conventional = "$modDir/$modRoot.pallet.json"
        val palletPath = if (
            adbClient.shell(serial, "test", "-f", shellQuoteRemotePath(conventional)).exit == 0
        ) {
            conventional
        } else {
            val found = adbClient.shell(
                serial, "find", shellQuoteRemotePath(modDir),
                "-maxdepth", "1", "-name", "'*.pallet.json'"
            )
            if (found.exit != 0) return null
            found.out.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }
                ?.takeIf { AndroidPathValidator.isSafe(it) && it.startsWith("$modDir/") }
                ?: return null
        }
        return readInstalledPalletIdentityFrom(serial, palletPath, modRoot)
    }

    /**
     * Pulls one remote pallet file byte-exact (bounded to 1MB) and parses
     * its authoritative identity.  Shared by assessment and staged-update
     * verification so both reason about identical evidence.
     */
    private suspend fun readInstalledPalletIdentityFrom(
        serial: String,
        palletPath: String,
        modRoot: String
    ): BonelabPalletIdentity? {
        if (!AndroidPathValidator.isSafe(palletPath)) return null
        val size = adbClient.shell(serial, "stat", "-c", "%s", shellQuoteRemotePath(palletPath))
        if (size.exit != 0) return null
        val bytes = size.out.trim().toLongOrNull() ?: return null
        if (bytes <= 0L || bytes > 1024L * 1024L) return null
        val local = runCatching {
            File.createTempFile("nfvr-installed-pallet-", ".json").also { it.deleteOnExit() }
        }.getOrNull() ?: return null
        try {
            val pulled = adbClient.pullReadOnly(serial, palletPath, local)
            if (pulled.exit != 0 || !local.isFile || local.length() != bytes) return null
            val json = runCatching {
                JSONObject(local.readText(Charsets.UTF_8))
            }.getOrNull() ?: return null
            return parseBonelabPalletIdentity(
                json,
                modRoot = modRoot,
                palletSha256 = sha256(local)
            )
        } finally {
            runCatching { local.delete() }
        }
    }

    /**
     * Beat Saber only.  Lists Scotland2 package/library names read-only
     * (`ls`); any failure yields an empty inventory, never a block.
     */
    private fun collectBeatSaberInventory(
        serial: String,
        installedApp: InstalledQuestApp
    ): BeatSaberModInventory? {
        if (installedApp.packageName != "com.beatgames.beatsaber") return null
        return runCatching {
            fun lsNames(path: String): List<String> {
                val result = adbClient.shell(serial, "ls", shellQuoteRemotePath(path))
                if (result.exit != 0) return emptyList()
                return result.out.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() && !it.contains('/') && !it.startsWith(".") }
                    .toList()
            }
            val base = "/sdcard/ModData/${installedApp.packageName}"
            val packagesRoot = "$base/Packages"
            val packages = lsNames(packagesRoot).flatMap { version ->
                lsNames("$packagesRoot/$version").map { dir ->
                    parseScotland2PackageDir(dir, "Packages/$version/$dir")
                }
            }
            val libs = lsNames("$base/Modloader/libs").filter { it.endsWith(".so", ignoreCase = true) }
            val mods = (lsNames("$base/Modloader/mods") + lsNames("$base/Modloader/early_mods"))
                .filter { it.endsWith(".so", ignoreCase = true) || it.endsWith(".dll", ignoreCase = true) }
            BeatSaberModInventory(
                serial = serial,
                packageId = installedApp.packageName,
                gameVersion = installedApp.versionName,
                packages = packages,
                loaderLibs = libs,
                mods = mods
            )
        }.getOrNull()
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
        update: ModUpdateAuthorization? = null,
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
        val scannedCurrentApp = scanInstalledQuestApps(serial)
            .firstOrNull { it.packageName == reviewedApp.packageName }
            ?: return ModInstallResult(false, "التطبيق المستهدف لم يعد مثبتًا؛ أعد تحليل الحزمة.")
        var currentApp = try {
            if (requiresStrictApkEvidence(plan)) {
                refreshApkEvidenceIfRequired(serial, scannedCurrentApp) { message ->
                    onProgress(ModExecutionProgress(ModInstallPhase.ANALYZING, null, message))
                }
            } else {
                scannedCurrentApp
            }
        } catch (error: Throwable) {
            return ModInstallResult(false, error.message ?: "تعذر التحقق من متطلبات تجهيز اللعبة.")
        }
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
        val inspectedDiscovery = discoverModDirectoriesInternal(serial, currentApp, loaderDetection)
        val discovery = if (
            plan.strategy == ModInstallStrategy.GENERIC_EXISTING_DIRECTORY_COPY &&
            plan.confirmation != null
        ) {
            val inspected = inspectedDiscovery
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
            /*
             * Dedicated content analyzers also consume this read-only evidence.
             * Do not let them turn a profile destination into an implicit mkdir
             * target merely because the package itself looks valid.
             */
            inspectedDiscovery
        }
        var freshAnalysis = ModPackageAnalyzer().analyze(
            zipFile,
            currentApp,
            loaderDetection,
            discovery
        )
        /*
         * The first pass is intentionally APK-free for content.  If the fresh
         * archive classification proves that this is a patch/native-loader
         * operation, acquire and bind the strict APK evidence before any
         * destination check or write.
         */
        if (requiresStrictApkEvidence(freshAnalysis.installPlan) &&
            currentApp.apkSha256.isNullOrBlank()
        ) {
            currentApp = try {
                refreshApkEvidenceIfRequired(serial, currentApp) { message ->
                    onProgress(ModExecutionProgress(ModInstallPhase.ANALYZING, null, message))
                }
            } catch (error: Throwable) {
                return ModInstallResult(
                    false,
                    error.message ?: "تعذر التحقق من متطلبات تجهيز اللعبة."
                )
            }
            if (!sameAppState(reviewedApp, currentApp)) {
                return ModInstallResult(false, "تغيرت حالة التطبيق المستهدف؛ أعد تحليل الحزمة.")
            }
            freshAnalysis = ModPackageAnalyzer().analyze(
                zipFile,
                currentApp,
                loaderDetection,
                discovery
            )
        }
        var freshPlan = freshAnalysis.installPlan.tryBindToDevice(serial)
            ?: freshAnalysis.installPlan
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
        if (requiresStrictApkEvidence(executionPlan) &&
            profile.supportedApkSha256.isNotEmpty() &&
            !profile.acceptsApkSha256(currentApp.apkSha256)
        ) {
            return ModInstallResult(
                false,
                "تغيرت بصمة APK عن النسخة الموثقة؛ أعد فحص اللعبة قبل النقل."
            )
        }
        if (profile.writeAuthorized && executionPlan.destinationRoot != profile.destination) {
            return ModInstallResult(false, "وجهة الخطة لا تطابق ملف تعريف اللعبة الموثوق.")
        }
        if (!profile.writeAuthorized &&
            (executionPlan.packageType !in setOf(
                    ModPackageType.QMOD,
                    ModPackageType.ANDROID_DATA_LAYOUT,
                    ModPackageType.ANDROID_OBB_LAYOUT
                ) ||
                executionPlan.mappings.isEmpty() ||
                executionPlan.mappings.any { !isApprovedDestination(it.destinationPath, profile, executionPlan) })
        ) {
            return ModInstallResult(false, "ملف تعريف اللعبة تصنيفي فقط؛ لا توجد وجهة محمّل موثقة قابلة للكتابة.")
        }

        // Host disk guard: extraction writes the full plan to a temp dir.
        // Fail before touching anything when free space cannot cover it.
        val hostFree = hostTempFreeBytes()
        if (!installDiskSpaceSufficient(hostFree, executionPlan.totalBytes)) {
            DiagnosticLogger.info(
                "Mod install blocked: host temp free=$hostFree required=${executionPlan.totalBytes}"
            )
            return ModInstallResult(false, "لا توجد مساحة كافية على الكمبيوتر لاستخراج المود.")
        }
        // Quest disk guard: fail before extraction when the destination
        // filesystem cannot cover the plan.  An unreadable df is logged
        // and treated as unknown (not as proof of insufficiency).
        val questFree = questFreeBytes(serial, executionPlan.destinationRoot)
        if (questFree == null) {
            DiagnosticLogger.info("Mod install: quest free space unreadable; proceeding without disk gate.")
        }
        if (!installDiskSpaceSufficient(questFree, executionPlan.totalBytes)) {
            DiagnosticLogger.info(
                "Mod install blocked: quest free=$questFree required=${executionPlan.totalBytes}"
            )
            return ModInstallResult(false, "لا توجد مساحة كافية على النظارة لتثبيت المود.")
        }
        val tempRoot = Files.createTempDirectory("NFVR_ModPlan_").toFile()
        var copiedBytes = 0L
        var copiedFiles = 0
        var remoteStagingDir: String? = null
        val tempRootBase = executionPlan.destinationRoot?.trimEnd('/')
            ?.takeIf { it.isNotBlank() && AndroidPathValidator.isSafe(it) }
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
            val scannedBeforeTransfer = scanInstalledQuestApps(serial)
                .firstOrNull { it.packageName == reviewedApp.packageName }
            val appBeforeTransfer = scannedBeforeTransfer?.let {
                runCatching {
                    if (requiresStrictApkEvidence(executionPlan)) {
                        refreshApkEvidenceIfRequired(serial, it) { message ->
                            onProgress(ModExecutionProgress(ModInstallPhase.VALIDATING, null, message))
                        }
                    } else {
                        it
                    }
                }.getOrNull()
            }
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

            // A user-confirmed proven-newer update takes the staged path:
            // transfer aside, verify staged metadata, rename-swap with
            // backup, verify final, rollback on any failure.  Null keeps
            // the exact proven first-install behavior below (which refuses
            // existing destinations outright).
            if (update != null) {
                return executeStagedUpdate(
                    serial = serial,
                    extracted = extracted,
                    executionPlan = executionPlan,
                    archiveIdentity = freshAnalysis.bonelabIdentity,
                    profile = profile,
                    reviewedApp = reviewedApp,
                    reviewedIdentity = reviewedIdentity,
                    operationBinding = operationBinding,
                    update = update,
                    onProgress = onProgress
                )
            }

            // Refuse collisions before the first mkdir/push. A direct install
            // is additive only; replacing a file could destroy an unrelated
            // mod and cannot be audited or rolled back safely.
            val destinationRoot = executionPlan.destinationRoot
            if (destinationRoot != null &&
                executionPlan.strategy != ModInstallStrategy.GENERIC_EXISTING_DIRECTORY_COPY
            ) {
                /*
                 * Reserve every first-level mod root, not just the common root.
                 * A package can legitimately contain multiple mod roots; checking
                 * only one common root would permit an unrelated sibling to be
                 * merged or replaced.
                 */
                for (modRoot in destinationModRoots(executionPlan)) {
                    val rootAvailable = adbClient.shell(serial, "test", "!", "-e", shellQuoteRemotePath(modRoot))
                    if (rootAvailable.exit == 1) {
                        DiagnosticLogger.info("Mod install destination root already exists: $modRoot")
                        return ModInstallResult(
                            false,
                            "مجلد المود موجود مسبقًا ($modRoot)؛ لم يتم دمج ملفات داخل مجلد موجود."
                        )
                    }
                    if (rootAvailable.exit != 0) {
                        return ModInstallResult(
                            false,
                            customerModExecutionFailureMessage(ModExecutionFailureKind.PREPARE_DESTINATION)
                        )
                    }
                }
            }
            for (mapping in executionPlan.mappings) {
                val destination = mapping.destinationPath
                if (!AndroidPathValidator.isSafe(destination)) {
                    return ModInstallResult(false, "تم رفض وجهة غير معتمدة: $destination")
                }
                // Ask for the safe (non-collision) result so legacy ADB
                // fakes and restricted shells can treat exit=0 as "absent".
                val collision = adbClient.shell(serial, "test", "!", "-e", shellQuoteRemotePath(destination))
                when {
                    collision.exit == 1 -> {
                        DiagnosticLogger.info("Mod install collision: $destination")
                        return ModInstallResult(
                            false,
                            "تعارضت ملفات المود مع ملفات موجودة مسبقًا؛ لم يتم استبدال أي ملف."
                        )
                    }
                    collision.exit != 0 -> {
                        DiagnosticLogger.info(
                            "Unable to inspect mod destination: path=$destination " +
                                "exit=${collision.exit} stderr=${collision.err.take(500)}"
                        )
                        return ModInstallResult(
                            false,
                            customerModExecutionFailureMessage(ModExecutionFailureKind.PREPARE_DESTINATION)
                        )
                    }
                }
            }
            if (isDedicatedContentPlan(executionPlan)) {
                val base = executionPlan.destinationRoot
                if (base.isNullOrBlank() || !AndroidPathValidator.isSafe(base)) {
                    return ModInstallResult(false, "لم يتم إثبات جذر محتوى معتمد قبل النقل.")
                }
                val baseExists = adbClient.shell(serial, "test", "-d", shellQuoteRemotePath(base))
                if (baseExists.exit != 0) {
                    return ModInstallResult(
                        false,
                        "اختفى مجلد المحتوى المعتمد ($base) قبل النقل؛ لم يتم إنشاء مجلد بديل."
                    )
                }
            }

            // Entries the host cannot materialize (Windows-illegal names)
            // stage through an NFVR-owned remote temp dir under the
            // approved root, then move to their exact final names.  Plans
            // without such entries transfer exactly like before.
            val tempIndices = executionPlan.mappings.mapIndexedNotNull { index, mapping ->
                index.takeIf {
                    requiresSafeTempName(mapping.destinationPath.substringAfterLast('/'))
                }
            }.toSet()
            if (tempIndices.isNotEmpty()) {
                if (tempRootBase == null) {
                    return ModInstallResult(false, "لم يتم إثبات جذر محتوى معتمد قبل النقل.")
                }
                val tmp = remoteTempDir(tempRootBase, operationBinding.analysisPlanId)
                if (tmp == null || !isNfvrTempDir(tmp, tempRootBase)) {
                    return ModInstallResult(
                        false,
                        customerModExecutionFailureMessage(ModExecutionFailureKind.PREPARE_DESTINATION)
                    )
                }
                runCatching {
                    adbClient.shell(serial, "rm", "-rf", shellQuoteRemotePath(tmp))
                }
                val mkTemp = createModPath(serial, tmp, approvedBase = tempRootBase)
                if (mkTemp.exit != 0) {
                    DiagnosticLogger.info(
                        "ADB mkdir failed for remote temp dir: exit=${mkTemp.exit}, stderr=${mkTemp.err}"
                    )
                    return ModInstallResult(
                        false,
                        customerModExecutionFailureMessage(ModExecutionFailureKind.PREPARE_DESTINATION)
                    )
                }
                remoteStagingDir = tmp
            }

            for ((index, mapping) in executionPlan.mappings.withIndex()) {
                val destination = mapping.destinationPath
                if (!isApprovedDestination(destination, profile, executionPlan)) {
                    return ModInstallResult(false, "تم رفض وجهة غير معتمدة: $destination")
                }
                val parent = destination.substringBeforeLast('/', profile.destination)
                val mkdir = createModPath(
                    serial,
                    parent,
                    approvedBase = executionPlan.destinationRoot
                )
                if (mkdir.exit != 0) {
                    DiagnosticLogger.info(
                        "ADB mkdir failed for mod destination: exit=${mkdir.exit}, stderr=${mkdir.err}, stdout=${mkdir.out}"
                    )
                    cleanupRemoteTempDir(serial, remoteStagingDir, tempRootBase)
                    return ModInstallResult(
                        false,
                        customerModExecutionFailureMessage(ModExecutionFailureKind.PREPARE_DESTINATION)
                    )
                }

                val source = extracted.getValue(mapping.sourcePath)
                // Staged entries push to the NFVR-owned temp dir, then move
                // to the exact final name.  Everything else pushes direct.
                val staging = remoteStagingDir
                val useStaging = index in tempIndices && staging != null
                val pushTarget = if (useStaging) {
                    "$staging/part-%04d".format(index)
                } else {
                    destination
                }
                val displayName = destination.substringAfterLast('/')
                onProgress(
                    ModExecutionProgress(
                        ModInstallPhase.TRANSFERRING,
                        executionPlan.progress(copiedBytes, copiedFiles).fraction.coerceAtMost(0.94),
                        transferProgressMessage(source, copiedFiles, executionPlan, displayName = displayName)
                    )
                )
                val pushed = pushModFileWithRetry(
                    serial,
                    source,
                    pushTarget,
                    maxAttempts = 2,
                    onProgress = { current, _ ->
                        val fraction = executionPlan.progress(copiedBytes + current, copiedFiles)
                            .fraction.coerceAtMost(0.94)
                        onProgress(
                            ModExecutionProgress(
                                ModInstallPhase.TRANSFERRING,
                                fraction,
                                transferProgressMessage(
                                    source,
                                    copiedFiles,
                                    executionPlan,
                                    currentBytes = current,
                                    displayName = displayName
                                )
                            )
                        )
                    }
                )
                if (pushed.exit != 0) {
                    DiagnosticLogger.info(
                        "ADB push failed for ${mapping.sourcePath}: exit=${pushed.exit}, stderr=${pushed.err}, stdout=${pushed.out}"
                    )
                    cleanupRemoteTempDir(serial, remoteStagingDir, tempRootBase)
                    return ModInstallResult(
                        false,
                        customerModExecutionFailureMessage(ModExecutionFailureKind.TRANSFER)
                    )
                }
                if (useStaging) {
                    val moved = adbClient.shell(
                        serial,
                        "mv",
                        shellQuoteRemotePath(pushTarget),
                        shellQuoteRemotePath(destination)
                    )
                    if (moved.exit != 0) {
                        DiagnosticLogger.info(
                            "ADB mv failed for ${mapping.sourcePath}: exit=${moved.exit}, stderr=${moved.err}, stdout=${moved.out}"
                        )
                        cleanupRemoteTempDir(serial, remoteStagingDir, tempRootBase)
                        return ModInstallResult(
                            false,
                            customerModExecutionFailureMessage(ModExecutionFailureKind.TRANSFER)
                        )
                    }
                }
                copiedBytes += mapping.sizeBytes
                copiedFiles++
            }
            cleanupRemoteTempDir(serial, remoteStagingDir, tempRootBase)

            onProgress(
                ModExecutionProgress(
                    ModInstallPhase.VERIFYING,
                    0.95,
                    "التحقق من الملفات على النظارة (0/${executionPlan.totalFiles})"
                )
            )
            val verificationErrors = buildList {
                val root = executionPlan.destinationRoot
                if (root.isNullOrBlank() || !AndroidPathValidator.isSafe(root)) {
                    add("فشل التحقق من جذر وجهة المود.")
                } else {
                    val rootResult = adbClient.shell(serial, "test", "-d", shellQuoteRemotePath(root))
                    if (rootResult.exit != 0) {
                        add("فشل التحقق: مجلد الوجهة غير موجود في $root")
                    }
                }
                executionPlan.mappings.mapIndexedNotNullTo(this) { index, mapping ->
                    val verificationFraction = if (executionPlan.totalFiles == 0) {
                        0.95
                    } else {
                        0.95 + (0.05 * (index + 1).toDouble() / executionPlan.totalFiles)
                    }
                    onProgress(
                        ModExecutionProgress(
                            ModInstallPhase.VERIFYING,
                            verificationFraction.coerceAtMost(0.99),
                            "التحقق من ${mapping.sourcePath.substringAfterLast('/')} " +
                                "(${index + 1}/${executionPlan.totalFiles})"
                        )
                    )
                    verifyRemoteFile(serial, mapping)
                }
            }
            if (verificationErrors.isNotEmpty()) {
                DiagnosticLogger.info(
                    "Mod verification failed: ${verificationErrors.joinToString(" | ")}"
                )
                return ModInstallResult(
                    false,
                    customerModExecutionFailureMessage(ModExecutionFailureKind.VERIFY)
                )
            }
            val scannedAfterVerification = scanInstalledQuestApps(serial)
                .firstOrNull { it.packageName == reviewedApp.packageName }
            val appAfterVerification = scannedAfterVerification?.let {
                runCatching {
                    if (requiresStrictApkEvidence(executionPlan)) {
                        refreshApkEvidenceIfRequired(serial, it) { message ->
                            onProgress(ModExecutionProgress(ModInstallPhase.VERIFYING, null, message))
                        }
                    } else {
                        it
                    }
                }.getOrNull()
            }
            if (appAfterVerification == null || !sameAppState(reviewedApp, appAfterVerification)) {
                return ModInstallResult(false, "تغير اتصال النظارة أو إصدار اللعبة أثناء التحقق؛ لم يتم اعتماد النجاح.")
            }

            onProgress(ModExecutionProgress(ModInstallPhase.COMPLETED, 1.0, "اكتمل التثبيت والتحقق"))
            ModInstallHistory.record(executionPlan, true, "verified")
            val successMessage = if (requiresStrictApkEvidence(executionPlan) &&
                profile.supportedApkSha256.isNotEmpty()
            ) {
                "تم تثبيت المود والتحقق من الملفات وبصمة APK بنجاح."
            } else {
                "تم تثبيت المود والتحقق من الملفات بنجاح."
            }
            return ModInstallResult(true, successMessage)
        } catch (e: Exception) {
            cleanupRemoteTempDir(serial, remoteStagingDir, tempRootBase)
            ModInstallHistory.record(executionPlan, false, e.message ?: "error")
            DiagnosticLogger.error("فشل تنفيذ خطة تثبيت المود", e)
            return ModInstallResult(
                false,
                customerModExecutionFailureMessage(ModExecutionFailureKind.UNEXPECTED)
            )
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    /**
     * Staged mod update: transfer the complete new version aside, verify
     * every staged file plus staged metadata, then rename-swap with the
     * installed version kept as a backup until final verification passes.
     * Never merges old and new files: the final directory is always born
     * from exactly one complete verified tree.  Any failure before the
     * swap leaves the installed mod untouched; any failure during/after
     * restores it from backup.
     */
    private suspend fun executeStagedUpdate(
        serial: String,
        extracted: Map<String, File>,
        executionPlan: ModInstallPlan,
        archiveIdentity: BonelabPalletIdentity?,
        profile: GameModProfile,
        reviewedApp: InstalledQuestApp,
        reviewedIdentity: ModArchiveIdentity,
        operationBinding: ModOperationBinding,
        update: ModUpdateAuthorization,
        onProgress: (ModExecutionProgress) -> Unit
    ): ModInstallResult {
        fun refuse(reason: String): ModInstallResult {
            val site = Throwable().stackTrace
                .firstOrNull { it.fileName == "ModsManager.kt" && !it.methodName.endsWith("refuse") }?.lineNumber
            DiagnosticLogger.info("Mod update refused [line=$site]: $reason")
            return ModInstallResult(false, reason)
        }
        // 1. Authorization must match this exact live operation.
        if (update.deviceSerial != serial ||
            update.packageId != reviewedApp.packageName ||
            update.archiveSha256 != reviewedIdentity.sha256
        ) {
            return refuse("تغير ربط الجهاز أو الخطة؛ أعد تحليل الحزمة قبل التحديث.")
        }
        if (executionPlan.packageType != ModPackageType.BONELAB_NATIVE_CONTENT ||
            executionPlan.mappings.isEmpty() ||
            archiveIdentity == null
        ) {
            return refuse("خطة التحديث غير صالحة لهذا النوع من المودات.")
        }
        if (!archiveIdentity.barcode.equals(update.archiveBarcode, ignoreCase = true) ||
            archiveIdentity.version != update.newVersion
        ) {
            return refuse("تغيرت الحزمة منذ تأكيد التحديث؛ أعد التحليل.")
        }
        val base = profile.destination.trimEnd('/').takeIf {
            it.isNotBlank() && AndroidPathValidator.isSafe(it)
        } ?: return refuse("لم يتم إثبات جذر محتوى معتمد قبل النقل.")
        val modRoot = update.modRoot.trim().takeIf {
            it.isNotBlank() && !it.contains('/')
        } ?: return refuse("اسم مجلد المود غير صالح للتحديث.")
        val finalRoot = "$base/$modRoot"
        if (!AndroidPathValidator.isSafe(finalRoot)) {
            return refuse("وجهة التحديث خارج مجلد المودات المعتمد.")
        }
        // 2. Live re-assessment: still the same mod, still proven newer.
        val live = assessInstalledModWithIdentity(serial, archiveIdentity, base, modRoot)
            ?: return refuse("تعذر إعادة فحص المود المثبت؛ أعد التحليل.")
        if (live.relation != InstalledModRelation.NEWER_THAN_INSTALLED) {
            return refuse("لم يعد التحديث صالحًا؛ أعد تحليل الحزمة.")
        }
        if (!live.installedIdentity?.version.equals(update.installedVersion, ignoreCase = false) &&
            !(live.installedIdentity?.version == null && update.installedVersion == null)
        ) {
            return refuse("تغير الإصدار المثبت منذ التأكيد؛ أعد التحليل.")
        }
        val expectedToken = modUpdateConfirmationToken(
            serial, reviewedApp.packageName, modRoot,
            reviewedIdentity.sha256, live.installedIdentity?.version, archiveIdentity.version.orEmpty()
        )
        if (expectedToken != update.confirmationToken) {
            return refuse("تغيرت بيانات التأكيد؛ أعد تحليل الحزمة وأكد التحديث مجددًا.")
        }
        if (adbClient.shell(serial, "test", "-d", shellQuoteRemotePath(finalRoot)).exit != 0) {
            return refuse("مجلد المود المثبت غير موجود؛ ثبّت المود كجديد أولًا.")
        }
        // 3. Staging + backup roots (owned namespace, cleared first).
        val nonce = operationBinding.analysisPlanId.filter { it.isLetterOrDigit() }.take(12)
        if (nonce.isBlank()) return refuse("معرف الخطة غير صالح للتحديث.")
        val stagingRoot = "$base/.nfvr-update-$nonce"
        val stagingMod = "$stagingRoot/$modRoot"
        val backupRoot = "$base/.nfvr-backup-$nonce"
        for (dir in listOf(stagingRoot, backupRoot)) {
            if (!isNfvrOwnedDir(dir, base, NFVR_OWNED_DIR_PREFIXES)) {
                return refuse("مسار مؤقت غير معتمد.")
            }
            runCatching { adbClient.shell(serial, "rm", "-rf", shellQuoteRemotePath(dir)) }
        }
        val mkStaging = createModPath(serial, stagingMod, approvedBase = base)
        if (mkStaging.exit != 0) {
            return refuse(customerModExecutionFailureMessage(ModExecutionFailureKind.PREPARE_DESTINATION))
        }
        // 4. Rewrite mappings onto the staging tree (same relative shape).
        val stagedMappings = executionPlan.mappings.map { mapping ->
            val relative = mapping.destinationPath.removePrefix("$finalRoot/")
            if (relative == mapping.destinationPath || relative.isBlank() ||
                relative.split('/').any { it == ".." }
            ) {
                return refuse("تم رفض وجهة غير معتمدة: ${mapping.destinationPath}")
            }
            val staged = "$stagingMod/$relative"
            if (!AndroidPathValidator.isSafe(staged) ||
                !isApprovedDestination(staged, profile, executionPlan)
            ) {
                return refuse("تم رفض وجهة غير معتمدة: ${mapping.destinationPath}")
            }
            mapping.copy(destinationPath = staged)
        }
        // Staging is fresh: every target must be absent (no silent merge).
        for (mapping in stagedMappings) {
            val collision = adbClient.shell(serial, "test", "!", "-e", shellQuoteRemotePath(mapping.destinationPath))
            if (collision.exit != 0) {
                cleanupRemoteTempDir(serial, stagingRoot, base)
                return refuse("تعارضت ملفات التحديث المؤقتة مع ملفات موجودة؛ تم إيقاف التحديث.")
            }
        }
        val tmpSubIndices = stagedMappings.mapIndexedNotNull { index, mapping ->
            index.takeIf {
                requiresSafeTempName(mapping.destinationPath.substringAfterLast('/'))
            }
        }.toSet()
        var remoteTmp: String? = null
        if (tmpSubIndices.isNotEmpty()) {
            val tmp = remoteTempDir(stagingRoot, operationBinding.analysisPlanId)
            if (tmp == null || !isNfvrTempDir(tmp, base)) {
                cleanupRemoteTempDir(serial, stagingRoot, base)
                return refuse(customerModExecutionFailureMessage(ModExecutionFailureKind.PREPARE_DESTINATION))
            }
            runCatching { adbClient.shell(serial, "rm", "-rf", shellQuoteRemotePath(tmp)) }
            if (createModPath(serial, tmp, approvedBase = base).exit != 0) {
                cleanupRemoteTempDir(serial, stagingRoot, base)
                return refuse(customerModExecutionFailureMessage(ModExecutionFailureKind.PREPARE_DESTINATION))
            }
            remoteTmp = tmp
        }
        fun cleanStaging() {
            cleanupRemoteTempDir(serial, remoteTmp, base)
            cleanupRemoteTempDir(serial, stagingRoot, base)
        }
        // 5. Transfer the complete new tree aside.
        var copiedBytes = 0L
        var copiedFiles = 0
        onProgress(ModExecutionProgress(ModInstallPhase.EXTRACTING, null, "تجهيز ملفات التحديث"))
        for ((index, mapping) in stagedMappings.withIndex()) {
            val parent = mapping.destinationPath.substringBeforeLast('/', stagingMod)
            if (createModPath(serial, parent, approvedBase = base).exit != 0) {
                cleanStaging()
                return refuse(customerModExecutionFailureMessage(ModExecutionFailureKind.PREPARE_DESTINATION))
            }
            val source = extracted[mapping.sourcePath]
                ?: run {
                    cleanStaging()
                    return refuse("ملف التحديث ${mapping.sourcePath} غير موجود بعد الاستخراج.")
                }
            val useTmp = index in tmpSubIndices && remoteTmp != null
            val pushTarget = if (useTmp) "$remoteTmp/part-%04d".format(index) else mapping.destinationPath
            val pushed = pushModFileWithRetry(
                serial, source, pushTarget, maxAttempts = 2
            ) { current, _ ->
                onProgress(
                    ModExecutionProgress(
                        ModInstallPhase.TRANSFERRING,
                        executionPlan.progress(copiedBytes + current, copiedFiles).fraction.coerceAtMost(0.94),
                        transferProgressMessage(
                            source, copiedFiles, executionPlan,
                            currentBytes = current,
                            displayName = mapping.destinationPath.substringAfterLast('/')
                        )
                    )
                )
            }
            if (pushed.exit != 0) {
                cleanStaging()
                return refuse(customerModExecutionFailureMessage(ModExecutionFailureKind.TRANSFER))
            }
            if (useTmp) {
                val moved = adbClient.shell(
                    serial, "mv",
                    shellQuoteRemotePath(pushTarget),
                    shellQuoteRemotePath(mapping.destinationPath)
                )
                if (moved.exit != 0) {
                    cleanStaging()
                    return refuse(customerModExecutionFailureMessage(ModExecutionFailureKind.TRANSFER))
                }
            }
            copiedBytes += mapping.sizeBytes
            copiedFiles++
        }
        // 6. Verify every staged file, then staged metadata/version.
        onProgress(ModExecutionProgress(ModInstallPhase.VERIFYING, 0.95, "التحقق من ملفات التحديث المؤقتة"))
        for ((index, mapping) in stagedMappings.withIndex()) {
            onProgress(
                ModExecutionProgress(
                    ModInstallPhase.VERIFYING,
                    (0.95 + 0.02 * (index + 1).toDouble() / stagedMappings.size).coerceAtMost(0.97),
                    "التحقق من ${mapping.destinationPath.substringAfterLast('/')} (${index + 1}/${stagedMappings.size})"
                )
            )
            if (verifyRemoteFile(serial, mapping) != null) {
                cleanStaging()
                return refuse(customerModExecutionFailureMessage(ModExecutionFailureKind.VERIFY))
            }
        }
        val stagedPallet = stagedMappings.firstOrNull {
            it.destinationPath.substringAfterLast('/').endsWith(".pallet.json", ignoreCase = true)
        } ?: run {
            cleanStaging()
            return refuse("تعذر التحقق من بيانات التحديث المؤقتة.")
        }
        val stagedIdentity = readInstalledPalletIdentityFrom(serial, stagedPallet.destinationPath, modRoot)
            ?: run {
                cleanStaging()
                return refuse("تعذر التحقق من بيانات التحديث المؤقتة.")
            }
        if (!stagedIdentity.barcode.equals(archiveIdentity.barcode, ignoreCase = true) ||
            stagedIdentity.version != archiveIdentity.version
        ) {
            cleanStaging()
            return refuse("بيانات التحديث المؤقتة لا تطابق الحزمة المراجعة.")
        }
        // 7. Swap: installed -> backup, staged -> final.
        val toBackup = adbClient.shell(
            serial, "mv", shellQuoteRemotePath(finalRoot), shellQuoteRemotePath(backupRoot)
        )
        if (toBackup.exit != 0) {
            cleanStaging()
            return refuse("تعذر بدء الاستبدال؛ بقي الإصدار المثبت دون تغيير.")
        }
        val toFinal = adbClient.shell(
            serial, "mv", shellQuoteRemotePath(stagingMod), shellQuoteRemotePath(finalRoot)
        )
        if (toFinal.exit != 0) {
            val restored = adbClient.shell(
                serial, "mv", shellQuoteRemotePath(backupRoot), shellQuoteRemotePath(finalRoot)
            )
            cleanStaging()
            return if (restored.exit == 0) {
                refuse("فشل التحديث؛ تمت استعادة الإصدار السابق.")
            } else {
                ModInstallResult(
                    false,
                    "فشل التحديث وتعذر الاستعادة التلقائية؛ النسخة السابقة محفوظة في $backupRoot."
                )
            }
        }
        // 8. Verify final, then drop the backup.  Rollback on failure.
        val finalErrors = executionPlan.mappings.mapIndexedNotNull { index, mapping ->
            onProgress(
                ModExecutionProgress(
                    ModInstallPhase.VERIFYING,
                    (0.97 + 0.02 * (index + 1).toDouble() / executionPlan.mappings.size).coerceAtMost(0.99),
                    "التحقق من ${mapping.destinationPath.substringAfterLast('/')} (${index + 1}/${executionPlan.mappings.size})"
                )
            )
            verifyRemoteFile(serial, mapping)
        }
        if (finalErrors.isNotEmpty()) {
            val restored = adbClient.shell(
                serial, "mv", shellQuoteRemotePath(backupRoot), shellQuoteRemotePath(finalRoot)
            )
            cleanStaging()
            return if (restored.exit == 0) {
                refuse("فشل التحقق النهائي؛ تمت استعادة الإصدار السابق.")
            } else {
                ModInstallResult(
                    false,
                    "فشل التحقق النهائي وتعذر الاستعادة التلقائية؛ النسخة السابقة محفوظة في $backupRoot."
                )
            }
        }
        runCatching { adbClient.shell(serial, "rm", "-rf", shellQuoteRemotePath(backupRoot)) }
        cleanStaging()
        onProgress(ModExecutionProgress(ModInstallPhase.COMPLETED, 1.0, "اكتمل التحديث والتحقق"))
        ModInstallHistory.record(executionPlan, true, "verified-update")
        return ModInstallResult(
            true,
            "تم تحديث المود إلى الإصدار ${archiveIdentity.version} والتحقق من الملفات بنجاح."
        )
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

    private fun hostTempFreeBytes(): Long? {
        val dir = File(System.getProperty("java.io.tmpdir") ?: return null)
        return runCatching { dir.usableSpace }.getOrNull()
    }

    private fun questFreeBytes(serial: String, destinationRoot: String?): Long? {
        val target = destinationRoot?.takeIf { AndroidPathValidator.isSafe(it) } ?: "/sdcard"
        val df = runCatching { adbClient.shell(serial, "df", shellQuoteRemotePath(target)) }.getOrNull()
            ?: return null
        if (df.exit != 0) return null
        return parseQuestDfAvailableBytes(df.out)
    }

    private fun extractPlannedFiles(
        zipFile: File,
        tempRoot: File,
        plan: ModInstallPlan
    ): Map<String, File> {
        val root = tempRoot.canonicalFile.toPath()
        val files = linkedMapOf<String, File>()
        ZipFile(zipFile).use { zip ->
            for ((index, mapping) in plan.mappings.withIndex()) {
                val entry = findNormalizedEntry(zip, mapping.sourcePath)
                    ?: error("الملف ${mapping.sourcePath} غير موجود داخل الحزمة.")
                require(!entry.isDirectory) { "لا يمكن تثبيت مجلد كملف: ${mapping.sourcePath}" }
                // Entries Windows cannot materialize (for example ASCII
                // quotes) stream into a generated safe temp name; the
                // archive path stays the mapping key and the remote final
                // name stays exact.
                val outputPath = if (requiresSafeTempName(mapping.sourcePath.substringAfterLast('/'))) {
                    root.resolve(safeLocalTempName(index, mapping.sha256)).normalize()
                } else {
                    root.resolve(mapping.sourcePath).normalize()
                }
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
            if (target == ModPackageAnalyzer.GORILLA_TAG_PACKAGE_ID &&
                plan.packageType == ModPackageType.QMOD
            ) {
                val mods = "/sdcard/Android/data/$target/files/mods"
                val libs = "/sdcard/Android/data/$target/files/libs"
                return path == mods || path.startsWith("$mods/") ||
                    path == libs || path.startsWith("$libs/")
            }
            return ModDestinationPolicy.isPackageBound(path, target)
        }
        return path == profile.destination || path.startsWith("${profile.destination}/")
    }

    /**
     * Direct installs are whole-mod directory operations. Even if each file
     * path is new, merging into an existing sibling directory can leave stale
     * files from a different archive. Reserve every first directory below the
     * approved content root, including all roots in a multi-root archive.
     */
    private fun destinationModRoots(plan: ModInstallPlan): List<String> {
        val root = plan.destinationRoot?.trimEnd('/').takeIf { !it.isNullOrBlank() }
            ?: return emptyList()
        return plan.mappings.mapNotNull { mapping ->
            val relative = mapping.destinationPath.removePrefix("$root/")
            if (relative == mapping.destinationPath || relative.isBlank()) null
            else relative.substringBefore('/').takeIf(String::isNotBlank)
        }.distinct().map { "$root/$it" }
    }

    private fun requiresStrictApkEvidence(plan: ModInstallPlan): Boolean =
        plan.patchRequirement?.required == true ||
            plan.loaderRequirement?.requested?.isNotEmpty() == true

    private fun isDedicatedContentPlan(plan: ModInstallPlan): Boolean =
        plan.packageType in setOf(
            ModPackageType.BONELAB_NATIVE_CONTENT,
            ModPackageType.BONELAB_CODE_MOD
        ) ||
            (plan.packageType == ModPackageType.KNOWN_GAME_PROFILE &&
                plan.targetPackageId == ModPackageAnalyzer.BLADE_AND_SORCERY_PACKAGE_ID)

    private fun transferProgressMessage(
        source: File,
        copiedFiles: Int,
        plan: ModInstallPlan,
        currentBytes: Long? = null,
        displayName: String? = null
    ): String {
        val current = currentBytes?.coerceAtLeast(0L)
        val byteText = if (current == null) {
            "${source.length()} ????"
        } else {
            "$current/${source.length()} ????"
        }
        return "??? ${displayName ?: source.name} - ??? ${copiedFiles + 1}/${plan.totalFiles}? $byteText"
    }

    private fun executionProfile(plan: ModInstallPlan): GameModProfile? {
        val target = plan.targetPackageId ?: return null
        // Never replace Gorilla Tag's non-authorizing registry profile with
        // the writable synthetic profile used for ordinary games.
        if (target == ModPackageAnalyzer.GORILLA_TAG_PACKAGE_ID) {
            return GameModProfileRegistry.findByPackageId(target)
        }
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

    private suspend fun refreshApkEvidenceIfRequired(
        serial: String,
        app: InstalledQuestApp,
        onProgress: (String) -> Unit = {}
    ): InstalledQuestApp {
        val profile = GameModProfileRegistry.findByPackageId(app.packageName)
        if (profile?.supportedApkSha256.isNullOrEmpty()) return app
        val ownerJob = currentCoroutineContext()[Job]
        return apkEvidenceRefresher.refresh(
            serial,
            app,
            onProgress,
            cancelled = { ownerJob?.isActive != true }
        )
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

    /**
     * Best-effort removal of the NFVR-owned remote staging dir.  Refuses
     * anything outside the approved root or outside our namespace, so a
     * confused caller can never delete user content.
     */
    private fun cleanupRemoteTempDir(serial: String, tmpDir: String?, root: String?) {
        if (tmpDir.isNullOrBlank() || root.isNullOrBlank()) return
        if (!isNfvrOwnedDir(tmpDir, root, NFVR_OWNED_DIR_PREFIXES)) {
            DiagnosticLogger.info("Refusing to clean non-NFVR temp dir: $tmpDir")
            return
        }
        runCatching { adbClient.shell(serial, "rm", "-rf", shellQuoteRemotePath(tmpDir)) }
        DiagnosticLogger.info("Cleaned remote temp dir: $tmpDir")
    }

    /**
     * Bounded per-file push retry for transient ADB transport flakes.
     * Same bytes to the same already-collision-checked destination; a
     * partially pushed file is overwritten by the retry and the existing
     * byte-size verification still guards the result, so fail-closed
     * semantics are unchanged.  Exactly one retry keeps long installs
     * robust without masking persistent failures.
     */
    private fun pushModFileWithRetry(
        serial: String,
        source: File,
        pushTarget: String,
        maxAttempts: Int,
        onProgress: (copiedBytes: Long, totalBytes: Long) -> Unit
    ): CmdResult {
        var last: CmdResult = CmdResult(1, "", "push did not run")
        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            if (attempt > 0) {
                DiagnosticLogger.info(
                    "Retrying ADB push of ${source.name} (attempt ${attempt + 1})"
                )
                runCatching { adbClient.killServer() }
                runCatching { adbClient.startServer() }
            }
            last = adbClient.pushModFileWithProgress(
                serial, source, pushTarget, onProgress = onProgress
            )
            if (last.exit == 0) return last
            DiagnosticLogger.info(
                "ADB push attempt ${attempt + 1} failed for ${source.name}: " +
                    "exit=${last.exit}, stderr=${last.err.take(300)}"
            )
        }
        return last
    }

    /**
     * Remote per-file verification with one bounded retry.  Sustained
     * installs can outlive the ADB daemon (proven on hardware: daemon
     * restarts mid-verification); a single re-check after a server
     * restart distinguishes transport flakes from genuinely missing or
     * corrupt files.  Read-only: retrying never writes.
     */
    private suspend fun verifyRemoteFile(serial: String, mapping: ModFileMapping): String? {
        repeat(2) { attempt ->
            if (attempt > 0) {
                DiagnosticLogger.info("Retrying remote verification of ${mapping.destinationPath}")
                runCatching { adbClient.killServer() }
                runCatching { adbClient.startServer() }
            }
            val attemptError = verifyRemoteFileOnce(serial, mapping)
            if (attemptError == null) return null
            if (attempt == 0) {
                DiagnosticLogger.info("Remote verification attempt 1 failed: $attemptError")
            } else {
                return attemptError
            }
        }
        return "فشل التحقق: ${mapping.destinationPath}"
    }

    private suspend fun verifyRemoteFileOnce(serial: String, mapping: ModFileMapping): String? {
        val exists = adbClient.shell(serial, "test", "-f", shellQuoteRemotePath(mapping.destinationPath))
        if (exists.exit != 0) return "فشل التحقق: الملف غير موجود في ${mapping.destinationPath}"
        val size = adbClient.shell(serial, "stat", "-c", "%s", shellQuoteRemotePath(mapping.destinationPath))
        if (size.exit != 0) {
            return "فشل قراءة حجم ${mapping.destinationPath}: ${size.err.take(300)}"
        }
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
            expected.versionCode == actual.versionCode &&
            (expected.apkSha256.isNullOrBlank() ||
                actual.apkSha256.isNullOrBlank() ||
                expected.apkSha256.equals(actual.apkSha256, ignoreCase = true))

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
    
    suspend fun createModPath(
        serial: String,
        path: String,
        approvedBase: String? = null
    ): CmdResult {
        val base = approvedBase?.trimEnd('/')?.takeIf { it.isNotBlank() }
        if (!AndroidPathValidator.isSafe(path) ||
            base == null ||
            !AndroidPathValidator.isSafe(base) ||
            (path != base && !path.startsWith("$base/"))
        ) {
            return CmdResult(1, "", "unsafe destination path")
        }
        if (path == base) return CmdResult(0, "", "")

        var current = base
        val relative = path.removePrefix("$base/").trim('/')
        for (segment in relative.split('/').filter(String::isNotBlank)) {
            current = "$current/$segment"
            val existing = adbClient.shell(serial, "test", "-d", shellQuoteRemotePath(current))
            when {
                existing.exit == 0 -> continue
                existing.exit != 1 ->
                    return CmdResult(1, "", "unable to inspect destination parent")
                else -> {
                    /*
                     * Deliberately do not use mkdir -p. If the approved base
                     * disappears between the immediate base check and this
                     * operation, mkdir fails instead of recreating it.
                     */
                    val created = adbClient.shell(serial, "mkdir", shellQuoteRemotePath(current))
                    if (created.exit != 0) {
                        // A negative `test -d` followed by EEXIST is a
                        // stale FUSE negative cache or a concurrent
                        // creator (proven on real hardware: the recheck
                        // can stay stale for its TTL, so retry with
                        // backoff).  Only an actual directory continues.
                        var rechecked = false
                        for (attempt in 0 until 3) {
                            if (attempt > 0) Thread.sleep(500)
                            if (adbClient.shell(serial, "test", "-d", shellQuoteRemotePath(current)).exit == 0) {
                                rechecked = true
                                break
                            }
                        }
                        if (!rechecked) return created
                        DiagnosticLogger.info("mkdir raced an existing dir, continuing: $current")
                    }
                }
            }
        }
        return CmdResult(0, "", "")
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

/** Safety margin kept free beyond the plan bytes on both host and Quest. */
internal const val MOD_INSTALL_DISK_MARGIN_BYTES = 64L * 1024L * 1024L

/**
 * Overflow-safe disk gate shared by the host-temp and Quest checks.
 * Unknown free space (null/negative) can never prove insufficiency, so it
 * proceeds; every other case requires plan bytes plus the safety margin.
 */
internal fun installDiskSpaceSufficient(freeBytes: Long?, requiredBytes: Long): Boolean {
    if (requiredBytes <= 0L) return true
    if (freeBytes == null || freeBytes < 0L) return true
    if (freeBytes < requiredBytes) return false
    return freeBytes - requiredBytes >= MOD_INSTALL_DISK_MARGIN_BYTES
}

/**
 * Parses toybox `df` output (`Filesystem 1K-blocks Used Available Use%
 * Mounted on`) into available bytes.  Returns null when the output is
 * not a recognizable df table rather than guessing.
 */
internal fun parseQuestDfAvailableBytes(dfOutput: String): Long? {
    val lines = dfOutput.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    if (lines.size < 2 || !lines.first().startsWith("Filesystem")) return null
    val parts = lines.last().split(Regex("\\s+"))
    if (parts.size < 5) return null
    val availableKb = parts[3].toLongOrNull() ?: return null
    if (availableKb < 0L) return null
    return availableKb * 1024L
}

private val WINDOWS_ILLEGAL_BASENAME_CHARS = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

/**
 * True when a remote basename cannot be materialized as a host temp file
 * on Windows (Win32 illegal set, controls, trailing space/dot).  Such
 * entries are extracted under a generated safe name and renamed remotely
 * to their exact final name; the archive and manifests are never altered.
 */
internal fun requiresSafeTempName(basename: String): Boolean {
    if (basename.isEmpty()) return true
    if (basename.any { it in WINDOWS_ILLEGAL_BASENAME_CHARS || it.code < 32 }) return true
    return basename.endsWith(' ') || basename.endsWith('.')
}

/** Deterministic, collision-resistant host temp name (never user data). */
internal fun safeLocalTempName(index: Int, sourceSha256: String?): String {
    val tag = sourceSha256?.filter { it.isLetterOrDigit() }?.take(16)?.ifBlank { null } ?: "nohash"
    return "%04d-%s.nfvrpart".format(index, tag)
}

/**
 * NFVR-owned remote staging dir inside the approved mod root, unique per
 * operation.  Null when no safe dir can be derived.
 */
internal fun remoteTempDir(root: String, planId: String): String? {
    val clean = root.trim().trimEnd('/')
    if (clean.isBlank() || !AndroidPathValidator.isSafe(clean)) return null
    val suffix = planId.filter { it.isLetterOrDigit() }.take(12).ifBlank { return null }
    return "$clean/.nfvr-tmp-$suffix"
}

/**
 * NFVR-owned staging namespaces, strictly inside the approved root:
 * transfer temp dirs, update staging dirs, and update backup dirs.
 * Anything else is never created, moved, or deleted by install flows.
 */
internal val NFVR_OWNED_DIR_PREFIXES = setOf(".nfvr-tmp-", ".nfvr-update-", ".nfvr-backup-")

/** True only for NFVR-owned dirs strictly inside the approved root. */
internal fun isNfvrOwnedDir(
    path: String,
    root: String,
    prefixes: Set<String> = setOf(".nfvr-tmp-")
): Boolean {
    val cleanRoot = root.trim().trimEnd('/')
    if (cleanRoot.isBlank()) return false
    val cleanPath = path.trim().trimEnd('/')
    val name = cleanPath.substringAfterLast('/')
    return cleanPath.startsWith("$cleanRoot/") &&
        prefixes.any { prefix -> name.startsWith(prefix) && name.length > prefix.length }
}

/** Backward-compatible transfer-temp check. */
internal fun isNfvrTempDir(path: String, root: String): Boolean =
    isNfvrOwnedDir(path, root)