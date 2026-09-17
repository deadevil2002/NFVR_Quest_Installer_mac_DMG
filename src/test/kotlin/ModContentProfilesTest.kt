import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Content-profile fixtures intentionally resemble distributed game packages:
 * a mod folder, game-owned metadata, and payload files.  These tests avoid
 * relying on a filename alone as the game signal.
 */
class ModContentProfilesTest {
    private val bonelab = InstalledQuestApp(
        ModPackageAnalyzer.BONELAB_PACKAGE_ID,
        "1.2974.57485",
        2974L,
        apkSha256 = "02adecc4af7354296205b4c2dbb50fba4132628aa0f0186ea9a7cf7419f670b1"
    )
    private val nomad = InstalledQuestApp(
        ModPackageAnalyzer.NOMAD_PACKAGE_ID,
        "1.0.7",
        682L,
        apkSha256 = "48e49e3d88590ae572df1885f67f4a5f2d9d8397dae16475c25b4d471e6e2324"
    )
    private val analyzer = ModPackageAnalyzer()

    @Test
    fun profilesExposeTheCompletedVersionAndApkFixtures() {
        val bonelabProfile = assertNotNull(
            GameModProfileRegistry.findByPackageId(ModPackageAnalyzer.BONELAB_PACKAGE_ID)
        )
        val nomadProfile = assertNotNull(
            GameModProfileRegistry.findByPackageId(ModPackageAnalyzer.NOMAD_PACKAGE_ID)
        )
        assertTrue(bonelabProfile.supportsApp(bonelab))
        assertEquals(
            "02adecc4af7354296205b4c2dbb50fba4132628aa0f0186ea9a7cf7419f670b1",
            bonelabProfile.apkSha256
        )
        assertTrue(nomadProfile.supportsApp(nomad))
        assertEquals(
            "48e49e3d88590ae572df1885f67f4a5f2d9d8397dae16475c25b4d471e6e2324",
            nomadProfile.apkSha256
        )
        assertFalse(nomadProfile.supportsVersion(nomad.copy(versionCode = 681L)))
        assertFalse(nomadProfile.acceptsApkSha256("00".repeat(32)))
    }

    @Test
    fun directReadinessRequiresFreshMatchingApkHash() {
        val archive = zipOf(
            "AvatarPallet/manifest.json" to
                """{"name":"Avatar Pallet","packageId":"com.StressLevelZero.BONELAB","gameVersion":"1.2974.57485","version":"1.0.0"}""",
            "AvatarPallet/pallet.json" to
                """{"name":"Avatar Pallet","pallet":"avatar","version":"1.0.0","author":"NFVR","files":["avatar.assetbundle"]}""",
            "AvatarPallet/avatar.assetbundle" to "bundle"
        )
        val missingHash = analyzer.analyze(archive, bonelab.copy(apkSha256 = null))
        assertFalse(missingHash.installable)
        assertTrue(missingHash.plan.preconditions.any { it.code == "APK_SHA256_REQUIRED" })
        val wrongHash = analyzer.analyze(archive, bonelab.copy(apkSha256 = "00".repeat(32)))
        assertFalse(wrongHash.installable)
        assertTrue(wrongHash.plan.preconditions.any { it.code == "APK_SHA256_UNSUPPORTED" })
        archive.delete()

        val nomadArchive = zipOf(
            "NomadMod/manifest.json" to
                """{"name":"Nomad","packageId":"com.Warpfrog.BladeAndSorcery","gameVersion":"1.0.7"}""",
            "NomadMod/module.json" to """{"name":"Nomad","id":"nomad","version":"1"}""",
            "NomadMod/content.assetbundle" to "bundle"
        )
        val nomadMissingHash = analyzer.analyze(nomadArchive, nomad.copy(apkSha256 = null))
        assertFalse(nomadMissingHash.installable)
        assertTrue(nomadMissingHash.plan.preconditions.any {
            it.code == "APK_SHA256_REQUIRED"
        })
        nomadArchive.delete()
    }

