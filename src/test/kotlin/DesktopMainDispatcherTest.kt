import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopMainDispatcherTest {
    @Test
    fun swingMainDispatcherIsAvailableAtRuntime() = runBlocking {
        var dispatched = false

        withContext(Dispatchers.Main) {
            dispatched = true
        }

        assertTrue(dispatched)
    }
}