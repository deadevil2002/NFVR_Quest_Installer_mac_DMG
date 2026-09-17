import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/** The deliberately small, read-only boundary used by the preparation probe. */
interface RestrictedQuestTransport {
    fun devices(): String
    fun getprop(serial: String, name: String): String
    fun packageList(serial: String): String
    fun packageInfo(serial: String, packageId: String): String
    fun packagePaths(serial: String, packageId: String): List<String>
    /**
     * Carries the original pm-path diagnostics across the transport boundary.
     * Legacy transports only implement packagePaths; their default result is
     * still normalized defensively, while production adapters preserve raw
     * line/accepted/unique counts from stdout.
     */
    fun packagePathResult(serial: String, packageId: String): QuestProbeApkPathResult =
        orderedPackagePathResult(serial, packageId)
    /**
     * Read-only fallback hook for adapters which expose `cmd package path`.
     * The default is deliberately absent so legacy/test transports remain safe.
     */
    fun cmdPackagePath(serial: String, packageId: String): String? = packagePathFromCmd(serial, packageId)
    fun packagePathFromCmd(serial: String, packageId: String): String? = null
    /**
     * Optional bounded, read-only `exec-out cat` fallback.  Implementations
     * must never create or modify files on the device.
     */
    fun streamReadOnly(serial: String, remotePath: String, local: File, maxBytes: Long): Boolean =
        streamReadOnly(serial, remotePath, local, maxBytes) { false }
    fun execOutCatReadOnly(serial: String, remotePath: String, local: File, maxBytes: Long): Boolean = false
    /** Cancellation-aware variants let the process coordinator interrupt adb. */
    fun pullReadOnly(serial: String, remotePath: String, local: File, cancelled: () -> Boolean): Boolean {
        check(!cancelled()) { "probe cancelled" }
        return pull(serial, remotePath, local, cancelled)
    }
    fun streamReadOnly(
        serial: String,
        remotePath: String,
        local: File,
        maxBytes: Long,
        cancelled: () -> Boolean
    ): Boolean {
        check(!cancelled()) { "probe cancelled" }
        return streamReadOnly(serial, remotePath, local, maxBytes)
    }
    /** Reads only an explicitly selected, bounded metadata file. */
    fun readText(serial: String, remotePath: String, maxBytes: Int, cancelled: () -> Boolean): String? {
        check(!cancelled()) { "probe cancelled" }
        return null
    }
    fun orderedPackagePathResult(serial: String, packageId: String): QuestProbeApkPathResult {
        val pm = runCatching {
            QuestProbeApkPaths.normalizeDirect(packagePaths(serial, packageId))
        }.getOrElse {
            QuestProbeApkPathResult(
                emptyList(),
                QuestProbeApkPathDiagnostics(0, 0, 0),
                error = "pm path failed",
                source = QuestProbeApkPathSource.PM_PATH
            )
        }
        if (pm.accepted) return pm
        val cmd = cmdPackagePath(serial, packageId)?.let {
            QuestProbeApkPaths.parse(it).copy(source = QuestProbeApkPathSource.CMD_PACKAGE_PATH)
        }
        if (cmd?.accepted == true) return cmd
        val dumpsys = runCatching {
            QuestProbeApkPaths.fromDumpsys(packageId, packageInfo(serial, packageId))
        }.getOrNull()
        if (dumpsys?.accepted == true) return dumpsys
        // Preserve the primary PM diagnostics when every fallback is empty or
        // rejected; a failed source must not be reported as the resolver.
        return pm
    }
    fun stat(serial: String, path: String): Long?
    fun list(serial: String, path: String): List<RemoteEntry>
    fun listing(serial: String, path: String): RemoteListing =
        runCatching { RemoteListing(true, list(serial, path)) }
            .getOrElse { RemoteListing(false, reason = it.message) }
    fun listResult(serial: String, path: String): RestrictedListResult =
        RestrictedListResult(true, list(serial, path), exists = true)
    fun pull(serial: String, remotePath: String, local: File): Boolean
    fun pull(
        serial: String,
        remotePath: String,
        local: File,
        cancelled: () -> Boolean
    ): Boolean = pull(serial, remotePath, local)
}

data class RemoteEntry(val path: String, val size: Long? = null, val modified: String? = null, val directory: Boolean = false)
data class RemoteListing(val success: Boolean, val entries: List<RemoteEntry> = emptyList(), val reason: String? = null)
data class RestrictedListResult(
    val success: Boolean,
    val entries: List<RemoteEntry> = emptyList(),
    val diagnostic: String? = null,
    val exists: Boolean = false
)

enum class ProbeTargetState { FOUND, NOT_FOUND, AMBIGUOUS }
enum class QuestProbeState { NOT_STARTED, RUNNING, COMPLETE, PARTIAL, FAILED, CANCELLED, STALE }
typealias ProbeGameState = QuestProbeState
enum class ProbeEngine { UNITY_IL2CPP, UNITY_MONO, UNREAL_ENGINE, OTHER, UNKNOWN }
enum class ProbePreparationState { STOCK_UNPREPARED, LOADER_READY, CONTENT_MOD_READY, CODE_MOD_READY, POSSIBLY_PATCHED, UNKNOWN }
enum class ProbeLoader { QUESTLOADER, SCOTLAND2, LEMONLOADER, MELONLOADER_ANDROID, OTHER_KNOWN_LOADER, NONE, UNKNOWN }

