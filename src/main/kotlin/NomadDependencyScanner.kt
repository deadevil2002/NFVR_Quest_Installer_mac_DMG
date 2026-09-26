import java.util.Locale

/**
 * Conservative ThunderRoad `$type` dependency scanner for Nomad mods.
 *
 * ThunderRoad item JSON uses `$type` fields holding .NET type specs such
 * as `"Framework_Pack.RecallModule, Framework Pack"`.  A mod whose JSON
 * names an external scripted type is NOT independent, even when its own
 * archive contains no DLL.  Scanning is field-scoped (only `$type`
 * string values), so ordinary commas, numbers, and prose can never
 * become dependencies.
 */
data class NomadTypeReference(
    val type: String,
    val assembly: String,
    val version: String? = null,
    val sourceFile: String
)

enum class NomadAssemblyStatus {
    /** ThunderRoad engine / Unity / framework assembly. */
    BASE_GAME,
    /** A DLL inside the same archive provides it. */
    SAME_ARCHIVE,
    /** An installed mod on the device provides it. */
    INSTALLED_MOD,
    /** Well-formed reference, proven absent everywhere looked. */
    MISSING,
    /** Malformed reference that cannot even be named. */
    UNKNOWN
}

data class NomadAssemblyReport(
    val assembly: String,
    val status: NomadAssemblyStatus,
    val foundVersion: String? = null,
    val sourceFiles: List<String> = emptyList()
)

/**
 * Engine/framework assemblies shipped with every game install.  Curated
 * prefix set; anything exhibited by real evidence can extend it, but
 * unknown assemblies are never assumed to be game-internal.
 */
internal val NOMAD_BASE_GAME_ASSEMBLIES = setOf(
    "thunderroad",
    "unity",
    "unityengine",
    "system",
    "mono",
    "mscorlib",
    "netstandard"
)

internal fun nomadAssemblyIsBaseGame(assembly: String): Boolean {
    val value = assembly.trim().lowercase(Locale.ROOT)
    if (value.isBlank()) return false
    return NOMAD_BASE_GAME_ASSEMBLIES.any { base ->
        value == base || value.startsWith("$base.") || value.startsWith("$base+")
    }
}

/** Normalizes mod/assembly names for identity matching. */
internal fun normalizeNomadAssemblyName(value: String): String =
    value.trim().lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

/** Strips one trailing file extension (e.g. NanoTechArmory.dll) before normalizing. */
internal fun normalizeNomadDllName(value: String): String =
    normalizeNomadAssemblyName(value.substringBeforeLast('.', value))

private val SHORT_TYPE_REFERENCE = Regex(
    """"([A-Za-z_][\w.]*\.[\w]+)\s*,\s*([A-Za-z_][\w \-.]*[A-Za-z0-9_\]])""""
)
private val LONG_TYPE_REFERENCE = Regex(
    """"([A-Za-z_][\w.]*\.[\w]+)\s*,\s*([A-Za-z_][\w \-.]*[A-Za-z0-9_\]])\s*,\s*[Vv]ersion\s*=\s*([^",}]+)"""
)

/**
 * Extracts `$type` references from one JSON document.  The document must
 * parse (bounded by the caller); anything unparsable yields nothing
 * rather than guesses.  Malformed `$type` values (empty assembly part)
 * are reported as UNKNOWN, never as named dependencies.
 */
internal fun extractNomadTypeReferences(
    jsonText: String,
    sourceFile: String,
    maxReferences: Int = 1000,
    maxDepth: Int = 32
): Pair<List<NomadTypeReference>, Boolean> {
    val root: Any? = runCatching { org.json.JSONObject(jsonText) }.getOrNull()
        ?: runCatching { org.json.JSONArray(jsonText) }.getOrNull()
        ?: return emptyList<NomadTypeReference>() to false
    val found = linkedMapOf<String, NomadTypeReference>()
    var malformed = false
    fun visit(value: Any?, depth: Int) {
        if (found.size >= maxReferences || depth > maxDepth) return
        when (value) {
            is org.json.JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val child = runCatching { value.opt(key) }.getOrNull()
                    if (key == "\$type" && child is String) {
                        val ref = parseTypeReference(child, sourceFile)
                        if (ref == null) {
                            malformed = true
                        } else {
                            found.putIfAbsent(ref.type + "|" + ref.assembly, ref)
                        }
                    } else {
                        visit(child, depth + 1)
                    }
                }
            }
            is org.json.JSONArray -> {
                for (index in 0 until value.length()) {
                    visit(runCatching { value.opt(index) }.getOrNull(), depth + 1)
                    if (found.size >= maxReferences) return
                }
            }
            else -> Unit
        }
    }
    visit(root, 0)
    return found.values.toList() to malformed
}

