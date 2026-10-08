package dev.hereliesaz.burningibridge

import kotlinx.serialization.json.*
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import kotlin.io.path.name

object Host {
    val os: String = System.getProperty("os.name").lowercase()
    val arch: String = System.getProperty("os.arch").lowercase()
    val isMac = os.contains("mac")
    val isLinux = os.contains("linux")
    val platform: String = (if (isMac) "macos" else if (isLinux) "linux" else "unsupported") +
        "/" + (if (arch == "aarch64" || arch == "arm64") "arm64" else if (arch == "amd64" || arch == "x86_64") "x86_64" else arch)

    val data: Path = Path.of(System.getProperty("user.home"), ".burning-ibridge")
    val tools: Path = data.resolve("tools")
    val logs: Path = data.resolve("logs")
    val knownHosts: Path = data.resolve("known_hosts")

    init {
        Files.createDirectories(tools.resolve("bin"))
        Files.createDirectories(logs)
        if (!Files.exists(knownHosts)) Files.createFile(knownHosts)
    }

    fun find(name: String): Path? {
        val installed = when (name) {
            "palera1n" -> {
                val app = tools.resolve("palera1n.app/Contents/MacOS")
                if (Files.isDirectory(app)) Files.list(app).use { stream ->
                    stream.filter { Files.isRegularFile(it) && Files.isExecutable(it) }
                        .sorted(compareByDescending<Path> { it.fileName.toString().contains("palera1n", true) })
                        .findFirst().orElse(null)
                } else null
            }
            else -> null
        }
        if (installed != null) return installed
        val managed = tools.resolve("bin").resolve(name)
        if (Files.isExecutable(managed)) return managed
        if (name == "palera1n") {
            val old = Path.of(System.getProperty("user.home"), "Downloads", "palera1n-linux-x86_64", "bin", "palera1n")
            if (Files.isExecutable(old)) return old
        }
        val path = System.getenv("PATH").orEmpty().split(System.getProperty("path.separator"))
        for (dir in path + listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")) {
            val candidate = Path.of(dir, name)
            if (Files.isExecutable(candidate)) return candidate
        }
        return null
    }

    fun newLog(label: String): Path {
        val time = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC).format(Instant.now())
        val folder = logs.resolve(label.replace(Regex("[^a-zA-Z0-9_-]"), "-") + "-" + time)
        return Files.createDirectories(folder)
    }
}

class Jobs(private val output: (String) -> Unit) {
    // Parallel jobs allow a long dyld transfer without freezing SSH and UI operations.
    val executor = java.util.concurrent.Executors.newFixedThreadPool(4) { task ->
        Thread(task, "burning-ibridge-worker").also { it.isDaemon = true }
    }
    data class JobState(val id: Long, val name: String, val state: String, val startedAt: Long, val folder: String)
    private val sequence = java.util.concurrent.atomic.AtomicLong()
    private val states = java.util.concurrent.ConcurrentHashMap<Long, JobState>()
    @Volatile var onJobsChanged: ((List<JobState>) -> Unit)? = null
    fun snapshot(): List<JobState> = states.values.sortedByDescending { it.id }
    private fun publish() { onJobsChanged?.invoke(snapshot()) }


    fun submit(title: String, work: (Path) -> Unit) {
        val id = sequence.incrementAndGet()
        states[id] = JobState(id, title, "QUEUED", System.currentTimeMillis(), "")
        publish()
        executor.execute {
            val dir = Host.newLog(title)
            states[id] = JobState(id, title, "RUNNING", System.currentTimeMillis(), dir.toString())
            publish()
            output("▶ " + title + " — logs: " + dir)
            try {
                work(dir)
                states[id] = states.getValue(id).copy(state = "SUCCESS")
                output("✓ Finished: " + title)
            } catch (error: Exception) {
                Files.writeString(dir.resolve("ERROR.txt"), error.stackTraceToString())
                states[id] = states.getValue(id).copy(state = "FAILED")
                output("✗ " + title + ": " + (error.message ?: error.javaClass.simpleName))
            } finally {
                // Retain recent history for the activity view.
                if (states.size > 40) states.keys.sorted().take(states.size - 40).forEach(states::remove)
                publish()
            }
        }
    }

