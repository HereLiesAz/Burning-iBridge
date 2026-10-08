package dev.hereliesaz.burningibridge

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import java.awt.Desktop
import java.awt.EventQueue
import java.awt.Window as AwtWindow
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import kotlin.io.path.extension
import kotlin.io.path.name

private object Ink {
    val ground = Color(0xFF0D1117)
    val panel = Color(0xFF151B23)
    val surface = Color(0xFF1B232C)
    val border = Color(0xFF2D3842)
    val text = Color(0xFFE8ECE8)
    val subdued = Color(0xFF8C9AA5)
    val accent = Color(0xFFD4F178)
    val cyan = Color(0xFF80D8D2)
    val red = Color(0xFFFF8A83)
    val yellow = Color(0xFFE8C486)
}
private val sections = listOf("Overview", "Connection", "Script library", "Research", "Tool manager", "OS workspace")
private fun timeNow() = System.currentTimeMillis()

@Composable
private fun Eyebrow(text: String, color: Color = Ink.subdued) {
    Text(text.uppercase(), fontSize = 10.sp, letterSpacing = 1.8.sp,
        fontWeight = FontWeight.SemiBold, color = color)
}
@Composable
private fun Readable(text: String, mono: Boolean = false, color: Color = Ink.text, size: Int = 13) {
    SelectionContainer {
        Text(text, fontSize = size.sp, color = color,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
            lineHeight = (size + 7).sp)
    }
}
@Composable
private fun Pane(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp)
            .border(1.dp, Ink.border, RoundedCornerShape(14.dp))
            .background(Ink.panel, RoundedCornerShape(14.dp)).padding(20.dp)
    ) {
        Text(title, color = Ink.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Ink.subdued, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(16.dp))
        content()
    }
}
@Composable
private fun Primary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.buttonColors(backgroundColor = Ink.accent, contentColor = Ink.ground),
        shape = RoundedCornerShape(9.dp), elevation = ButtonDefaults.elevation(defaultElevation = 0.dp),
        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)
    ) { Text(label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }
}
@Composable
private fun Secondary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick, enabled = enabled,
        colors = ButtonDefaults.outlinedButtonColors(backgroundColor = Ink.surface, contentColor = Ink.text),
        border = BorderStroke(1.dp, Ink.border), shape = RoundedCornerShape(9.dp),
        modifier = Modifier.padding(end = 8.dp, bottom = 8.dp)
    ) { Text(label, fontSize = 12.sp) }
}
@Composable
private fun Field(value: String, onChange: (String) -> Unit, caption: String,
                  modifier: Modifier = Modifier.fillMaxWidth(), height: Int = 52) {
    OutlinedTextField(
        value, onChange, label = { Text(caption, fontSize = 12.sp) },
        singleLine = height < 80,
        colors = TextFieldDefaults.outlinedTextFieldColors(
            textColor = Ink.text, focusedBorderColor = Ink.accent,
            unfocusedBorderColor = Ink.border, cursorColor = Ink.accent,
            focusedLabelColor = Ink.accent, unfocusedLabelColor = Ink.subdued
        ),
        modifier = modifier.height(height.dp)
    )
}
@Composable
private fun Status(label: String, value: String, tint: Color = Ink.subdued) {
    Column(Modifier.padding(end = 26.dp)) {
        Eyebrow(label)
        Spacer(Modifier.height(5.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(tint, RoundedCornerShape(50)))
            Spacer(Modifier.width(7.dp))
            Text(value, color = Ink.text, fontWeight = FontWeight.Medium, fontSize = 12.sp)
        }
    }
}
private fun chooseFolder(): Path? {
    val chooser = JFileChooser()
    chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}
private fun chooseFile(): Path? {
    val chooser = JFileChooser()
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
}
private fun chooseExportFile(): Path? {
    val chooser = JFileChooser()
    chooser.selectedFile = java.io.File("Burning-iBridge-support.zip")
    return if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
        val path = chooser.selectedFile.toPath()
        if (path.toString().endsWith(".zip")) path else Path.of(path.toString() + ".zip")
    } else null
}
private fun openDir(path: Path, report: (String) -> Unit) {
    try { Desktop.getDesktop().open(path.toFile()) }
    catch (e: Exception) { report("Could not open file manager: " + e.message + "; folder: " + path) }
}