    @Test
    fun realisticBonelabPalletIsDirectContentWithoutLemonLoader() {
        val archive = zipOf(
            "AvatarPallet/manifest.json" to
                """{"name":"Avatar Pallet","packageId":"com.StressLevelZero.BONELAB","gameVersion":"1.2974.57485","version":"1.0.0"}""",
            "AvatarPallet/pallet.json" to
                """{"name":"Avatar Pallet","pallet":"avatar","version":"1.0.0","author":"NFVR"}""",
            "AvatarPallet/Android/avatar.assetbundle" to "bundle"
        )
        val result = analyzer.analyze(archive, bonelab)
        assertEquals(ModPackageType.BONELAB_NATIVE_CONTENT, result.packageType)
        assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, result.outcome)
        assertTrue(result.plan.loaderRequirement == null)
        assertTrue(result.plan.mappings.any {
            it.destinationPath ==
                "/sdcard/Android/data/com.StressLevelZero.BONELAB/files/Mods/AvatarPallet/Android/avatar.assetbundle"
        })
        archive.delete()
    }

    @Test
    fun selectedAppDoesNotTurnEmptyPalletOrGenericNomadSidecarIntoContent() {
        val emptyPallet = zipOf(
            "Foo/pallet.json" to "{}",
            "Foo/file.assetbundle" to "bundle"
        )
        val bonelabResult = analyzer.analyze(emptyPallet, bonelab)
        assertFalse(bonelabResult.installable)
        assertFalse(bonelabResult.outcome == ModInstallOutcome.DIRECT_INSTALL_READY)
        emptyPallet.delete()

        val genericNomad = zipOf(
            "Generic/module.json" to """{"name":"Generic","version":"1.0"}""",
            "Generic/catalog.json" to """{"name":"Generic","version":"1.0"}""",
            "Generic/file.assetbundle" to "bundle"
        )
        val nomadResult = analyzer.analyze(genericNomad, nomad)
        assertFalse(nomadResult.installable)
        assertFalse(nomadResult.outcome == ModInstallOutcome.DIRECT_INSTALL_READY)
        genericNomad.delete()
    }

    @Test
    fun nomadManifestModuleAndCatalogPreserveOneInnerFolder() {
        val archive = zipOf(
            "Mods/Weapons/Weapons/manifest.json" to
                """{"name":"Weapons","packageId":"com.Warpfrog.BladeAndSorcery","gameVersion":"1.0.7"}""",
            "Mods/Weapons/Weapons/module.json" to
                """{"name":"Weapons","id":"weapons","version":"2.0"}""",
            "Mods/Weapons/Weapons/catalog.json" to
                """{"module":"weapons","bundles":["weapons.assetbundle"]}""",
            "Mods/Weapons/Weapons/weapons.assetbundle" to "bundle"
        )
        val result = analyzer.analyze(archive, nomad)
        assertEquals(ModPackageType.KNOWN_GAME_PROFILE, result.packageType)
        assertEquals(ModInstallOutcome.DIRECT_INSTALL_READY, result.outcome)
        assertTrue(result.plan.loaderRequirement == null)
        assertTrue(result.plan.mappings.any {
            it.destinationPath ==
                "/sdcard/Android/data/com.Warpfrog.BladeAndSorcery/files/Mods/Weapons/weapons.assetbundle"
        })
        assertFalse(result.plan.mappings.any {
            it.destinationPath.contains("/Mods/Weapons/Weapons/")
        })
        archive.delete()
    }

    @Test
    fun nomadRejectsStaleVersionAndMismatchedContentPackage() {
        val stale = zipOf(
            "NomadMod/manifest.json" to
                """{"name":"Nomad Mod","packageId":"com.Warpfrog.BladeAndSorcery","gameVersion":"1.0.6"}""",
            "NomadMod/module.json" to """{"name":"Nomad Mod","id":"nomad"}""",
            "NomadMod/mod.assetbundle" to "bundle"
        )
        val staleResult = analyzer.analyze(stale, nomad)
        assertFalse(staleResult.installable)
        assertTrue(staleResult.plan.preconditions.any {
            it.code == "GAME_VERSION_UNSUPPORTED"
        })
        stale.delete()

        val mismatch = zipOf(
            "NomadMod/manifest.json" to
                """{"name":"Nomad Mod","packageId":"com.Warpfrog.OtherGame","gameVersion":"1.0.7"}""",
            "NomadMod/module.json" to """{"name":"Nomad Mod","id":"nomad"}""",
            "NomadMod/mod.assetbundle" to "bundle"
        )
        val mismatchResult = analyzer.analyze(mismatch, nomad)
        assertFalse(mismatchResult.installable)
        assertTrue(mismatchResult.plan.preconditions.any {
            it.code == "TARGET_PACKAGE_MISMATCH"
        })
        mismatch.delete()
    }

    @Test
    fun unrelatedPcContentDoesNotBecomeNomadFromManifestName() {
        val archive = zipOf(
            "Windows/manifest.json" to
                """{"name":"Desktop Export","packageId":"com.Warpfrog.BladeAndSorcery","gameVersion":"1.0.7"}""",
            "Windows/Desktop.dll" to "not a Quest payload"
        )
        val result = analyzer.analyze(archive, nomad)
        assertFalse(result.installable)
        assertTrue(result.outcome == ModInstallOutcome.UNSUPPORTED ||
            result.outcome == ModInstallOutcome.UNSAFE_ARCHIVE)
        archive.delete()
    }

    @Test
    fun malformedOrUnrelatedSidecarDoesNotBecomeGameEvidence() {
        val malformed = zipOf(
            "NomadMod/manifest.json" to """{"name":"Nomad","version":""",
            "NomadMod/module.json" to """{"unrelated":true}""",
            "NomadMod/sidecar.assetbundle" to "bundle"
        )
        val malformedResult = analyzer.analyze(malformed, nomad)
        assertFalse(malformedResult.installable)
        assertFalse(malformedResult.packageType == ModPackageType.KNOWN_GAME_PROFILE)
        malformed.delete()

        val unrelated = zipOf(
            "NomadMod/manifest.json" to
                """{"name":"Nomad","packageId":"com.Warpfrog.BladeAndSorcery","gameVersion":"1.0.7"}""",
            "NomadMod/module.json" to """{"name":"Nomad","id":"nomad","version":"1"}""",
            "NomadMod/content.assetbundle" to "bundle",
            "Unrelated/sidecar.json" to """{"name":"other","files":["not a Nomad module"]}"""
        )
        val unrelatedResult = analyzer.analyze(unrelated, nomad)
        assertFalse(unrelatedResult.installable)
        assertTrue(unrelatedResult.plan.preconditions.any {
            it.code == "UNRELATED_ARCHIVE_CONTENT"
        })
        assertFalse(unrelatedResult.plan.mappings.any {
            it.sourcePath.startsWith("Unrelated/")
        })
        unrelated.delete()
    }

    @Test
    fun bonelabManagedCodeIsLoaderRequiredAndTraversalIsRejected() {
        val code = zipOf(
            "CodeMod/manifest.json" to
                """{"name":"Code","packageId":"com.StressLevelZero.BONELAB","version":"1.0.0"}""",
            "CodeMod/Plugin.dll" to "not a managed PE"
        )
        val codeResult = analyzer.analyze(code, bonelab)
        assertEquals(ModPackageType.BONELAB_CODE_MOD, codeResult.packageType)
        assertEquals(ModInstallOutcome.REQUIRES_MOD_LOADER, codeResult.outcome)
        assertTrue(codeResult.requiresModLoader)
        code.delete()

        val traversal = zipOf("../escape/manifest.json" to "{}")
        val traversalResult = analyzer.analyze(traversal, bonelab)
        assertEquals(ModInstallOutcome.UNSAFE_ARCHIVE, traversalResult.outcome)
        traversal.delete()
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("nfvr-content-profile-", ".zip")
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
        return file
    }
}