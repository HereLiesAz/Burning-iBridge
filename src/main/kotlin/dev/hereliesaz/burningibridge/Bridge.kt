package dev.hereliesaz.burningibridge

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.io.path.name

val remotePresets: LinkedHashMap<String, String> = linkedMapOf(
    "Identity" to "id; uname -a",
    "EFI manager" to "ioreg -p IOService -r -c MacEFIManager -l -w 0",
    "EFI user clients" to "ioreg -p IOService -r -c MacEFIManagerUserClient -l -w 0",
    "SEP manager" to "ioreg -p IOService -r -c AppleSEPManager -l -w 0",
    "Credential manager" to "ioreg -p IOService -r -c AppleCredentialManager -l -w 0",
    "Boot properties" to "ioreg -p IODeviceTree -r -n chosen -l -w 0",
    "NVRAM (read only)" to "nvram -p",
    "dyld cache inventory" to "ls -ln /System/Library/Caches/com.apple.dyld/dyld_shared_cache_arm64*",
    "Running EFI clients" to "ps -A -o pid,comm"
)

class Bridge(private val jobs: Jobs, private val out: (String) -> Unit) {
    @Volatile private var proxy: Process? = null
    @Volatile var sshPort = 2233
    @Volatile var password: String = ""

    private fun session(): Session {
        val jsch = JSch()
        jsch.setKnownHosts(Host.knownHosts.toString())
        val session = jsch.getSession("root", "127.0.0.1", sshPort)
        if (password.isNotEmpty()) session.setPassword(password)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "publickey,password,keyboard-interactive")
        session.connect(15000)
        return session
    }

    private var candidateKey: String? = null
    fun fingerprint() {
        jobs.submit("ssh-host-key") { dir ->
            val keyscan = Host.find("ssh-keyscan") ?: error("OpenSSH ssh-keyscan not installed")
            val p = ProcessBuilder(keyscan.toString(), "-T", "6", "-p", sshPort.toString(), "127.0.0.1")
                .redirectErrorStream(true).start()
            val scan = p.inputStream.bufferedReader().readText()
            p.waitFor(9, TimeUnit.SECONDS)
            if (p.isAlive) p.destroyForcibly()
            Files.writeString(dir.resolve("ssh-keyscan.log"), scan)
            val key = scan.lineSequence().filter { it.startsWith("[127.0.0.1]:") }
                .firstOrNull { it.contains("ssh-ed25519") }
                ?: scan.lineSequence().firstOrNull { it.startsWith("[127.0.0.1]:") }
                ?: error("No SSH host key returned. Check iproxy and device state.")
            val fields = key.split(Regex("\\s+"))
            require(fields.size >= 3)
            val digest = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(fields[2]))
            val fingerprint = Base64.getEncoder().withoutPadding().encodeToString(digest)
            candidateKey = key
            out("Host key (" + fields[1] + "): SHA256:" + fingerprint)
            out("Check this fingerprint independently. Then click 'Trust displayed key' if correct.")
        }
    }

    fun trustKey() {
        val key = candidateKey ?: error("Inspect SSH fingerprint first")
        val existing = Files.readString(Host.knownHosts)
        if (!existing.lineSequence().contains(key)) {
            Files.writeString(Host.knownHosts, key + "\n", StandardOpenOption.APPEND)
        }
        out("Trusted one SSH host key in " + Host.knownHosts)
        candidateKey = null
    }

    fun startProxy() {
        val current = proxy
        if (current != null && current.isAlive) { out("Managed iproxy already running"); return }
        val tool = Host.find("iproxy") ?: error("iproxy missing; install USB helper tools")
        val p = ProcessBuilder(tool.toString(), sshPort.toString(), "44").redirectErrorStream(true).start()
        proxy = p
        out("iproxy PID " + p.pid() + " maps localhost:" + sshPort + " -> T2:44")
        Thread {
            p.inputStream.bufferedReader().useLines { it.forEach { line -> out("iproxy: " + line) } }
            out("Managed iproxy stopped (exit " + p.waitFor() + ")")
        }.apply { isDaemon = true }.start()
    }

    fun stopProxy() {
        proxy?.destroy()
        proxy = null
        out("Stop requested for managed iproxy (other iproxy processes are untouched)")
    }

    fun runRemote(name: String, script: String) {
        jobs.submit(name) { dir ->
            Files.writeString(dir.resolve("script.sh"), "#!/bin/sh\n" + script + "\n")
            val ssh = session()
            try {
                val command = ssh.openChannel("exec") as ChannelExec
                command.setCommand("/bin/sh -s")
                command.setInputStream(ByteArrayInputStream((script + "\n").toByteArray()))
                command.setErrStream(java.io.ByteArrayOutputStream())
                val logfile = dir.resolve("remote.log")
                command.connect(12000)
                command.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        Files.writeString(logfile, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                        out(line.take(500))
                    }
                }
                var count = 0
                while (!command.isClosed && count++ < 100) Thread.sleep(100)
                val exit = command.exitStatus
                command.disconnect()
                out("Remote exit code: " + exit + "; " + logfile)
                if (exit != 0) error("Remote command failed; inspect " + logfile)
            } finally { ssh.disconnect() }
        }
    }

    fun fetchDyld() {
        jobs.submit("fetch-dyld") { dir ->
            val target = Files.createDirectories(dir.resolve("dyld"))
            val ssh = session()
            try {
                val sftp = ssh.openChannel("sftp") as ChannelSftp
                sftp.connect(15000)
                try {
                    val remote = "/System/Library/Caches/com.apple.dyld/"
                    val entries = sftp.ls(remote).map { it as ChannelSftp.LsEntry }
                        .filter { it.filename.startsWith("dyld_shared_cache_arm64") && !it.attrs.isDir }
                    check(entries.isNotEmpty()) { "No dyld cache files on T2" }
                    entries.forEach { entry ->
                        val length = entry.attrs.size
                        require(length in 1..1_073_741_824L) { "Unexpected cache size for " + entry.filename }
                        out("SFTP: " + entry.filename + " (" + length + " bytes)")
                        sftp.get(remote + entry.filename, target.resolve(entry.filename).toString())
                        check(Files.size(target.resolve(entry.filename)) == length) { "Incomplete download " + entry.filename }
                    }
                    Files.writeString(dir.resolve("files.txt"), entries.joinToString("\n") { it.filename } + "\n")
                } finally { sftp.disconnect() }
            } finally { ssh.disconnect() }
            out("Fetched dyld files: " + target)
            out("Do not publish raw caches or device identifiers from logs.")
        }
    }

    fun analyzeDyld(cache: String) {
        val file = Path.of(cache).toAbsolutePath().normalize()
        require(Files.isRegularFile(file)) { "Select the locally downloaded dyld_shared_cache_arm64 file" }
        val ipsw = Host.find("ipsw") ?: error("Install ipsw in Tools first")
        jobs.submit("ipsw-dyld") { dir ->
            val cmds = linkedMapOf(
                "01-image.log" to listOf(ipsw.toString(), "dyld", "image", file.toString(), "libMacEFIHostInterface", "-V"),
                "02-symbols.log" to listOf(ipsw.toString(), "dyld", "macho", file.toString(), "libMacEFIHostInterface", "--symbols"),
                "03-set-variable.log" to listOf(ipsw.toString(), "dyld", "symaddr", file.toString(), "setNVRAMVariable", "--all"),
                "04-get-variable.log" to listOf(ipsw.toString(), "dyld", "symaddr", file.toString(), "getNVRAMVariable", "--all")
            )
            cmds.forEach { (name, argv) ->
                try { jobs.run(argv, dir, name) }
                catch (error: Exception) { out("Check " + name + ": " + error.message) }
            }
            out("Analysis logs: " + dir)
            out("Names/symbols are evidence only; they do not establish write authorization.")
        }
    }

    fun launchPalera1n() {
        val exe = Host.find("palera1n") ?: error("Install palera1n in Tools first")
        val dir = Host.newLog("palera1n")
        val logfile = dir.resolve("palera1n.log")
        val args = listOf(exe.toString(), "--cli", "-f", "-d").joinToString(" ") { quote(it) }
        val script = dir.resolve("launch.sh")
        val dollar = '$'
        val contents = "#!/usr/bin/env bash\nset -o pipefail\necho 'Burning-iBridge: palera1n CLI (rootful, debug)'\n" +
            "sudo " + args + " 2>&1 | tee " + quote(logfile.toString()) + "\n" +
            "rc=" + dollar + "{PIPESTATUS[0]}\n" +
            "echo EXIT_STATUS=" + dollar + "rc\n" +
            "read -r -p 'Press Enter to close...' ignored || true\nexit " + dollar + "rc\n"
        Files.writeString(script, contents)
        script.toFile().setExecutable(true)
        out("Launching palera1n in an interactive terminal; logfile: " + logfile)
        val terminal = when {
            Host.isMac -> listOf("open", "-a", "Terminal", script.toString())
            Host.find("konsole") != null -> listOf("konsole", "-e", "bash", script.toString())
            Host.find("gnome-terminal") != null -> listOf("gnome-terminal", "--", "bash", script.toString())
            Host.find("xterm") != null -> listOf("xterm", "-e", "bash", script.toString())
            else -> error("No terminal emulator found. Run: bash " + script)
        }
        ProcessBuilder(terminal).start()
    }

    fun probeUsb() {
        jobs.submit("usb-detect") { dir ->
            if (Host.isLinux) {
                val lsusb = Host.find("lsusb") ?: error("Install usbutils")
                jobs.run(listOf(lsusb.toString(), "-d", "05ac:"), dir)
            } else {
                jobs.run(listOf("system_profiler", "SPUSBDataType"), dir)
            }
        }
    }

    fun osChecksum(imagePath: String) {
        val path = Path.of(imagePath)
        require(Files.isRegularFile(path)) { "Select an existing OS ISO/image" }
        jobs.submit("os-image-sha256") { dir ->
            val md = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val bytes = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(bytes)
                    if (n < 0) break
                    md.update(bytes, 0, n)
                }
            }
            val hash = md.digest().joinToString("") { "%02x".format(it) }
            Files.writeString(dir.resolve("sha256.txt"), hash + "  " + path.fileName + "\n")
            out("OS image SHA-256: " + hash)
            out("Compare the hash with the OS vendor's official checksum.")
        }
    }

    companion object {
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