/** The OS desktop window accepts files dropped anywhere over the app, not only a simulated drop zone. */
@Composable
private fun ReceiveScriptDrops(window: AwtWindow, shelf: ScriptShelf, report: (String) -> Unit) {
    DisposableEffect(window, shelf) {
        val previous = window.dropTarget
        val target = DropTarget(window, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
            override fun drop(event: DropTargetDropEvent) {
                try {
                    if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        event.rejectDrop()
                        return
                    }
                    event.acceptDrop(DnDConstants.ACTION_COPY)
                    @Suppress("UNCHECKED_CAST")
                    val dropped = event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<java.io.File>
                    shelf.importFiles(dropped.map { it.toPath() })
                    event.dropComplete(true)
                } catch (e: Exception) {
                    event.dropComplete(false)
                    report("Script drop failed: " + e.message)
                }
            }
        }, true)
        onDispose {
            window.dropTarget = previous
            target.isActive = false
        }
    }
}
@Composable
private fun SectionHeader(index: Int, detail: String) {
    Eyebrow("WORKSPACE  /  " + "%02d".format(index + 1))
    Spacer(Modifier.height(8.dp))
    Text(sections[index], fontSize = 33.sp, fontWeight = FontWeight.Bold, color = Ink.text,
        letterSpacing = (-1.1).sp)
    Spacer(Modifier.height(5.dp))
    Text(detail, color = Ink.subdued, fontSize = 13.sp, lineHeight = 19.sp)
    Spacer(Modifier.height(24.dp))
}