    fun run(command: List<String>, folder: Path, logName: String = "command.log", environment: Map<String, String> = emptyMap()): Int {
        require(command.isNotEmpty())
        val logfile = folder.resolve(logName)
        Files.writeString(logfile, "COMMAND: " + command.joinToString(" ") + "\n")
        output("⌁ " + command.joinToString(" "))
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        pb.environment().putAll(environment)
        val process = pb.start()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                Files.writeString(logfile, line + "\n", java.nio.file.StandardOpenOption.APPEND)
                output(line.take(400))
            }
        }
        val exit = process.waitFor()
        Files.writeString(logfile, "EXIT: " + exit + "\n", java.nio.file.StandardOpenOption.APPEND)
        if (exit != 0) throw IllegalStateException("Command exited " + exit + " (see " + logfile + ")")
        return exit
    }
}

class Installer(private val message: (String) -> Unit, private val jobs: Jobs) {
    private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
    private val json = Json { ignoreUnknownKeys = true }

    private fun requestText(url: String): String {
        val req = HttpRequest.newBuilder(URI(url)).header("User-Agent", "Burning-iBridge/0.1").GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        check(res.statusCode() == 200) { "GitHub HTTP " + res.statusCode() + ": " + url }
        return res.body()
    }

    data class Asset(val name: String, val url: String, val sha256: String)
    fun releaseAsset(tool: String): Asset {
        check(Host.isLinux || Host.isMac) { "Only Linux and macOS are supported" }
        val repo = if (tool == "ipsw") "blacktop/ipsw" else "palera1n/palera1n"
        val endpoint = if (tool == "ipsw") "latest" else "tags/v3.0.0-beta.2"
        val release = json.parseToJsonElement(requestText("https://api.github.com/repos/" + repo + "/releases/" + endpoint)).jsonObject
        val version = release.getValue("tag_name").jsonPrimitive.content.removePrefix("v")
        val wanted = if (tool == "palera1n") {
            if (Host.isMac) "palera1n-macos-universal.dmg"
            else "palera1n-linux-" + (if (Host.arch == "aarch64" || Host.arch == "arm64") "arm64" else "x86_64") + ".tar.gz"
        } else {
            "ipsw_" + version + "_" + (if (Host.isMac) "macOS_universal" else if (Host.arch == "aarch64" || Host.arch == "arm64") "linux_arm64" else "linux_x86_64") + ".tar.gz"
        }
        val assets = release.getValue("assets").jsonArray.map { it.jsonObject }
        val asset = assets.firstOrNull { it.getValue("name").jsonPrimitive.content == wanted }
            ?: error("No official release asset " + wanted + "; browse " + repo + " releases")
        val url = asset.getValue("browser_download_url").jsonPrimitive.content
        require(URI(url).scheme == "https" && URI(url).host == "github.com") { "Unexpected download origin" }
        var digest = asset["digest"]?.jsonPrimitive?.contentOrNull?.removePrefix("sha256:").orEmpty()
        if (!digest.matches(Regex("[0-9a-fA-F]{64}")) && tool == "ipsw") {
            val sums = assets.firstOrNull { it["name"]?.jsonPrimitive?.content == "checksums.txt" }
            if (sums != null) {
                val checksumText = requestText(sums.getValue("browser_download_url").jsonPrimitive.content)
                digest = checksumText.lineSequence().map { it.trim() }.firstOrNull { it.endsWith(wanted) }
                    ?.split(Regex("\\s+"))?.firstOrNull().orEmpty()
            }
        }
        require(digest.matches(Regex("[0-9a-fA-F]{64}"))) {
            "No verifiable SHA-256 provided for " + wanted + ". Refusing unverified installation."
        }
        return Asset(wanted, url, digest.lowercase())
    }

