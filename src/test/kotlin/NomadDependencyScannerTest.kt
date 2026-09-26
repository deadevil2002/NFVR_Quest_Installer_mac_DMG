import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression tests for Nomad `$type` runtime dependency detection.
 *
 * Field-scoped extraction (only `$type` values) can never turn prose
 * commas into dependencies.  Base-game assemblies never false-positive;
 * same-archive and installed mods satisfy; genuinely missing assemblies
 * block the ready state with exact names.
 */
class NomadDependencyScannerTest {
    @Test
    fun shortAndLongTypeReferencesExtract() {
        val (refs, malformed) = extractNomadTypeReferences(
            """{"modules":[{"${"$"}type":"Framework_Pack.RecallModule, Framework Pack"}]}""",
            "a.json"
        )
        assertFalse(malformed)
        assertEquals(1, refs.size)
        assertEquals("Framework_Pack.RecallModule", refs.single().type)
        assertEquals("Framework Pack", refs.single().assembly)
        assertEquals(null, refs.single().version)
        val (longRefs, longBad) = extractNomadTypeReferences(
            """{"${"$"}type":"ThunderRoad.Manikin, ThunderRoad, Version=1.0.0.0, Culture=neutral"}""",
            "b.json"
        )
        assertFalse(longBad)
        assertEquals(1, longRefs.size)
        assertEquals("ThunderRoad.Manikin", longRefs.single().type)
        assertEquals("ThunderRoad", longRefs.single().assembly)
        assertEquals("1.0.0.0", longRefs.single().version)
    }

    @Test
    fun frameworkPackReferenceDetected() {
        val (refs, _) = extractNomadTypeReferences(
            """{"${"$"}type":"Framework_Pack.RecallModule, Framework Pack","speed":15}""",
            "Item_Weapon_HypersHammer.json"
        )
        assertTrue(refs.any { it.assembly == "Framework Pack" })
    }

    @Test
    fun proseCommasNeverBecomeDependencies() {
        val (refs, malformed) = extractNomadTypeReferences(
            """{"tags":"Spinnable, Jabbing","count":"1, 2, 3","flag":true,"n":42}""",
            "Item.json"
        )
        assertFalse(malformed)
        assertTrue(refs.isEmpty())
    }

    @Test
    fun malformedJsonHandledSafely() {
        val (refs, malformed) = extractNomadTypeReferences("{not json", "x.json")
        assertTrue(refs.isEmpty())
        assertFalse(malformed)
        val (refs2, malformed2) = extractNomadTypeReferences(
            """{"${"$"}type":""}""",
            "y.json"
        )
        assertTrue(refs2.isEmpty())
        assertTrue(malformed2)
        val (refs3, malformed3) = extractNomadTypeReferences(
            """{"${"$"}type":123}""",
            "z.json"
        )
        assertTrue(refs3.isEmpty())
        assertFalse(malformed3)
    }

    @Test
    fun baseGameAssembliesNeverFlagged() {
        val reports = resolveNomadAssemblyStatuses(
            listOf(
                NomadTypeReference("ThunderRoad.ItemData", "ThunderRoad", null, "a.json"),
                NomadTypeReference("UnityEngine.Keyframe", "UnityEngine.CoreModule", null, "a.json"),
                NomadTypeReference("ThunderRoad.Manikin", "ThunderRoad", "1.0.0.0", "c.json"),
                NomadTypeReference("Unity.ResourceManager", "Unity.ResourceManager", null, "c.json")
            ),
            emptyList(),
            null
        )
        assertTrue(reports.all { it.status == NomadAssemblyStatus.BASE_GAME })
    }

    @Test
    fun resolutionMatrix() {
        val index = NomadDependencyIndex(
            serial = "S",
            packageId = "com.Warpfrog.BladeAndSorcery",
            mods = listOf(
                NomadInstalledModRef("FrameworkPack", "Framework Pack", listOf("FrameworkPack.dll")),
                NomadInstalledModRef("ForceChoke", "ForceChoke", listOf("ForceChokeNomad.dll"))
            )
        )
        // Same archive wins even without an index entry.
        val sameArchive = resolveNomadAssemblyStatuses(
            listOf(NomadTypeReference("NanoTechArmory.Nano", "NanoTechArmory", null, "a.json")),
            listOf("NanoTechArmory.dll"),
            NomadDependencyIndex("S", "com.Warpfrog.BladeAndSorcery", emptyList())
        )
        assertEquals(NomadAssemblyStatus.SAME_ARCHIVE, sameArchive.single().status)
        // Installed manifest name and DLL basename both resolve.
        val installed = resolveNomadAssemblyStatuses(
            listOf(
                NomadTypeReference("Framework_Pack.RecallModule", "Framework Pack", null, "a.json"),
                NomadTypeReference("ForceChokeNomad.Foo", "ForceChokeNomad", null, "b.json")
            ),
            emptyList(),
            index
        )
        assertTrue(installed.all { it.status == NomadAssemblyStatus.INSTALLED_MOD })
        // Nothing known: missing with an index, unknown without one.
        val missing = resolveNomadAssemblyStatuses(
            listOf(NomadTypeReference("X.Y", "SomeOtherMod", null, "a.json")),
            emptyList(),
            index
        )
        assertEquals(NomadAssemblyStatus.MISSING, missing.single().status)
        val unknown = resolveNomadAssemblyStatuses(
            listOf(NomadTypeReference("X.Y", "SomeOtherMod", null, "a.json")),
            emptyList(),
            null
        )
        assertEquals(NomadAssemblyStatus.UNKNOWN, unknown.single().status)
    }

