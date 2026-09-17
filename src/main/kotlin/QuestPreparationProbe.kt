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
    fun stat(serial: String, path: String): Long?
    fun list(serial: String, path: String): List<RemoteEntry>
    fun listing(serial: String, path: String): RemoteListing =
        runCatching { RemoteListing(true, list(serial, path)) }
            .getOrElse { RemoteListing(false, reason = it.message) }
    fun listResult(serial: String, path: String): RestrictedListResult =
        RestrictedListResult(true, list(serial, path), exists = true)
    fun pull(serial: String, remotePath: String, local: File): Boolean
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
)
data class QuestProbeApk(
    val remotePath: String, val splitName: String?, val sizeBytes: Long?,
    val sha256: String?, val nativeHashes: Map<String, String> = emptyMap(), val manifestSha256: String? = null,
    val signingEntries: List<String> = emptyList(), val certificateFingerprints: List<String> = emptyList()
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
    val firstInstallTime: String? = null, val lastUpdateTime: String? = null, val installer: String? = null,
    val minSdk: Int? = null, val targetSdk: Int? = null, val applicationFlags: String? = null,
    val apks: List<QuestProbeApk> = emptyList(), val manifest: QuestProbeManifest? = null,
    val signing: QuestProbeSigning = QuestProbeSigning(), val engine: QuestProbeEngineEvidence = QuestProbeEngineEvidence(ProbeEngine.UNKNOWN, "LOW", emptyList()),
    val obb: QuestProbeObb = QuestProbeObb(false, false), val androidData: QuestProbeData = QuestProbeData(false, false),
    val modData: QuestProbeData = QuestProbeData(false, false), val loader: ProbeLoader = ProbeLoader.NONE,
    val contentModState: String? = null, val codeModLoaderState: String? = null,
    val preparationState: ProbePreparationState = ProbePreparationState.UNKNOWN, val warnings: List<String> = emptyList()
)
data class QuestPreparationProbeReport(
    val schemaVersion: Int = 1, val device: QuestProbeDevice, val discoveries: List<QuestProbeDiscovery>,
    val games: List<QuestProbeGame>, val cancelled: Boolean = false, val stale: Boolean = false
) {
    fun toJson(): String {
        fun q(s: String?) = if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val gs = games.joinToString(",") { g ->
            """{"displayName":${q(g.displayName)},"packageId":${q(g.packageId)},"versionName":${q(g.versionName)},"versionCode":${g.versionCode ?: "null"},"engine":${q(g.engine.engine.name)},"engineEvidence":[${g.engine.evidence.joinToString(",") { q(it) }}],"preparationState":${q(g.preparationState.name)},"loader":${q(g.loader.name)},"apkInventory":[${g.apks.joinToString(",") { a -> """{"remotePath":${q(a.remotePath)},"splitName":${q(a.splitName)},"sizeBytes":${a.sizeBytes ?: "null"},"sha256":${q(a.sha256)}}""" }}],"manifestSha256":${q(g.manifest?.sha256)},"signing":{"metaInfEntries":[${g.signing.entries.joinToString(",") { q(it) }}],"certificateFingerprints":[${g.signing.certificateFingerprints.joinToString(",") { q(it) }}],"baseSplitsConsistent":${g.signing.baseSplitsConsistent ?: "null"}},"obb":{"exists":${g.obb.exists},"accessible":${g.obb.accessible},"totalBytes":${g.obb.totalBytes},"entries":[${g.obb.entries.joinToString(",") { q(it.path) }}]},"androidData":{"exists":${g.androidData.exists},"accessible":${g.androidData.accessible},"paths":[${g.androidData.paths.joinToString(",") { q(it) }}]},"warnings":[${g.warnings.joinToString(",") { q(it) }}]}"""
        }
        return """{"schemaVersion":$schemaVersion,"device":{"serial":${q(device.serial)},"model":${q(device.model)},"android":${q(device.android)},"abi":${q(device.abi)},"abis":[${device.abis.joinToString(",") { q(it) }}],"architecture":${q(device.architecture)},"authorized":${device.authorized}},"cancelled":$cancelled,"stale":$stale,"games":[$gs]}"""
    }
    fun toText(): String = buildString {
        appendLine("Quest preparation probe (read-only)")
        appendLine("Device: ${device.model ?: "unknown"} / Android ${device.android ?: "unknown"} / ${device.serial}")
        games.forEach { appendLine("${it.displayName} (${it.packageId}) — ${it.preparationState}; ${it.engine.engine}; loader=${it.loader}") }
    }
}

