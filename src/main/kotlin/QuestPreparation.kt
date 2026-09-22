import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipFile

/**
 * Preparation and package installation deliberately have different contracts.
 * Preparation owns evidence about the installed application; a mod strategy
 * can consume that evidence but cannot make an APK ready by itself.
 */
interface QuestGamePreparationStrategy {
    fun assess(
        app: InstalledQuestApp,
        inventory: QuestApkInventory,
        profile: QuestPreparationProfile?
    ): QuestPreparationAssessment

    fun assess(
        app: InstalledQuestApp,
        inventory: QuestApkInventory,
        profile: QuestPreparationProfile?,
        inspections: List<QuestZipInspection>
    ): QuestPreparationAssessment = assess(app, inventory, profile)
}

interface QuestModInstallStrategy {
    fun assess(
        app: InstalledQuestApp,
        preparation: QuestPreparationAssessment,
        packageType: ModPackageType
    ): QuestModInstallAssessment
}

enum class QuestPreparationStage(val title: String) {
    SELECT_GAME("Select installed game"),
    CAPTURE_IDENTITY("Capture package and version"),
    INVENTORY_APKS("Inventory base and split APKs"),
    INSPECT_APK("Inspect ZIP, ABI, Unity and IL2CPP evidence"),
    CHECK_PROFILE("Check exact game/version/resource evidence"),
    CREATE_BACKUP("Create local APK backup"),
    VERIFY_BACKUP("Verify backup hashes and rollback metadata"),
    READY_FOR_MOD_INSTALL("Report mod-install readiness")
}

enum class QuestStageState { NOT_STARTED, COMPLETE, BLOCKED }

data class QuestPreparationStageResult(
    val stage: QuestPreparationStage,
    val state: QuestStageState,
    val detail: String
)

data class QuestPreparationBlocker(
    val code: String,
    val detail: String,
    val scope: String
)

data class QuestApkArtifact(
    val remotePath: String,
    val splitName: String?,
    val sizeBytes: Long,
    val remoteSha256: String? = null,
    val sha256: String? = null,
    val localPath: String? = null
) {
    val isBase: Boolean get() = splitName == null
}

data class QuestApkInventory(
    val packageId: String,
    val artifacts: List<QuestApkArtifact>,
    val serial: String? = null,
    val selectedBasePath: String? = null,
    val capturedAt: Instant = Instant.now(),
    val readOnly: Boolean = true
) {
    val base: QuestApkArtifact? get() = artifacts.singleOrNull { it.isBase }
    val splits: List<QuestApkArtifact> get() = artifacts.filterNot { it.isBase }
    val complete: Boolean get() = readOnly && !serial.isNullOrBlank() && base != null && artifacts.isNotEmpty()
}

data class QuestZipInspection(
    val valid: Boolean,
    val entryNames: List<String> = emptyList(),
    val abiDirectories: Set<String> = emptySet(),
    val elfFiles: List<String> = emptyList(),
    val il2cppEvidence: Boolean = false,
    val unityEvidence: Boolean = false,
    val elfAbiValid: Boolean = true,
    val resourceHashes: Map<String, String> = emptyMap(),
    val sourceRemotePath: String? = null,
    val sourceSha256: String? = null,
    val reason: String? = null
)

data class QuestPreparationProfile(
    val profileId: String,
    val packageId: String,
    val engine: String,
    val allowedVersions: Set<String>,
    val allowedVersionCodes: Set<Long>,
    val requiredResourceHashes: Map<String, String>,
    val allowedAbis: Set<String>,
    val evidenceScope: String,
    val evidenceRank: String
) {
    val validForReadiness: Boolean
        get() = profileId.matches(PROFILE_ID) &&
            packageId.matches(PACKAGE_ID) &&
            engine == "Unity IL2CPP" &&
            allowedVersions.size == 1 &&
            allowedVersions.all { it.matches(EXACT_VERSION) } &&
            allowedVersionCodes.size == 1 &&
            allowedVersionCodes.all { it > 0L } &&
            requiredResourceHashes.isNotEmpty() &&
            requiredResourceHashes.all { (path, hash) ->
                runCatching { QuestPreparationEngine.normalizeZipPath(path) }.isSuccess &&
                    hash.matches(SHA256)
            } &&
            allowedAbis.isNotEmpty() &&
            allowedAbis.all { it in TRUSTED_ABIS } &&
            evidenceScope.isNotBlank() &&
            evidenceRank in TRUSTED_EVIDENCE_RANKS

    fun matches(app: InstalledQuestApp): Boolean =
        validForReadiness &&
            app.packageName == packageId &&
            app.versionName == allowedVersions.singleOrNull() &&
            app.versionCode == allowedVersionCodes.singleOrNull()

    companion object {
        private val PROFILE_ID = Regex("""[A-Za-z0-9][A-Za-z0-9._-]{1,127}""")
        private val PACKAGE_ID = Regex("""[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+""")
        private val EXACT_VERSION = Regex("""\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?""")
        val SHA256 = Regex("""[0-9a-fA-F]{64}""")
        val TRUSTED_ABIS = setOf("arm64-v8a", "armeabi-v7a")
        val TRUSTED_EVIDENCE_RANKS = setOf("AUTHORITATIVE_UPSTREAM", "MAINTAINED_OPEN_SOURCE")
    }
}

