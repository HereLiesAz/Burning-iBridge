package dev.hereliesaz.burningibridge

import androidx.compose.desktop.ui.tooling.preview.Preview
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser

private val tabs = listOf("Tools & setup", "Jailbreak & SSH", "Scripts", "dyld research", "OS planning")

@Composable
private fun Action(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)) {
        Text(label)
    }
}

@Composable
private fun Hint(value: String) {
    Text(value, fontSize = 12.sp, color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f))
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(elevation = 3.dp, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Column(modifier = Modifier.padding(16.dp), content = {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            content()
        })
    }
}

private fun chooseFile(): String? {
    val dialog = JFileChooser()
    return if (dialog.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) dialog.selectedFile.absolutePath else null
}

@Composable
@Preview
fun BurningIBridgeApp() {
    val messages = remember { mutableStateListOf<String>() }
    val log: (String) -> Unit = remember {
        { line ->
            EventQueue.invokeLater {
                messages.add(line)
                while (messages.size > 220) messages.removeAt(0)
            }
        }
    }
    val jobs = remember { Jobs(log) }
    val bridge = remember { Bridge(jobs, log) }
    val installer = remember { Installer(log, jobs) }
    var tab by remember { mutableIntStateOf(0) }
    var port by remember { mutableStateOf("2233") }
    var password by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf("Identity") }
    var custom by remember { mutableStateOf("") }
    val queue = remember { mutableStateListOf<Pair<String, String>>() }
    var cache by remember { mutableStateOf("") }
    var osImage by remember { mutableStateOf("") }
    var distro by remember { mutableStateOf("Ubuntu") }
    var reload by remember { mutableIntStateOf(0) }
    var showConsole by remember { mutableStateOf(true) }

    fun run(action: () -> Unit) {
        try {
            bridge.sshPort = port.toInt().also { require(it in 1024..65535) { "SSH port must be between 1024 and 65535" } }
            bridge.password = password
            action()
        } catch (e: Exception) { log("ERROR: " + (e.message ?: e.javaClass.simpleName)) }
    }

    MaterialTheme(colors = darkColors(
        primary = Color(0xFFFFB74D),
        primaryVariant = Color(0xFFE78C24),
        secondary = Color(0xFF82C5C2),
        background = Color(0xFF111419),
        surface = Color(0xFF20252E)
    )) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colors.background).padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("Burning iBridge", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("T2 research console  •  " + Host.platform + "  •  Compose Desktop", fontSize = 12.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Experimental / read-first", color = Color(0xFFFFB74D), fontWeight = FontWeight.SemiBold)
                    Hint("Activation Lock unchanged  ·  No automatic boot-policy writes")
                }
            }
            Spacer(Modifier.height(12.dp))
            ScrollableTabRow(selectedTabIndex = tab, backgroundColor = MaterialTheme.colors.surface, edgePadding = 2.dp) {
                tabs.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                when (tab) {
                    0 -> {
                        Section("Runtime tool manager") {
                            Text("The app installs palera1n v3 CLI and the latest stable ipsw directly from official GitHub Releases. Every downloaded archive requires a verified SHA-256 digest.", fontSize = 13.sp)
                            Spacer(Modifier.height(10.dp))
                            Action("Install / update palera1n") { run { installer.install("palera1n") } }
                            Action("Install / update ipsw") { run { installer.install("ipsw") } }
                            Action("Install USB helpers") { run { installer.installSystemHelpers() } }
                            Action("Refresh status") { reload++ }
                            val tools = listOf("palera1n", "ipsw", "iproxy", "irecovery", "idevice_id", "idevicerestore", "ssh", "ssh-keyscan", "lsusb")
                            key(reload) {
                                tools.forEach { name ->
                                    val resolved = Host.find(name)?.toString() ?: "Not found"
                                    Text(name.padEnd(16) + resolved, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                }
                            }
                        }
                        Section("Connection diagnostics") {
                            Row {
                                Action("Detect Apple USB state") { run { bridge.probeUsb() } }
                                Action("Open log directory") { run {
                                    java.awt.Desktop.getDesktop().open(Host.logs.toFile())
                                } }
                            }
                            Hint("05ac:1227 = T2 DFU; 05ac:8600 = running iBridge. A running iBridge is not proof of SSH availability.")
                        }
                    }
                    1 -> {
                        Section("Jailbreak") {
                            Hint("Starts the installed palera1n executable with --cli -f -d in a terminal so sudo and DFU interaction work normally. This changes device state.")
                            Spacer(Modifier.height(6.dp))
                            Action("Launch palera1n CLI") { run { bridge.launchPalera1n() } }
                            Hint("The app does not bypass Activation Lock or assume that a T2 jailbreak changes Intel Secure Boot policy.")
                        }
                        Section("Managed USB ↔ SSH tunnel") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Local port") }, singleLine = true, modifier = Modifier.width(155.dp))
                                Spacer(Modifier.width(8.dp))
                                Action("Start iproxy") { run { bridge.startProxy() } }
                                Action("Stop managed iproxy") { run { bridge.stopProxy() } }
                            }
                            OutlinedTextField(password, { password = it }, label = { Text("T2 root SSH password (not saved)") },
                                visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.width(360.dp))
                            Spacer(Modifier.height(8.dp))
                            Row {
                                Action("Inspect SSH host key") { run { bridge.fingerprint() } }
                                Action("Trust displayed key") { run { bridge.trustKey() } }
                                Action("Test root SSH") { run { bridge.runRemote("ssh-identity", remotePresets.getValue("Identity")) } }
                            }
                            Hint("Compare the displayed SHA-256 fingerprint before trusting the key. Host keys are stored in ~/.burning-ibridge/known_hosts. SSH credentials stay in RAM.")
                        }
                    }
                    2 -> {
                        Section("Remote experiment builder") {
                            Hint("Queue read-only presets or your own shell commands. Saved scripts and outputs are stored separately; custom commands run as root on the T2.")
                            var expanded by remember { mutableStateOf(false) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box {
                                    OutlinedButton(onClick = { expanded = true }) { Text("Preset: " + preset + "  ▾") }
                                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                        remotePresets.keys.forEach { name ->
                                            DropdownMenuItem(onClick = { preset = name; expanded = false }) { Text(name) }
                                        }
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Action("Add preset") { run { queue.add(preset to remotePresets.getValue(preset)) } }
                                Action("Clear queue") { queue.clear() }
                            }
                            OutlinedTextField(custom, { custom = it }, label = { Text("Custom remote shell script") },
                                modifier = Modifier.fillMaxWidth().height(135.dp))
                            Action("Queue custom script") { run {
                                require(custom.isNotBlank()) { "Script is empty" }
                                queue.add("Custom experiment" to custom)
                                custom = ""
                            } }
                            queue.forEachIndexed { index, item ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text((index + 1).toString() + ". " + item.first, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { queue.removeAt(index) }) { Text("Remove") }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Action("Save queue as .sh", enabled = queue.isNotEmpty()) { run {
                                val dir = Host.newLog("script-builder")
                                Files.writeString(dir.resolve("experiment.sh"), "#!/bin/sh\n" + queue.joinToString("\n") { "# " + it.first + "\n" + it.second } + "\n")
                                log("Saved: " + dir.resolve("experiment.sh"))
                            } }
                            Action("Run queue on T2", enabled = queue.isNotEmpty()) { run {
                                val script = queue.joinToString("\n") { "# " + it.first + "\n" + it.second }
                                bridge.runRemote("experiment", script)
                            } }
                        }
                    }
                    3 -> {
                        Section("dyld shared-cache research") {
                            Hint("First fetch the ARM64 cache/subcaches via SFTP, then select the local main dyld_shared_cache_arm64 file for ipsw symbol inspection.")
                            Action("Fetch T2 dyld files") { run { bridge.fetchDyld() } }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(cache, { cache = it }, label = { Text("Local dyld_shared_cache_arm64") },
                                    modifier = Modifier.weight(1f), singleLine = true)
                                Spacer(Modifier.width(8.dp))
                                Action("Browse") { chooseFile()?.let { cache = it } }
                            }
                            Action("Find EFI image / symbols") { run { bridge.analyzeDyld(cache) } }
                            Hint("Runs ipsw dyld image, macho --symbols, and symaddr for getNVRAMVariable/setNVRAMVariable. No T2 writes.")
                        }
                    }
                    4 -> {
                        Section("Operating system preparation") {
                            Hint("Choose a distribution and verify its ISO before installation. Actual disk writing and Linux boot are blocked until compatibility and Secure Boot/external boot are independently confirmed.")
                            Row {
                                listOf("Ubuntu", "Debian", "Fedora", "Other Linux").forEach { item ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        RadioButton(selected = distro == item, onClick = { distro = item })
                                        Text(item, fontSize = 12.sp)
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(osImage, { osImage = it }, label = { Text("Installer image") }, modifier = Modifier.weight(1f), singleLine = true)
                                Spacer(Modifier.width(8.dp))
                                Action("Browse") { chooseFile()?.let { osImage = it } }
                            }
                            Action("Calculate SHA-256") { run { bridge.osChecksum(osImage) } }
                            Action("Generate setup checklist") { run {
                                val dir = Host.newLog("os-plan")
                                val items = listOf(
                                    "# Burning-iBridge OS preparation: " + distro,
                                    "- [ ] Verify image SHA-256 against the official vendor",
                                    "- [ ] Back up all data and firmware diagnostics",
                                    "- [ ] Verify T2Linux compatibility for exact model/firmware",
                                    "- [ ] Confirm Intel Secure Boot and external boot permissions",
                                    "- [ ] Confirm known-good installer path and recovery procedure",
                                    "- [ ] Confirm disk layout and explicit user approval before writing",
                                    "- [ ] Do not mistake bridgeOS jailbreak for Activation Lock removal"
                                )
                                Files.writeString(dir.resolve("checklist.md"), items.joinToString("\n") + "\n")
                                log("Saved OS preparation checklist: " + dir.resolve("checklist.md"))
                            } }
                            Hint("Automatic OS installation is NOT implemented. This UI will not silently repartition or change firmware.")
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Experiment console", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = { showConsole = !showConsole }) { Text(if (showConsole) "Hide" else "Show") }
                TextButton(onClick = { messages.clear() }) { Text("Clear view") }
            }
            if (showConsole) {
                val scroll = rememberScrollState()
                LaunchedEffect(messages.size) { scroll.animateScrollTo(scroll.maxValue) }
                Box(Modifier.fillMaxWidth().height(195.dp).background(Color(0xFF090B0F)).verticalScroll(scroll).padding(12.dp)) {
                    Text(messages.joinToString("\n").ifBlank { "Logs will appear here. Files: " + Host.logs },
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color(0xFFC7DDD5), lineHeight = 18.sp)
                }
            }
        }
    }
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Burning iBridge",
        state = rememberWindowState(width = 1170.dp, height = 850.dp)) {
        BurningIBridgeApp()
    }
}
