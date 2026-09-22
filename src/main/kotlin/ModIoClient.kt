import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * mod.io acquisition support (read/download only).
 *
 * Pavlov's `taint` marker must contain the authoritative numeric modfile
 * ID, which is server-side metadata: it cannot be derived from a manual
 * archive alone.  Given a `GET games/{game}/mods/{mod}/files` response,
 * [matchModfileForTaint] resolves the ID by EXACT archive MD5 plus
 * platform approval.  Anything else (wrong hash, wrong platform, denied
 * status, malformed payload) yields null: taint is never invented.
 *
 * Live API access needs a user-supplied mod.io API key (read-only GET is
 * enough for matching) plus the game's numeric ID; NFVR ships no key and
 * extracts none from games.  [ModIoSidecar] lets a previous NFVR-managed
 * download skip re-querying.
 */
data class ModIoModfileEntry(
    val id: Long,
    val modId: Long,
    val version: String?,
    val md5: String?,
    val filename: String?,
    val platforms: Map<String, Int> = emptyMap(),
    val live: Boolean = true
)

/** Platform statuses that mean "shippable to this platform". */
private val APPROVED_PLATFORM_STATUSES = setOf(1, 3)

fun modIoPlatformApproved(entry: ModIoModfileEntry, platform: String): Boolean {
    val status = entry.platforms[platform.trim().lowercase(Locale.ROOT)] ?: return false
    return status in APPROVED_PLATFORM_STATUSES
}

/**
 * Parses a mod.io `GET .../files` response body.  Malformed entries are
 * skipped, never guessed; a malformed body yields an empty list.
 */
fun parseModIoModfiles(responseJson: String): List<ModIoModfileEntry> {
    if (responseJson.isBlank()) return emptyList()
    return runCatching {
        val root = JSONObject(responseJson)
        val data = root.optJSONArray("data") ?: JSONArray()
        (0 until data.length()).mapNotNull { index ->
            runCatching {
                val item = data.getJSONObject(index)
                val platforms = linkedMapOf<String, Int>()
                item.optJSONArray("platforms")?.let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        runCatching { array.getJSONObject(i) }.getOrNull()
                    }.forEach { platform ->
                        val name = platform.optString("platform", "").trim().lowercase(Locale.ROOT)
                        if (name.isNotBlank()) {
                            platforms[name] = platform.optInt("status", -1)
                        }
                    }
                }
                ModIoModfileEntry(
                    id = item.getLong("id"),
                    modId = item.optLong("mod_id", -1L),
                    version = item.optString("version", "").trim().ifBlank { null },
                    md5 = item.optJSONObject("filehash")?.optString("md5", "")
                        ?.trim()?.lowercase(Locale.ROOT)?.ifBlank { null },
                    filename = item.optString("filename", "").trim().ifBlank { null },
                    platforms = platforms,
                    live = item.optBoolean("live", true)
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
}

/**
 * Resolves the authoritative taint modfile ID for a local archive:
 * exact MD5 match (case-insensitive) AND platform approval required.
 * Multiple exact matches (re-uploads) resolve to the highest live ID.
 * Returns null for every other case.
 */
fun matchModfileForTaint(
    entries: List<ModIoModfileEntry>,
    localArchiveMd5: String,
    platform: String
): ModIoModfileEntry? {
    val want = localArchiveMd5.trim().lowercase(Locale.ROOT)
    if (want.isBlank()) return null
    return entries
        .filter { it.live }
        .filter { it.md5?.lowercase(Locale.ROOT) == want }
        .filter { modIoPlatformApproved(it, platform) }
        .maxByOrNull { it.id }
}

/**
 * Safe local record of a previous NFVR-managed mod.io acquisition.
 * Keyed by exact archive hashes: a future reinstall of the identical
 * archive reuses the proven modfile ID without re-querying.
 */
data class ModIoSidecar(
    val gameId: Long,
    val modId: Long,
    val modfileId: Long,
    val platform: String,
    val archiveSha256: String,
    val archiveMd5: String,
    val version: String? = null
) {
    fun toJson(): String = JSONObject()
        .put("schemaVersion", 1)
        .put("gameId", gameId)
        .put("modId", modId)
        .put("modfileId", modfileId)
        .put("platform", platform)
        .put("archiveSha256", archiveSha256)
        .put("archiveMd5", archiveMd5)
        .put("version", version ?: JSONObject.NULL)
        .toString()

    companion object {
        fun parse(raw: String?): ModIoSidecar? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val json = JSONObject(raw)
                if (json.optInt("schemaVersion", -1) != 1) return@runCatching null
                val gameId = json.optLong("gameId", -1L)
                val modId = json.optLong("modId", -1L)
                val modfileId = json.optLong("modfileId", -1L)
                val archiveSha256 = json.optString("archiveSha256", "").trim()
                val archiveMd5 = json.optString("archiveMd5", "").trim()
                val platform = json.optString("platform", "").trim().lowercase(Locale.ROOT)
                if (gameId <= 0L || modId <= 0L || modfileId <= 0L ||
                    archiveSha256.isBlank() || archiveMd5.isBlank() || platform.isBlank()
                ) {
                    return@runCatching null
                }
                ModIoSidecar(
                    gameId = gameId,
                    modId = modId,
                    modfileId = modfileId,
                    platform = platform,
                    archiveSha256 = archiveSha256,
                    archiveMd5 = archiveMd5,
                    version = json.optString("version", "").trim().ifBlank { null }
                )
            }.getOrNull()
        }

        /** A sidecar authorizes taint only for the byte-identical archive. */
        fun authorizesTaint(sidecar: ModIoSidecar?, archiveSha256: String, archiveMd5: String): Long? {
            if (sidecar == null) return null
            if (!sidecar.archiveSha256.equals(archiveSha256.trim(), ignoreCase = true)) return null
            if (!sidecar.archiveMd5.equals(archiveMd5.trim(), ignoreCase = true)) return null
            return sidecar.modfileId.takeIf { it > 0L }
        }
    }
}
