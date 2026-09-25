import org.json.JSONObject
import java.util.Locale

/**
 * Installed-mod version decision layer (BONELAB/Marrow pallets).
 *
 * The existing collision protection (never merge into an existing mod
 * directory) stays untouched.  This layer only ADDS information: when an
 * archive resolves to an already-installed mod identity, it classifies
 * the version relation from authoritative pallet metadata so the UI can
 * offer a safe staged update instead of a bare refusal.
 */
enum class InstalledModRelation {
    /** No installed directory for this mod identity.  Normal install. */
    ABSENT,
    /** Same version string.  No install; message upgrades on hash proof. */
    SAME_VERSION,
    /** Same version AND identical pallet bytes.  Definitely no reinstall. */
    IDENTICAL_CONTENT,
    /** Selected version is proven newer.  Staged update only, on confirm. */
    NEWER_THAN_INSTALLED,
    /** Selected version is proven older.  Downgrade stays blocked. */
    OLDER_THAN_INSTALLED,
    /** Present, but the relation cannot be proven.  Blocked, no guessing. */
    UNKNOWN_VERSION_RELATION,
    /** Installed directory exists but is a different mod.  Blocked. */
    IDENTITY_MISMATCH
}

/**
 * Authoritative Marrow pallet identity, parsed from the pallet#0 root
 * object (`barcode` is the unique mod ID, `version` its release
 * version).  Folder-name equality is supporting evidence only.
 */
data class BonelabPalletIdentity(
    val barcode: String,
    val version: String?,
    val title: String? = null,
    val author: String? = null,
    /** SHA-256 of the pallet.json bytes (archive entry or installed). */
    val palletSha256: String? = null,
    /** Evidenced mod folder, e.g. Mango.MGP2Proto. */
    val modRoot: String? = null
)

data class InstalledModAssessment(
    val relation: InstalledModRelation,
    val modRoot: String,
    val archiveIdentity: BonelabPalletIdentity,
    val installedIdentity: BonelabPalletIdentity?,
    val installedFileCount: Int? = null
)

/**
 * Parses the pallet#0 root object.  Null when no authoritative identity
 * exists (no pallet root, blank barcode).  Never throws.
 */
internal fun parseBonelabPalletIdentity(
    json: JSONObject,
    modRoot: String? = null,
    palletSha256: String? = null
): BonelabPalletIdentity? {
    val root = json.optJSONObject("root")
    val objects = json.optJSONObject("objects")
    val rootRef = root?.opt("ref")?.toString()?.takeIf { it.isNotBlank() }
    val rootObject = rootRef?.let { objects?.optJSONObject(it) }
    if (root?.optString("type", "").equals("pallet#0", true) && rootObject != null) {
        val barcode = rootObject.optString("barcode", "").trim()
        if (barcode.isBlank()) return null
        return BonelabPalletIdentity(
            barcode = barcode,
            version = rootObject.optString("version", "").trim().ifBlank { null },
            title = rootObject.optString("title", "").trim().ifBlank { null },
            author = rootObject.optString("author", "").trim().ifBlank { null },
            palletSha256 = palletSha256?.trim()?.ifBlank { null },
            modRoot = modRoot?.trim()?.ifBlank { null }
        )
    }
    val barcode = json.optString("barcode", "").trim()
    if (barcode.isBlank()) return null
    return BonelabPalletIdentity(
        barcode = barcode,
        version = json.optString("version", "").trim().ifBlank { null },
        title = json.optString("title", "").trim().ifBlank { null },
        author = json.optString("author", "").trim().ifBlank { null },
        palletSha256 = palletSha256?.trim()?.ifBlank { null },
        modRoot = modRoot?.trim()?.ifBlank { null }
    )
}

/**
 * Compares mod version strings on numeric dot-segments (missing segments
 * count as zero; leading `v` and surrounding whitespace ignored).
 * Returns null when either side is absent or non-numeric: callers must
 * classify UNKNOWN rather than guess.
 */
