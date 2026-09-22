import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/**
 * Read-only loader evidence used by the mod engine.  Detection never patches
 * an APK, installs a loader, changes permissions, or creates a destination.
 */
enum class ModLoaderKind(val displayName: String) {
    QUEST_LOADER("QuestLoader"),
    SCOTLAND2("Scotland2"),
    LEMON_LOADER("LemonLoader"),
    MELON_LOADER("MelonLoader");

    companion object {
        fun parse(value: String): ModLoaderKind? {
            val normalized = value.trim().lowercase()
                .replace("-", "")
                .replace("_", "")
                .replace(" ", "")
            return entries.firstOrNull {
                it.name.lowercase().replace("_", "").replace(" ", "") == normalized ||
                    it.displayName.lowercase().replace("_", "").replace(" ", "") == normalized
            }
        }
    }
}

enum class ModLoaderStatus {
    DETECTED,
    NOT_DETECTED,
    UNKNOWN
}

/**
 * Public summary used by discovery UI.  UNKNOWN is deliberately distinct
 * from NONE: the absence of authenticated evidence is not proof that a
 * loader is absent.
 */
enum class ModLoaderState {
    QUESTLOADER,
    SCOTLAND2,
    OTHER,
    NONE,
    UNKNOWN
}

data class ModLoaderEvidence(
    val loader: ModLoaderKind,
    val status: ModLoaderStatus,
    val evidence: List<String> = emptyList()
)

data class ModLoaderDetection(
    val packageId: String,
    val evidence: Map<ModLoaderKind, ModLoaderEvidence>,
    val checkedReadOnly: Boolean = true
) {
    val detected: Set<ModLoaderKind>
        get() = if (!checkedReadOnly) {
            emptySet()
        } else {
            evidence.filterValues { it.status == ModLoaderStatus.DETECTED }.keys
        }

    fun statusFor(required: Set<ModLoaderKind>): ModLoaderStatus {
        if (required.isEmpty()) return ModLoaderStatus.DETECTED
        if (!checkedReadOnly) return ModLoaderStatus.UNKNOWN
        if (required.any { evidence[it]?.status == ModLoaderStatus.DETECTED }) {
            return ModLoaderStatus.DETECTED
        }
        if (required.any { evidence[it]?.status == ModLoaderStatus.UNKNOWN || evidence[it] == null }) {
            return ModLoaderStatus.UNKNOWN
        }
        return ModLoaderStatus.NOT_DETECTED
    }

    fun hasAny(required: Set<ModLoaderKind>): Boolean =
        required.isEmpty() ||
            (checkedReadOnly && required.any { evidence[it]?.status == ModLoaderStatus.DETECTED })

    val state: ModLoaderState
        get() {
            if (!checkedReadOnly) return ModLoaderState.UNKNOWN
            val detected = this.detected
            return when {
                ModLoaderKind.QUEST_LOADER in detected -> ModLoaderState.QUESTLOADER
                ModLoaderKind.SCOTLAND2 in detected -> ModLoaderState.SCOTLAND2
                ModLoaderKind.LEMON_LOADER in detected ||
                    ModLoaderKind.MELON_LOADER in detected -> ModLoaderState.OTHER
                evidence.values.any { it.status == ModLoaderStatus.UNKNOWN } ->
                    ModLoaderState.UNKNOWN
                else -> ModLoaderState.NONE
            }
        }

    val loaderState: ModLoaderState
        get() = state

    fun summary(required: Set<ModLoaderKind>): String {
        if (required.isEmpty()) return "لا يتطلب هذا النوع محمّل مودات."
        val names = required.joinToString(" أو ") { it.displayName }
        return when (statusFor(required)) {
            ModLoaderStatus.DETECTED -> "تم اكتشاف محمّل المود المطلوب: $names."
            ModLoaderStatus.NOT_DETECTED -> "لم يتم اكتشاف محمّل المود المطلوب: $names."
            ModLoaderStatus.UNKNOWN -> "تعذر التحقق من محمّل المود المطلوب: $names."
        }
    }
}

