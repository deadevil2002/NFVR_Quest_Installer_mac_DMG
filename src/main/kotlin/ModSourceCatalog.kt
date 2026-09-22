/**
 * Data-driven catalog separating WHERE a mod comes from (source) from HOW
 * a game installs it (install method).  A source never implies an install
 * path: mod.io may supply an archive while each game keeps its own local
 * layout, runtime, registration, and verification contract.  There is no
 * universal install folder; every row is per-game evidence.
 */
enum class ModSourceProvider {
    /** A local archive the user already has (ZIP/QMOD). */
    LOCAL_ARCHIVE,
    /** mod.io REST acquisition (browse/download only; future work). */
    MOD_IO,
    /** A GitHub release asset (future work). */
    GITHUB_RELEASE,
    /** Any other manual download the user supplies as a file. */
    MANUAL_DOWNLOAD
}

/** How the game consumes a local archive on Quest. */
enum class GameInstallMethod {
    /** Direct verified file copy into a game-owned mod directory. */
    DIRECT_COPY,
    /** QMOD staged into the Scotland2 Modloader tree. */
    SCOTLAND2_QMOD,
    /** Managed by the game's own mod.io runtime; NFVR copies nothing. */
    GAME_MANAGED_MOD_IO,
    /** Managed by the game's built-in importer; NFVR copies nothing. */
    GAME_MANAGED_IMPORTER,
    /** No proven local contract. */
    UNKNOWN
}

enum class ModOfflineBehavior {
    OFFLINE_READY,
    ONLINE_REQUIRED,
    PARTIALLY_OFFLINE,
    UNKNOWN
}

data class GameModSourceEntry(
    val packageId: String,
    val sources: Set<ModSourceProvider>,
    val format: String,
    val loader: String?,
    val installMethod: GameInstallMethod,
    val registration: String,
    val verification: String,
    val offline: ModOfflineBehavior
)

/**
 * Provenance for every row: real-device evidence and public sources, not
 * assumptions.  Kept beside (not inside) the install profiles so a future
 * Mod.ioProvider can reuse the source half without touching install gates.
 */
object ModSourceCatalog {
    val entries: List<GameModSourceEntry> = listOf(
        GameModSourceEntry(
            packageId = ModPackageAnalyzer.BONELAB_PACKAGE_ID,
            sources = setOf(ModSourceProvider.LOCAL_ARCHIVE, ModSourceProvider.MANUAL_DOWNLOAD),
            format = "Marrow pallet folder (pallet.json + catalog + .bundle)",
            loader = null,
            installMethod = GameInstallMethod.DIRECT_COPY,
            registration = "None: the game scans files/Mods/ at launch.",
            verification = "Per-file existence + byte size on device.",
            offline = ModOfflineBehavior.OFFLINE_READY
        ),
        GameModSourceEntry(
            packageId = "com.beatgames.beatsaber",
            sources = setOf(ModSourceProvider.LOCAL_ARCHIVE, ModSourceProvider.MANUAL_DOWNLOAD),
            format = "QMOD (mod.json + loader-relative payloads)",
            loader = "Scotland2 (Modloader/libsl2.so on device)",
            installMethod = GameInstallMethod.SCOTLAND2_QMOD,
            registration = "QMOD Packages/<gameVersion>/ tree + loader evidence; dependencies resolved against the on-device inventory, never downloaded.",
            verification = "Per-file existence + byte size on device.",
            offline = ModOfflineBehavior.OFFLINE_READY
        ),
        GameModSourceEntry(
            packageId = ModPackageAnalyzer.PAVLOV_PACKAGE_ID,
            sources = setOf(ModSourceProvider.MOD_IO, ModSourceProvider.MANUAL_DOWNLOAD, ModSourceProvider.LOCAL_ARCHIVE),
            format = "Unreal mod.io UGC (metadata.json EngineVersion/ModType + UGC<id>pakchunk0-Android_ASTC.pak)",
            loader = "mod.io UE runtime (X-Modio-Platform in native lib; EOS + Oculus auth SDKs)",
            installMethod = GameInstallMethod.GAME_MANAGED_MOD_IO,
            registration = "Required and game-internal: subscription store + AssetRegistry in app-private storage. Not reproducible from sdcard.",
            verification = "Recognition only (format + mod ID); no install plan exists.",
            offline = ModOfflineBehavior.UNKNOWN
        ),
        GameModSourceEntry(
            packageId = ModPackageAnalyzer.GORILLA_TAG_PACKAGE_ID,
            sources = setOf(ModSourceProvider.MOD_IO, ModSourceProvider.MANUAL_DOWNLOAD, ModSourceProvider.LOCAL_ARCHIVE),
            format = "Virtual Stump map (package.json + vs_android/vs_win64); game's ModIOManager verifies installed mods",
            loader = null,
            installMethod = GameInstallMethod.GAME_MANAGED_IMPORTER,
            registration = "Required and game-internal: ModIOManager::IsInstalledModOutdated reads the game's own managed state.",
            verification = "Recognition only (Virtual Stump markers); no import contract proven.",
            offline = ModOfflineBehavior.UNKNOWN
        ),
        GameModSourceEntry(
            packageId = ModPackageAnalyzer.NOMAD_PACKAGE_ID,
            sources = setOf(ModSourceProvider.LOCAL_ARCHIVE, ModSourceProvider.MANUAL_DOWNLOAD),
            format = "ThunderRoad manifest/module/catalog + asset bundles",
            loader = null,
            installMethod = GameInstallMethod.DIRECT_COPY,
            registration = "None beyond the verified files/Mods/ root (created by first launch).",
            verification = "Per-file existence + byte size on device.",
            offline = ModOfflineBehavior.OFFLINE_READY
        )
    )

    fun forPackage(packageId: String): GameModSourceEntry? =
        entries.firstOrNull { it.packageId == packageId }
}
