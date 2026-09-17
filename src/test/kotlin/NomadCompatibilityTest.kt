import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NomadCompatibilityTest {
    @Test
    fun frameworkManifestUsesFamilyCompatibilityInsteadOfRawStringEquality() {
        val result = NomadCompatibilityEvaluator.evaluate("1.0.0.0", "1.0.7")

        assertEquals(NomadCompatibilityState.COMPATIBLE_FAMILY, result.state)
        assertTrue(result.installAllowed)
        assertTrue(result.reason.contains("not publicly specified"))
    }

    @Test
    fun exactVersionsAreStrongestEvidence() {
        assertEquals(
            NomadCompatibilityState.EXACT,
            NomadCompatibilityEvaluator.evaluate("1.0.7", "1.0.7").state
        )
    }

    @Test
    fun differentMajorMinorFamilyIsIncompatible() {
        assertEquals(
            NomadCompatibilityState.INCOMPATIBLE,
            NomadCompatibilityEvaluator.evaluate("1.1.0.0", "1.0.7").state
        )
    }

    @Test
    fun unknownVersionRemainsWarningBecauseRuntimeSemanticsArePrivate() {
        assertEquals(
            NomadCompatibilityState.WARNING,
            NomadCompatibilityEvaluator.evaluate(null, "1.0.7").state
        )
    }
}