import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant

/**
 * The export boundary for the read-only Quest probe.  In particular, this is
 * not a backup exporter: APK bytes, OBBs, Android/data, and user data never
 * cross this boundary.
 */
object QuestPreparationReportExporter {
    const val JSON_FILE_NAME = "quest-mod-preparation-report.json"
    const val TXT_FILE_NAME = "quest-mod-preparation-report.txt"

    private val targetPackages = setOf(
        "com.AnotherAxiom.GorillaTag",
        "com.beatgames.beatsaber",
        "com.StressLevelZero.BONELAB",
        "com.Warpfrog.BladeAndSorcery"
    )

    data class ExportedFiles(val json: Path, val text: Path)

    /**
     * Exports only the target-game reports.  The report objects are treated as
     * immutable snapshots; no list or field in them is changed.
     */
    fun export(
        reports: Iterable<QuestPreparationReport>,
        directory: Path,
        deviceSerial: String? = null,
        deviceModel: String? = null
    ): ExportedFiles {
        val root = confinedDirectory(directory)
        val games = reports.asSequence()
            .filter { it.app.packageName in targetPackages }
            .distinctBy { it.app.packageName }
            .map(::gameJson)
            .toList()
        val rootJson = JSONObject()
            .put("schemaVersion", 1)
            .put("generatedAt", Instant.now().toString())
            .put("nfvrVersion", AppInfo.version)
            .put("device", JSONObject().apply {
                deviceSerial?.takeIf(String::isNotBlank)?.let { put("serial", it) }
                deviceModel?.takeIf(String::isNotBlank)?.let { put("model", it) }
            })
            .put("games", JSONArray(games))
        val json = rootJson.toString(2) + "\n"
        val text = textReport(games)
        val jsonPath = root.resolve(JSON_FILE_NAME)
        val textPath = root.resolve(TXT_FILE_NAME)
        atomicPairWrite(root, jsonPath, json.toByteArray(Charsets.UTF_8), textPath, text.toByteArray(Charsets.UTF_8))
        return ExportedFiles(jsonPath, textPath)
    }

    fun export(report: QuestPreparationReport, directory: Path): ExportedFiles =
        export(listOf(report), directory)

    /** Export the immutable report emitted by QuestPreparationProbe. */
    fun export(report: QuestPreparationProbeReport, directory: Path): ExportedFiles {
        val root = confinedDirectory(directory)
        val games = canonicalProbeGames(report).map { (game, discovery, probeState) ->
            probeGameJson(game, discovery.state.name, probeState)
        }
        val rootJson = JSONObject()
            .put("schemaVersion", 1)
            .put("generatedAt", Instant.now().toString())
            .put("nfvrVersion", AppInfo.version)
            .put("device", JSONObject().apply {
                put("serial", report.device.serial)
                report.device.model?.let { put("model", it) }
                report.device.android?.let { put("android", it) }
                report.device.abi?.let { put("abi", it) }
                put("abis", JSONArray(report.device.abis))
                report.device.architecture?.let { put("architecture", it) }
                put("authorized", report.device.authorized)
            })
            .put("partial", report.cancelled || report.stale)
            .put("cancelled", report.cancelled)
            .put("stale", report.stale)
            .put("discoveries", JSONArray(report.discoveries.map { discovery ->
                JSONObject().apply {
                    put("game", discovery.game)
                    put("state", discovery.state.name)
                    put("candidateCount", discovery.candidates.size)
                    put("candidateIds", JSONArray(discovery.candidates.map { it.packageId }))
                    put("candidates", JSONArray(discovery.candidates.map { candidate ->
                        JSONObject().apply {
                            put("packageId", candidate.packageId)
                            candidate.label?.let { put("label", it) }
                            put("evidence", JSONArray(candidate.evidence))
                        }
                    }))
                }
            }))
            .put("games", JSONArray(games))
        val text = textReport(games, report.cancelled || report.stale)
        val jsonPath = root.resolve(JSON_FILE_NAME)
        val textPath = root.resolve(TXT_FILE_NAME)
        atomicPairWrite(
            root, jsonPath, (rootJson.toString(2) + "\n").toByteArray(Charsets.UTF_8),
            textPath, text.toByteArray(Charsets.UTF_8)
        )
        return ExportedFiles(jsonPath, textPath)
    }