data class QuestPreparationAssessment(
    val readyForModInstall: Boolean,
    val blockers: List<QuestPreparationBlocker> = emptyList(),
    val evidenceScope: String = "none",
    val profileId: String? = null
)

data class QuestModInstallAssessment(
    val installable: Boolean,
    val reason: String,
    val preparationRequired: Boolean,
    val destructiveActionAvailable: Boolean = false
)

data class QuestBackupFile(
    val remotePath: String,
    val localPath: String,
    val sizeBytes: Long,
    val sha256: String,
    val remoteSha256: String
)

data class QuestRollbackMetadata(
    val packageId: String,
    val serialFingerprint: String,
    val appVersion: String?,
    val appVersionCode: Long?,
    val originalArtifacts: List<QuestBackupFile>,
    val createdAt: Instant,
    val baseApkPath: String? = null,
    val rollbackAvailable: Boolean = false,
    val note: String = "Metadata only; NFVR does not uninstall, reinstall, or claim rollback capability."
)

data class QuestBackupStatus(
    val available: Boolean,
    val integrityVerified: Boolean,
    val directory: File? = null,
    val metadata: QuestRollbackMetadata? = null,
    val reason: String? = null
)

data class QuestPreparationReport(
    val app: InstalledQuestApp,
    val inventory: QuestApkInventory? = null,
    val zipInspections: List<QuestZipInspection> = emptyList(),
    val assessment: QuestPreparationAssessment = QuestPreparationAssessment(false),
    val backup: QuestBackupStatus = QuestBackupStatus(false, false),
    val stages: List<QuestPreparationStageResult> = emptyList(),
    val blockers: List<QuestPreparationBlocker> = emptyList(),
    val modStrategyReady: Boolean = false
) {
    val readyForModInstall: Boolean
        get() = assessment.readyForModInstall && backup.integrityVerified && modStrategyReady
}

data class QuestPreparationUiState(
    val report: QuestPreparationReport? = null,
    val busy: Boolean = false,
    val status: String? = null
)

fun questPreparationStageRows(report: QuestPreparationReport?): List<QuestPreparationStageResult> =
    report?.stages ?: QuestPreparationStage.entries.map {
        QuestPreparationStageResult(it, QuestStageState.NOT_STARTED, "Not started")
    }

/**
 * No current Quest game/version/resource profile is promoted by the research
 * report. The empty default is intentional: unknown versions fail closed.
 */
object QuestPreparationProfileRegistry {
    val profiles: List<QuestPreparationProfile> by lazy { load() }

    fun find(app: InstalledQuestApp): QuestPreparationProfile? =
        profiles.firstOrNull { it.matches(app) }

    private fun load(): List<QuestPreparationProfile> {
        val stream = javaClass.classLoader?.getResourceAsStream("quest-preparation-profiles.json")
            ?: return emptyList()
        return runCatching {
            val root = JSONArray(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            (0 until root.length()).mapNotNull { index ->
                val value = root.optJSONObject(index) ?: return@mapNotNull null
                val id = value.optString("profileId").trim()
                val packageId = value.optString("packageId").trim()
                val versions = value.optJSONArray("allowedVersions").strings()
                val codes = value.optJSONArray("allowedVersionCodes").longs()
                val hashes = value.optJSONObject("requiredResourceHashes").stringMap()
                val abis = value.optJSONArray("allowedAbis").strings()
                if (id.isBlank() || packageId.isBlank() ||
                    versions.isEmpty() && codes.isEmpty() || hashes.isEmpty() || abis.isEmpty()
                ) null else QuestPreparationProfile(
                    id, packageId, value.optString("engine", "unknown"), versions, codes,
                    hashes, abis, value.optString("evidenceScope", "unspecified"),
                    value.optString("evidenceRank", "unverified")
                )
            }.filter { it.validForReadiness }
        }.getOrElse { emptyList() }
    }

    private fun JSONArray?.strings(): Set<String> =
        if (this == null) emptySet() else (0 until length()).mapNotNull {
            optString(it).trim().takeIf(String::isNotBlank)
        }.toSet()

    private fun JSONArray?.longs(): Set<Long> =
        if (this == null) emptySet() else (0 until length()).mapNotNull {
            optLong(it, Long.MIN_VALUE).takeIf { value -> value != Long.MIN_VALUE }
        }.toSet()

    private fun JSONObject?.stringMap(): Map<String, String> =
        if (this == null) emptyMap() else keys().asSequence().mapNotNull { key ->
            optString(key).trim().takeIf(String::isNotBlank)?.let { key to it }
        }.toMap()
}

class DataDrivenQuestGamePreparationStrategy : QuestGamePreparationStrategy {
    override fun assess(
        app: InstalledQuestApp,
        inventory: QuestApkInventory,
        profile: QuestPreparationProfile?
    ): QuestPreparationAssessment {
        val blockers = mutableListOf<QuestPreparationBlocker>()
        if (!inventory.complete) {
            blockers += QuestPreparationBlocker(
                "APK_INVENTORY_INCOMPLETE",
                "Base APK and every installed split must be captured read-only.",
                "installed-apk-inventory"
            )
        }
        if (profile == null || !profile.validForReadiness || !profile.matches(app)) {
            blockers += QuestPreparationBlocker(
                "UNSUPPORTED_GAME_VERSION",
                "No exact package/version/resource profile is verified for ${app.packageName} " +
                    "version=${app.versionName ?: "unknown"} code=${app.versionCode ?: "unknown"}.",
                "profile-registry"
            )
        } else {
            val unknownAbi = inventory.artifacts.any { artifact ->
                artifact.localPath == null
            }
            if (unknownAbi) {
                blockers += QuestPreparationBlocker(
                    "APK_INSPECTION_REQUIRED",
                    "Every base and split APK must be inspected before readiness can be assessed.",
                    "apk-inspection"
                )
            }
        }
        return QuestPreparationAssessment(
            readyForModInstall = blockers.isEmpty(),
            blockers = blockers,
            evidenceScope = profile?.evidenceScope ?: "no verified profile",
            profileId = profile?.profileId
        )
    }