/**
 * APK patching is intentionally an interface boundary.  Implementations may
 * report a reviewed, version-specific capability in a later release, but no
 * generic implementation is provided by this engine.
 */
data class ApkPatchAssessment(
    val packageId: String,
    val required: Boolean,
    val supported: Boolean = false,
    val patcherId: String? = null,
    val reason: String = "The installed APK requires a reviewed mod-loader patch."
)

fun interface ApkModLoaderPatcher {
    fun assess(serial: String, app: InstalledQuestApp): ApkPatchAssessment
}

object NoOpApkModLoaderPatcher : ApkModLoaderPatcher {
    override fun assess(serial: String, app: InstalledQuestApp): ApkPatchAssessment =
        ApkPatchAssessment(
            packageId = app.packageName,
            required = false,
            supported = false,
            reason = "No APK patcher is enabled."
        )
}

/**
 * The interface intentionally takes a serial.  Loader evidence belongs to a
 * particular connected headset and must not be inferred from a game name.
 */
fun interface ModLoaderDetector {
    fun detect(serial: String, app: InstalledQuestApp): ModLoaderDetection
}

/**
 * A deterministic detector useful for offline analysis and unit tests.  It
 * has no ADB or filesystem side effects.
 */
class StaticModLoaderDetector(
    private val result: ModLoaderDetection
) : ModLoaderDetector {
    override fun detect(serial: String, app: InstalledQuestApp): ModLoaderDetection =
        result
}

/**
 * Main owns the bundled ADB process implementation.  It can inject a
 * verifier after it has safely read a bounded, exact APK/artifact record.
 * Returning null (or a package-mismatched/unread-only result) keeps detection
 * UNKNOWN instead of turning arbitrary shell text into proof.
 */
fun interface ModLoaderArtifactVerifier {
    fun verify(serial: String, app: InstalledQuestApp): ModLoaderDetection?
}

/**
 * Read-only production verifier for the QuestPatcher APK tag. The verifier
 * deliberately uses the exact package-manager APK path and exact
 * `modded.json` entry; it never scans arbitrary APK strings or file
 * names for loader-looking text.
 *
 * QuestLoader and Scotland2 are accepted only when the tag contains the
 * canonical QuestPatcher identity/version and loader name. The documented
 * loader-version field is validated when present (the QuestPatcher schema
 * permits it to be null). LemonLoader/MelonLoader do not have an authoritative
 * tag contract here and remain UNKNOWN.
 */
