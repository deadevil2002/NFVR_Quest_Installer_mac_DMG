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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

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
    fun pickerLifecycleRevokesShowPermitOnlyWhileOpening() {
        val lifecycle = PickerLifecycle(DesktopChooserMode.FILES, 77L)
        val lease = PickerTaskLease(1L, lifecycle)
        lifecycle.transition(PickerLifecycleState.OPENING)
        assertTrue(lease.allowShow())
        assertEquals(PickerLifecycleState.OPEN, lifecycle.current())
        assertTrue(!lease.revokeIfOpening())
        assertTrue(lease.revoke())
    }

    @Test
    fun initializationTimeoutReturnsImmediatelyAndQuarantinesLateWorker() = runBlocking {
        val root = Files.createTempDirectory("nfvr-picker-timeout-").toFile()
        val zip = File(root, "second.zip").also { it.writeBytes(byteArrayOf(1)) }
        val started = CountDownLatch(1)
        val releaseLateWorker = CountDownLatch(1)
        val firstCallback = CountDownLatch(1)
        val secondCallback = CountDownLatch(1)
        val calls = AtomicInteger(0)
        val callbacks = AtomicInteger(0)
        val previousAdapter = WindowsIsolatedPicker.adapter
        val previousTimeout = WindowsIsolatedPicker.initializationWatchdogMillis
        val previousOs = System.getProperty("os.name")
        WindowsIsolatedPicker.initializationWatchdogMillis = 80L
        System.setProperty("os.name", "Windows 11")
        WindowsIsolatedPicker.adapter = object : WindowsNativePickerAdapter, NativePickerShowGate {
            override fun choose(
                mode: DesktopChooserMode,
                initialDirectory: File?,
                title: String,
                ownerHwnd: Long?
            ): NativePickerOutput = NativePickerOutput.Cancelled

            override fun chooseWithShowGate(
                mode: DesktopChooserMode,
                initialDirectory: File?,
                title: String,
                ownerHwnd: Long?,
                canShow: () -> Boolean
            ): NativePickerOutput {
                if (calls.incrementAndGet() == 1) {
                    started.countDown()
                    releaseLateWorker.await(5, TimeUnit.SECONDS)
                }
                return if (canShow()) {
                    NativePickerOutput.Selected(zip.path)
                } else {
                    NativePickerOutput.Failed(null, "late Show permit revoked")
                }
            }
        }
        try {
            val first = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                WindowsIsolatedPicker.chooseZipFile(
                    onNativeTaskComplete = {
                        callbacks.incrementAndGet()
                        firstCallback.countDown()
                    }
                )
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val timedOut = withTimeout(2_000L) { first.await() }
            assertTrue(timedOut is DesktopChooserResult.Failed)
            assertTrue(firstCallback.await(2, TimeUnit.SECONDS))
            assertEquals(1, callbacks.get())

            // The first STA worker is still blocked, but a new attempt must
            // not queue behind it after its lease was quarantined.
            val second = withTimeout(2_000L) {
                WindowsIsolatedPicker.chooseZipFile(
                    onNativeTaskComplete = {
                        callbacks.incrementAndGet()
                        secondCallback.countDown()
                    }
                )
            }
            assertEquals(DesktopChooserResult.Selected(zip.absoluteFile), second)
            assertTrue(secondCallback.await(2, TimeUnit.SECONDS))
            releaseLateWorker.countDown()
            assertEquals(2, callbacks.get())
        } finally {
            releaseLateWorker.countDown()
            WindowsIsolatedPicker.adapter = previousAdapter
            WindowsIsolatedPicker.initializationWatchdogMillis = previousTimeout
            if (previousOs == null) System.clearProperty("os.name")
            else System.setProperty("os.name", previousOs)
            root.deleteRecursively()
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