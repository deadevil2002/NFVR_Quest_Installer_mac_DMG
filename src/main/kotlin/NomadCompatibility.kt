/**
 * Compatibility evidence for the Nomad manifest GameVersion field.
 *
 * Warpfrog's current runtime/loader comparison is not public.  Consequently
 * NFVR must not treat the opaque four-component manifest value as an exact
 * app version string.  Matching is deliberately conservative: an exact
 * normalized match is strongest, while matching major/minor components is a
 * family relationship (useful for 1.0.0.0 versus the app's 1.0.7).  Different
 * major/minor families are incompatible; malformed or absent values remain a
 * warning for callers that require manual review.
 */
enum class NomadCompatibilityState {
    EXACT,
    COMPATIBLE_FAMILY,
    WARNING,
    INCOMPATIBLE
}

data class NomadCompatibility(
    val state: NomadCompatibilityState,
    val manifestVersion: String?,
    val installedVersion: String?,
    val reason: String
) {
    val installAllowed: Boolean
        get() = state == NomadCompatibilityState.EXACT ||
            state == NomadCompatibilityState.COMPATIBLE_FAMILY
}

    object NomadCompatibilityEvaluator {
    /**
     * Generation-anchored evaluation for Nomad Quest.  The manifest
     * GameVersion tracks the ThunderRoad content generation, NOT the Meta
     * store version: U11-era mods declare 0.11.0.0 while current U12+ era
     * mods declare 1.0.0.0 (store 1.x line).  Comparing the manifest
     * against the store version is apples-to-oranges; the manifest major
     * and minor must equal the evidenced generation instead.
     */
    fun evaluateForGeneration(manifestVersion: String?, major: Int, minor: Int): NomadCompatibility {
        val manifest = parse(manifestVersion)
        if (manifest == null) {
            return NomadCompatibility(
                NomadCompatibilityState.WARNING,
                manifestVersion?.trim()?.ifBlank { null },
                "$major.$minor",
                "Nomad manifest GameVersion is missing or malformed; manual compatibility review is required."
            )
        }
        if (manifest[0] == major && manifest[1] == minor) {
            return NomadCompatibility(
                NomadCompatibilityState.COMPATIBLE_FAMILY,
                manifestVersion?.trim(),
                "$major.$minor",
                "Nomad manifest targets ThunderRoad generation $major.$minor, matching the installed U12+ era game; the runtime's opaque GameVersion semantics are not publicly specified."
            )
        }
        return NomadCompatibility(
            NomadCompatibilityState.INCOMPATIBLE,
            manifestVersion?.trim(),
            "$major.$minor",
            "Nomad manifest targets ThunderRoad generation ${manifest[0]}.${manifest[1]}, not the installed $major.$minor era."
        )
    }

    fun evaluate(manifestVersion: String?, installedVersion: String?): NomadCompatibility {
        val manifest = parse(manifestVersion)
        val installed = parse(installedVersion)
        if (manifest == null || installed == null) {
            return NomadCompatibility(
                NomadCompatibilityState.WARNING,
                manifestVersion?.trim()?.ifBlank { null },
                installedVersion?.trim()?.ifBlank { null },
                "Nomad GameVersion comparison semantics are not publicly specified; manual compatibility review is required."
            )
        }
        if (manifest == installed) {
            return NomadCompatibility(
                NomadCompatibilityState.EXACT,
                manifestVersion?.trim(),
                installedVersion?.trim(),
                "Nomad manifest and installed version match exactly."
            )
        }
        if (manifest[0] == installed[0] && manifest[1] == installed[1]) {
            return NomadCompatibility(
                NomadCompatibilityState.COMPATIBLE_FAMILY,
                manifestVersion?.trim(),
                installedVersion?.trim(),
                "Nomad manifest and installed version share the same major/minor compatibility family; the runtime's opaque GameVersion semantics are not publicly specified."
            )
        }
        return NomadCompatibility(
            NomadCompatibilityState.INCOMPATIBLE,
            manifestVersion?.trim(),
            installedVersion?.trim(),
            "Nomad manifest and installed version belong to different major/minor compatibility families."
        )
    }

    private fun parse(value: String?): List<Int>? {
        val parts = value?.trim()?.split('.') ?: return null
        if (parts.size !in 2..4 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
        return parts.map { it.toIntOrNull() ?: return null }.let {
            if (it.size == 2) it + listOf(0, 0)
            else if (it.size == 3) it + 0
            else it
        }
    }
}