private fun parseTypeReference(raw: String, sourceFile: String): NomadTypeReference? {
    val text = raw.trim()
    if (text.isBlank()) return null
    LONG_TYPE_REFERENCE.find("\"$text\"")?.let { match ->
        val assembly = match.groupValues[2].trim()
        if (assembly.isBlank()) return null
        return NomadTypeReference(
            type = match.groupValues[1].trim(),
            assembly = assembly,
            version = match.groupValues[3].trim().ifBlank { null },
            sourceFile = sourceFile
        )
    }
    SHORT_TYPE_REFERENCE.find("\"$text\"")?.let { match ->
        val assembly = match.groupValues[2].trim()
        if (assembly.isBlank()) return null
        return NomadTypeReference(
            type = match.groupValues[1].trim(),
            assembly = assembly,
            sourceFile = sourceFile
        )
    }
    return null
}

/** One installed Nomad mod for dependency resolution. */
data class NomadInstalledModRef(
    val folder: String,
    val manifestName: String?,
    val dllNames: List<String> = emptyList()
)

data class NomadDependencyIndex(
    val serial: String,
    val packageId: String,
    val mods: List<NomadInstalledModRef> = emptyList()
)

/**
 * Resolves extracted references against archive DLLs, the base-game set,
 * and the on-device installed index.  Malformed references are UNKNOWN
 * (warning only).  Well-formed references found nowhere are MISSING.
 */
fun resolveNomadAssemblyStatuses(
    references: List<NomadTypeReference>,
    archiveDllNames: List<String>,
    index: NomadDependencyIndex?
): List<NomadAssemblyReport> {
    if (references.isEmpty()) return emptyList()
    // Archive entries carry file extensions (NanoTechArmory.dll) while
    // references name assemblies (NanoTechArmory): strip one trailing
    // extension before normalizing, or nothing would ever match.
    val normalizedArchiveDlls = archiveDllNames.map(::normalizeNomadDllName).toSet()
    return references.groupBy { it.assembly }.map { (assembly, refs) ->
        val files = refs.map { it.sourceFile }.distinct().sorted()
        val version = refs.firstNotNullOfOrNull { it.version }
        when {
            nomadAssemblyIsBaseGame(assembly) ->
                NomadAssemblyReport(assembly, NomadAssemblyStatus.BASE_GAME, version, files)
            normalizedArchiveDlls.contains(normalizeNomadAssemblyName(assembly)) ->
                NomadAssemblyReport(assembly, NomadAssemblyStatus.SAME_ARCHIVE, version, files)
            index != null && index.mods.any { mod ->
                normalizeNomadAssemblyName(mod.manifestName.orEmpty()) ==
                    normalizeNomadAssemblyName(assembly) ||
                    mod.dllNames.any { dll ->
                        normalizeNomadDllName(dll) ==
                            normalizeNomadAssemblyName(assembly)
                    }
            } ->
                NomadAssemblyReport(assembly, NomadAssemblyStatus.INSTALLED_MOD, version, files)
            index != null ->
                NomadAssemblyReport(assembly, NomadAssemblyStatus.MISSING, version, files)
            else ->
                NomadAssemblyReport(assembly, NomadAssemblyStatus.UNKNOWN, version, files)
        }
    }.sortedBy { it.assembly.lowercase(Locale.ROOT) }
}

internal fun customerNomadAssemblyStatusMessage(status: NomadAssemblyStatus): String = when (status) {
    NomadAssemblyStatus.BASE_GAME -> "من اللعبة الأساسية"
    NomadAssemblyStatus.SAME_ARCHIVE -> "موجودة في نفس الحزمة"
    NomadAssemblyStatus.INSTALLED_MOD -> "مثبتة على الجهاز"
    NomadAssemblyStatus.MISSING -> "مفقودة على الجهاز"
    NomadAssemblyStatus.UNKNOWN -> "تعذر التحقق"
}
