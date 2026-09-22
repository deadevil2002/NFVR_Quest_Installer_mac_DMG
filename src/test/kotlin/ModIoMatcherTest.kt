import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for mod.io modfile-ID matching and sidecars.
 *
 * The Pavlov `taint` marker must contain the authoritative numeric
 * modfile ID.  It resolves ONLY by exact archive MD5 plus platform
 * approval from a `GET games/{game}/mods/{mod}/files` response.
 * Everything else yields null: taint is never invented.
 */
class ModIoMatcherTest {
    private fun apiResponse(): String = """
        {"data":[
          {"id":8239484,"mod_id":5883807,"version":"1.0",
           "filehash":{"md5":"3fdd763834fc01893cd5ca72e1825e77"},
           "filename":"modfile_5883807-6eef.zip",
           "platforms":[{"platform":"oculus","status":1},{"platform":"windows","status":1}],
           "live":true},
          {"id":8239400,"mod_id":5883807,"version":"0.9",
           "filehash":{"md5":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
           "filename":"old.zip",
           "platforms":[{"platform":"oculus","status":1}],
           "live":true},
          {"id":8239500,"mod_id":5883807,"version":"1.0",
           "filehash":{"md5":"3fdd763834fc01893cd5ca72e1825e77"},
           "filename":"win-only.zip",
           "platforms":[{"platform":"windows","status":1}],
           "live":true},
          {"id":8239510,"mod_id":5883807,"version":"1.0",
           "filehash":{"md5":"3fdd763834fc01893cd5ca72e1825e77"},
           "filename":"denied.zip",
           "platforms":[{"platform":"oculus","status":2}],
           "live":true},
          {"id":8239520,"mod_id":5883807,"version":"1.0",
           "filehash":{"md5":"3fdd763834fc01893cd5ca72e1825e77"},
           "filename":"targeted.zip",
           "platforms":[{"platform":"oculus","status":3}],
           "live":true}
        ]}
    """.trimIndent()

    @Test
    fun exactMd5PlusOculusResolvesAuthoritativeId() {
        val entries = parseModIoModfiles(apiResponse())
        assertEquals(5, entries.size)
        val match = matchModfileForTaint(entries, "3FDD763834FC01893CD5CA72E1825E77", "oculus")
        // Exact hash (case-insensitive) + approved platform wins; the
        // higher live ID among exact matches is preferred.
        assertTrue(match != null)
        assertEquals(8239520L, match.id)
    }

    @Test
    fun wrongMd5NeverProducesTaintId() {
        val entries = parseModIoModfiles(apiResponse())
        assertNull(matchModfileForTaint(entries, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "oculus"))
        assertNull(matchModfileForTaint(entries, "", "oculus"))
    }

    @Test
    fun platformFilteringRejectsNonOculusFile() {
        val windowsOnly = parseModIoModfiles(apiResponse()).filter { it.id == 8239500L }
        assertNull(matchModfileForTaint(windowsOnly, "3fdd763834fc01893cd5ca72e1825e77", "oculus"))
        assertTrue(matchModfileForTaint(windowsOnly, "3fdd763834fc01893cd5ca72e1825e77", "windows") != null)
        val denied = parseModIoModfiles(apiResponse()).filter { it.id == 8239510L }
        assertNull(matchModfileForTaint(denied, "3fdd763834fc01893cd5ca72e1825e77", "oculus"))
    }

    @Test
    fun malformedPayloadYieldsNothing() {
        assertTrue(parseModIoModfiles("").isEmpty())
        assertTrue(parseModIoModfiles("not json").isEmpty())
        assertTrue(parseModIoModfiles("""{"data":"nope"}""").isEmpty())
        assertNull(matchModfileForTaint(parseModIoModfiles("not json"), "abc", "oculus"))
    }

    @Test
    fun sidecarRoundTripsAndAuthorizesExactArchiveOnly() {
        val sidecar = ModIoSidecar(
            gameId = 1L,
            modId = 5883807L,
            modfileId = 8239484L,
            platform = "oculus",
            archiveSha256 = "a".repeat(64),
            archiveMd5 = "b".repeat(32),
            version = "1.0"
        )
        val parsed = ModIoSidecar.parse(sidecar.toJson())
        assertEquals(sidecar, parsed)
        assertEquals(8239484L, ModIoSidecar.authorizesTaint(parsed, "a".repeat(64), "b".repeat(32)))
        assertNull(ModIoSidecar.authorizesTaint(parsed, "c".repeat(64), "b".repeat(32)))
        assertNull(ModIoSidecar.authorizesTaint(parsed, "a".repeat(64), "d".repeat(32)))
        assertNull(ModIoSidecar.authorizesTaint(null, "a".repeat(64), "b".repeat(32)))
        assertNull(ModIoSidecar.parse("""{"schemaVersion":2}"""))
        assertNull(ModIoSidecar.parse("""{"schemaVersion":1}"""))
    }
}