    /**
     * Build one stable game row per discovery, not one row per successful
     * inspection.  A failed/cancelled inspection therefore remains visible in
     * the exported snapshot instead of silently disappearing.
     */
    private fun canonicalProbeGames(
        report: QuestPreparationProbeReport
    ): List<Triple<QuestProbeGame, QuestProbeDiscovery, String>> {
        val rows = report.discoveries.mapNotNull { discovery ->
            val candidateIds = discovery.candidates.map { it.packageId }.toSet()
            val game = report.games.firstOrNull { it.packageId in candidateIds }
                ?: report.games.firstOrNull { it.displayName == discovery.game }
            // A NOT_FOUND discovery has no target package and therefore is not
            // a game evidence row. FOUND rows are retained even when probing
            // failed or was cancelled so their placeholder is explicit.
            if (game == null && discovery.state != ProbeTargetState.FOUND) return@mapNotNull null
            val packageId = game?.packageId
                ?: discovery.candidates.singleOrNull()?.packageId
                ?: ""
            val value = game ?: QuestProbeGame(
                displayName = discovery.game,
                packageId = packageId,
                warnings = when {
                    report.cancelled -> listOf("probe cancelled before evidence collection")
                    report.stale -> listOf("probe stopped because the device became stale")
                    discovery.state == ProbeTargetState.FOUND -> listOf("inspection did not complete")
                    else -> emptyList()
                }
            )
            val probeState = when {
                game != null && game.probeState != QuestProbeState.NOT_STARTED -> game.probeState.name
                report.cancelled && game == null -> "CANCELLED"
                report.stale && game == null -> "CANCELLED"
                game == null && discovery.state == ProbeTargetState.FOUND -> "FAILED"
                game == null -> "NOT_PROBED"
                game.warnings.isNotEmpty() -> "PARTIAL"
                else -> "COMPLETE"
            }
            Triple(value, discovery, probeState)
        }
        val known = rows.map { it.first.packageId }.toSet()
        val additional = report.games.filter { isTargetGame(it) && it.packageId !in known }.map { game ->
            Triple(
                game,
                QuestProbeDiscovery(
                    game.displayName,
                    ProbeTargetState.FOUND,
                    listOf(QuestProbeCandidate(game.packageId, null, listOf("probe result")))
                ),
                if (game.probeState != QuestProbeState.NOT_STARTED) game.probeState.name
                else if (game.warnings.isEmpty()) "COMPLETE" else "PARTIAL"
            )
        }
        return (rows + additional).distinctBy { "${it.first.displayName}:${it.first.packageId}" }
    }

    private fun isTargetGame(game: QuestProbeGame): Boolean =
        game.packageId in targetPackages ||
            game.displayName.lowercase() in setOf("blade & sorcery: nomad", "blade and sorcery: nomad")

