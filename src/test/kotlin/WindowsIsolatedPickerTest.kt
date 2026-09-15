import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.sun.jna.Pointer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun nativePickerUsesExplicitModernFolderAndZipFlagFamilies() {
        assertEquals(
            FOS_PICKFOLDERS or FOS_FORCEFILESYSTEM or FOS_PATHMUSTEXIST or FOS_NOCHANGEDIR,
            pickerOptions(DesktopChooserMode.DIRECTORY)
        )
        assertEquals(
            FOS_FORCEFILESYSTEM or FOS_PATHMUSTEXIST or FOS_NOCHANGEDIR or FOS_FILEMUSTEXIST,
            pickerOptions(DesktopChooserMode.FILES)
        )
    }

    @Test
    fun nativeAdapterBoundaryPropagatesSelectedCancelledAndHresultError() {
        val root = Files.createTempDirectory("nfvr-native-boundary-").toFile()
        try {
            val zip = File(root, "selected.zip").also { it.writeBytes(byteArrayOf(1)) }
            assertEquals(
                DesktopChooserResult.Selected(zip.absoluteFile),
                mapNativePickerOutput(
                    DesktopChooserMode.FILES,
                    NativePickerOutput.Selected(zip.path)
                )
            )
            assertEquals(
                DesktopChooserResult.Cancelled,
                mapNativePickerOutput(DesktopChooserMode.FILES, NativePickerOutput.Cancelled)
            )
            val failed = mapNativePickerOutput(
                DesktopChooserMode.FILES,
                NativePickerOutput.Failed(0x800704C7.toInt(), "dialog dismissed")
            )
            assertTrue(failed is DesktopChooserResult.Failed)
            assertTrue((failed as DesktopChooserResult.Failed).technicalMessage.contains("HRESULT=0x800704C7"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun comVtableArgumentsAlwaysPutThisPointerFirst() {
        val thisPointer = Pointer.createConstant(0x1234L)
        val args = comInvocationArguments(thisPointer, arrayOf("argument", 7))
        assertTrue(args[0] === thisPointer)
        assertEquals("argument", args[1])
        assertEquals(7, args[2])
    }

    @Test
    fun pickerTaskGateStaysBusyUntilDelayedNativeTaskCompletes() {
        val executor = Executors.newSingleThreadExecutor()
        val coordinator = PickerTaskCoordinator(executor)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        try {
            assertTrue(coordinator.tryAcquire())
            coordinator.submitAfterAcquire(
                task = {
                    started.countDown()
                    release.await(5, TimeUnit.SECONDS)
                },
                completed = { completed.countDown() }
            )
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(coordinator.isOpen())
            assertTrue(!coordinator.tryAcquire())
            release.countDown()
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            assertTrue(!coordinator.isOpen())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun pickerPathIsNormalizedWithoutCanonicalization() {
        val root = Files.createTempDirectory("nfvr-normalize-").toFile()
        val path = normalizePickerPath(File(root, "one/../two"))
        assertEquals(File(root, "two").absolutePath, path?.path)
        assertNull(normalizePickerPath("\u0000invalid"))
        root.deleteRecursively()
    }
}