class QuestPreparationProbe(
    private val transport: RestrictedQuestTransport,
    private val tempRoot: File = File(System.getProperty("java.io.tmpdir"), "nfvr-quest-probe")
) {
    companion object {
        const val MAX_CANDIDATES = 256
        const val MAX_APKS_PER_GAME = 32
        const val MAX_APK_BYTES = 512L * 1024 * 1024
        const val MAX_TOTAL_APK_BYTES = 1024L * 1024 * 1024
        const val MAX_DIRECTORY_ENTRIES = 512
        const val MAX_ZIP_ENTRIES = 25_000
        const val MAX_ZIP_BYTES = 1024L * 1024 * 1024
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
        val results = mutableListOf<QuestProbeGame>()
        var stale = false
        try {
        for (d in discoveries.filter {
            it.state == ProbeTargetState.FOUND &&
                (targetGame == null || it.game.equals(targetGame, true) ||
                    profiles.firstOrNull { profile -> profile.displayName == it.game }?.let { profile ->
                        targetGame.equals(profile.id, true) || targetGame in profile.knownPackages
                    } == true)
        }) {
                if (cancelled()) break
                ensureLive(serial, cancelled)
                val c = d.candidates.single()
                val result = runCatching { inspect(device, d.game, c.packageId, cancelled) }.getOrElse {
                    if (it.message?.contains("cancel", true) == true || it.message?.contains("stale", true) == true) throw it
                    QuestProbeGame(d.game, c.packageId, warnings = listOf(it.message ?: "inspection failed"))
                }
                results += result; onGame(result)
            }
            stale = authorizedSerial(transport.devices()) != serial
        } catch (e: IllegalStateException) {
            stale = e.message?.contains("stale", true) == true || authorizedSerial(transport.devices()) != serial
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
        val paths = transport.packagePaths(device.serial, packageId).also { ensureLive(device.serial, cancelled) }
        require(paths.size in 1..MAX_APKS_PER_GAME) { "APK count exceeds safe limit" }
        val sizes = paths.map { path -> transport.stat(device.serial, path).also { ensureLive(device.serial, cancelled) } }
        require(sizes.all { it != null && it in 1..MAX_APK_BYTES } && sizes.filterNotNull().sum() <= MAX_TOTAL_APK_BYTES) {
            "APK size exceeds safe limit"
        }
        val apks = paths.zip(sizes).map { (path, size) -> pullAnalyze(device.serial, path, size, cancelled) }
        val manifest = apks.firstOrNull()?.let { it.manifestSha256?.let { hash -> QuestProbeManifest(hash) } }
        val signing = QuestProbeSigning(apks.flatMap { it.signingEntries }.distinct(),
            apks.flatMap { it.certificateFingerprints }.distinct(),
            apks.map { it.certificateFingerprints.toSet() }.distinct().size <= 1)
        val engine = engine(apks)
        val obbResult = runCatching {
            transport.listResult(device.serial, "/sdcard/Android/obb/$packageId")
                .also { ensureLive(device.serial, cancelled) }
        }.getOrNull()
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
            inspectDir(device.serial, "/sdcard/ModData/$packageId/Loader", cancelled)
        )
        val loader = loader(mod, data, apks)
        val content = if (display == "BONELAB") when {
            !data.accessible -> "UNKNOWN"
            data.paths.any { it.endsWith("/files/Mods") } -> "READY"
            else -> "NOT_READY"
        } else null
        val code = if (display == "BONELAB") when {
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
        return QuestProbeGame(display, packageId, field("versionName"), field("versionCode")?.toLongOrNull(), installer = field("installer"),
            minSdk = field("minSdk")?.toIntOrNull(), targetSdk = field("targetSdk")?.toIntOrNull(), applicationFlags = field("flags"),
            apks = apks, manifest = manifest, signing = signing, engine = engine, obb = obb,
            androidData = data, modData = mod, loader = loader,
            contentModState = content, codeModLoaderState = code, preparationState = state)
    }

    private fun inspectDir(s: String, path: String, cancelled: () -> Boolean): QuestProbeData {
        val result = runCatching { transport.listResult(s, path).also { ensureLive(s, cancelled) } }
            .getOrElse { return QuestProbeData(false, false, emptyList()) }
        if (!result.success) return QuestProbeData(result.exists, false, emptyList())
        val e = result.entries
        val queriedPath = if (result.exists) listOf(path) else emptyList()
        if (e.size > MAX_DIRECTORY_ENTRIES) {
            return QuestProbeData(
                result.exists,
                true,
                (queriedPath + e.take(MAX_DIRECTORY_ENTRIES).map { it.path }).distinct()
            )
        }
        return QuestProbeData(result.exists, true, (queriedPath + e.map { it.path }).distinct())
    }
    private fun mergeDirs(vararg values: QuestProbeData): QuestProbeData =
        QuestProbeData(
            exists = values.firstOrNull()?.exists ?: false,
            accessible = values.firstOrNull()?.accessible == true,
            paths = values.flatMap { it.paths }.distinct().take(MAX_DIRECTORY_ENTRIES)
        )
    private fun pullAnalyze(s: String, path: String, size: Long?, cancelled: () -> Boolean): QuestProbeApk {
        tempRoot.mkdirs(); val f = File.createTempFile("apk-", ".tmp", tempRoot)
        return try {
            ensureLive(s, cancelled)
            if (!transport.pull(s, path, f).also { ensureLive(s, cancelled) } || (size != null && f.length() != size)) QuestProbeApk(path, split(path), size, null)
            else {
                val signatures = signingEvidence(f)
                QuestProbeApk(path, split(path), size, sha(f), zipEvidence(f, cancelled), manifestSha(f), signatures.first, signatures.second)
            }
        } finally { f.delete(); if (cancelled()) cleanupOrphans() }
    }
    private fun split(path: String) = path.substringAfterLast('/').removeSuffix(".apk").takeUnless { it == "base" }
    private fun sha(f: File): String = f.inputStream().use(::sha)
    private fun zipEvidence(f: File, cancelled: () -> Boolean): Map<String, String> = runCatching {
        ZipFile(f).use { z ->
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
                    result[e.name] = sha(z.getInputStream(e))
                }
            }
            result
        }
    }.getOrElse { error ->
        if (error.message?.contains("cancel", true) == true) throw error
        emptyMap()
    }
    private fun manifestSha(f: File): String? = runCatching {
        ZipFile(f).use { z -> z.getEntry("AndroidManifest.xml")?.let { e -> sha(z.getInputStream(e)) } }
    }.getOrNull()
    private fun signingEvidence(f: File): Pair<List<String>, List<String>> = runCatching {
        ZipFile(f).use { z ->
            val names = z.entries().asSequence().filter { it.name.startsWith("META-INF/") && !it.isDirectory }
                .map { it.name }.toList()
            val certs = names.filter { it.endsWith(".RSA", true) || it.endsWith(".DSA", true) || it.endsWith(".EC", true) }.mapNotNull { name ->
                runCatching {
                    val cert = java.security.cert.CertificateFactory.getInstance("X.509")
                        .generateCertificate(z.getInputStream(z.getEntry(name)))
                    sha(cert.encoded.inputStream())
                }.getOrNull()
            }
            names to certs
        }
    }.getOrDefault(emptyList<String>() to emptyList())
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
    private fun loader(m: QuestProbeData, d: QuestProbeData, a: List<QuestProbeApk>): ProbeLoader {
        val all = (m.paths + d.paths + a.flatMap { it.nativeHashes.keys }).joinToString(" ").lowercase()
        return when { "scotland2" in all -> ProbeLoader.SCOTLAND2; "lemonloader" in all -> ProbeLoader.LEMONLOADER
            ; "melonloader" in all -> ProbeLoader.MELONLOADER_ANDROID; "questloader" in all -> ProbeLoader.QUESTLOADER
            ; m.paths.isEmpty() -> ProbeLoader.NONE; else -> ProbeLoader.UNKNOWN }
    }
}