internal fun compareModVersions(first: String?, second: String?): Int? {
    fun segments(value: String): List<Int>? {
        val core = value.trim().removePrefix("v").removePrefix("V").trim()
        if (core.isBlank()) return null
        val parts = core.split('.')
        if (parts.isEmpty() || parts.size > 8) return null
        return parts.map { it.trim().toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
    }
    val left = segments(first ?: return null) ?: return null
    val right = segments(second ?: return null) ?: return null
    val width = maxOf(left.size, right.size)
    for (index in 0 until width) {
        val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
        if (comparison != 0) return comparison
    }
    return 0
}

/**
 * Pure version decision.  Identity must already be proven by barcode
 * equality before calling (folder equality alone is not enough).
 */
fun decideInstalledModRelation(
    archive: BonelabPalletIdentity,
    installed: BonelabPalletIdentity?
): InstalledModRelation {
    if (installed == null) return InstalledModRelation.ABSENT
    if (!installed.barcode.equals(archive.barcode, ignoreCase = true)) {
        return InstalledModRelation.IDENTITY_MISMATCH
    }
    val comparison = compareModVersions(archive.version, installed.version)
        ?: return InstalledModRelation.UNKNOWN_VERSION_RELATION
    if (comparison != 0) {
        return if (comparison > 0) InstalledModRelation.NEWER_THAN_INSTALLED
        else InstalledModRelation.OLDER_THAN_INSTALLED
    }
    val archiveHash = archive.palletSha256?.lowercase(Locale.ROOT)
    val installedHash = installed.palletSha256?.lowercase(Locale.ROOT)
    if (!archiveHash.isNullOrBlank() && archiveHash == installedHash) {
        return InstalledModRelation.IDENTICAL_CONTENT
    }
    return InstalledModRelation.SAME_VERSION
}

internal fun installedModRelationMessage(relation: InstalledModRelation): String = when (relation) {
    InstalledModRelation.ABSENT -> "غير مثبت."
    InstalledModRelation.SAME_VERSION -> "هذا المود مثبت بالفعل بنفس الإصدار."
    InstalledModRelation.IDENTICAL_CONTENT -> "هذا الإصدار مثبت بالفعل ولا يحتاج إلى إعادة تثبيت."
    InstalledModRelation.NEWER_THAN_INSTALLED -> "يوجد إصدار أقدم من هذا المود مثبت."
    InstalledModRelation.OLDER_THAN_INSTALLED -> "الإصدار المحدد أقدم من الإصدار المثبت."
    InstalledModRelation.UNKNOWN_VERSION_RELATION ->
        "المود موجود مسبقًا، ولكن تعذر تحديد ما إذا كان الملف المحدد أحدث من الإصدار المثبت."
    InstalledModRelation.IDENTITY_MISMATCH -> "مجلد موجود باسم مختلف المحتوى؛ لن يتم المساس به."
}

/** Customer confirmation text for a proven newer version. */
internal fun modUpdateConfirmationText(assessment: InstalledModAssessment): String =
    "الإصدار المثبت:\n${assessment.installedIdentity?.version ?: "؟"}\n\n" +
        "الإصدار المحدد:\n${assessment.archiveIdentity.version ?: "؟"}\n\n" +
        "هل تريد تحديث المود؟"

/**
 * Authoritative ThunderRoad manifest identity.  `Name` is the mod's
 * stable identity (folder equality alone never proves it); ModVersion
 * and GameVersion drive the relation decision.
 */
data class NomadModIdentity(
    val name: String,
    val author: String?,
    val modVersion: String?,
    val gameVersion: String?,
    /** Evidenced mod folder, e.g. MarvelWeaponsNOMAD. */
    val modRoot: String? = null
)

data class NomadInstalledAssessment(
    val relation: InstalledModRelation,
    val modRoot: String,
    val archiveIdentity: NomadModIdentity,
    val installedIdentity: NomadModIdentity?,
    val installedFileCount: Int? = null
)

/**
 * Pure Nomad version decision.  Identity must already be proven by
 * Name (+Author when both sides declare one) before calling.
 */
fun decideNomadInstalledRelation(
    archive: NomadModIdentity,
    installed: NomadModIdentity?
): InstalledModRelation {
    if (installed == null) return InstalledModRelation.ABSENT
    if (!installed.name.equals(archive.name, ignoreCase = false)) {
        return InstalledModRelation.IDENTITY_MISMATCH
    }
    val archiveAuthor = archive.author?.trim()?.ifBlank { null }
    val installedAuthor = installed.author?.trim()?.ifBlank { null }
    if (archiveAuthor != null && installedAuthor != null &&
        !archiveAuthor.equals(installedAuthor, ignoreCase = true)
    ) {
        return InstalledModRelation.IDENTITY_MISMATCH
    }
    val comparison = compareModVersions(archive.modVersion, installed.modVersion)
        ?: return InstalledModRelation.UNKNOWN_VERSION_RELATION
    return when {
        comparison > 0 -> InstalledModRelation.NEWER_THAN_INSTALLED
        comparison < 0 -> InstalledModRelation.OLDER_THAN_INSTALLED
        else -> InstalledModRelation.SAME_VERSION
    }
}

internal fun nomadInstalledMessage(relation: InstalledModRelation): String = when (relation) {
    InstalledModRelation.ABSENT -> "غير مثبت."
    InstalledModRelation.SAME_VERSION,
    InstalledModRelation.IDENTICAL_CONTENT -> "المود مثبت بالفعل."
    InstalledModRelation.NEWER_THAN_INSTALLED -> "إصدار أحدث متوفر."
    InstalledModRelation.OLDER_THAN_INSTALLED -> "الإصدار المحدد أقدم من المثبت."
    InstalledModRelation.UNKNOWN_VERSION_RELATION -> "تعذر إثبات بنية مود Nomad آمنة."
    InstalledModRelation.IDENTITY_MISMATCH -> "مجلد موجود باسم مختلف المحتوى؛ لن يتم المساس به."
}

/**
 * Binds a user-confirmed update to its exact evidence.  The executor
 * re-validates every field live; any drift refuses the operation.
 */
data class ModUpdateAuthorization(
    val confirmationToken: String,
    val deviceSerial: String,
    val packageId: String,
    val modRoot: String,
    val archiveSha256: String,
    val installedVersion: String?,
    val newVersion: String,
    val installedBarcode: String,
    val archiveBarcode: String
)

internal fun modUpdateConfirmationToken(
    serial: String,
    packageId: String,
    modRoot: String,
    archiveSha256: String,
    installedVersion: String?,
    newVersion: String
): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(
            listOf(
                serial.trim(),
                packageId.trim(),
                modRoot,
                archiveSha256.trim().lowercase(Locale.ROOT),
                installedVersion.orEmpty(),
                newVersion
            ).joinToString("").toByteArray(Charsets.UTF_8)
        )
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
