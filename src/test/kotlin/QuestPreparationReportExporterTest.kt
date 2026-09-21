import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject

class QuestPreparationReportExporterTest {
    private fun report(packageName: String) = QuestPreparationReport(
        app = InstalledQuestApp(packageName, "1.2.3", 12, packageName),
        inventory = QuestApkInventory(
            packageName,
            listOf(QuestApkArtifact("/data/app/$packageName/base.apk", null, 42, "a".repeat(64)))
        ),
        zipInspections = listOf(
            QuestZipInspection(
                valid = true,
                abiDirectories = setOf("arm64-v8a"),
                elfFiles = listOf("lib/arm64-v8a/libunity.so"),
                unityEvidence = true,
                il2cppEvidence = true,
                sourceRemotePath = "/data/app/$packageName/base.apk",
                sourceSha256 = "a".repeat(64)
            )
        )
    )

    @Test
    fun `json and Arabic technical text contain only target evidence`() {
        val dir = Files.createTempDirectory("quest-report-test-")
        val output = QuestPreparationReportExporter.export(
            listOf(report("com.beatgames.beatsaber"), report("com.example.unrelated")),
            dir,
            deviceSerial = "serial-technical-only"
        )
        val json = Files.readString(output.json)
        val text = Files.readString(output.text)
        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("com.beatgames.beatsaber"))
        assertFalse(json.contains("com.example.unrelated"))
        assertTrue(text.contains("تقرير تقني"))
        assertFalse(json.contains("obb"))
        assertFalse(json.contains("customer"))
    }

    @Test
    fun `writes atomically and leaves no partial files`() {
        val dir = Files.createTempDirectory("quest-report-atomic-")
        QuestPreparationReportExporter.export(report("com.AnotherAxiom.GorillaTag"), dir)
        assertTrue(Files.isRegularFile(dir.resolve(QuestPreparationReportExporter.JSON_FILE_NAME)))
        assertTrue(Files.isRegularFile(dir.resolve(QuestPreparationReportExporter.TXT_FILE_NAME)))
        assertTrue(Files.list(dir).use { stream ->
            stream.noneMatch { it.fileName.toString().endsWith(".partial") }
        })
    }

    @Test
    fun `refuses symlink destination and confinement escape`() {
        val dir = Files.createTempDirectory("quest-report-privacy-")
        val outside = Files.createTempFile("quest-report-outside-", ".txt")
        val link = dir.resolve(QuestPreparationReportExporter.JSON_FILE_NAME)
        try {
            Files.createSymbolicLink(link, outside)
        } catch (_: UnsupportedOperationException) {
            return
        } catch (_: java.nio.file.FileSystemException) {
            // Windows without symlink privilege (SeCreateSymbolicLinkPrivilege)
            // cannot create the fixture link; the exporter itself is not at
            // fault, so skip instead of failing the suite.
            return
        }
        assertFailsWith<IllegalArgumentException> {
            QuestPreparationReportExporter.export(report("com.StressLevelZero.BONELAB"), dir)
        }
        assertTrue(Files.size(outside) == 0L)
    }

    @Test
    fun `partial probe exports string warnings and parity with text`() {
        val dir = Files.createTempDirectory("quest-report-partial-")
        val probe = QuestPreparationProbeReport(
            device = QuestProbeDevice("serial", "Quest 3", "13", "arm64-v8a", listOf("arm64-v8a"), "arm64", true),
            discoveries = listOf(
                QuestProbeDiscovery("Gorilla Tag", ProbeTargetState.FOUND, emptyList()),
                QuestProbeDiscovery("Beat Saber", ProbeTargetState.NOT_FOUND, emptyList())
            ),
            games = listOf(
                QuestProbeGame(
                    "Gorilla Tag", "com.AnotherAxiom.GorillaTag",
                    preparationState = ProbePreparationState.UNKNOWN,
                    warnings = listOf("inspection failed: cancelled")
                )
            ),
            cancelled = true
        )
        val output = QuestPreparationReportExporter.export(probe, dir)
        val json = JSONObject(Files.readString(output.json))
        val text = Files.readString(output.text)
        assertTrue(json.getBoolean("partial"))
        assertTrue(json.getJSONArray("games").getJSONObject(0)
            .getJSONArray("warnings").getString(0).contains("cancelled"))
        assertTrue(text.contains("التقرير جزئي"))
        assertTrue(text.contains("inspection failed: cancelled"))
        assertTrue(text.contains("Gorilla Tag"))
        assertTrue(text.contains("Beat Saber").not())
    }

    @Test
    fun `obb metadata is bounded and contains no content`() {
        val dir = Files.createTempDirectory("quest-report-obb-")
        val obbEntries = (0..1002).map { RemoteEntry("/obb/$it.obb", it.toLong(), "2025-01-01") }
        val game = QuestProbeGame(
            "BONELAB", "com.StressLevelZero.BONELAB",
            obb = QuestProbeObb(true, true, obbEntries, totalBytes = 1234)
        )
        val report = QuestPreparationProbeReport(
            device = QuestProbeDevice("serial", null, null, null, emptyList(), null, true),
            discoveries = emptyList(), games = listOf(game)
        )
        val json = JSONObject(Files.readString(QuestPreparationReportExporter.export(report, dir).json))
        val obb = json.getJSONArray("games").getJSONObject(0).getJSONObject("obb")
        assertTrue(obb.getBoolean("truncated"))
        assertTrue(obb.getJSONArray("entries").length() <= 1000)
        assertFalse(obb.has("content"))
        assertFalse(obb.has("data"))
    }

    @Test
    fun `found discoveries export one canonical row even when inspection fails`() {
        val dir = Files.createTempDirectory("quest-report-four-games-")
        val discoveries = listOf(
            "Gorilla Tag" to "com.AnotherAxiom.GorillaTag",
            "Beat Saber" to "com.beatgames.beatsaber",
            "BONELAB" to "com.StressLevelZero.BONELAB",
            "Blade & Sorcery: Nomad" to "com.Warpfrog.BladeAndSorcery"
        ).map { (name, packageId) ->
            QuestProbeDiscovery(
                name,
                ProbeTargetState.FOUND,
                listOf(QuestProbeCandidate(packageId, null, listOf("known package")))
            )
        }
        val report = QuestPreparationProbeReport(
            device = QuestProbeDevice("serial", "Quest 3", "13", "arm64-v8a", emptyList(), "arm64", true),
            discoveries = discoveries,
            games = listOf(
                QuestProbeGame(
                    "Gorilla Tag",
                    "com.AnotherAxiom.GorillaTag",
                    warnings = listOf("APK inventory unavailable")
                )
            )
        )
        val root = JSONObject(Files.readString(QuestPreparationReportExporter.export(report, dir).json))
        val games = root.getJSONArray("games")
        assertTrue(games.length() == 4)
        for (i in 0 until games.length()) {
            val game = games.getJSONObject(i)
            assertTrue(game.has("discoveryState"))
            assertTrue(game.has("probeState"))
            assertTrue(game.has("versionName"))
            assertTrue(game.has("manifest"))
            assertTrue(game.has("signing"))
            assertTrue(game.has("obb"))
            assertTrue(game.has("androidData"))
            assertTrue(game.has("modData"))
            assertTrue(game.has("loaderEvidence"))
            assertTrue(game.has("warnings"))
        }
        assertTrue(root.getJSONArray("discoveries").getJSONObject(0).has("candidateIds"))
        assertTrue(
            games.getJSONObject(0).getString("discoveryState") == "FOUND"
        )
    }

    @Test
    fun `data-only readiness is exported separately from APK evidence`() {
        val dir = Files.createTempDirectory("quest-report-data-only-")
        val report = QuestPreparationProbeReport(
            device = QuestProbeDevice("serial", "Quest 3", "13", "arm64-v8a", emptyList(), "arm64", true),
            discoveries = listOf(
                QuestProbeDiscovery(
                    "BONELAB",
                    ProbeTargetState.FOUND,
                    listOf(QuestProbeCandidate("com.StressLevelZero.BONELAB", null, emptyList()))
                )
            ),
            games = listOf(
                QuestProbeGame(
                    "BONELAB",
                    "com.StressLevelZero.BONELAB",
                    contentModState = "READY",
                    apkInspectionFailureCode = "APK_SIGNATURE_SCAN_FAILED"
                )
            )
        )

        val game = JSONObject(Files.readString(QuestPreparationReportExporter.export(report, dir).json))
            .getJSONArray("games").getJSONObject(0)

        assertTrue(game.getString("contentModState") == "READY")
        assertTrue(game.getString("apkInspectionFailureCode") == "APK_SIGNATURE_SCAN_FAILED")
        assertTrue(game.getJSONObject("loaderEvidence").getString("contentModState") == "READY")
    }
}