data class QuestProbeDevice(
    val serial: String, val model: String?, val android: String?, val abi: String?,
    val abis: List<String>, val architecture: String?, val authorized: Boolean
)
data class QuestProbeCandidate(val packageId: String, val label: String?, val evidence: List<String>)
data class QuestProbeDiscovery(
    val game: String, val state: ProbeTargetState, val candidates: List<QuestProbeCandidate>
) {
    val discoveryState: ProbeTargetState get() = state
}
data class QuestProbeApk(
    val remotePath: String, val splitName: String?, val sizeBytes: Long?,
    val sha256: String?, val nativeHashes: Map<String, String> = emptyMap(), val manifestSha256: String? = null,
    val signingEntries: List<String> = emptyList(), val certificateFingerprints: List<String> = emptyList(),
    val inspectionStage: String? = null, val inspectionFailureCode: String? = null
)
data class QuestProbeManifest(
    val sha256: String?, val packageName: String? = null, val versionName: String? = null,
    val versionCode: Long? = null, val minSdk: Int? = null, val targetSdk: Int? = null,
    val applicationClass: String? = null, val launcherActivity: String? = null,
    val permissions: List<String> = emptyList(), val extractNativeLibs: Boolean? = null,
    val debuggable: Boolean? = null, val services: List<String> = emptyList(),
    val providers: List<String> = emptyList(), val receivers: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(), val decoded: Boolean = false
)
data class QuestProbeSigning(val entries: List<String> = emptyList(), val certificateFingerprints: List<String> = emptyList(), val baseSplitsConsistent: Boolean? = null)
data class QuestProbeObb(val exists: Boolean, val accessible: Boolean, val entries: List<RemoteEntry> = emptyList(), val totalBytes: Long = 0)
data class QuestProbeData(val exists: Boolean, val accessible: Boolean, val paths: List<String> = emptyList())
data class QuestProbeEngineEvidence(val engine: ProbeEngine, val confidence: String, val evidence: List<String>)
data class QuestProbeGame(
    val displayName: String, val packageId: String, val versionName: String? = null, val versionCode: Long? = null,
    val discoveryState: ProbeTargetState = ProbeTargetState.FOUND,
    val probeState: QuestProbeState = QuestProbeState.NOT_STARTED,
    val firstInstallTime: String? = null, val lastUpdateTime: String? = null, val installer: String? = null,
    val minSdk: Int? = null, val targetSdk: Int? = null, val applicationFlags: String? = null,
    val apks: List<QuestProbeApk> = emptyList(), val manifest: QuestProbeManifest? = null,
    val signing: QuestProbeSigning = QuestProbeSigning(), val engine: QuestProbeEngineEvidence = QuestProbeEngineEvidence(ProbeEngine.UNKNOWN, "LOW", emptyList()),
    val obb: QuestProbeObb = QuestProbeObb(false, false), val androidData: QuestProbeData = QuestProbeData(false, false),
    val modData: QuestProbeData = QuestProbeData(false, false), val loader: ProbeLoader = ProbeLoader.NONE,
    val contentModState: String? = null, val codeModLoaderState: String? = null,
    val preparationState: ProbePreparationState = ProbePreparationState.UNKNOWN,
    val warnings: List<String> = emptyList(),
    val apkPathDiagnostics: QuestProbeApkPathDiagnostics? = null,
    val apkPathSource: QuestProbeApkPathSource? = null,
    val apkInspectionStage: String? = null,
    val apkInspectionFailureCode: String? = null
) {
    val apkInventory: List<QuestProbeApk> get() = apks
    val loaderEvidence: ProbeLoader get() = loader
}
data class QuestPreparationProbeReport(
    val schemaVersion: Int = 1, val device: QuestProbeDevice, val discoveries: List<QuestProbeDiscovery>,
    val games: List<QuestProbeGame>, val cancelled: Boolean = false, val stale: Boolean = false
) {
    fun toJson(): String {
        fun q(s: String?) = if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val gs = games.joinToString(",") { g ->
            """{"displayName":${q(g.displayName)},"packageId":${q(g.packageId)},"discoveryState":${q(g.discoveryState.name)},"probeState":${q(g.probeState.name)},"versionName":${q(g.versionName)},"versionCode":${g.versionCode ?: "null"},"firstInstallTime":${q(g.firstInstallTime)},"lastUpdateTime":${q(g.lastUpdateTime)},"installer":${q(g.installer)},"engine":${q(g.engine.engine.name)},"engineEvidence":[${g.engine.evidence.joinToString(",") { q(it) }}],"preparationState":${q(g.preparationState.name)},"loader":${q(g.loader.name)},"apkPathSource":${q(g.apkPathSource?.name)},"apkInspectionStage":${q(g.apkInspectionStage)},"apkInspectionFailureCode":${q(g.apkInspectionFailureCode)},"apkPathDiagnostics":${g.apkPathDiagnostics?.let { d -> """{"rawLineCount":${d.rawLineCount},"validApkLineCount":${d.validApkLineCount},"uniqueApkCount":${d.uniqueApkCount},"limit":${d.limit}}""" } ?: "null"},"apkInventory":[${g.apks.joinToString(",") { a -> """{"remotePath":${q(a.remotePath)},"splitName":${q(a.splitName)},"sizeBytes":${a.sizeBytes ?: "null"},"sha256":${q(a.sha256)},"inspectionStage":${q(a.inspectionStage)},"inspectionFailureCode":${q(a.inspectionFailureCode)}}""" }}],"manifestSha256":${q(g.manifest?.sha256)},"signing":{"metaInfEntries":[${g.signing.entries.joinToString(",") { q(it) }}],"certificateFingerprints":[${g.signing.certificateFingerprints.joinToString(",") { q(it) }}],"baseSplitsConsistent":${g.signing.baseSplitsConsistent ?: "null"}},"obb":{"exists":${g.obb.exists},"accessible":${g.obb.accessible},"totalBytes":${g.obb.totalBytes},"entries":[${g.obb.entries.joinToString(",") { q(it.path) }}]},"androidData":{"exists":${g.androidData.exists},"accessible":${g.androidData.accessible},"paths":[${g.androidData.paths.joinToString(",") { q(it) }}]},"modData":{"exists":${g.modData.exists},"accessible":${g.modData.accessible},"paths":[${g.modData.paths.joinToString(",") { q(it) }}]},"loaderEvidence":{"loader":${q(g.loader.name)},"contentModState":${q(g.contentModState)},"codeModLoaderState":${q(g.codeModLoaderState)}},"warnings":[${g.warnings.joinToString(",") { q(it) }}]}"""
        }
        return """{"schemaVersion":$schemaVersion,"device":{"serial":${q(device.serial)},"model":${q(device.model)},"android":${q(device.android)},"abi":${q(device.abi)},"abis":[${device.abis.joinToString(",") { q(it) }}],"architecture":${q(device.architecture)},"authorized":${device.authorized}},"cancelled":$cancelled,"stale":$stale,"games":[$gs]}"""
    }
    fun toText(): String = buildString {
        appendLine("Quest preparation probe (read-only)")
        appendLine("Device: ${device.model ?: "unknown"} / Android ${device.android ?: "unknown"} / ${device.serial}")
        games.forEach {
            appendLine("${it.displayName} (${it.packageId}) — ${it.preparationState}; ${it.engine.engine}; loader=${it.loader}")
            it.apkInspectionFailureCode?.let { code ->
                appendLine("  APK inspection: ${it.apkInspectionStage ?: "unknown"} / $code")
            }
        }
    }
}