    fun install(tool: String) {
        jobs.submit("install-" + tool) { dir ->
            val asset = releaseAsset(tool)
            message("Downloading verified " + asset.name)
            val archive = dir.resolve(asset.name)
            val response = http.send(
                HttpRequest.newBuilder(URI(asset.url)).header("User-Agent", "Burning-iBridge/0.1").GET().build(),
                HttpResponse.BodyHandlers.ofFile(archive)
            )
            check(response.statusCode() == 200) { "Download HTTP " + response.statusCode() }
            val actual = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive))
                .joinToString("") { "%02x".format(it) }
            check(actual.equals(asset.sha256, true)) { "SHA-256 mismatch. Download retained at " + archive }
            Files.writeString(dir.resolve("checksum.txt"), asset.name + "  sha256:" + actual + "\n")
            message("Verified SHA-256: " + actual)
            if (asset.name.endsWith(".dmg")) {
                installMacDmg(archive, dir)
            } else {
                val executable = Host.tools.resolve("bin").resolve(tool)
                val temporary = executable.resolveSibling(tool + ".incoming")
                var found = false
                TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive.toFile())))).use { tar ->
                    while (true) {
                        val entry = tar.nextEntry ?: break
                        if (entry.isFile && entry.name.substringAfterLast('/') == tool) {
                            Files.copy(tar, temporary, StandardCopyOption.REPLACE_EXISTING)
                            found = true
                            break
                        }
                    }
                }
                check(found) { "Executable '" + tool + "' not found inside verified archive" }
                check(temporary.toFile().setExecutable(true, true)) { "Cannot mark tool executable" }
                Files.move(temporary, executable, StandardCopyOption.REPLACE_EXISTING)
                message("Installed: " + executable)
            }
        }
    }

    private fun installMacDmg(dmg: Path, folder: Path) {
        check(Host.isMac)
        val mount = Files.createDirectories(folder.resolve("mounted"))
        val attach = ProcessBuilder("hdiutil", "attach", "-readonly", "-nobrowse", "-mountpoint", mount.toString(), dmg.toString())
            .inheritIO().start()
        check(attach.waitFor() == 0) { "Cannot mount verified palera1n disk image" }
        try {
            val app = Files.walk(mount).use { stream ->
                stream.filter { it.fileName.toString().endsWith(".app") && Files.isDirectory(it) }.findFirst().orElse(null)
            } ?: error("No .app bundle in palera1n DMG")
            val destination = Host.tools.resolve("palera1n.app")
            val incoming = Host.tools.resolve("palera1n.incoming.app")
            if (Files.exists(incoming)) incoming.toFile().deleteRecursively()
            val copy = ProcessBuilder("ditto", app.toString(), incoming.toString()).inheritIO().start()
            check(copy.waitFor() == 0) { "Cannot copy palera1n application bundle" }
            if (Files.exists(destination)) destination.toFile().deleteRecursively()
            Files.move(incoming, destination)
            message("Installed: " + destination)
        } finally {
            ProcessBuilder("hdiutil", "detach", mount.toString()).inheritIO().start().waitFor()
        }
    }

    fun installSystemHelpers() {
        jobs.submit("usb-helpers") { dir ->
            when {
                Host.isLinux -> {
                    val pkexec = Host.find("pkexec") ?: error("pkexec is required for graphical package installation. Install polkit or use your distribution's package manager.")
                    jobs.run(listOf(pkexec.toString(), "apt-get", "install", "-y",
                        "usbmuxd", "libusbmuxd-tools", "libimobiledevice-utils", "irecovery", "idevicerestore", "usbutils", "openssh-client"), dir)
                }
                Host.isMac -> {
                    val brew = Host.find("brew") ?: error("Homebrew is not installed; see https://brew.sh")
                    jobs.run(listOf(brew.toString(), "install", "libusbmuxd", "libimobiledevice", "libirecovery", "idevicerestore"), dir)
                }
                else -> error("Unsupported operating system")
            }
        }
    }
}