    private fun probeGameJson(
        game: QuestProbeGame,
        discoveryState: String = ProbeTargetState.FOUND.name,
        probeState: String = if (game.warnings.isEmpty()) "COMPLETE" else "PARTIAL"
    ): JSONObject = JSONObject().apply {
        put("displayName", game.displayName)
        put("packageId", game.packageId)
        put("discoveryState", discoveryState)
        put("probeState", probeState)
        putNullable("versionName", game.versionName)
        putNullable("versionCode", game.versionCode)
        putNullable("firstInstallTime", game.firstInstallTime)
        putNullable("lastUpdateTime", game.lastUpdateTime)
        putNullable("installer", game.installer)
        putNullable("minSdk", game.minSdk)
        putNullable("targetSdk", game.targetSdk)
        putNullable("applicationFlags", game.applicationFlags)
        put("engine", JSONObject()
            .put("name", game.engine.engine.name)
            .put("confidence", game.engine.confidence)
            .put("evidence", JSONArray(game.engine.evidence)))
        put("apkInventory", JSONArray(game.apks.map { apk ->
            JSONObject().apply {
                put("remotePath", apk.remotePath)
                apk.splitName?.let { put("splitName", it) }
                apk.sizeBytes?.let { put("sizeBytes", it) }
                apk.sha256?.let { put("sha256", it) }
                 apk.inspectionStage?.let { put("inspectionStage", it) }
                 apk.inspectionFailureCode?.let { put("inspectionFailureCode", it) }
                if (apk.nativeHashes.isNotEmpty()) put("nativeHashes", JSONObject(apk.nativeHashes))
                apk.manifestSha256?.let { put("manifestSha256", it) }
            }
        }))
        putNullable("manifestSha256", game.manifest?.sha256)
        put("manifest", JSONObject().apply {
            val manifest = game.manifest
            putNullable("sha256", manifest?.sha256)
            putNullable("packageName", manifest?.packageName)
            putNullable("versionName", manifest?.versionName)
            putNullable("versionCode", manifest?.versionCode)
            putNullable("minSdk", manifest?.minSdk)
            putNullable("targetSdk", manifest?.targetSdk)
            putNullable("applicationClass", manifest?.applicationClass)
            putNullable("launcherActivity", manifest?.launcherActivity)
            if (manifest != null) {
                put("permissions", JSONArray(manifest.permissions))
                manifest.extractNativeLibs?.let { put("extractNativeLibs", it) }
                manifest.debuggable?.let { put("debuggable", it) }
                put("services", JSONArray(manifest.services))
                put("providers", JSONArray(manifest.providers))
                put("receivers", JSONArray(manifest.receivers))
                put("metadata", JSONObject(manifest.metadata))
                put("decoded", manifest.decoded)
            } else {
                put("permissions", JSONArray())
                put("services", JSONArray())
                put("providers", JSONArray())
                put("receivers", JSONArray())
                put("metadata", JSONObject())
                put("decoded", false)
            }
        })
        put("signing", JSONObject().apply {
            put("entries", JSONArray(game.signing.entries))
            put("certificateFingerprints", JSONArray(game.signing.certificateFingerprints))
            game.signing.baseSplitsConsistent?.let { put("baseSplitsConsistent", it) }
        })
        put("loaderEvidence", JSONObject()
            .put("loader", game.loader.name)
            .put("contentModState", game.contentModState)
            .put("codeModLoaderState", game.codeModLoaderState))
        put("obb", JSONObject().apply {
            put("exists", game.obb.exists)
            put("accessible", game.obb.accessible)
            put("totalBytes", game.obb.totalBytes)
            put("entries", JSONArray(game.obb.entries.take(MAX_METADATA_ENTRIES).map { entry ->
                JSONObject().apply {
                    put("path", entry.path)
                    entry.size?.let { put("sizeBytes", it) }
                    entry.modified?.let { put("modified", it) }
                }
            }))
            put("truncated", game.obb.entries.size > MAX_METADATA_ENTRIES)
        })
        put("androidData", safeDataJson(game.androidData))
        put("modData", safeDataJson(game.modData))
        put("preparationState", game.preparationState.name)
         game.apkPathSource?.let { put("apkPathSource", it.name) }
         game.apkInspectionStage?.let { put("apkInspectionStage", it) }
         game.apkInspectionFailureCode?.let { put("apkInspectionFailureCode", it) }
        put("warnings", JSONArray(game.warnings))
        put("diagnostics", JSONObject().apply {
            game.apkPathDiagnostics?.let { paths ->
                put("apkPath", JSONObject()
                    .put("rawLineCount", paths.rawLineCount)
                    .put("validApkLineCount", paths.validApkLineCount)
                    .put("uniqueApkCount", paths.uniqueApkCount)
                    .put("limit", paths.limit)
                     .put("limitExceeded", paths.limitExceeded))
                game.apkPathSource?.let { put("apkPathSource", it.name) }
                game.apkInspectionStage?.let { put("apkInspectionStage", it) }
                game.apkInspectionFailureCode?.let { put("apkInspectionFailureCode", it) }
            }
            put("warnings", JSONArray(game.warnings))
        })
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private fun safeDataJson(data: QuestProbeData): JSONObject = JSONObject().apply {
        put("exists", data.exists)
        put("accessible", data.accessible)
        // Names/paths are technical evidence; file bytes and file contents are
        // deliberately never represented.
        put("paths", JSONArray(data.paths.take(MAX_METADATA_ENTRIES)))
        put("truncated", data.paths.size > MAX_METADATA_ENTRIES)
    }

    private fun gameJson(report: QuestPreparationReport): JSONObject {
        val app = report.app
        val inventory = report.inventory
        val inspections = report.zipInspections
        val evidence = JSONArray(inspections.map { inspection ->
            JSONObject().apply {
                put("valid", inspection.valid)
                put("abiDirectories", JSONArray(inspection.abiDirectories.sorted()))
                put("nativeLibraries", JSONArray(inspection.elfFiles.sorted()))
                put("unity", inspection.unityEvidence)
                put("il2cpp", inspection.il2cppEvidence)
                put("elfAbiValid", inspection.elfAbiValid)
                put("resourceHashes", JSONObject(inspection.resourceHashes))
                inspection.sourceRemotePath?.let { put("sourceApkPath", it) }
                inspection.sourceSha256?.let { put("sourceSha256", it) }
                inspection.reason?.let { put("reason", it) }
            }
        })
        val apkInventory = JSONArray(inventory?.artifacts.orEmpty().map { artifact ->
            JSONObject().apply {
                put("remotePath", artifact.remotePath)
                artifact.splitName?.let { put("splitName", it) }
                put("sizeBytes", artifact.sizeBytes)
                artifact.remoteSha256?.let { put("remoteSha256", it) }
                artifact.sha256?.let { put("sha256", it) }
            }
        })
        return JSONObject().apply {
            put("displayName", app.displayName ?: app.packageName)
            put("packageId", app.packageName)
            app.versionName?.let { put("versionName", it) }
            app.versionCode?.let { put("versionCode", it) }
            put("engine", JSONObject().put("evidence", evidence))
            put("apkInventory", apkInventory)
            put("preparationState", when {
                report.readyForModInstall -> "READY"
                report.blockers.isNotEmpty() -> "BLOCKED"
                else -> "NOT_READY"
            })
            put("warnings", JSONArray((report.blockers + report.assessment.blockers).distinctBy {
                "${it.code}:${it.scope}:${it.detail}"
            }.map { JSONObject().put("code", it.code).put("scope", it.scope).put("detail", it.detail) }))
        }
    }

    private fun textReport(games: List<JSONObject>, partial: Boolean = false): String = buildString {
        appendLine("تقرير توافق ألعاب Quest — NFVR")
        appendLine("تقرير تقني للقراءة فقط؛ لا يتضمن محتوى APK أو OBB أو بيانات المستخدم.")
        if (partial) appendLine("تحذير: التقرير جزئي؛ بعض الألعاب أو الأدلة لم تكتمل.")
        appendLine()
        games.forEach { game ->
            appendLine("${game.optString("displayName")} (${game.optString("packageId")})")
            appendLine("  الإصدار: ${game.optString("versionName", "غير متوفر")} / ${game.opt("versionCode") ?: "غير متوفر"}")
            appendLine("  الحالة: ${game.optString("preparationState")}")
            appendLine("  أدلة APK/native: ${game.optJSONArray("apkInventory")?.length() ?: 0} ملف تقني")
             if (game.has("apkInspectionStage")) {
                 appendLine("  مرحلة فحص APK: ${game.optString("apkInspectionStage")}")
             }
             if (game.has("apkInspectionFailureCode")) {
                 appendLine("  فشل فحص APK: ${game.optString("apkInspectionFailureCode")}")
             }
            val warnings = game.optJSONArray("warnings")
            if (warnings != null) for (i in 0 until warnings.length()) {
                val warning = warnings.opt(i)
                val message = if (warning is JSONObject) {
                    warning.optString("code").ifBlank { warning.toString() }
                } else warning?.toString().orEmpty()
                appendLine("  تحذير: $message")
            }
            appendLine()
        }
    }

    private const val MAX_METADATA_ENTRIES = 1_000

    private fun confinedDirectory(directory: Path): Path {
        require(Files.exists(directory, LinkOption.NOFOLLOW_LINKS) &&
            Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            "export directory must be an existing directory"
        }
        val root = directory.toAbsolutePath().normalize()
        require(root == root.toRealPath(LinkOption.NOFOLLOW_LINKS)) {
            "export directory must not be a symlink"
        }
        return root
    }

    private fun atomicPairWrite(root: Path, first: Path, firstBytes: ByteArray, second: Path, secondBytes: ByteArray) {
        require(first.parent == root && second.parent == root) { "export path escapes selected directory" }
        val temp1 = Files.createTempFile(root, ".quest-report-", ".partial")
        val temp2 = Files.createTempFile(root, ".quest-report-", ".partial")
        try {
            Files.write(temp1, firstBytes, StandardOpenOption.TRUNCATE_EXISTING)
            Files.write(temp2, secondBytes, StandardOpenOption.TRUNCATE_EXISTING)
            // Validate both destinations before moving either file. This
            // avoids producing a JSON-only report when the second destination
            // is a hostile symlink or otherwise invalid.
            validateDestination(first, root)
            validateDestination(second, root)
            moveIntoRoot(temp1, first, root)
            moveIntoRoot(temp2, second, root)
        } finally {
            Files.deleteIfExists(temp1)
            Files.deleteIfExists(temp2)
        }
    }

    private fun moveIntoRoot(temp: Path, destination: Path, root: Path) {
        validateDestination(destination, root)
        Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun validateDestination(destination: Path, root: Path) {
        require(destination.parent == root && destination.normalize().startsWith(root)) {
            "export path escapes selected directory"
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            require(!Files.isSymbolicLink(destination)) { "refusing to replace symlinked export" }
            require(!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                "export destination must be a file"
            }
        }
    }
}