class QuestPreparationProbe(
    private val transport: RestrictedQuestTransport,
    private val tempRoot: File = File(System.getProperty("java.io.tmpdir"), "nfvr-quest-probe")
) {
    companion object {
        const val MAX_CANDIDATES = 256
        const val MAX_APKS_PER_GAME = QuestProbeApkPaths.DEFAULT_LIMIT
        const val MAX_APK_BYTES = 512L * 1024 * 1024
        const val MAX_TOTAL_APK_BYTES = 1024L * 1024 * 1024
        const val MAX_DIRECTORY_ENTRIES = 512
        const val MAX_ZIP_ENTRIES = 25_000
        const val MAX_ZIP_BYTES = 1024L * 1024 * 1024
        const val MAX_LOADER_METADATA_FILES = 32
        const val MAX_LOADER_METADATA_BYTES = 64 * 1024
    }
    data class Profile(val id: String, val displayName: String, val knownPackages: Set<String> = emptySet(), val labels: Set<String> = emptySet())
    val profiles = listOf(
        Profile("gorilla-tag", "Gorilla Tag", setOf("com.AnotherAxiom.GorillaTag"), setOf("gorilla tag")),
        Profile("beat-saber", "Beat Saber", setOf("com.beatgames.beatsaber"), setOf("beat saber")),
        Profile("bonelab", "BONELAB", setOf("com.StressLevelZero.BONELAB"), setOf("bonelab")),
        Profile("blade-and-sorcery", "Blade & Sorcery: Nomad",
            knownPackages = setOf("com.Warpfrog.BladeAndSorcery"),
            labels = setOf("blade & sorcery", "blade and sorcery", "nomad", "warpfrog", "bladeandsorcery"))
    )

    fun probe(
        targetGame: String? = null,
        onGame: (QuestProbeGame) -> Unit = {},
        cancelled: () -> Boolean = { false }
    ): QuestPreparationProbeReport {
        cleanupOrphans()
        val serial = authorizedSerial(transport.devices())
            ?: error("No authorized Quest device")
        val device = QuestProbeDevice(serial, prop(serial, "ro.product.model", cancelled), prop(serial, "ro.build.version.release", cancelled),
            prop(serial, "ro.product.cpu.abi", cancelled), prop(serial, "ro.product.cpu.abilist", cancelled).orEmpty().split(',').filter(String::isNotBlank),
            prop(serial, "ro.product.cpu.arch", cancelled), true)
        ensureLive(serial, cancelled)
        val packages = transport.packageList(serial).lineSequence().mapNotNull { line ->
            val id = Regex("""(?:package:)?([A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+)""").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val listedLabel = line.substringAfter("label=", "").substringBefore(' ').ifBlank { null }
            id to listedLabel
        }.toMap()
        val discoveries = profiles.map { p ->
            val candidates = packages.mapNotNull { (id, label) ->
                val hay = "${id} ${label.orEmpty()}".lowercase()
                val evidence = buildList { if (id in p.knownPackages) add("known package") ; if (p.labels.any(hay::contains)) add("label/package evidence") }
                if (evidence.isNotEmpty()) QuestProbeCandidate(id, label, evidence) else null
            }
            QuestProbeDiscovery(p.displayName, when { candidates.size == 1 -> ProbeTargetState.FOUND; candidates.isEmpty() -> ProbeTargetState.NOT_FOUND; else -> ProbeTargetState.AMBIGUOUS }, candidates)
        }
        val selected = discoveries.filter {
            it.state == ProbeTargetState.FOUND &&
                (targetGame == null || it.game.equals(targetGame, true) ||
                    profiles.firstOrNull { profile -> profile.displayName == it.game }?.let { profile ->
                        targetGame.equals(profile.id, true) || targetGame in profile.knownPackages
                    } == true)
        }
        // Pre-seed every discovered target.  A later failed/cancelled probe
        // must not make a previously discovered game disappear from the
        // report.
        val results = selected.map { d ->
            val c = d.candidates.single()
            QuestProbeGame(d.game, c.packageId, discoveryState = d.state)
        }.toMutableList()
        results.forEach(onGame)
        var stale = false
        try {
        for ((index, d) in selected.withIndex()) {
                if (cancelled()) break
                ensureLive(serial, cancelled)
                val c = d.candidates.single()
                results[index] = results[index].copy(probeState = QuestProbeState.RUNNING)
                onGame(results[index])
                val result = runCatching { inspect(device, d.game, c.packageId, cancelled) }.getOrElse {
                    if (it.message?.contains("cancel", true) == true || it.message?.contains("stale", true) == true) throw it
                    QuestProbeGame(
                        d.game, c.packageId, discoveryState = d.state,
                        probeState = QuestProbeState.FAILED,
                        warnings = listOf("PROBE_FAILED")
                    )
                }
                results[index] = result
                onGame(result)
            }
            stale = authorizedSerial(transport.devices()) != serial
        } catch (e: IllegalStateException) {
            stale = e.message?.contains("stale", true) == true || authorizedSerial(transport.devices()) != serial
        }
        if (cancelled() || stale) {
            val remainingState = if (stale) QuestProbeState.STALE else QuestProbeState.CANCELLED
            results.indices.filter {
                results[it].probeState !in setOf(
                    QuestProbeState.COMPLETE, QuestProbeState.PARTIAL, QuestProbeState.FAILED
                )
            }.forEach { index ->
                results[index] = results[index].copy(probeState = remainingState)
                onGame(results[index])
            }
        }
        return QuestPreparationProbeReport(device = device, discoveries = discoveries, games = results, cancelled = cancelled() || stale, stale = stale)
    }

    private fun prop(s: String, p: String, cancelled: () -> Boolean) = transport.getprop(s, p).trim().ifBlank { null }.also { ensureLive(s, cancelled) }
    private fun authorizedSerial(output: String): String? = output.lineSequence().mapNotNull {
        val fields = it.trim().split(Regex("\\s+"))
        fields.takeIf { it.size >= 2 && it[0] != "List" && it[1] == "device" }?.first()
    }.singleOrNull()
    private fun ensureLive(serial: String, cancelled: () -> Boolean) {
        check(!cancelled()) { "probe cancelled" }
        check(authorizedSerial(transport.devices()) == serial) { "stale Quest device" }
    }
    private fun cleanupOrphans() {
        tempRoot.listFiles()?.filter { it.name.startsWith("apk-") && it.isFile }?.forEach { it.delete() }
    }
    private fun inspect(device: QuestProbeDevice, display: String, packageId: String, cancelled: () -> Boolean): QuestProbeGame {
        ensureLive(device.serial, cancelled)
        val info = transport.packageInfo(device.serial, packageId).also { ensureLive(device.serial, cancelled) }
        fun field(n: String) = Regex("""(?m)$n[=:]([^\s]+)""").find(info)?.groupValues?.get(1)
        fun dateField(n: String) = Regex(
            """(?m)(?:^|\s)$n[=:]\s*([^\r\n]+?)(?=\s+[A-Za-z][A-Za-z0-9]*[=:]|$)"""
        ).find(info)?.groupValues?.get(1)?.trim()
        // Capture dumpsys metadata before touching APKs.  It remains useful
        // evidence when an APK path, stat, or pull stage fails.
        val metadata = QuestProbeGame(
            displayName = display,
            packageId = packageId,
            discoveryState = ProbeTargetState.FOUND,
            probeState = QuestProbeState.RUNNING,
            versionName = field("versionName"),
            versionCode = field("versionCode")?.toLongOrNull(),
            firstInstallTime = dateField("firstInstallTime"),
            lastUpdateTime = dateField("lastUpdateTime"),
            installer = field("installer"),
            minSdk = field("minSdk")?.toIntOrNull(),
            targetSdk = field("targetSdk")?.toIntOrNull(),
            applicationFlags = field("flags")
        )
        val warnings = mutableListOf<String>()
        var pathDiagnostics: QuestProbeApkPathDiagnostics? = null
        var pathSource: QuestProbeApkPathSource? = null
        var apks = emptyList<QuestProbeApk>()
        var apkStageComplete = false
        var apkInspectionStage: String? = null
        var apkInspectionFailureCode: String? = null
        try {
            val primaryPaths = transport.packagePathResult(device.serial, packageId)
            val normalized = if (primaryPaths.accepted) primaryPaths else {
                transport.orderedPackagePathResult(device.serial, packageId)
            }.also {
                ensureLive(device.serial, cancelled)
            }
            pathDiagnostics = normalized.diagnostics
            pathSource = normalized.source
            val pathError = when {
                normalized.paths.isEmpty() -> "zero"
                normalized.paths.size > MAX_APKS_PER_GAME -> "limit"
                normalized.paths.distinct().size != normalized.paths.size -> "duplicate"
                normalized.paths.any { !QuestProbeApkPaths.isSafeApkPath(it) } -> "unsafe"
                normalized.diagnostics.uniqueApkCount != normalized.paths.size -> "diagnostic-mismatch"
                normalized.error != null -> "rejected"
                else -> null
            }
            if (pathError != null) {
                apkInspectionStage = "APK_PATH_DISCOVERY"
                if (pathError == "zero") {
                    apkInspectionFailureCode = "PM_PATH_ZERO_APK_COUNT"
                    warnings += apkInspectionFailureCode!!
                } else if (pathError == "limit") {
                    apkInspectionFailureCode = "PM_PATH_LIMIT_EXCEEDED"
                    warnings += "${apkInspectionFailureCode}: ${normalized.diagnostics.uniqueApkCount} > ${normalized.diagnostics.limit}"
                    warnings += "عدد ملفات APK المثبتة غير معتاد ويحتاج مراجعة"
                } else {
                    apkInspectionFailureCode = "APK_PATH_DISCOVERY_FAILED"
                    warnings += apkInspectionFailureCode!!
                }
            } else {
                val sizes = normalized.paths.map { path ->
                    runCatching { transport.stat(device.serial, path) }
                        .getOrElse { error ->
                            if (isProbeAbort(error)) throw error
                            null
                        }.also { ensureLive(device.serial, cancelled) }
                }
                val totalExceeded = sizes.filterNotNull().sum() > MAX_TOTAL_APK_BYTES
                val inspected = normalized.paths.zip(sizes).map { (path, size) ->
                    if (size == null || size !in 1..MAX_APK_BYTES || totalExceeded) {
                        val code = when {
                            totalExceeded -> "APK_TOTAL_SIZE_LIMIT_EXCEEDED"
                            size == null -> "APK_REMOTE_STAT_UNAVAILABLE"
                            else -> "APK_REMOTE_SIZE_LIMIT_EXCEEDED"
                        }
                        ApkInspectionResult(
                            QuestProbeApk(path, split(path), size, null,
                                inspectionStage = "APK_REMOTE_SIZE_VERIFY",
                                inspectionFailureCode = code),
                            "APK_REMOTE_SIZE_VERIFY", code
                        )
                    } else {
                        pullAnalyze(device.serial, path, size, cancelled)
                    }
                }
                apks = inspected.map { it.apk }
                val failure = inspected.firstOrNull { it.failureCode != null }
                apkInspectionStage = failure?.stage
                apkInspectionFailureCode = failure?.failureCode
                failure?.failureCode?.let { warnings += it }
                apkStageComplete = failure == null
            }
        } catch (error: Throwable) {
            if (isProbeAbort(error)) throw error
            apkInspectionStage = apkInspectionStage ?: "APK_PATH_DISCOVERY"
            apkInspectionFailureCode = apkInspectionFailureCode ?: "APK_PATH_DISCOVERY_FAILED"
            warnings += apkInspectionFailureCode!!
        }
        val manifest = apks.firstOrNull()?.let { it.manifestSha256?.let { hash -> QuestProbeManifest(hash) } }
        val signing = QuestProbeSigning(apks.flatMap { it.signingEntries }.distinct(),
            apks.flatMap { it.certificateFingerprints }.distinct(),
            apks.map { it.certificateFingerprints.toSet() }.distinct().size <= 1)
        val engine = engine(apks)
        val obbResult = runCatching {
            transport.listResult(device.serial, "/sdcard/Android/obb/$packageId")
                .also { ensureLive(device.serial, cancelled) }
        }.getOrElse {
            if (isProbeAbort(it)) throw it
            null
        }
        val obbEntries = obbResult?.entries.orEmpty()
        val obb = QuestProbeObb(
            obbResult?.exists == true,
            obbResult?.success == true,
            obbEntries.take(MAX_DIRECTORY_ENTRIES),
            obbEntries.mapNotNull { it.size }.sum()
        )
        val data = mergeDirs(
            inspectDir(device.serial, "/sdcard/Android/data/$packageId", cancelled),
            inspectDir(device.serial, "/sdcard/Android/data/$packageId/files/Mods", cancelled)
        )
        val mod = mergeDirs(
            inspectDir(device.serial, "/sdcard/ModData/$packageId", cancelled),
            inspectDir(device.serial, "/sdcard/ModData/$packageId/Mods", cancelled),
            inspectDir(device.serial, "/sdcard/ModData/$packageId/Plugins", cancelled),
            inspectDir(device.serial, "/sdcard/ModData/$packageId/Loader", cancelled),
            inspectDir(device.serial, "/sdcard/ModData/$packageId/Modloader", cancelled),
            inspectDir(device.serial, "/sdcard/ModData/$packageId/Configs", cancelled),
            inspectDir(device.serial, "/sdcard/ModData/$packageId/Packages", cancelled)
        )
        if (obbResult?.success != true || !data.accessible || !mod.accessible) {
            warnings += "DIRECTORY_PROBE_INCOMPLETE"
        }
        val loader = loader(device.serial, packageId, mod, data, apks, cancelled)
        val content = if (display == "BONELAB" || display == "Blade & Sorcery: Nomad") when {
            !data.accessible -> "UNKNOWN"
            data.paths.any { it.endsWith("/files/Mods") } -> "READY"
            else -> "NOT_READY"
        } else null
        val code = if (display == "BONELAB" || display == "Blade & Sorcery: Nomad") when {
            !mod.accessible -> "UNKNOWN"
            loader == ProbeLoader.LEMONLOADER || loader == ProbeLoader.MELONLOADER_ANDROID -> loader.name
            else -> "NONE"
        } else null
        val completeEvidence = data.accessible && mod.accessible && apks.isNotEmpty() &&
            engine.engine != ProbeEngine.UNKNOWN
        val state = when {
            !completeEvidence -> ProbePreparationState.UNKNOWN
            loader in setOf(ProbeLoader.QUESTLOADER, ProbeLoader.SCOTLAND2, ProbeLoader.LEMONLOADER,
                ProbeLoader.MELONLOADER_ANDROID, ProbeLoader.OTHER_KNOWN_LOADER) -> ProbePreparationState.LOADER_READY
            loader == ProbeLoader.UNKNOWN -> ProbePreparationState.POSSIBLY_PATCHED
            content == "READY" -> ProbePreparationState.CONTENT_MOD_READY
            else -> ProbePreparationState.STOCK_UNPREPARED
        }
        val probeState = when {
            warnings.isNotEmpty() || !apkStageComplete -> QuestProbeState.PARTIAL
            else -> QuestProbeState.COMPLETE
        }
        return metadata.copy(
            probeState = probeState, apks = apks, manifest = manifest, signing = signing,
            engine = engine, obb = obb, androidData = data, modData = mod, loader = loader,
            contentModState = content, codeModLoaderState = code, preparationState = state,
            warnings = warnings, apkPathDiagnostics = pathDiagnostics, apkPathSource = pathSource,
            apkInspectionStage = apkInspectionStage, apkInspectionFailureCode = apkInspectionFailureCode
        )
    }

    private fun isProbeAbort(error: Throwable): Boolean =
        error.message?.contains("cancel", true) == true ||
            error.message?.contains("stale", true) == true

    private fun inspectDir(s: String, path: String, cancelled: () -> Boolean): QuestProbeData {
        val result = runCatching { transport.listResult(s, path).also { ensureLive(s, cancelled) } }
            .getOrElse {
                if (isProbeAbort(it)) throw it
                return QuestProbeData(false, false, emptyList())
            }
        if (!result.success) return QuestProbeData(result.exists, false, emptyList())
        val e = result.entries
        val queriedPath = if (result.exists) listOf(path) else emptyList()
        if (e.size > MAX_DIRECTORY_ENTRIES) {
            return QuestProbeData(
                result.exists,
                true,
                (queriedPath + e.take(MAX_DIRECTORY_ENTRIES).map { it.path }.filter { it.length <= 512 }).distinct()
            )
        }
        return QuestProbeData(result.exists, true,
            (queriedPath + e.map { it.path }.filter { it.length <= 512 }).distinct())
    }
    private fun mergeDirs(vararg values: QuestProbeData): QuestProbeData =
        QuestProbeData(
            exists = values.any { it.exists },
            accessible = values.any { it.accessible },
            paths = values.flatMap { it.paths }.distinct().take(MAX_DIRECTORY_ENTRIES)
        )
    private data class ApkInspectionResult(
        val apk: QuestProbeApk,
        val stage: String? = null,
        val failureCode: String? = null
    )
    private data class ZipScanResult(
        val nativeHashes: Map<String, String>,
        val stage: String? = null,
        val failureCode: String? = null
    )
    private data class SigningScanResult(
        val entries: List<String>,
        val certificateFingerprints: List<String>,
        val stage: String? = null,
        val failureCode: String? = null
    )
    private fun pullAnalyze(s: String, path: String, size: Long?, cancelled: () -> Boolean): ApkInspectionResult {
        tempRoot.mkdirs()
        val f = File.createTempFile("apk-", ".tmp", tempRoot)
        var result = ApkInspectionResult(QuestProbeApk(path, split(path), size, null))
        try {
            ensureLive(s, cancelled)
            val pulled = runCatching { transport.pullReadOnly(s, path, f, cancelled) }.getOrDefault(false)
            val retrieved = if (pulled) true else runCatching {
                transport.streamReadOnly(s, path, f, MAX_APK_BYTES, cancelled)
            }.getOrDefault(false)
            ensureLive(s, cancelled)
            if (!retrieved) {
                result = result.copy(
                    apk = result.apk.copy(inspectionStage = "APK_PULL", inspectionFailureCode = "APK_PULL_FAILED"),
                    stage = "APK_PULL", failureCode = "APK_PULL_FAILED"
                )
            } else if (f.length() <= 0L || (size != null && f.length() != size)) {
                result = result.copy(
                    apk = result.apk.copy(inspectionStage = "APK_LOCAL_SIZE_VERIFY", inspectionFailureCode = "APK_LOCAL_SIZE_MISMATCH"),
                    stage = "APK_LOCAL_SIZE_VERIFY", failureCode = "APK_LOCAL_SIZE_MISMATCH"
                )
            } else {
                val hash = runCatching { f.inputStream().use(::sha) }.getOrNull()
                if (hash == null) {
                    result = result.copy(
                        apk = result.apk.copy(inspectionStage = "APK_SHA256", inspectionFailureCode = "APK_HASH_FAILED"),
                        stage = "APK_SHA256", failureCode = "APK_HASH_FAILED"
                    )
                } else {
                    val zip = openAndScanZip(f, cancelled)
                    var apk = result.apk.copy(sha256 = hash, nativeHashes = zip.nativeHashes)
                    var failureStage = zip.stage
                    var failureCode = zip.failureCode
                    if (failureCode == null) {
                        val manifest = manifestSha(f)
                        if (manifest == null) {
                            failureStage = "MANIFEST_EXTRACT"
                            failureCode = "APK_MANIFEST_READ_FAILED"
                        } else apk = apk.copy(manifestSha256 = manifest)
                    }
                    val signatures = signingEvidenceChecked(f)
                    apk = apk.copy(
                        signingEntries = signatures.entries,
                        certificateFingerprints = signatures.certificateFingerprints
                    )
                    if (failureCode == null && signatures.failureCode != null) {
                        failureStage = signatures.stage
                        failureCode = signatures.failureCode
                    }
                    result = ApkInspectionResult(
                        apk.copy(inspectionStage = failureStage, inspectionFailureCode = failureCode),
                        failureStage, failureCode
                    )
                }
            }
        } catch (error: Throwable) {
            if (isProbeAbort(error)) throw error
            result = result.copy(
                apk = result.apk.copy(inspectionStage = "APK_PULL", inspectionFailureCode = "APK_PULL_FAILED"),
                stage = "APK_PULL", failureCode = "APK_PULL_FAILED"
            )
        } finally {
            if (!f.delete() && f.exists() && result.failureCode == null) {
                result = result.copy(
                    apk = result.apk.copy(inspectionStage = "TEMP_CLEANUP", inspectionFailureCode = "TEMP_CLEANUP_FAILED"),
                    stage = "TEMP_CLEANUP", failureCode = "TEMP_CLEANUP_FAILED"
                )
            }
            if (cancelled()) cleanupOrphans()
        }
        return result
    }
    private fun split(path: String) = path.substringAfterLast('/').removeSuffix(".apk").takeUnless { it == "base" }
    private fun sha(f: File): String = f.inputStream().use(::sha)
    private fun openAndScanZip(f: File, cancelled: () -> Boolean): ZipScanResult {
        val zip = try {
            ZipFile(f)
        } catch (_: Throwable) {
            return ZipScanResult(emptyMap(), "APK_ZIP_OPEN", "APK_ZIP_INVALID")
        }
        return try {
            zip.use { z ->
            val entries = z.entries()
            val result = linkedMapOf<String, String>()
            var count = 0
            var total = 0L
            while (entries.hasMoreElements()) {
                check(!cancelled()) { "probe cancelled" }
                val e = entries.nextElement()
                if (++count > MAX_ZIP_ENTRIES) error("ZIP entry count exceeds safe limit")
                require(e.name.length <= 512) { "ZIP entry name exceeds safe limit" }
                require(e.compressedSize <= MAX_ZIP_BYTES && e.size <= MAX_ZIP_BYTES) { "ZIP entry exceeds safe limit" }
                total += e.size.coerceAtLeast(0L)
                require(total <= MAX_ZIP_BYTES) { "ZIP uncompressed bytes exceed safe limit" }
                if (!e.isDirectory && e.name.startsWith("lib/") && e.name.endsWith(".so") && result.size < 128) {
                    val nativeHash = runCatching { sha(z.getInputStream(e)) }.getOrElse {
                        return ZipScanResult(result, "NATIVE_LIBRARY_SCAN", "APK_NATIVE_SCAN_FAILED")
                    }
                    result[e.name] = nativeHash
                }
            }
            ZipScanResult(result)
            }
        } catch (error: Throwable) {
            if (isProbeAbort(error)) throw error
            ZipScanResult(emptyMap(), "APK_ENTRY_SCAN", "APK_ENTRY_SCAN_FAILED")
        }
    }
    private fun manifestSha(f: File): String? = runCatching {
        ZipFile(f).use { z -> z.getEntry("AndroidManifest.xml")?.let { e -> sha(z.getInputStream(e)) } }
    }.getOrNull()
    private fun signingEvidenceChecked(f: File): SigningScanResult {
        return try {
        ZipFile(f).use { z ->
            val names = z.entries().asSequence().filter { it.name.startsWith("META-INF/") && !it.isDirectory }
                .map { it.name }.toList()
            val certs = mutableListOf<String>()
            names.filter { it.endsWith(".RSA", true) || it.endsWith(".DSA", true) || it.endsWith(".EC", true) }.forEach { name ->
                val cert = java.security.cert.CertificateFactory.getInstance("X.509")
                    .generateCertificate(z.getInputStream(z.getEntry(name)))
                certs += sha(cert.encoded.inputStream())
            }
            SigningScanResult(names, certs)
        }
        } catch (error: Throwable) {
            if (isProbeAbort(error)) throw error
            SigningScanResult(emptyList(), emptyList(), "SIGNATURE_SCAN", "APK_SIGNATURE_SCAN_FAILED")
        }
    }
    private fun sha(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun engine(a: List<QuestProbeApk>): QuestProbeEngineEvidence {
        val n = a.flatMap { it.nativeHashes.keys }
        return when { n.any { it.endsWith("libil2cpp.so") } -> QuestProbeEngineEvidence(ProbeEngine.UNITY_IL2CPP, "HIGH", listOf("libil2cpp.so"))
            n.any { it.endsWith("libUE4.so") } -> QuestProbeEngineEvidence(ProbeEngine.UNREAL_ENGINE, "HIGH", listOf("libUE4.so"))
            n.any { it.endsWith("libunity.so") && n.any { name -> name.contains("mono", true) } } ->
                QuestProbeEngineEvidence(ProbeEngine.UNITY_MONO, "MEDIUM", listOf("libunity.so", "mono evidence"))
            else -> QuestProbeEngineEvidence(ProbeEngine.UNKNOWN, "LOW", emptyList()) }
    }
    private fun loader(
        serial: String,
        packageId: String,
        m: QuestProbeData,
        d: QuestProbeData,
        a: List<QuestProbeApk>,
        cancelled: () -> Boolean
    ): ProbeLoader {
        /*
         * A directory called Modloader/Mods/Packages is not evidence that a
         * loader is installed.  Only bounded, concrete filenames or APK
         * library entries may establish a loader family.
         */
        val names = (m.paths + d.paths + a.flatMap { it.nativeHashes.keys })
            .map { it.substringAfterLast('/').lowercase() }
        val metadata = m.paths.asSequence()
            .filter { isLoaderMetadataPath(it, packageId) }
            .take(MAX_LOADER_METADATA_FILES)
            .mapNotNull { path ->
                runCatching { transport.readText(serial, path, MAX_LOADER_METADATA_BYTES, cancelled) }
                    .getOrElse {
                        if (isProbeAbort(it)) throw it
                        null
                    }
            }
            .map { it?.take(MAX_LOADER_METADATA_BYTES) }
            .joinToString("\n")
            .take(MAX_LOADER_METADATA_BYTES * MAX_LOADER_METADATA_FILES)
            .lowercase()
        fun has(vararg markers: String) = names.any { name ->
            markers.any { marker -> name == marker || name.startsWith(marker) && name.endsWith(".so") }
        }
        return when {
            has("libscotland2.so", "scotland2.json", "scotland2.dll") ||
                metadata.contains("scotland2") -> ProbeLoader.SCOTLAND2
            has("libquestloader.so", "questloader.json") ||
                metadata.contains("questloader") -> ProbeLoader.QUESTLOADER
            has("liblemonloader.so", "lemonloader.json") ||
                metadata.contains("lemonloader") -> ProbeLoader.LEMONLOADER
            has("libmelonloader.so", "melonloader.json") ||
                metadata.contains("melonloader") -> ProbeLoader.MELONLOADER_ANDROID
            has("libcodepatch.so") -> ProbeLoader.OTHER_KNOWN_LOADER
            m.paths.any { path ->
                path.substringAfterLast('/').equals("modloader", true) ||
                    path.substringAfterLast('/').equals("loader", true)
            } -> ProbeLoader.UNKNOWN
            else -> ProbeLoader.NONE
        }
    }

    private fun isLoaderMetadataPath(path: String, packageId: String): Boolean {
        val prefix = "/sdcard/ModData/$packageId/"
        if (!path.startsWith(prefix) || path.count { it == '/' } != prefix.count { it == '/' } + 1) return false
        val lower = path.lowercase()
        val allowedDir = listOf("/modloader/", "/configs/", "/packages/").any { it in lower }
        val name = path.substringAfterLast('/').lowercase()
        return allowedDir && name.matches(Regex("""[a-z0-9_.-]{1,96}\.(json|cfg|ini|xml|properties)$"""))
    }

}