import java.util.Locale

/**
 * Read-only installed-mod inventory for Beat Saber on Quest.
 *
 * Scotland2 keeps machine-readable evidence on device: QMOD package
 * directories under `Packages/<gameVersion>/<id>_v<version>/` plus the
 * loader libraries under `Modloader/libs/`.  NFVR only lists directory
 * and file names (`ls`); it never pulls binaries or writes anything.
 * This inventory lets the analyzer resolve QMOD dependency declarations
 * against on-device evidence instead of blocking every dependency chain
 * or — worse — downloading anything automatically.
 */
data class BeatSaberInstalledPackage(
    val id: String,
    val version: String?,
    val source: String
)

data class BeatSaberModInventory(
    val serial: String,
    val packageId: String,
    val gameVersion: String?,
    val packages: List<BeatSaberInstalledPackage> = emptyList(),
    val loaderLibs: List<String> = emptyList(),
    val mods: List<String> = emptyList()
) {
    val isEmpty: Boolean
        get() = packages.isEmpty() && loaderLibs.isEmpty() && mods.isEmpty()
}

enum class QmodDependencyStatus {
    SATISFIED,
    MISSING,
    VERSION_MISMATCH,
    OPTIONAL_MISSING,
    UNKNOWN
}

data class QmodDependencyReport(
    val dependency: ModPackageDependency,
    val status: QmodDependencyStatus,
    val foundVersion: String? = null
)

internal fun customerDependencyStatusMessage(status: QmodDependencyStatus): String = when (status) {
    QmodDependencyStatus.SATISFIED -> "مثبتة على الجهاز"
    QmodDependencyStatus.MISSING -> "مفقودة على الجهاز"
    QmodDependencyStatus.VERSION_MISMATCH -> "إصدار غير متوافق على الجهاز"
    QmodDependencyStatus.OPTIONAL_MISSING -> "اختيارية ومفقودة"
    QmodDependencyStatus.UNKNOWN -> "تعذر التحقق"
}

/**
 * Parses a Scotland2 package directory name such as
 * `beatsaber-hook_v6.4.2` into (id, version).  Directories without a
 * version suffix keep the full name as id with unknown version.
 */
internal fun parseScotland2PackageDir(name: String, source: String): BeatSaberInstalledPackage {
    val trimmed = name.trim().trimEnd('/')
    val match = Regex("""^(.*)_v(\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.\-]+)?)$""").find(trimmed)
    return if (match != null) {
        BeatSaberInstalledPackage(
            id = match.groupValues[1].trim().ifBlank { trimmed },
            version = match.groupValues[2],
            source = source
        )
    } else {
        BeatSaberInstalledPackage(id = trimmed, version = null, source = source)
    }
}

private data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
}

private fun parseSemVer(value: String): SemVer? {
    val core = value.trim().split('-', '+').firstOrNull()?.trim() ?: return null
    val parts = core.split('.')
    if (parts.size != 3) return null
    val numbers = parts.map { it.toIntOrNull() ?: return null }
    if (numbers.any { it < 0 }) return null
    return SemVer(numbers[0], numbers[1], numbers[2])
}

/**
 * Minimal range matcher for QMOD dependency declarations (`^`, `~`,
 * exact).  Anything else (compound ranges, wildcards, tags) is UNKNOWN
 * rather than guessed.
 */
internal fun qmodVersionSatisfies(installed: String?, range: String?): Boolean? {
    if (range.isNullOrBlank()) return null
    val have = installed?.let(::parseSemVer) ?: return null
    val want = range.trim()
    return when {
        want.startsWith("^") -> {
            val base = parseSemVer(want.removePrefix("^")) ?: return null
            if (have < base) false
            else if (base.major > 0) have.major == base.major
            else if (base.minor > 0) have.minor == base.minor
            else have == base
        }
        want.startsWith("~") -> {
            val base = parseSemVer(want.removePrefix("~")) ?: return null
            have >= base && have.major == base.major && have.minor == base.minor
        }
        else -> {
            val exact = parseSemVer(want) ?: return null
            have == exact
        }
    }
}

private fun normalizeQmodId(value: String): String =
    value.trim().lowercase(Locale.ROOT)

/**
 * Resolves every declared dependency against the on-device inventory.
 * A library `.so` under `Modloader/libs/` proves presence but carries no
 * version, so it yields UNKNOWN (never a false SATISFIED).
 */
fun resolveQmodDependencyStatuses(
    dependencies: List<ModPackageDependency>,
    inventory: BeatSaberModInventory?
): List<QmodDependencyReport> {
    if (inventory == null) {
        return dependencies.map { QmodDependencyReport(it, QmodDependencyStatus.UNKNOWN) }
    }
    return dependencies.map { dependency ->
        val id = normalizeQmodId(dependency.id)
        val candidates = inventory.packages.filter { normalizeQmodId(it.id) == id }
        if (candidates.isEmpty()) {
            val libHit = inventory.loaderLibs.any { lib ->
                val base = lib.lowercase(Locale.ROOT).removePrefix("lib").removeSuffix(".so")
                base == id || base == id.replace("-", "_") || base == id.replace("_", "-")
            }
            val status = if (libHit) {
                QmodDependencyStatus.UNKNOWN
            } else if (dependency.optional) {
                QmodDependencyStatus.OPTIONAL_MISSING
            } else {
                QmodDependencyStatus.MISSING
            }
            QmodDependencyReport(dependency, status)
        } else {
            val verdicts = candidates.map { qmodVersionSatisfies(it.version, dependency.version) }
            when {
                verdicts.any { it == true } -> QmodDependencyReport(
                    dependency,
                    QmodDependencyStatus.SATISFIED,
                    candidates.firstOrNull {
                        qmodVersionSatisfies(it.version, dependency.version) == true
                    }?.version
                )
                verdicts.any { it == false } -> QmodDependencyReport(
                    dependency,
                    QmodDependencyStatus.VERSION_MISMATCH,
                    candidates.firstNotNullOfOrNull { it.version }
                )
                else -> QmodDependencyReport(dependency, QmodDependencyStatus.UNKNOWN)
            }
        }
    }
}