    override fun assess(
        app: InstalledQuestApp,
        inventory: QuestApkInventory,
        profile: QuestPreparationProfile?,
        inspections: List<QuestZipInspection>
    ): QuestPreparationAssessment {
        val base = assess(app, inventory, profile)
        if (profile == null || !profile.validForReadiness || !profile.matches(app)) return base
        val blockers = base.blockers.toMutableList()
        if (inspections.size != inventory.artifacts.size || inspections.any { !it.valid }) {
            blockers += QuestPreparationBlocker(
                "APK_INSPECTION_FAILED",
                "Every base and split APK must pass bounded ZIP and native-library inspection.",
                "apk-inspection"
            )
        }
        val artifactsByPath = inventory.artifacts.associateBy { it.remotePath }
        val inspectionPaths = inspections.mapNotNull { it.sourceRemotePath }
        val evidenceBound = inspections.size == inventory.artifacts.size &&
            inspectionPaths.size == inventory.artifacts.size &&
            inspectionPaths.toSet() == artifactsByPath.keys &&
            inspections.all { inspection ->
                val artifact = inspection.sourceRemotePath?.let(artifactsByPath::get)
                artifact != null &&
                    inspection.sourceSha256?.equals(artifact.remoteSha256, ignoreCase = true) == true &&
                    artifact.sha256?.equals(artifact.remoteSha256, ignoreCase = true) == true
            }
        if (!evidenceBound) {
            blockers += QuestPreparationBlocker(
                "APK_EVIDENCE_UNBOUND",
                "ZIP and native evidence must be bound to the exact inspected APK path and SHA-256.",
                "apk-inspection"
            )
        }
        val unsupportedAbi = inspections.flatMap { it.abiDirectories }
            .filterNot { it in profile.allowedAbis }
            .distinct()
        if (unsupportedAbi.isNotEmpty()) {
            blockers += QuestPreparationBlocker(
                "ABI_NOT_PROFILED",
                "Installed ABI(s) ${unsupportedAbi.joinToString()} are outside profile ${profile.profileId}.",
                "abi"
            )
        }
        if (inspections.none { it.unityEvidence && it.il2cppEvidence && it.abiDirectories.isNotEmpty() }) {
            blockers += QuestPreparationBlocker(
                "ENGINE_EVIDENCE_MISSING",
                "Unity IL2CPP and native ABI evidence is required for the profiled engine.",
                "engine"
            )
        }
        val observedResources = inspections.flatMap { it.resourceHashes.entries }
            .associate { it.key to it.value }
        val resourceMismatch = profile.requiredResourceHashes.any { (path, expected) ->
            observedResources[path]?.equals(expected, ignoreCase = true) != true
        }
        if (resourceMismatch) {
            blockers += QuestPreparationBlocker(
                "RESOURCE_HASH_MISMATCH",
                "One or more exact profile resource SHA-256 values do not match the inspected APK set.",
                "resource-evidence"
            )
        }
        return base.copy(
            readyForModInstall = blockers.isEmpty(),
            blockers = blockers
        )
    }
}

/**
 * Package strategies never patch an APK. Existing direct-ready QMOD/content
 * flows remain owned by ModPackageAnalyzer/ModsManager; this adapter only
 * expresses the preparation gate for a future strategy.
 */
class DataDrivenQuestModInstallStrategy : QuestModInstallStrategy {
    override fun assess(
        app: InstalledQuestApp,
        preparation: QuestPreparationAssessment,
        packageType: ModPackageType
    ): QuestModInstallAssessment {
        if (packageType == ModPackageType.GORILLA_TAG_VIRTUAL_STUMP ||
            packageType == ModPackageType.PAVLOV_UGC_CONTENT
        ) {
            return QuestModInstallAssessment(
                false,
                "Built-in game content is informational and does not require APK preparation.",
                false
            )
        }
        if (!preparation.readyForModInstall) {
            return QuestModInstallAssessment(
                false,
                preparation.blockers.joinToString("; ") { "${it.code}: ${it.detail}" },
                true
            )
        }
        return QuestModInstallAssessment(
            false,
            "No verified payload, signing, reinstall, and rollback strategy is enabled.",
            true
        )
    }
}

class QuestPreparationEngine(
    private val adb: AdbClient,
    private val profileRegistry: QuestPreparationProfileRegistry = QuestPreparationProfileRegistry,
    private val strategy: QuestGamePreparationStrategy = DataDrivenQuestGamePreparationStrategy()
) {
    fun inspectInstalledApks(serial: String, app: InstalledQuestApp): QuestApkInventory {
        require(serial.isNotBlank()) { "device serial is required" }
        require(isAuthorizedSerial(adb.devices(), serial)) {
            "device serial is not currently authorized"
        }
        val packageDetails = adb.shell(serial, "dumpsys", "package", app.packageName)
        require(packageDetails.exit == 0) {
            "installed package identity is unavailable: ${packageDetails.err.ifBlank { packageDetails.out }}"
        }
        val observedVersion = Regex("""(?m)^\s*versionName=([^\s]+)""")
            .find(packageDetails.out)?.groupValues?.getOrNull(1)
        val observedCode = Regex("""versionCode=(\d+)""")
            .find(packageDetails.out)?.groupValues?.getOrNull(1)?.toLongOrNull()
        require(observedVersion != null || observedCode != null) {
            "installed package version evidence is unavailable"
        }
        require(
            (app.versionName == null || app.versionName == observedVersion) &&
                (app.versionCode == null || app.versionCode == observedCode)
        ) { "installed package version changed; re-scan the selected app" }
        val result = adb.shell(serial, "pm", "path", app.packageName)
        require(result.exit == 0) { "pm path failed: ${result.err.ifBlank { result.out }}" }
        val paths = parseInstalledApkPaths(result.out, app.packageName)
        val basePath = paths.single { it.endsWith("/base.apk") }
        require(app.apkPath.isNullOrBlank() || app.apkPath == basePath) {
            "installed base APK path changed; re-scan the selected app"
        }
        val artifacts = paths.map { path ->
            val stat = adb.shell(serial, "stat", "-c", "%s", path)
            val size = stat.out.trim().lineSequence().lastOrNull()?.toLongOrNull()
                ?: error("APK size unavailable for $path")
            require(size in 1..MAX_APK_BYTES) { "APK size outside safe limit for $path" }
            val temp = Files.createTempFile("nfvr-apk-evidence-", ".apk").toFile()
            try {
                val pulled = adb.pullReadOnly(serial, path, temp)
                require(pulled.exit == 0 && temp.isFile && temp.length() == size) {
                    "read-only APK hash capture failed for $path"
                }
                QuestApkArtifact(
                    remotePath = path,
                    splitName = splitName(path),
                    sizeBytes = size,
                    remoteSha256 = sha256(temp)
                )
            } finally {
                temp.delete()
            }
        }
        require(isAuthorizedSerial(adb.devices(), serial)) {
            "device serial changed while inspecting APKs"
        }
        return QuestApkInventory(app.packageName, artifacts, serial, basePath)
    }

    fun inspectLocalApk(apk: File): QuestZipInspection = inspectZip(apk)

    fun inspectLocalApk(
        apk: File,
        resourcePaths: Set<String>
    ): QuestZipInspection = inspectZip(apk, resourcePaths)

    fun inspectLocalApk(
        apk: File,
        artifact: QuestApkArtifact,
        resourcePaths: Set<String>
    ): QuestZipInspection {
        require(artifact.remoteSha256?.matches(QuestPreparationProfile.SHA256) == true) {
            "remote APK SHA-256 evidence is required for ZIP inspection"
        }
        require(artifact.sha256?.matches(QuestPreparationProfile.SHA256) == true) {
            "local APK SHA-256 evidence is required for ZIP inspection"
        }
        require(artifact.sha256.equals(artifact.remoteSha256, ignoreCase = true)) {
            "local APK hash does not match the captured remote APK hash"
        }
        val inspection = inspectZip(apk, resourcePaths)
        require(inspection.sourceSha256.equals(artifact.sha256, ignoreCase = true) &&
            inspection.sourceSha256.equals(artifact.remoteSha256, ignoreCase = true)
        ) {
            "inspected APK hash does not match the bound artifact evidence"
        }
        return inspection.copy(sourceRemotePath = artifact.remotePath)
    }

    fun assess(app: InstalledQuestApp, inventory: QuestApkInventory): QuestPreparationAssessment =
        strategy.assess(app, inventory, profileRegistry.find(app))

    fun assess(
        app: InstalledQuestApp,
        inventory: QuestApkInventory,
        inspections: List<QuestZipInspection>
    ): QuestPreparationAssessment =
        strategy.assess(app, inventory, profileRegistry.find(app), inspections)

    /**
     * Pulls only the paths returned by `pm path`, hashes each result, writes an
     * integrity manifest, and verifies the APK-only copy. Read-only file flags
     * are best-effort hints, not an immutability/security guarantee.
     */
    fun createApkBackup(
        serial: String,
        app: InstalledQuestApp,
        inventory: QuestApkInventory
    ): QuestBackupStatus {
        require(serial.isNotBlank()) { "device serial is required" }
        require(inventory.complete && inventory.packageId == app.packageName &&
            inventory.serial == serial &&
            (app.apkPath.isNullOrBlank() || inventory.selectedBasePath == app.apkPath)
        ) {
            "complete package-scoped APK inventory is required"
        }
        require(inventory.artifacts.map { it.remotePath }.distinct().size == inventory.artifacts.size &&
            inventory.artifacts.all {
                isSafeInstalledArtifactPath(it.remotePath, app.packageName) &&
                    it.sizeBytes > 0L &&
                    it.remoteSha256?.matches(QuestPreparationProfile.SHA256) == true
            }
        ) { "inventory contains an unsafe path, duplicate artifact, or invalid remote hash" }
        require(inventory.artifacts.all {
            it.remoteSha256?.matches(QuestPreparationProfile.SHA256) == true
        }) { "remote APK SHA-256 evidence is required before backup" }
        require(isAuthorizedSerial(adb.devices(), serial)) {
            "device serial is not currently authorized"
        }
        val before = inspectInstalledApks(serial, app)
        require(sameInventory(inventory, before)) {
            "installed APK path-set, sizes, or remote hashes changed before backup"
        }
        val root = File(UserDataPaths.root, "quest-backups")
        root.mkdirs()
        val temp = Files.createTempDirectory(root.toPath(), ".pending-").toFile()
        try {
            val files = before.artifacts.map { artifact ->
                require(isSafeInstalledArtifactPath(artifact.remotePath, app.packageName))
                val index = before.artifacts.indexOfFirst { it.remotePath == artifact.remotePath }
                val name = if (artifact.isBase) "base.apk" else
                    "split-${index.toString().padStart(3, '0')}-${artifact.splitName}.apk"
                val local = File(temp, name)
                val pulled = adb.pullReadOnly(serial, artifact.remotePath, local)
                require(pulled.exit == 0 && local.isFile) {
                    "read-only APK pull failed for ${artifact.remotePath}"
                }
                require(local.length() == artifact.sizeBytes) {
                    "pulled APK size changed for ${artifact.remotePath}"
                }
                val hash = sha256(local)
                require(hash.matches(QuestPreparationProfile.SHA256))
                require(hash.equals(artifact.remoteSha256, ignoreCase = true)) {
                    "backup pull hash does not match captured remote hash for ${artifact.remotePath}"
                }
                QuestBackupFile(
                    artifact.remotePath,
                    local.name,
                    local.length(),
                    hash,
                    artifact.remoteSha256!!
                )
            }
            require(isAuthorizedSerial(adb.devices(), serial)) {
                "device serial changed during backup"
            }
            val after = inspectInstalledApks(serial, app)
            require(sameInventory(before, after)) {
                "installed APK path-set, sizes, or remote hashes changed after backup"
            }
            files.forEach { makeReadOnly(File(temp, it.localPath)) }
            val createdAt = Instant.now()
            val metadata = QuestRollbackMetadata(
                app.packageName,
                fingerprint(serial),
                app.versionName,
                app.versionCode,
                files,
                createdAt,
                baseApkPath = app.apkPath
            )
            val manifest = File(temp, "rollback-metadata.json")
            manifest.writeText(metadata.toJson().toString(2), Charsets.UTF_8)
            makeReadOnly(manifest)
            val prefix = "${fingerprint(serial)}-${safeName(app.packageName)}-${createdAt.toEpochMilli()}"
            var finalDir = File(root, prefix)
            var suffix = 1
            while (finalDir.exists()) {
                finalDir = File(root, "$prefix-$suffix")
                suffix++
            }
            Files.move(temp.toPath(), finalDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return verifyBackup(finalDir, serial, app, after)
        } catch (error: Throwable) {
            temp.deleteRecursively()
            throw error
        }
    }

    fun latestBackup(
        serial: String,
        app: InstalledQuestApp,
        inventory: QuestApkInventory? = null
    ): QuestBackupStatus {
        if (!isAuthorizedSerial(adb.devices(), serial)) {
            return QuestBackupStatus(false, false, reason = "Device serial is not currently authorized.")
        }
        val root = File(UserDataPaths.root, "quest-backups")
        val rootCanonical = runCatching { root.canonicalFile.toPath() }.getOrNull()
        val candidates = root.listFiles()?.filter {
            val candidatePath = runCatching { it.canonicalFile.toPath() }.getOrNull()
            it.isDirectory &&
                candidatePath != null &&
                rootCanonical != null &&
                candidatePath.startsWith(rootCanonical) &&
                it.name.startsWith("${fingerprint(serial)}-${safeName(app.packageName)}-")
        }.orEmpty().sortedByDescending { it.name }
        val newest = candidates.firstOrNull()
        return newest?.let { verifyBackup(it, serial, app, inventory) }
            ?: QuestBackupStatus(
            false,
            false,
            reason = "No verified local backup exists for this exact serial, package, and version."
        )
    }

    private fun verifyBackup(
        directory: File,
        serial: String? = null,
        app: InstalledQuestApp? = null,
        inventory: QuestApkInventory? = null
    ): QuestBackupStatus {
        val root = File(UserDataPaths.root, "quest-backups").canonicalFile.toPath()
        val candidate = runCatching { directory.canonicalFile.toPath() }.getOrNull()
        if (candidate == null || !candidate.startsWith(root) || !directory.isDirectory) {
            return QuestBackupStatus(false, false, directory, reason = "Backup directory is outside the local backup root.")
        }
        val manifest = File(directory, "rollback-metadata.json")
        val metadata = runCatching {
            val json = JSONObject(manifest.readText(Charsets.UTF_8))
            require(json.has("rollbackAvailable") && !json.getBoolean("rollbackAvailable"))
            require(json.has("note") && json.getString("note") == ROLLBACK_NOTE)
            val files = json.getJSONArray("originalArtifacts")
            require(files.length() > 0)
            QuestRollbackMetadata(
                json.getString("packageId"),
                json.getString("serialFingerprint"),
                json.optString("appVersion").ifBlank { null },
                if (json.has("appVersionCode") && !json.isNull("appVersionCode")) {
                    json.getLong("appVersionCode")
                } else null,
                (0 until files.length()).map { index ->
                    val item = files.getJSONObject(index)
                    require(item.has("remotePath") && item.has("localPath") &&
                        item.has("sizeBytes") && item.has("sha256") && item.has("remoteSha256")
                    )
                    QuestBackupFile(
                        item.getString("remotePath"),
                        item.getString("localPath"),
                        item.getLong("sizeBytes"),
                        item.getString("sha256"),
                        item.getString("remoteSha256")
                    )
                },
                Instant.parse(json.getString("createdAt")),
                baseApkPath = json.optString("baseApkPath").ifBlank { null },
                rollbackAvailable = false,
                note = json.getString("note")
            )
        }.getOrNull() ?: return QuestBackupStatus(false, false, directory, reason = "Backup metadata is invalid.")
        val manifestFiles = metadata.originalArtifacts
        val localNames = manifestFiles.map { it.localPath }
        val remoteNames = manifestFiles.map { it.remotePath }
        val expectedDirectoryPrefix = if (serial != null && app != null) {
            "${fingerprint(serial)}-${safeName(app.packageName)}-"
        } else null
        val pathsValid = metadata.packageId.matches(PACKAGE) &&
            metadata.serialFingerprint == serial?.let(::fingerprint) &&
            (expectedDirectoryPrefix == null || directory.name.startsWith(expectedDirectoryPrefix)) &&
            localNames.all { isConfinedManifestPath(directory, it) } &&
            localNames.distinct().size == localNames.size &&
            remoteNames.distinct().size == remoteNames.size &&
            directory.listFiles()?.filter { it.name != "rollback-metadata.json" }
                ?.map { it.name }?.toSet() == localNames.toSet() &&
            manifestFiles.all {
                it.sizeBytes > 0L &&
                    it.sha256.matches(QuestPreparationProfile.SHA256) &&
                    it.remoteSha256.matches(QuestPreparationProfile.SHA256) &&
                    isSafeInstalledArtifactPath(it.remotePath, metadata.packageId)
            }
        val intact = pathsValid && manifestFiles.all { item ->
            val file = File(directory, item.localPath).canonicalFile
            file.isFile && file.length() == item.sizeBytes &&
                sha256(file).equals(item.sha256, ignoreCase = true) &&
                sha256(file).equals(item.remoteSha256, ignoreCase = true)
        }
        val identityMatches = app == null || (
            metadata.packageId == app.packageName &&
                metadata.appVersion == app.versionName &&
                metadata.appVersionCode == app.versionCode &&
                (app.apkPath.isNullOrBlank() || metadata.baseApkPath == app.apkPath)
            )
        val inventoryMatches = inventory == null || (
            inventory.serial == serial &&
                sameInventoryMetadata(inventory, manifestFiles)
            )
        val accepted = intact && identityMatches && inventoryMatches
        return QuestBackupStatus(
            available = accepted,
            integrityVerified = accepted,
            directory = directory,
            metadata = metadata,
            reason = if (accepted) null else "Backup metadata, local hashes, or current APK identity/path-set does not match."
        )
    }

    companion object {
        const val MAX_APK_BYTES: Long = 512L * 1024L * 1024L
        const val MAX_ZIP_ENTRIES: Int = 25_000
        const val MAX_ZIP_ENTRY_BYTES: Long = 512L * 1024L * 1024L
        private val PACKAGE = Regex("""[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+""")
        private val SAFE_REMOTE = Regex("""/data/app/[A-Za-z0-9_./~+=@-]+\.apk""")
        private const val ROLLBACK_NOTE =
            "Metadata only; NFVR does not uninstall, reinstall, or claim rollback capability."

        fun isAuthorizedSerial(result: CmdResult, serial: String): Boolean {
            if (result.exit != 0 || serial.isBlank()) return false
            return result.out.lineSequence().any { line ->
                val fields = line.trim().split(Regex("\\s+"))
                fields.size >= 2 && fields[0] == serial && fields[1] == "device"
            }
        }

        fun parseInstalledApkPaths(output: String, packageId: String): List<String> {
            require(PACKAGE.matches(packageId)) { "invalid package id" }
            val paths = output.lineSequence().map(String::trim).filter(String::isNotEmpty).map {
                require(it.startsWith("package:")) { "unexpected pm path output" }
                it.removePrefix("package:")
            }.toList()
            require(paths.isNotEmpty()) { "pm path returned no APKs" }
            require(paths.all { SAFE_REMOTE.matches(it) && !it.contains("..") }) {
                "unsafe installed APK path"
            }
            require(paths.count { it.endsWith("/base.apk") } == 1) {
                "installed package must have exactly one base APK"
            }
            require(paths.distinct().size == paths.size) { "duplicate installed APK path" }
            val baseDir = paths.first { it.endsWith("/base.apk") }.substringBeforeLast('/')
            val installDirectory = baseDir.substringAfterLast('/')
            require(
                installDirectory == packageId || installDirectory.startsWith("$packageId-")
            ) { "APK path is not owned by selected package" }
            require(paths.all { it.substringBeforeLast('/') == baseDir }) {
                "base and split APKs are not from one install"
            }
            return paths
        }

        private fun isSafeInstalledArtifactPath(path: String, packageId: String): Boolean =
            runCatching {
                parseInstalledApkPaths(
                    "package:$path",
                    packageId
                )
            }.isSuccess || runCatching {
                require(SAFE_REMOTE.matches(path) && !path.contains(".."))
                val dir = path.substringBeforeLast('/').substringAfterLast('/')
                require(dir == packageId || dir.startsWith("$packageId-"))
                require(path.endsWith("/base.apk") || path.substringAfterLast('/').startsWith("split_"))
            }.isSuccess

        private fun isConfinedManifestPath(directory: File, raw: String): Boolean {
            if (raw.isBlank() || raw.contains('\u0000') ||
                raw.startsWith("/") || raw.startsWith("\\") ||
                Regex("""^[A-Za-z]:([/\\]|$)""").containsMatchIn(raw)
            ) return false
            val normalized = raw.replace('\\', '/')
            if (normalized.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
            val root = runCatching { directory.canonicalFile.toPath() }.getOrNull() ?: return false
            val candidate = runCatching { directory.resolve(normalized).canonicalFile.toPath() }.getOrNull()
                ?: return false
            return candidate.startsWith(root) && candidate != root
        }

        private fun sameInventory(
            expected: QuestApkInventory,
            actual: QuestApkInventory
        ): Boolean =
            expected.packageId == actual.packageId &&
                expected.serial == actual.serial &&
                expected.selectedBasePath == actual.selectedBasePath &&
                sameInventoryMetadata(expected, actual.artifacts.map {
                    QuestBackupFile(
                        it.remotePath,
                        "",
                        it.sizeBytes,
                        it.remoteSha256.orEmpty(),
                        it.remoteSha256.orEmpty()
                    )
                })

        private fun sameInventoryMetadata(
            inventory: QuestApkInventory,
            metadata: List<QuestBackupFile>
        ): Boolean {
            val expected = inventory.artifacts.associateBy { it.remotePath }
            val actual = metadata.associateBy { it.remotePath }
            return expected.size == inventory.artifacts.size &&
                actual.size == metadata.size &&
                expected.keys == actual.keys &&
                expected.all { (path, artifact) ->
                    val saved = actual[path] ?: return false
                    artifact.sizeBytes == saved.sizeBytes &&
                        artifact.remoteSha256.equals(saved.remoteSha256, ignoreCase = true)
                }
        }

        fun inspectZip(
            apk: File,
            requiredResourcePaths: Set<String> = emptySet()
        ): QuestZipInspection {
            if (!apk.isFile || apk.length() > MAX_APK_BYTES) {
                return QuestZipInspection(false, reason = "APK is missing or exceeds the safe inspection limit.")
            }
            val sourceBefore = runCatching { sha256(apk) }.getOrNull()
                ?: return QuestZipInspection(false, reason = "APK hash could not be captured.")
            if (containsZip64(apk)) {
                return QuestZipInspection(
                    false,
                    sourceSha256 = sourceBefore,
                    reason = "ZIP64 APKs are not supported."
                )
            }
            val inspected = runCatching {
                val names = linkedSetOf<String>()
                val abis = linkedSetOf<String>()
                val elf = mutableListOf<String>()
                var il2cpp = false
                var unity = false
                var elfAbiValid = true
                val resourceHashes = linkedMapOf<String, String>()
                ZipFile(apk).use { zip ->
                    val entries = zip.entries()
                    var count = 0
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (++count > MAX_ZIP_ENTRIES) error("APK has too many ZIP entries.")
                        val normalized = normalizeZipPath(entry.name)
                        require(names.add(normalized)) { "duplicate APK ZIP entry: $normalized" }
                        require(entry.size <= MAX_ZIP_ENTRY_BYTES) { "APK entry is too large." }
                        if (normalized in requiredResourcePaths && !entry.isDirectory) {
                            resourceHashes[normalized] = zip.getInputStream(entry).use(::sha256)
                        }
                        if (normalized.startsWith("lib/") && normalized.endsWith(".so")) {
                            val parts = normalized.split('/')
                            if (parts.size >= 3) {
                                val abi = parts[1]
                                abis += abi
                                elf += normalized
                                val bytes = zip.getInputStream(entry).use { input ->
                                    ByteArray(20).also { buffer ->
                                        var offset = 0
                                        while (offset < buffer.size) {
                                            val read = input.read(buffer, offset, buffer.size - offset)
                                            if (read < 0) break
                                            offset += read
                                        }
                                        require(offset == buffer.size) { "ELF header is truncated." }
                                    }
                                }
                                if (bytes.size >= 20 &&
                                    bytes[0] == 0x7f.toByte() &&
                                    bytes[1] == 'E'.code.toByte() &&
                                    bytes[2] == 'L'.code.toByte() &&
                                    bytes[3] == 'F'.code.toByte()
                                ) {
                                    val elfClass = bytes[4].toInt() and 0xff
                                    val elfData = bytes[5].toInt() and 0xff
                                    val elfVersion = bytes[6].toInt() and 0xff
                                    val machine = when (elfData) {
                                        1 -> (bytes[18].toInt() and 0xff) or
                                            ((bytes[19].toInt() and 0xff) shl 8)
                                        2 -> ((bytes[18].toInt() and 0xff) shl 8) or
                                            (bytes[19].toInt() and 0xff)
                                        else -> null
                                    }
                                    val expected = when (abi) {
                                        "arm64-v8a" -> 2 to 183
                                        "armeabi-v7a" -> 1 to 40
                                        else -> null
                                    }
                                    if (elfClass !in 1..2 || elfData !in 1..2 || elfVersion != 1 ||
                                        machine == null ||
                                        (expected != null &&
                                            (elfClass != expected.first || machine != expected.second))
                                    ) {
                                        elfAbiValid = false
                                    }
                                    val name = normalized.substringAfterLast('/')
                                    il2cpp = il2cpp || name == "libil2cpp.so"
                                    unity = unity || name == "libunity.so"
                                } else {
                                    elfAbiValid = false
                                }
                            }
                        }
                    }
                }
                QuestZipInspection(
                    valid = elfAbiValid,
                    entryNames = names.toList(),
                    abiDirectories = abis,
                    elfFiles = elf,
                    il2cppEvidence = il2cpp,
                    unityEvidence = unity,
                    elfAbiValid = elfAbiValid,
                    resourceHashes = resourceHashes,
                    sourceSha256 = sourceBefore,
                    reason = if (elfAbiValid) null else "Native library ELF ABI does not match its lib/<ABI> directory."
                )
            }
            val sourceAfter = runCatching { sha256(apk) }.getOrNull()
            if (sourceAfter == null || sourceAfter != sourceBefore) {
                return QuestZipInspection(
                    false,
                    sourceSha256 = sourceBefore,
                    reason = "APK changed during bounded ZIP inspection."
                )
            }
            return inspected.getOrElse { error ->
                QuestZipInspection(
                    false,
                    sourceSha256 = sourceBefore,
                    reason = error.message ?: "APK ZIP inspection failed."
                )
            }
        }

        fun normalizeZipPath(raw: String): String {
            require(raw.isNotBlank() && !raw.contains('\u0000')) { "invalid ZIP path" }
            val value = raw.replace('\\', '/')
            require(!value.startsWith("/") && !value.startsWith("//") &&
                !Regex("""^[A-Za-z]:/""").containsMatchIn(value)
            ) { "absolute ZIP path" }
            val parts = value.split('/')
            require(parts.none { it.isEmpty() || it == "." || it == ".." }) { "ZIP traversal path" }
            return parts.joinToString("/")
        }

        private fun containsZip64(file: File): Boolean {
            file.inputStream().use { input ->
                var previous = -1
                var previous2 = -1
                var previous3 = -1
                while (true) {
                    val current = input.read()
                    if (current < 0) return false
                    if (previous3 == 0x50 && previous2 == 0x4b &&
                        previous == 0x06 && (current == 0x06 || current == 0x07)
                    ) return true
                    previous3 = previous2
                    previous2 = previous
                    previous = current
                }
            }
        }

        private fun splitName(path: String): String? =
            path.substringAfterLast('/').removeSuffix(".apk").takeIf { it.startsWith("split_") }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun sha256(input: java.io.InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_ZIP_ENTRY_BYTES) { "resource exceeds safe inspection limit" }
                digest.update(buffer, 0, count)
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun fingerprint(serial: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(serial.trim().toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }.take(24)

        private fun safeName(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_")

        private fun makeReadOnly(file: File) {
            file.setReadOnly()
            runCatching {
                Files.setPosixFilePermissions(
                    file.toPath(),
                    setOf(
                        java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                        java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                        java.nio.file.attribute.PosixFilePermission.OTHERS_READ
                    )
                )
            }
        }

        private fun QuestRollbackMetadata.toJson(): JSONObject =
            JSONObject().put("packageId", packageId)
                .put("serialFingerprint", serialFingerprint)
                .put("appVersion", appVersion)
                .put("appVersionCode", appVersionCode)
                .put("baseApkPath", baseApkPath)
                .put("createdAt", createdAt.toString())
                .put("rollbackAvailable", false)
                .put("note", note)
                .put("originalArtifacts", JSONArray(originalArtifacts.map {
                    JSONObject().put("remotePath", it.remotePath)
                        .put("localPath", it.localPath)
                        .put("sizeBytes", it.sizeBytes)
                        .put("sha256", it.sha256)
                        .put("remoteSha256", it.remoteSha256)
                }))
    }
}

private fun JSONObject.optLong(key: String, fallback: Long): Long =
    runCatching { getLong(key) }.getOrDefault(fallback)

fun QuestPreparationAssessment.toBlockerMessages(): List<String> =
    blockers.map { "${it.code}: ${it.detail} [scope=${it.scope}]" }