@Composable
fun BurningIBridgeApp(window: AwtWindow) {
    var revision by remember { mutableIntStateOf(0) }
    val book = remember { LogBook { EventQueue.invokeLater { revision++ } } }
    val report: (String) -> Unit = remember(book) { { line -> book.record(line) } }
    val jobs = remember { Jobs(report) }
    var jobStates by remember { mutableStateOf(emptyList<Jobs.JobState>()) }
    val bridge = remember { Bridge(jobs, report) }
    val installer = remember { Installer(report, jobs) }
    val scripts = remember { mutableStateListOf<ScriptShelf.ScriptItem>() }
    val shelf = remember {
        ScriptShelf(
            onChange = { entries -> EventQueue.invokeLater { scripts.clear(); scripts.addAll(entries) } },
            onEvent = report
        )
    }
    DisposableEffect(jobs, bridge, shelf) {
        jobs.onJobsChanged = { updated -> EventQueue.invokeLater { jobStates = updated } }
        onDispose { bridge.stopProxy(); shelf.close(); jobs.executor.shutdownNow() }
    }
    ReceiveScriptDrops(window, shelf, report)
    LaunchedEffect(bridge) {
        while (true) {
            delay(2500)
            bridge.refreshConnectionState()
        }
    }

    var page by remember { mutableIntStateOf(0) }
    var sshPort by remember { mutableStateOf("2233") }
    var sshPassword by remember { mutableStateOf("") }
    var scriptText by remember { mutableStateOf("") }
    var scriptTitle by remember { mutableStateOf("efi-inspection") }
    var selectedScript by remember { mutableStateOf<Path?>(null) }
    var scriptQuery by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf("Identity") }
    var cache by remember { mutableStateOf("") }
    var osImage by remember { mutableStateOf("") }
    var distro by remember { mutableStateOf("Ubuntu") }
    var logChannel by remember { mutableStateOf("ALL") }
    var logLevel by remember { mutableStateOf("ALL") }
    var logQuery by remember { mutableStateOf("") }
    var logsVisible by remember { mutableStateOf(true) }
    var confirmScript by remember { mutableStateOf<Pair<String, String>?>(null) }
    val clipboard = LocalClipboardManager.current

    fun act(task: () -> Unit) {
        try {
            bridge.sshPort = sshPort.toInt().also { require(it in 1024..65535) }
            bridge.password = sshPassword
            task()
        } catch (e: Exception) { report("ERROR: " + (e.message ?: e.javaClass.simpleName)) }
    }
    fun connect() = act { bridge.connectManaged { dir -> installer.ensureSystemHelpers(dir) } }
    fun selectedScriptContent(): String? {
        val path = selectedScript ?: return null
        require(Files.size(path) <= 512_000) { "Script exceeds 512 KB preview limit" }
        return Files.readString(path)
    }

    MaterialTheme(colors = darkColors(
        primary = Ink.accent, secondary = Ink.cyan, background = Ink.ground, surface = Ink.panel,
        onBackground = Ink.text, onSurface = Ink.text
    )) {
        Row(Modifier.fillMaxSize().background(Ink.ground)) {
            // Purposeful fixed navigation, not a horizontal carousel of setup steps.
            Column(
                Modifier.width(220.dp).fillMaxHeight().background(Ink.panel)
                    .border(0.5.dp, Ink.border).padding(horizontal = 18.dp, vertical = 24.dp)
            ) {
                Eyebrow("HERE LIES AZ  /  LAB", Ink.accent)
                Spacer(Modifier.height(8.dp))
                Text("Burning", fontSize = 27.sp, fontWeight = FontWeight.Bold,
                    color = Ink.text, letterSpacing = (-1).sp)
                Text("iBridge", fontSize = 27.sp, fontWeight = FontWeight.Light,
                    color = Ink.accent, letterSpacing = (-1).sp)
                Spacer(Modifier.height(35.dp))
                Eyebrow("WORKSPACES")
                Spacer(Modifier.height(14.dp))
                sections.forEachIndexed { i, name ->
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 5.dp)
                            .background(if (page == i) Ink.surface else Color.Transparent, RoundedCornerShape(9.dp))
                            .clickable { page = i }.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("%02d".format(i + 1), color = if (page == i) Ink.accent else Ink.subdued,
                            fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        Spacer(Modifier.width(12.dp))
                        Text(name, color = if (page == i) Ink.text else Ink.subdued,
                            fontWeight = if (page == i) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.border))
                Spacer(Modifier.height(18.dp))
                Eyebrow("LOCAL WORKSPACE")
                Readable(Host.platform, mono = true, size = 11, color = Ink.subdued)
                Spacer(Modifier.height(9.dp))
                Text("Research tools · experimental", color = Ink.subdued, fontSize = 11.sp)
            }

            Column(Modifier.fillMaxSize()) {
                // The connection and active job state is always present, on every page.
                Row(
                    Modifier.fillMaxWidth().height(78.dp).border(0.5.dp, Ink.border)
                        .padding(horizontal = 26.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tick = revision // recomposes states fed by the SSH/background log
                    Status("TUNNEL", if (bridge.tunnelRunning) "OPEN" else "CLOSED",
                        if (bridge.tunnelRunning) Ink.accent else Ink.subdued)
                    Status("SSH / 01 CONTROL", bridge.controlState,
                        if (bridge.controlState == "ONLINE") Ink.cyan else Ink.subdued)
                    Status("SSH / 02 RESEARCH", bridge.monitorState,
                        if (bridge.monitorState == "ONLINE") Ink.cyan else Ink.subdued)
                    val running = jobStates.filter { it.state == "RUNNING" || it.state == "QUEUED" }
                    Status("OPERATIONS", if (running.isEmpty()) "IDLE" else running.size.toString() + " ACTIVE",
                        if (running.isEmpty()) Ink.subdued else Ink.yellow)
                    Spacer(Modifier.weight(1f))
                    Secondary("SSH logs") { logChannel = "SSH"; logsVisible = true }
                    Primary(if (bridge.linkState == "READY") "Connected" else "Connect bridge") { connect() }
                }

                Row(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                            .padding(horizontal = 28.dp, vertical = 28.dp)
                    ) {
                        when (page) {
                            0 -> {
                                SectionHeader(page, "Your device, experiments and tooling at a glance.")
                                Pane("Session overview", "One connection starts the tunnel, then authenticates control and research SSH channels.") {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(bridge.linkState.replace('_', ' '), fontSize = 21.sp,
                                            fontWeight = FontWeight.Bold, color = if (bridge.linkState == "READY") Ink.accent else Ink.yellow)
                                        Spacer(Modifier.width(18.dp))
                                        Primary("Connect / recover") { connect() }
                                        Secondary("Device scan") { act { bridge.probeUsb() } }
                                    }
                                    if (bridge.linkState == "HOST_KEY_APPROVAL") {
                                        Readable("Fingerprint: " + bridge.hostFingerprint, mono = true)
                                        Spacer(Modifier.height(10.dp))
                                        Primary("Trust fingerprint & continue") { act { bridge.trustKey() } }
                                    }
                                }
                                Pane("Operations", "Live tasks remain visible even when you change workspaces.") {
                                    val recent = jobStates.take(12)
                                    if (recent.isEmpty()) Readable("No operations yet. Start a connection or run an experiment.", color = Ink.subdued)
                                    recent.forEach { item ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(if (item.state == "SUCCESS") "●" else if (item.state == "FAILED") "!" else "◌",
                                                color = when (item.state) {
                                                    "SUCCESS" -> Ink.accent; "FAILED" -> Ink.red; else -> Ink.yellow
                                                }, fontSize = 16.sp)
                                            Spacer(Modifier.width(12.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(item.name, color = Ink.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                                Text(if (item.state == "RUNNING") "Running · " + ((timeNow() - item.startedAt) / 1000) + "s"
                                                    else item.state.lowercase(), color = Ink.subdued, fontSize = 11.sp)
                                            }
                                            if (item.folder.isNotBlank()) TextButton(onClick = { openDir(Path.of(item.folder), report) }) {
                                                Text("Files ↗", color = Ink.cyan, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                                Pane("Quick research") {
                                    Row {
                                        Primary("Read identity") { act { bridge.runRemote("identity", remotePresets.getValue("Identity")) } }
                                        Secondary("Inspect EFI manager") { act { bridge.runRemote("efi-manager", remotePresets.getValue("EFI manager")) } }
                                        Secondary("Script library") { page = 2 }
                                    }
                                }
                            }
                            1 -> {
                                SectionHeader(page, "A single action controls both SSH sessions; every transition is logged.")
                                Pane("Bridge connection", "Dependencies are installed if missing, then the tunnel and two SSH sessions are started.") {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Field(sshPort, { sshPort = it.filter(Char::isDigit) }, "LOCAL SSH PORT", Modifier.width(156.dp))
                                        Spacer(Modifier.width(12.dp))
                                        Field(sshPassword, { sshPassword = it }, "ROOT PASSWORD (MEMORY ONLY)", Modifier.weight(1f))
                                    }
                                    Spacer(Modifier.height(15.dp))
                                    Row {
                                        Primary("Start tunnel + 2 SSH sessions") { connect() }
                                        Secondary("Disconnect") { act { bridge.stopProxy() } }
                                        Secondary("Scan USB") { act { bridge.probeUsb() } }
                                    }
                                    if (bridge.linkState == "HOST_KEY_APPROVAL") {
                                        Spacer(Modifier.height(10.dp))
                                        Eyebrow("TRUST BOUNDARY", Ink.yellow)
                                        Readable(bridge.hostFingerprint, mono = true, color = Ink.yellow)
                                        Primary("I verified the fingerprint · continue") { act { bridge.trustKey() } }
                                    }
                                    if (bridge.linkState == "SSH_ERROR") Readable("SSH authentication failed. Check credentials and try Connect.", color = Ink.red)
                                }
                                Pane("Live SSH", "Both channels authenticate independently; logging is isolated from other tools.") {
                                    Row {
                                        Status("CONTROL", bridge.controlState, if (bridge.controlState == "ONLINE") Ink.accent else Ink.subdued)
                                        Status("RESEARCH / SFTP", bridge.monitorState, if (bridge.monitorState == "ONLINE") Ink.accent else Ink.subdued)
                                    }
                                    Spacer(Modifier.height(14.dp))
                                    Row {
                                        Primary("Run identity check") { act { bridge.runRemote("ssh-identity", remotePresets.getValue("Identity")) } }
                                        Secondary("Inspect host fingerprint") { act { bridge.fingerprint() } }
                                        Secondary("Show SSH-only logs") { logChannel = "SSH"; logsVisible = true }
                                    }
                                }
                                Pane("Recovery / jailbreak", "Interactive palera1n session. Jailbreaking affects bridgeOS, not Activation Lock or host Secure Boot policy.") {
                                    Row {
                                        Primary("Launch palera1n") { act { bridge.launchPalera1n() } }
                                        Secondary("Install missing tools") { page = 4 }
                                    }
                                }
                            }
                            2 -> {
                                SectionHeader(page, "A real watched folder and native file drops, with explicit execution approval.")
                                Pane("Script shelf", "Drag .sh, .bash, .zsh, .py or .txt files anywhere into this window.") {
                                    Eyebrow("WATCHED FOLDER")
                                    Spacer(Modifier.height(6.dp))
                                    Readable(shelf.folder.toString(), mono = true, color = Ink.cyan, size = 11)
                                    Spacer(Modifier.height(10.dp))
                                    Row {
                                        Secondary("Choose watched folder") { act { chooseFolder()?.let(shelf::setFolder) } }
                                        Secondary("Open folder ↗") { openDir(shelf.folder, report) }
                                        Secondary("Import script") { act { chooseFile()?.let { shelf.importFiles(listOf(it)) } } }
                                        Secondary("Refresh") { shelf.refresh() }
                                    }
                                    Field(scriptQuery, { scriptQuery = it }, "FILTER SCRIPTS")
                                    Spacer(Modifier.height(8.dp))
                                    val visible = scripts.filter { it.path.name.contains(scriptQuery, true) }
                                    if (visible.isEmpty()) Readable("No scripts here yet. Save one below or drop files onto the app.", color = Ink.subdued)
                                    visible.forEach { script ->
                                        Row(
                                            Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                                .background(if (selectedScript == script.path) Ink.surface else Ink.panel, RoundedCornerShape(7.dp))
                                                .clickable { selectedScript = script.path }.padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("⌁", color = Ink.accent, fontSize = 17.sp)
                                            Spacer(Modifier.width(9.dp))
                                            Text(script.path.name, color = Ink.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                            Eyebrow(script.source, if (script.source == "WATCHED") Ink.cyan else Ink.yellow)
                                        }
                                    }
                                }
                                Pane("Editor · generate & save", "New scripts go straight into the watched folder and appear in the shelf.") {
                                    Field(scriptTitle, { scriptTitle = it }, "SCRIPT NAME")
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(scriptText, { scriptText = it }, modifier = Modifier.fillMaxWidth().height(185.dp),
                                        label = { Text("Shell script", fontSize = 12.sp) },
                                        textStyle = androidx.compose.ui.text.TextStyle(color = Ink.text, fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                        colors = TextFieldDefaults.outlinedTextFieldColors(focusedBorderColor = Ink.accent, unfocusedBorderColor = Ink.border))
                                    Spacer(Modifier.height(10.dp))
                                    Row {
                                        Primary("Save to watched folder", scriptText.isNotBlank()) { act {
                                            val file = shelf.save(scriptTitle, scriptText)
                                            selectedScript = file
                                            report("Saved script: " + file)
                                        } }
                                        Secondary("Insert read-only preset") {
                                            scriptText = "#!/bin/sh\n" + remotePresets.getValue(preset) + "\n"
                                        }
                                        var presetsExpanded by remember { mutableStateOf(false) }
                                        Box {
                                            Secondary(preset + " ▾") { presetsExpanded = true }
                                            DropdownMenu(presetsExpanded, { presetsExpanded = false }) {
                                                remotePresets.keys.forEach { key ->
                                                    DropdownMenuItem({ preset = key; presetsExpanded = false }) { Text(key) }
                                                }
                                            }
                                        }
                                    }
                                }
                                selectedScript?.let { path ->
                                    Pane("Selected · " + path.name, "Review the script before executing; dropped files never auto-run.") {
                                        val script = try { selectedScriptContent().orEmpty() } catch (e: Exception) { "Preview unavailable: " + e.message }
                                        Box(Modifier.fillMaxWidth().heightIn(min = 70.dp, max = 260.dp).verticalScroll(rememberScrollState())) {
                                            Readable(script, mono = true, size = 12)
                                        }
                                        Spacer(Modifier.height(14.dp))
                                        Row {
                                            Primary("Review & run", path.extension.lowercase() in setOf("sh", "bash", "zsh")) {
                                                act { confirmScript = path.name to Files.readString(path) }
                                            }
                                            Secondary("Copy into editor") { scriptText = script; scriptTitle = path.name.substringBeforeLast('.') }
                                            Secondary("Copy path") { clipboard.setText(AnnotatedString(path.toString())) }
                                        }
                                    }
                                }
                            }
                            3 -> {
                                SectionHeader(page, "Static dyld analysis and read-only interface investigation.")
                                Pane("dyld shared cache", "This action ensures SSH is connected before fetching caches, and installs ipsw when absent.") {
                                    Row {
                                        Primary("Fetch dyld caches") { act { if (bridge.linkState != "READY") connect(); bridge.fetchDyld() } }
                                        Secondary("Browse local cache") { chooseFile()?.let { cache = it.toString() } }
                                    }
                                    Field(cache, { cache = it }, "LOCAL DYLD CACHE")
                                    Spacer(Modifier.height(12.dp))
                                    Primary("Inspect EFI symbols", cache.isNotBlank()) { act { bridge.analyzeDyld(cache) } }
                                    Readable("Candidates: libMacEFIHostInterface, getNVRAMVariable, setNVRAMVariable. Symbol presence is not write authorization.",
                                        color = Ink.subdued, size = 11)
                                }
                                Pane("Read-only probes") {
                                    remotePresets.filterKeys { it != "Identity" }.forEach { (name, command) ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(name, Modifier.weight(1f), color = Ink.text, fontSize = 13.sp)
                                            Secondary("Run ↗") { act { bridge.runRemote(name, command) } }
                                        }
                                    }
                                }
                            }
                            4 -> {
                                SectionHeader(page, "Verified upstream releases, installed when the workflow needs them.")
                                Pane("Platform toolchain") {
                                    val tools = listOf("palera1n", "ipsw", "iproxy", "ssh-keyscan", "irecovery", "idevice_id", "idevicerestore")
                                    tools.forEach { tool ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(tool, color = Ink.text, fontFamily = FontFamily.Monospace,
                                                fontSize = 13.sp, modifier = Modifier.weight(1f))
                                            val installed = Host.find(tool)
                                            Eyebrow(if (installed == null) "MISSING" else "AVAILABLE",
                                                if (installed == null) Ink.yellow else Ink.accent)
                                            Spacer(Modifier.width(12.dp))
                                            if (tool in listOf("palera1n", "ipsw")) {
                                                Secondary(if (installed == null) "Install" else "Update") { act { installer.install(tool) } }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(12.dp))
                                    Row {
                                        Primary("Install USB / SSH helpers") { act { installer.installSystemHelpers() } }
                                        Secondary("Open tool directory ↗") { openDir(Host.tools, report) }
                                    }
                                }
                            }
                            else -> {
                                SectionHeader(page, "Prepare OS media without modifying T2 policy or disks.")
                                Pane("Installation planning") {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        listOf("Ubuntu", "Debian", "Fedora").forEach { option ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                RadioButton(distro == option, { distro = option })
                                                Text(option, color = Ink.text, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                    Field(osImage, { osImage = it }, "INSTALLER IMAGE")
                                    Row {
                                        Secondary("Browse ISO") { chooseFile()?.let { osImage = it.toString() } }
                                        Primary("Verify image checksum", osImage.isNotBlank()) { act { bridge.osChecksum(osImage) } }
                                        Secondary("Write preparation checklist") { act {
                                            val folder = Host.newLog("os-plan")
                                            Files.writeString(folder.resolve("checklist.md"), listOf(
                                                "# " + distro + " installation checklist",
                                                "- [ ] Verify vendor SHA-256 checksum",
                                                "- [ ] Confirm T2Linux support and boot authorization",
                                                "- [ ] Confirm disk backup and recovery path",
                                                "- [ ] Confirm exact installer media and destination",
                                                "- [ ] Explicitly authorize any destructive disk operation"
                                            ).joinToString("\n"))
                                            openDir(folder, report)
                                        } }
                                    }
                                    Readable("OS installation and firmware policy writes are not implemented. Activation Lock remains unchanged.",
                                        color = Ink.yellow, size = 12)
                                }
                            }
                        }
                    }

                    if (logsVisible) {
                        Column(
                            Modifier.width(360.dp).fillMaxHeight().background(Ink.panel)
                                .border(0.5.dp, Ink.border).padding(17.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Eyebrow("TELEMETRY / LIVE", Ink.accent)
                                    Text("Activity stream", color = Ink.text, fontSize = 19.sp,
                                        fontWeight = FontWeight.SemiBold)
                                }
                                TextButton({ logsVisible = false }) { Text("Hide", color = Ink.subdued) }
                            }
                            Spacer(Modifier.height(12.dp))
                            Field(logQuery, { logQuery = it }, "SEARCH LOGS", height = 50)
                            Spacer(Modifier.height(8.dp))
                            Row {
                                var channelsExpanded by remember { mutableStateOf(false) }
                                Box(Modifier.weight(1f)) {
                                    Secondary("Channel: " + logChannel + " ▾") { channelsExpanded = true }
                                    DropdownMenu(channelsExpanded, { channelsExpanded = false }) {
                                        listOf("ALL", "SSH", "LINK", "DEVICE", "RESEARCH", "TOOLS", "SYSTEM").forEach { channel ->
                                            DropdownMenuItem({ logChannel = channel; channelsExpanded = false }) { Text(channel) }
                                        }
                                    }
                                }
                                var levelsExpanded by remember { mutableStateOf(false) }
                                Box {
                                    Secondary(logLevel + " ▾") { levelsExpanded = true }
                                    DropdownMenu(levelsExpanded, { levelsExpanded = false }) {
                                        listOf("ALL", "INFO", "WARN", "ERROR").forEach { level ->
                                            DropdownMenuItem({ logLevel = level; levelsExpanded = false }) { Text(level) }
                                        }
                                    }
                                }
                            }
                            val live = revision.let { book.copyText(logChannel, logQuery, logLevel) }
                            val scroll = rememberScrollState()
                            LaunchedEffect(live.length, logChannel, logQuery) { scroll.scrollTo(scroll.maxValue) }
                            Box(
                                Modifier.fillMaxWidth().weight(1f)
                                    .background(Ink.ground, RoundedCornerShape(8.dp))
                                    .verticalScroll(scroll).padding(12.dp)
                            ) {
                                SelectionContainer {
                                    Text(if (live.isEmpty()) "No matching events. Logs will appear as tasks run." else live,
                                        fontFamily = FontFamily.Monospace, color = Ink.text, fontSize = 11.sp,
                                        lineHeight = 19.sp)
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row {
                                Secondary("Copy") { clipboard.setText(AnnotatedString(live)) }
                                Primary("Export .zip") { act {
                                    chooseExportFile()?.let { target ->
                                        book.exportBundle(target, logChannel, logQuery, logLevel)
                                        report("Exported redacted diagnostic bundle: " + target)
                                        openDir(target.parent, report)
                                    }
                                } }
                            }
                            Secondary("Open all run logs ↗") { openDir(Host.logs, report) }
                        }
                    } else {
                        Column(Modifier.fillMaxHeight().width(30.dp).clickable { logsVisible = true }.background(Ink.panel),
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(Modifier.height(20.dp))
                            Text("›", fontSize = 22.sp, color = Ink.accent)
                        }
                    }
                }
            }
        }
        confirmScript?.let { item ->
            AlertDialog(
                onDismissRequest = { confirmScript = null },
                title = { Text("Execute " + item.first + "?", color = Ink.text) },
                text = { Readable("This shell script will run with root privileges on the connected T2. Review its contents and approve the execution explicitly:\n\n" + item.second.take(1800), mono = true, size = 12) },
                confirmButton = {
                    TextButton({
                        confirmScript = null
                        act { bridge.runRemote("script-" + item.first, item.second) }
                    }) { Text("Run on T2", color = Ink.accent) }
                },
                dismissButton = { TextButton({ confirmScript = null }) { Text("Cancel", color = Ink.subdued) } },
                backgroundColor = Ink.panel
            )
        }
    }
}
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Burning iBridge — Research workstation",
        state = rememberWindowState(width = 1450.dp, height = 900.dp)) {
        BurningIBridgeApp(window)
    }
}