    @Test
    fun marvelLikeFixtureReportsFrameworkPackMissing() {
        val archive = zipOf(
            "MarvelMod/manifest.json" to
                """{"Name":"MarvelMod","Author":"A","ModVersion":"1.0","GameVersion":"1.0.0.0"}""",
            "MarvelMod/Items/Item_Hammer.json" to
                """{"${"$"}type":"ThunderRoad.ItemData, ThunderRoad","modules":[{"${"$"}type":"Framework_Pack.RecallModule, Framework Pack"}]}""",
            "MarvelMod/content.bundle" to "bundle"
        )
        try {
            val app = InstalledQuestApp(
                ModPackageAnalyzer.NOMAD_PACKAGE_ID, "1.3.1", 260730005L
            )
            val discovery = ModDirectoryDiscovery(
                serial = "SERIAL",
                packageId = app.packageName,
                candidates = listOf(
                    ModDirectoryCandidate(
                        packageId = app.packageName,
                        path = "/sdcard/Android/data/${app.packageName}/files/Mods",
                        exists = true,
                        source = "fixture"
                    )
                ),
                nomadDependencyIndex = NomadDependencyIndex("SERIAL", app.packageName, emptyList())
            )
            val analysis = ModPackageAnalyzer().analyze(archive, app, null, discovery)
            assertFalse(analysis.installable)
            assertTrue(
                analysis.installPlan.preconditions.any {
                    !it.satisfied && it.code == "NOMAD_MISSING_DEPENDENCY"
                },
                analysis.message
            )
            val report = analysis.installPlan.nomadDependencies
                .firstOrNull { it.assembly == "Framework Pack" }
            assertTrue(report != null)
            assertEquals(NomadAssemblyStatus.MISSING, report.status)
            // Base-game references never surface as dependencies.
            assertFalse(analysis.installPlan.nomadDependencies.any { it.assembly == "ThunderRoad" })
        } finally {
            archive.delete()
        }
    }

    @Test
    fun presentDependencyFixtureBecomesReady() {
        val file = File.createTempFile("nfvr-nomad-present-", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("NanoMod/manifest.json"))
            zip.write(
                """{"Name":"NanoMod","Author":"A","ModVersion":"1.0","GameVersion":"1.0.0.0"}"""
                    .toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("NanoMod/Items/Item_Nano.json"))
            zip.write(
                """{"${"$"}type":"NanoTechArmory.Nano, NanoTechArmory"}"""
                    .toByteArray(Charsets.UTF_8)
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("NanoMod/NanoTechArmory.dll"))
            zip.write(managedPeCliBytes())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("NanoMod/content.bundle"))
            zip.write("bundle".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        try {
            val app = InstalledQuestApp(
                ModPackageAnalyzer.NOMAD_PACKAGE_ID, "1.3.1", 260730005L
            )
            val analysis = ModPackageAnalyzer().analyze(file, app)
            assertTrue(analysis.installable, analysis.message)
            val report = analysis.installPlan.nomadDependencies
                .firstOrNull { it.assembly == "NanoTechArmory" }
            assertTrue(report != null)
            assertEquals(NomadAssemblyStatus.SAME_ARCHIVE, report.status)
        } finally {
            file.delete()
        }
    }

    private fun managedPeCliBytes(): ByteArray {
        val bytes = ByteArray(512)
        bytes[0] = 'M'.code.toByte()
        bytes[1] = 'Z'.code.toByte()
        fun le32(offset: Int, value: Int) {
            for (shift in 0 until 4) bytes[offset + shift] = ((value ushr (shift * 8)) and 0xff).toByte()
        }
        fun le16(offset: Int, value: Int) {
            bytes[offset] = (value and 0xff).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        }
        le32(0x3c, 64)
        bytes[64] = 'P'.code.toByte()
        bytes[65] = 'E'.code.toByte()
        bytes[66] = 0
        bytes[67] = 0
        le16(64 + 24, 0x10b)
        val cli = 64 + 24 + 96 + 14 * 8
        le32(cli, 0x2000)
        le32(cli + 4, 72)
        return bytes
    }

    @Test
    fun dependencyFreeContentRemainsReady() {
        val archive = zipOf(
            "CleanMod/manifest.json" to
                """{"Name":"CleanMod","Author":"A","ModVersion":"1.0","GameVersion":"1.0.0.0"}""",
            "CleanMod/Items/Item_Plain.json" to
                """{"${"$"}type":"ThunderRoad.ItemData, ThunderRoad"}""",
            "CleanMod/content.bundle" to "bundle"
        )
        try {
            val app = InstalledQuestApp(
                ModPackageAnalyzer.NOMAD_PACKAGE_ID, "1.3.1", 260730005L
            )
            val analysis = ModPackageAnalyzer().analyze(archive, app)
            assertTrue(analysis.installable, analysis.message)
            assertTrue(analysis.installPlan.nomadDependencies.isEmpty())
        } finally {
            archive.delete()
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = File.createTempFile("nfvr-nomad-dep-", ".zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return file
    }
}