class AdbAuthenticatedModLoaderArtifactVerifier(
    private val adbClient: AdbClient,
    private val maxApkBytes: Long = MAX_APK_BYTES
) : ModLoaderArtifactVerifier {
    override fun verify(serial: String, app: InstalledQuestApp): ModLoaderDetection? =
        runCatching { verifyInternal(serial, app) }.getOrNull()

    private fun verifyInternal(serial: String, app: InstalledQuestApp): ModLoaderDetection? {
        val pathResult = adbClient.shell(serial, "pm", "path", app.packageName)
        if (pathResult.exit != 0) return null
        val remotePath = parseBaseApkPath(pathResult.out, app.packageName) ?: return null

        val statResult = adbClient.shell(serial, "stat", "-c", "%s", remotePath)
        if (statResult.exit != 0) return null
        val remoteSize = statResult.out.trim().toLongOrNull() ?: return null
        if (remoteSize <= 0L || remoteSize > maxApkBytes) return null

        val local = File.createTempFile("nfvr-loader-artifact-", ".apk")
        try {
            val pullResult = adbClient.pullReadOnly(serial, remotePath, local)
            if (pullResult.exit != 0 ||
                !local.isFile ||
                local.length() != remoteSize ||
                local.length() > maxApkBytes
            ) {
                return null
            }
            if (readApkPackageName(local) != app.packageName) return null
            return inspectQuestPatcherTag(local, app.packageName)
        } finally {
            runCatching { local.delete() }
        }
    }

    private fun parseBaseApkPath(stdout: String, packageName: String): String? {
        val lines = stdout.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.isEmpty() || lines.any { !it.startsWith("package:") }) return null
        // `pm path` emits package:<path>; unlike `pm list packages -f`, it
        // does not append an owner after '='. Android's encoded install
        // directories legitimately contain '=' (for example `~~foo==`).
        val paths = lines.map { it.removePrefix("package:") }
        if (paths.size != lines.size || paths.count { it.endsWith("/base.apk") } != 1) return null
        if (paths.any { !isSafeInstalledApkPath(it, packageName) }) return null
        return paths.single { it.endsWith("/base.apk") }
    }

    private fun isSafeInstalledApkPath(path: String, packageName: String): Boolean {
        if (path.length > MAX_REMOTE_PATH_LENGTH ||
            !path.startsWith("/data/app/") ||
            path.contains('\u0000') ||
            path.contains("//") ||
            path.contains("..") ||
            path.substringAfterLast('/') != "base.apk"
        ) {
            return false
        }
        val components = path.removePrefix("/").split('/')
        val installDirectory = components.drop(2).dropLast(1).lastOrNull()
        return components.size in 3..6 &&
            components[0] == "data" &&
            components[1] == "app" &&
            installDirectory != null &&
            (installDirectory == packageName || installDirectory.startsWith("$packageName-")) &&
            components.drop(2).dropLast(1).all { component ->
                component.isNotEmpty() &&
                    component.all { it.isLetterOrDigit() || it in "._~+=@-" }
            }
    }

    private fun inspectQuestPatcherTag(apk: File, packageId: String): ModLoaderDetection? {
        ZipFile(apk).use { zip ->
            val tags = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name == QUEST_PATCHER_TAG_ENTRY }
                .toList()
            if (tags.size != 1) return null
            val entry = tags.single()
            if (entry.size < 0L || entry.size > MAX_TAG_BYTES) return null
            val content = ByteArrayOutputStream()
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (content.size().toLong() + read > MAX_TAG_BYTES) return null
                    content.write(buffer, 0, read)
                }
            }
            val tagText = runCatching {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content.toByteArray()))
                    .toString()
            }.getOrNull() ?: return null
            val tag = JSONObject(tagText)
            val patcherName = (tag.opt("patcherName") as? String)?.trim() ?: return null
            val patcherVersion = (tag.opt("patcherVersion") as? String)?.trim() ?: return null
            if (patcherName != "QuestPatcher" ||
                patcherVersion.isEmpty() ||
                !VERSION_PATTERN.matches(patcherVersion)
            ) {
                return null
            }
            val loaderName = (tag.opt("modloaderName") as? String)?.trim() ?: return null
            val loaderVersionValue = tag.opt("modloaderVersion")
            val loaderVersion = when (loaderVersionValue) {
                null, JSONObject.NULL -> ""
                is String -> loaderVersionValue.trim()
                else -> return null
            }
            if (loaderVersion.isNotEmpty() && !VERSION_PATTERN.matches(loaderVersion)) return null
            val loader = when (loaderName) {
                "QuestLoader" -> ModLoaderKind.QUEST_LOADER
                "Scotland2" -> ModLoaderKind.SCOTLAND2
                else -> null
            }
            if (loader !in SUPPORTED_TAG_LOADERS) return null
            return ModLoaderDetection(
                packageId = packageId,
                evidence = ModLoaderKind.entries.associateWith { kind ->
                    when {
                        kind == loader -> ModLoaderEvidence(
                            loader = kind,
                            status = ModLoaderStatus.DETECTED,
                            evidence = listOf(
                                "authenticated APK $QUEST_PATCHER_TAG_ENTRY " +
                                    "patcherVersion=$patcherVersion loaderVersion=" +
                                    (loaderVersion.ifEmpty { "unspecified" })
                            )
                        )
                        kind in SUPPORTED_TAG_LOADERS -> ModLoaderEvidence(
                            loader = kind,
                            status = ModLoaderStatus.NOT_DETECTED,
                            evidence = listOf("authenticated QuestPatcher tag names another supported loader")
                        )
                        else -> ModLoaderEvidence(
                            loader = kind,
                            status = ModLoaderStatus.UNKNOWN,
                            evidence = listOf("no authoritative APK tag schema for ${kind.displayName}")
                        )
                    }
                }
            )
        }
    }

    private companion object {
        const val QUEST_PATCHER_TAG_ENTRY = "modded.json"
        const val MAX_APK_BYTES = 256L * 1024L * 1024L
        const val MAX_TAG_BYTES = 64L * 1024L
        const val MAX_REMOTE_PATH_LENGTH = 512
        val SUPPORTED_TAG_LOADERS = setOf(
            ModLoaderKind.QUEST_LOADER,
            ModLoaderKind.SCOTLAND2
        )
        val VERSION_PATTERN = Regex("""[0-9]+(?:\.[0-9]+){1,3}(?:[-+][A-Za-z0-9.-]+)?""")
    }
}

