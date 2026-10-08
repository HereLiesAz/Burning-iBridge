package dev.hereliesaz.burningibridge

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
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
    @Volatile var jailbreakState: String = "IDLE"
        private set
    @Volatile var jailbreakStartedAt: Long = 0L
        private set
    @Volatile var jailbreakLastOutputAt: Long = 0L
        private set
    @Volatile var jailbreakLog: Path? = null
        private set
    @Volatile private var jailbreakPidFile: Path? = null
    @Volatile private var jailbreakExitFile: Path? = null

    @Volatile var installHelpers: ((Path) -> Unit)? = null
    @Volatile var installIpsw: ((Path) -> Unit)? = null
    @Volatile var linkState: String = "DISCONNECTED"
        private set
    @Volatile var controlState: String = "OFFLINE"
        private set
    @Volatile var monitorState: String = "OFFLINE"
        private set
    @Volatile var hostFingerprint: String = ""
        private set
    @Volatile private var control: Session? = null
    @Volatile private var monitor: Session? = null

    val tunnelRunning: Boolean get() = proxy?.isAlive == true || linkState == "EXTERNAL_TUNNEL"
    private fun status(state: String) {
        linkState = state
        out("SSH: connection state -> " + state)
    }

    private fun connectBoth() {
        if (control?.isConnected == true && monitor?.isConnected == true) {
            status("READY")
            return
        }
        control?.disconnect()
        monitor?.disconnect()
        control = null
        monitor = null
        controlState = "CONNECTING"
        monitorState = "WAITING"
        try {
            val one = session()
            control = one
            controlState = "ONLINE"
            out("SSH: channel 1 / control authenticated")
            val two = session()
            monitor = two
            monitorState = "ONLINE"
            out("SSH: channel 2 / research authenticated")
            status("READY")
        } catch (e: Exception) {
            control?.disconnect()
            monitor?.disconnect()
            control = null
            monitor = null
            controlState = "OFFLINE"
            monitorState = "OFFLINE"
            val message = e.message.orEmpty()
            if (message.contains("reject HostKey", true) || message.contains("UnknownHostKey", true) ||
                message.contains("HostKey has been changed", true)) {
                status("HOST_KEY_APPROVAL")
                fingerprintNow(Host.newLog("host-key"))
            } else {
                status("SSH_ERROR")
                throw e
            }
        }
    }

    @Synchronized private fun ensureReady(dir: Path, helper: ((Path) -> Unit)? = installHelpers) {
        if (linkState == "READY" && control?.isConnected == true && monitor?.isConnected == true) return
        status("PREPARING")
        if (Host.find("iproxy") == null || Host.find("ssh-keyscan") == null) {
            out("SSH: provisioning required helpers before connecting")
            (helper ?: error("USB helper provisioner is not configured")).invoke(dir)
        }
        status("STARTING_TUNNEL")
        startProxy()
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && !portOpen()) Thread.sleep(250)
        check(portOpen()) { "SSH tunnel did not open: verify T2 USB state" }
        status("AUTHENTICATING")
        connectBoth()
        check(linkState == "READY") { "Host-key approval required in Connection panel" }
    }

    fun connectManaged(ensureTools: (Path) -> Unit) {
        jobs.submit("connect-bridge") { dir -> ensureReady(dir, ensureTools) }
    }

    fun refreshConnectionState() {
        if (linkState == "READY" && (control?.isConnected != true || monitor?.isConnected != true)) {
            controlState = if (control?.isConnected == true) "ONLINE" else "OFFLINE"
            monitorState = if (monitor?.isConnected == true) "ONLINE" else "OFFLINE"
            status("DISCONNECTED")
        }
    }

    private fun portOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", sshPort), 500); true }
    } catch (_: Exception) { false }

    fun connectAfterApproval() {
        jobs.submit("ssh-two-channels") { _ -> connectBoth() }
    }
    private fun controlSession(): Session = control?.takeIf { it.isConnected } ?: session()
    private fun researchSession(): Session = monitor?.takeIf { it.isConnected } ?: session()


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
        jobs.submit("ssh-host-key") { dir -> fingerprintNow(dir) }
    }
    private fun fingerprintNow(dir: Path) {
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
            hostFingerprint = "SHA256:" + fingerprint
            out("SSH: Host key (" + fields[1] + "): SHA256:" + fingerprint)
            out("SSH: confirm fingerprint before authenticating")
    }

    fun trustKey() {
        val key = candidateKey ?: error("Inspect SSH fingerprint first")
        val existing = Files.readString(Host.knownHosts)
        if (!existing.lineSequence().contains(key)) {
            Files.writeString(Host.knownHosts, key + "\n", StandardOpenOption.APPEND)
        }
        out("Trusted one SSH host key in " + Host.knownHosts)
        candidateKey = null
        connectAfterApproval()
    }

    fun startProxy() {
        val current = proxy
        if (current != null && current.isAlive) { out("Managed iproxy already running"); return }
        val alreadyListening = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", sshPort), 400)
                true
            }
        } catch (_: Exception) { false }
        if (alreadyListening) {
            status("EXTERNAL_TUNNEL")
            out("SSH: Port " + sshPort + " has a listener; reusing existing proxy")
            return
        }
        val tool = Host.find("iproxy") ?: error("iproxy missing; install USB helper tools")
        val p = ProcessBuilder(tool.toString(), sshPort.toString(), "44").redirectErrorStream(true).start()
        proxy = p
        status("TUNNEL_READY")
        out("SSH: iproxy PID " + p.pid() + " maps localhost:" + sshPort + " -> T2:44")
        Thread {
            p.inputStream.bufferedReader().useLines { it.forEach { line -> out("iproxy: " + line) } }
            out("Managed iproxy stopped (exit " + p.waitFor() + ")")
        }.apply { isDaemon = true }.start()
    }

    fun stopProxy() {
        control?.disconnect(); control = null
        monitor?.disconnect(); monitor = null
        controlState = "OFFLINE"; monitorState = "OFFLINE"
        proxy?.destroy()
        proxy = null
        status("DISCONNECTED")
        out("SSH: managed proxy stopped; external processes untouched")
    }

    fun runRemote(name: String, script: String) {
        jobs.submit(name) { dir ->
            Files.writeString(dir.resolve("script.sh"), "#!/bin/sh\n" + script + "\n")
            ensureReady(dir)
            val ssh = controlSession()
            try {
                val command = ssh.openChannel("exec") as ChannelExec
                out("SSH: running " + name + " through control channel")
                command.setCommand("/bin/sh -s")
                command.setInputStream(ByteArrayInputStream((script + "\n").toByteArray()))
                val errors = ByteArrayOutputStream()
                command.setErrStream(errors)
                val logfile = dir.resolve("remote.log")
                command.connect(12000)
                command.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        Files.writeString(logfile, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                        out("SSH: " + line.take(500))
                    }
                }
                var count = 0
                while (!command.isClosed && count++ < 100) Thread.sleep(100)
                val stderr = errors.toString(Charsets.UTF_8)
                if (stderr.isNotBlank()) {
                    Files.writeString(logfile, stderr, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                    stderr.lineSequence().forEach { if (it.isNotEmpty()) out("SSH: stderr: " + it.take(400)) }
                }
                val exit = command.exitStatus
                command.disconnect()
                out("SSH: remote exit code: " + exit + "; " + logfile)
                if (exit != 0) error("Remote command failed; inspect " + logfile)
            } finally { if (ssh !== control && ssh !== monitor) ssh.disconnect() }
        }
    }

    fun fetchDyld() {
        jobs.submit("fetch-dyld") { dir ->
            val target = Files.createDirectories(dir.resolve("dyld"))
            ensureReady(dir)
            val ssh = researchSession()
            try {
                out("SSH: research SFTP channel opened")
                val sftp = ssh.openChannel("sftp") as ChannelSftp
                sftp.connect(15000)
                try {
                    val remote = "/System/Library/Caches/com.apple.dyld/"
                    val entries = sftp.ls(remote).map { it as ChannelSftp.LsEntry }
                        .filter { it.filename.startsWith("dyld_shared_cache_arm64") && !it.attrs.isDir }
                    check(entries.isNotEmpty()) { "No dyld cache files on T2" }
                    entries.forEach { entry ->
                        val length = entry.attrs.size
                        require(length in 1L..1_073_741_824L) { "Unexpected cache size for " + entry.filename }
                        out("SSH: SFTP: " + entry.filename + " (" + length + " bytes)")
                        sftp.get(remote + entry.filename, target.resolve(entry.filename).toString())
                        check(Files.size(target.resolve(entry.filename)) == length) { "Incomplete download " + entry.filename }
                    }
                    Files.writeString(dir.resolve("files.txt"), entries.joinToString("\n") { it.filename } + "\n")
                } finally { sftp.disconnect() }
            } finally { if (ssh !== control && ssh !== monitor) ssh.disconnect() }
            out("Fetched dyld files: " + target)
            out("Do not publish raw caches or device identifiers from logs.")
        }
    }

    fun analyzeDyld(cache: String) {
        val file = Path.of(cache).toAbsolutePath().normalize()
        require(Files.isRegularFile(file)) { "Select the locally downloaded dyld_shared_cache_arm64 file" }
        jobs.submit("ipsw-dyld") { dir ->
            if (Host.find("ipsw") == null) {
                out("TOOLS: downloading verified ipsw before analysis")
                (installIpsw ?: error("ipsw tool provisioner is not configured")).invoke(dir)
            }
            val ipsw = Host.find("ipsw") ?: error("ipsw installation did not complete")
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

    fun launchPalera1nManaged(ensureTool: (Path) -> Unit) {
        jobs.submit("jailbreak-launcher") { dir ->
            val state = UsbProbe.detectNow()
            out("DEVICE: preflight state: " + state.mode.label + " — " + state.detail)
            require(state.mode == UsbMode.DFU) {
                "DFU required before launching palera1n; current state is " +
                    state.mode.label + ". Use the DFU indicator to confirm 05ac:1227."
            }
            if (Host.find("palera1n") == null) ensureTool(dir)
            launchPalera1n()
        }
    }

    fun stopPalera1n() {
        val pid = jailbreakPidFile?.takeIf(Files::exists)?.let {
            try { Files.readString(it).trim().toLongOrNull() } catch (_: Exception) { null }
        }
        if (pid == null) {
            out("DEVICE: no managed palera1n session PID; press Ctrl+C in its terminal to stop it")
            return
        }
        val handle = ProcessHandle.of(pid).orElse(null)
        if (handle == null || !handle.isAlive) {
            jailbreakState = "STOPPED"
            out("DEVICE: palera1n terminal process already exited")
            return
        }
        handle.descendants().forEach { it.destroy() }
        handle.destroy()
        jailbreakState = "STOP_REQUESTED"
        out("DEVICE: stop requested for session PID " + pid + "; if sudo remains, press Ctrl+C in terminal")
    }

    fun launchPalera1n() {
        val device = UsbProbe.detectNow()
        require(device.mode == UsbMode.DFU) { "DFU is not active (" + device.mode.label + "). Launch blocked to avoid waiting indefinitely." }
        check(jailbreakState !in listOf("RUNNING", "LAUNCHING")) { "palera1n is already running" }
        val exe = Host.find("palera1n") ?: error("Install palera1n first")
        val dir = Host.newLog("palera1n")
        val log = dir.resolve("palera1n.log")
        val pid = dir.resolve("terminal.pid")
        val exit = dir.resolve("exit-code.txt")
        val script = dir.resolve("launch.sh")
        val args = listOf(exe.toString(), "--cli", "-f", "-d").joinToString(" ") { quote(it) }
        val dollar = '$'
        val contents = "#!/usr/bin/env bash\nset -o pipefail\n" +
            "echo " + dollar + dollar + " > " + quote(pid.toString()) + "\n" +
            "printf '%s\\n' 'Burning-iBridge: DFU was detected before launching palera1n' | tee " + quote(log.toString()) + "\n" +
            "sudo " + args + " 2>&1 | tee -a " + quote(log.toString()) + "\n" +
            "rc=" + dollar + "{PIPESTATUS[0]}\n" +
            "printf '%s\\n' " + doubleQuote(dollar + "rc") + " > " + quote(exit.toString()) + "\n" +
            "printf 'palera1n exit status: %s\\n' " + doubleQuote(dollar + "rc") + " | tee -a " + quote(log.toString()) + "\n" +
            "read -r -p 'Press Enter to close...' ignored || true\n" +
            "exit " + dollar + "rc\n"
        Files.writeString(script, contents)
        script.toFile().setExecutable(true)
        jailbreakLog = log
        jailbreakPidFile = pid
        jailbreakExitFile = exit
        jailbreakStartedAt = System.currentTimeMillis()
        jailbreakLastOutputAt = jailbreakStartedAt
        jailbreakState = "LAUNCHING"
        out("DEVICE: launching palera1n in interactive terminal; logs: " + log)
        val terminal = when {
            Host.isMac -> listOf("open", "-a", "Terminal", script.toString())
            Host.find("konsole") != null -> listOf("konsole", "-e", "bash", script.toString())
            Host.find("gnome-terminal") != null -> listOf("gnome-terminal", "--", "bash", script.toString())
            Host.find("xterm") != null -> listOf("xterm", "-e", "bash", script.toString())
            else -> error("No terminal emulator found. Launch manually: bash " + script)
        }
        ProcessBuilder(terminal).start()
        Thread {
            var seen = 0
            var final = false
            var deadline = System.currentTimeMillis() + 3_600_000
            while (System.currentTimeMillis() < deadline && !final) {
                try {
                    if (Files.isRegularFile(log)) {
                        val lines = Files.readAllLines(log)
                        if (lines.size > seen) {
                            lines.drop(seen).forEach { out("DEVICE: palera1n: " + it.take(500)) }
                            seen = lines.size
                            jailbreakLastOutputAt = System.currentTimeMillis()
                            if (jailbreakState == "LAUNCHING") jailbreakState = "RUNNING"
                        }
                    }
                    if (Files.isRegularFile(exit)) {
                        val status = Files.readString(exit).trim()
                        jailbreakState = if (status == "0") "COMPLETE" else "FAILED ($status)"
                        out("DEVICE: palera1n finished, exit status " + status)
                        final = true
                    }
                    Thread.sleep(1200)
                } catch (e: Exception) {
                    out("DEVICE: log monitor error: " + e.message)
                    final = true
                }
            }
            if (!final && jailbreakState in listOf("RUNNING", "LAUNCHING"))
                jailbreakState = "UNKNOWN / NO EXIT MARKER"
        }.apply { name = "palera1n-log-watch"; isDaemon = true }.start()
    }

    private fun doubleQuote(value: String): String = "\"" + value.replace("\"", "\\\"") + "\""

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
