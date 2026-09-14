import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object OwnedProcessRegistry {
    private val processes = ConcurrentHashMap.newKeySet<Process>()

    fun add(process: Process) {
        processes.add(process)
    }

    fun remove(process: Process) {
        processes.remove(process)
    }

    fun destroyAll() {
        processes.toList().forEach { process ->
            runCatching {
                if (process.isAlive) process.destroyForcibly()
            }
        }
        processes.clear()
    }
}

fun runOwnedCommand(command: List<String>, timeoutMs: Long = 15_000): List<String> {
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    OwnedProcessRegistry.add(process)
    return try {
        val output = mutableListOf<String>()
        val readerThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.take(2_000).forEach(output::add)
            }
        }
        readerThread.start()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        readerThread.join(5_000)
        output.toList()
    } finally {
        if (process.isAlive) process.destroyForcibly()
        OwnedProcessRegistry.remove(process)
    }
}