/**
 * Filesystem-based Scotland2 detection.  Reads the read-only ModData
 * directory structure created by Scotland2's loader at
 * `/sdcard/ModData/<packageId>/Modloader/`.  The presence of the
 * `libs/libsl2.so` file is the strongest filesystem evidence because
 * Scotland2 places its bootstrap library there.  The Modloader directory
 * tree (early_mods, mods, libs) is corroborating evidence.
 *
 * This verifier intentionally does not read or modify any APK, nor does
 * it push or create any files.  The evidence is fully read-only.
 */
class AdbFilesystemLoaderVerifier(
    private val adbClient: AdbClient
) : ModLoaderArtifactVerifier {
    override fun verify(serial: String, app: InstalledQuestApp): ModLoaderDetection? =
        runCatching { verifyInternal(serial, app) }.getOrNull()

    private fun verifyInternal(serial: String, app: InstalledQuestApp): ModLoaderDetection? {
        val packageId = app.packageName

        // Scotland2 ModData root: /sdcard/ModData/<packageId>/Modloader/
        // Real-device evidence (Quest 3, Beat Saber): the bootstrap library
        // lives directly at Modloader/libsl2.so, with dependency libraries
        // under Modloader/libs/lib*.so.  Both layouts are accepted.
        val modloaderRoot = "/sdcard/ModData/$packageId/Modloader"
        val modloaderExists = adbClient.shell(serial, "test", "-d", shellQuoteRemotePath(modloaderRoot)).exit == 0

        // Check for the Scotland2 bootstrap library (both known layouts)
        val libsl2Paths = listOf(
            "$modloaderRoot/libsl2.so",
            "$modloaderRoot/libs/libsl2.so"
        )
        val foundLibsl2Path = libsl2Paths.firstOrNull { path ->
            adbClient.shell(serial, "test", "-f", shellQuoteRemotePath(path)).exit == 0
        }
        val libsl2Exists = foundLibsl2Path != null

        // Check for the Scotland2 loader directory structure
        val earlyModsExists = adbClient.shell(serial, "test", "-d", shellQuoteRemotePath("$modloaderRoot/early_mods")).exit == 0
        val modsExists = adbClient.shell(serial, "test", "-d", shellQuoteRemotePath("$modloaderRoot/mods")).exit == 0
        val libsExists = adbClient.shell(serial, "test", "-d", shellQuoteRemotePath("$modloaderRoot/libs")).exit == 0

        val hasScotland2Evidence = libsl2Exists || (modloaderExists && (earlyModsExists || modsExists || libsExists))
        if (!hasScotland2Evidence) return null

        val evidenceList = mutableListOf<String>()
        if (foundLibsl2Path != null) evidenceList += "filesystem: $foundLibsl2Path exists"
        if (modloaderExists) evidenceList += "filesystem: $modloaderRoot exists"
        if (earlyModsExists) evidenceList += "filesystem: $modloaderRoot/early_mods exists"
        if (modsExists) evidenceList += "filesystem: $modloaderRoot/mods exists"
        if (libsExists) evidenceList += "filesystem: $modloaderRoot/libs exists"

        return ModLoaderDetection(
            packageId = packageId,
            evidence = ModLoaderKind.entries.associateWith { kind ->
                when (kind) {
                    ModLoaderKind.SCOTLAND2 -> ModLoaderEvidence(
                        loader = kind,
                        status = ModLoaderStatus.DETECTED,
                        evidence = evidenceList
                    )
                    else -> ModLoaderEvidence(
                        loader = kind,
                        status = ModLoaderStatus.UNKNOWN,
                        evidence = listOf("filesystem scan does not assess ${kind.displayName}")
                    )
                }
            },
            checkedReadOnly = true
        )
    }
}

