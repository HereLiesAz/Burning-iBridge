package dev.hereliesaz.burningibridge

import java.nio.file.*
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

data class EventLine(val time: String, val channel: String, val severity: String, val text: String) {
    fun format(): String = time + "  [" + channel + "] " + text
}

class LogBook(private val changed: () -> Unit = {}) {
    private val events = CopyOnWriteArrayList<EventLine>()
    private val stamp = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val logfile = Host.logs.resolve("session-" + DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneId.systemDefault()).format(Instant.now()) + ".log")

    fun record(line: String, channel: String = inferChannel(line)) {
        val severity = when {
            line.contains("ERROR", true) || line.startsWith("✗") || line.contains("failed", true) -> "ERROR"
            line.contains("warning", true) || line.contains("unavailable", true) -> "WARN"
            else -> "INFO"
        }
        val event = EventLine(stamp.format(Instant.now()), channel, severity, line)
        events.add(event)
        while (events.size > 2800) events.removeAt(0)
        try {
            Files.writeString(logfile, event.format() + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (_: Exception) { /* errors still visible in the live console */ }
        changed()
    }

    fun lines(channel: String = "ALL", query: String = "", level: String = "ALL"): List<EventLine> =
        events.filter { (channel == "ALL" || it.channel == channel) &&
            (level == "ALL" || it.severity == level) &&
            (query.isBlank() || it.format().contains(query, ignoreCase = true)) }

    fun snapshot(): List<EventLine> = events.toList()

    fun copyText(channel: String, query: String, level: String): String =
        lines(channel, query, level).joinToString("\n") { it.format() }

    companion object {
        fun inferChannel(line: String): String = when {
            line.startsWith("DEVICE:", true) -> "DEVICE"
            line.startsWith("SSH:", true) -> "SSH"
            line.startsWith("LINK:", true) -> "LINK"
            line.startsWith("TOOLS:", true) -> "TOOLS"
            line.contains("ssh", true) || line.contains("host key", true) || line.contains("SFTP", true) ||
                line.contains("remote", true) || line.contains("127.0.0.1", true) -> "SSH"
            line.contains("iproxy", true) || line.contains("tunnel", true) || line.contains("USB", true) -> "LINK"
            line.contains("palera1n", true) || line.contains("jailbreak", true) -> "DEVICE"
            line.contains("dyld", true) || line.contains("ipsw", true) -> "RESEARCH"
            line.contains("install", true) || line.contains("download", true) -> "TOOLS"
            else -> "SYSTEM"
        }

        fun redact(text: String): String = text
            .replace(Regex("(?i)(ECID|UDID|SERIAL|TOKEN|PASSWORD)\\s*[:=]\\s*[^\\s,;]+"), "$1=[REDACTED]")
            .replace(Regex("(?i)bearer\\s+[a-z0-9._~-]{12,}"), "Bearer [REDACTED]")
    }

    /** Redacted log bundles stay on disk until the user explicitly shares them. */
    fun exportBundle(destination: Path, channel: String = "ALL", query: String = "", level: String = "ALL"): Path {
        Files.createDirectories(destination.toAbsolutePath().parent)
        ZipOutputStream(Files.newOutputStream(destination)).use { zip ->
            fun add(entry: String, text: String) {
                zip.putNextEntry(ZipEntry(entry))
                zip.write(redact(text).toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
            add("activity.log", copyText(channel, query, level))
            add("README.txt", "Burning-iBridge diagnostic bundle.\nIdentifiers, tokens and passwords are best-effort redacted. Inspect before sharing.\n")
            val dirs = Files.list(Host.logs).use { it.filter(Files::isDirectory).sorted().toList().takeLast(25) }
            for (dir in dirs) {
                Files.walk(dir).use { walk ->
                    walk.filter { Files.isRegularFile(it) && Files.size(it) < 2_000_000 &&
                        (it.fileName.toString().endsWith(".log") || it.fileName.toString().endsWith(".txt")) }
                        .limit(100).forEach { file ->
                            try {
                                val relative = Host.logs.relativize(file).toString().replace('\\', '/')
                                add("runs/" + relative, Files.readString(file))
                            } catch (_: Exception) { /* unreadable diagnostics skipped */ }
                        }
                }
            }
        }
        return destination
    }
}

/** Watch only the selected top-level directory. Dropped files remain references, never execute automatically. */
class ScriptShelf(private val onChange: (List<ScriptItem>) -> Unit, private val onEvent: (String) -> Unit) : AutoCloseable {
    data class ScriptItem(val path: Path, val source: String, val changedAt: Long)
    private val propertiesFile = Host.data.resolve("settings.properties")
    private val settings = Properties()
    private val dropped = linkedSetOf<Path>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "script-shelf-watch").apply { isDaemon = true } }
    private var watchService: WatchService? = null
    @Volatile private var watching = false
    var folder: Path = Host.data.resolve("scripts")
        private set

    init {
        if (Files.exists(propertiesFile)) Files.newInputStream(propertiesFile).use(settings::load)
        folder = Path.of(settings.getProperty("scriptFolder", folder.toString())).toAbsolutePath().normalize()
        try { Files.createDirectories(folder) } catch (_: Exception) { folder = Files.createDirectories(Host.data.resolve("scripts")) }
        refresh()
        startWatcher()
    }

    @Synchronized fun setFolder(next: Path) {
        val destination = next.toAbsolutePath().normalize()
        require(Files.isDirectory(destination)) { "Choose an existing directory" }
        stopWatcher()
        folder = destination
        settings.setProperty("scriptFolder", folder.toString())
        Files.newOutputStream(propertiesFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            .use { settings.store(it, "Burning-iBridge script workspace") }
        refresh()
        startWatcher()
        onEvent("Now watching " + folder)
    }

    @Synchronized fun importFiles(files: List<Path>) {
        var count = 0
        for (path in files) {
            val abs = path.toAbsolutePath().normalize()
            if (isScript(abs) && Files.isRegularFile(abs)) {
                dropped.add(abs)
                count++
            }
        }
        refresh()
        onEvent("Added " + count + " dropped script(s); nothing was executed")
    }

    fun save(name: String, content: String): Path {
        val safe = name.replace(Regex("[^a-zA-Z0-9_-]"), "-").trim('-').ifBlank { "experiment" }
        val target = folder.resolve(safe + "-" + System.currentTimeMillis() + ".sh")
        Files.writeString(target, content, StandardOpenOption.CREATE_NEW)
        refresh()
        return target
    }

    @Synchronized fun refresh() {
        val collected = mutableListOf<ScriptItem>()
        if (Files.isDirectory(folder)) {
            Files.list(folder).use { files ->
                files.filter(::isScript).forEach { path ->
                    collected.add(ScriptItem(path, "WATCHED", mtime(path)))
                }
            }
        }
        dropped.removeIf { !Files.isRegularFile(it) }
        for (path in dropped) {
            if (collected.none { it.path == path }) collected.add(ScriptItem(path, "DROPPED", mtime(path)))
        }
        onChange(collected.sortedWith(compareByDescending<ScriptItem> { it.changedAt }.thenBy { it.path.name }))
    }

    private fun isScript(path: Path): Boolean =
        Files.isRegularFile(path) && path.extension.lowercase() in setOf("sh", "bash", "zsh", "py", "txt")
    private fun mtime(path: Path): Long = try { Files.getLastModifiedTime(path).toMillis() } catch (_: Exception) { 0L }

    @Synchronized private fun startWatcher() {
        val service = folder.fileSystem.newWatchService()
        watchService = service
        watching = true
        val targetFolder = folder
        worker.submit {
            try {
                targetFolder.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY)
                while (watching && watchService === service) {
                    val key = service.take()
                    key.pollEvents()
                    if (!key.reset()) break
                    refresh()
                }
            } catch (_: ClosedWatchServiceException) {
            } catch (error: Exception) {
                onEvent("Script watch failed: " + error.message)
            }
        }
    }

    @Synchronized private fun stopWatcher() {
        watching = false
        watchService?.close()
        watchService = null
    }

    override fun close() { stopWatcher(); worker.shutdownNow() }
}
