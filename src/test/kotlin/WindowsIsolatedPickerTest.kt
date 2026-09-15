import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsIsolatedPickerTest {
    @Test
    fun parsesCancellationUnicodeAndMalformedPickerOutput() {
        assertEquals(
            IsolatedPickerOutput.Cancelled,
            parseIsolatedPickerOutput("\uFEFF \r\n")
        )
        assertEquals(
            IsolatedPickerOutput.Selected("C:\\Игры\\BONELAB"),
            parseIsolatedPickerOutput("\uFEFFC:\\Игры\\BONELAB\r\n")
        )
        assertTrue(
            parseIsolatedPickerOutput("C:\\one\r\nC:\\two") is IsolatedPickerOutput.Invalid
        )
        assertTrue(
            parseIsolatedPickerOutput("C:\\bad\u0000name") is IsolatedPickerOutput.Invalid
        )
    }

    @Test
    fun validatesActualUnicodeDirectoryAndZipResults() {
        val root = Files.createTempDirectory("nfvr-picker-اختبار-").toFile()
        try {
            val directory = File(root, "مجلد اللعبة").also { it.mkdirs() }
            val archive = File(root, "مود-اختبار.ZIP").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            assertNull(pickerSelectionError(DesktopChooserMode.DIRECTORY, directory))
            assertNull(pickerSelectionError(DesktopChooserMode.FILES, archive))
            assertTrue(
                pickerSelectionError(DesktopChooserMode.FILES, directory)
                    ?.contains("ZIP") == true
            )
            assertTrue(
                pickerSelectionError(DesktopChooserMode.DIRECTORY, archive)
                    ?.contains("directory") == true
            )
            assertTrue(
                pickerSelectionError(DesktopChooserMode.DIRECTORY, File(root, "لا-يوجد"))
                    ?.contains("directory") == true
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun timeoutAndCancellationWaitHelpersAreDistinct() {
        assertEquals(
            PickerWaitOutcome.TIMED_OUT,
            pickerWaitOutcome(0L, interrupted = false)
        )
        assertEquals(
            PickerWaitOutcome.CANCELLED,
            pickerWaitOutcome(1L, interrupted = true)
        )
        assertEquals(
            PickerWaitOutcome.WAITING,
            pickerWaitOutcome(1L, interrupted = false)
        )
    }

    @Test
    fun powershellPathIsPinnedToSystemRootAndNeverPathSearched() {
        val path = trustedWindowsPowerShellExecutable("C:\\Windows")
        assertEquals(
            File("C:\\Windows/System32/WindowsPowerShell/v1.0/powershell.exe").path,
            path?.path
        )
        assertNull(trustedWindowsPowerShellExecutable("relative-windows"))
        assertNull(trustedWindowsPowerShellExecutable(null))
    }
}