/**
 * The detector never infers a loader from free-form `dumpsys`, `pm`, or
 * directory text. Names in those outputs are not authenticated loader
 * evidence: they can be package labels, mod names, or arbitrary files. The
 * default verifier chain is: APK tag verifier → filesystem evidence
 * verifier.  Callers may pass null only for an intentionally fail-closed
 * integration.
 */
class AdbModLoaderDetector(
    private val adbClient: AdbClient,
    private val artifactVerifier: ModLoaderArtifactVerifier? =
        AdbAuthenticatedModLoaderArtifactVerifier(adbClient),
    private val filesystemVerifier: ModLoaderArtifactVerifier? =
        AdbFilesystemLoaderVerifier(adbClient)
) : ModLoaderDetector {
    override fun detect(serial: String, app: InstalledQuestApp): ModLoaderDetection {
        // Primary: authenticated APK tag (most authoritative)
        val verified = runCatching {
            artifactVerifier?.verify(serial, app)
        }.getOrNull()
        if (verified != null &&
            verified.packageId == app.packageName &&
            verified.checkedReadOnly &&
            verified.detected.isNotEmpty()
        ) {
            return verified
        }

        // Secondary: read-only filesystem evidence (corroborating)
        val filesystemEvidence = runCatching {
            filesystemVerifier?.verify(serial, app)
        }.getOrNull()
        if (filesystemEvidence != null &&
            filesystemEvidence.packageId == app.packageName &&
            filesystemEvidence.checkedReadOnly &&
            filesystemEvidence.detected.isNotEmpty()
        ) {
            return filesystemEvidence
        }

        // Merge both results if one has positive evidence
        if (verified != null && filesystemEvidence != null &&
            verified.packageId == app.packageName &&
            filesystemEvidence.packageId == app.packageName
        ) {
            val mergedEvidence = ModLoaderKind.entries.associateWith { kind ->
                val apkResult = verified.evidence[kind]
                val fsResult = filesystemEvidence.evidence[kind]
                when {
                    apkResult?.status == ModLoaderStatus.DETECTED -> apkResult
                    fsResult?.status == ModLoaderStatus.DETECTED -> fsResult
                    apkResult?.status == ModLoaderStatus.NOT_DETECTED -> apkResult
                    fsResult != null -> fsResult
                    else -> apkResult ?: ModLoaderEvidence(
                        loader = kind,
                        status = ModLoaderStatus.UNKNOWN,
                        evidence = listOf("no evidence available")
                    )
                }
            }
            return ModLoaderDetection(
                packageId = app.packageName,
                evidence = mergedEvidence,
                checkedReadOnly = true
            )
        }

        // Do not call shell here: unverified command output must never become
        // loader evidence. The production verifier performs all artifact I/O.
        return ModLoaderDetection(
            packageId = app.packageName,
            evidence = ModLoaderKind.entries.associateWith {
                ModLoaderEvidence(
                    loader = it,
                    status = ModLoaderStatus.UNKNOWN,
                     evidence = listOf("authenticated loader artifact unavailable or invalid")
                )
            },
            checkedReadOnly = true
        )
    }
}
