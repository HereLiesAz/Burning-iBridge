# Desktop workstation — UX and interaction contract

## Visual direction

Burning-iBridge now uses a purposeful desktop shell rather than stacked generic tabs. The design takes workflow principles from [Raycast](https://www.raycast.com/blog/a-fresh-look-and-feel)—clear primary actions and highly scannable lists—and the persistent navigation model common to desktop productivity apps. This is not a pixel reproduction of either product.

**UI tokens:** near-black workspace #0D1117; dark-slate panels #151B23; subtle #2D3842 outlines; light ivory #E8ECE8 text; electric-lime #D4F178 for actionable state; cyan #80D8D2 for established connections; warm warning/error accents. System sans-serif for body copy; tabular monospace for log data and paths. Small uppercase overlines anchor the typography hierarchy without overwhelming content.

Three continuously visible regions:

1. **Navigation** (left) — Overview, Connection, Script library, Research, Tool manager, OS workspace.
2. **Work area** (center) — the current task; previews and context, not repeated procedural help text.
3. **Telemetry** (right) — live, selectable, searchable, level-filtered and channel-filtered logs, with copy and export actions. Can be collapsed.

A persistent global state strip shows **USB mode, proxy state, SSH control, SSH research, and running job count**.

## Workflow contract

### Start / recover connection

A single **Connect bridge** action:
1. checks for required host tools and invokes a privilege-aware package manager *only if missing*;
2. starts or reuses iproxy on the selected localhost port;
3. waits for the local port to become reachable;
4. authenticates **two separate** JSch sessions, one for control and one for long research/SFTP tasks;
5. stops only when an unknown/mismatched SSH host key needs a real trust decision, or the device/network refuses to connect.

A first-time SSH key requires explicit fingerprint review; the UI will not silently disable strict host key checking. Operations also ensure the connection before issuing an SSH command.

### Jailbreak / DFU (MacBook Air A1932)

The app polls Linux USB IDs / macOS ioreg every three seconds and labels **DFU / bridgeOS / recovery / other Apple USB / disconnected / unavailable**. On the tested T2 model, Apple DFU is \`05ac:1227\` and running iBridge is \`05ac:8600\`.

**Launch palera1n** stays disabled unless a DFU device is detected and does another independent USB check immediately before launching. The target needs a physical keyboard combination; the app can guide and detect that step, but cannot press the hardware keys for the user. Official Intel T2 laptop guidance: use the supported left-front USB-C port and a data-capable cable; power off; press and release Power; immediately hold LEFT Control, LEFT Option, RIGHT Shift, and Power for about three seconds. A successful transition produces an Apple DFU USB device; it is not established solely by a black screen. [Apple firmware recovery guidance](https://support.apple.com/108900).

palera1n still runs interactively in a system terminal for sudo/DFU access. The application writes an individual script, PID/exit markers and a log file, tails that log into a dedicated DEVICE stream, shows running/finished/no-output state, and provides a best-effort stop action; for privileged terminal subprocesses Ctrl+C may still be necessary.

Jailbreaking bridgeOS does **not** grant permission to modify Apple Activation Lock, SEP-controlled Intel boot policy, or install operating systems.

### Scripts

A persistent watched folder can be selected through a graphical folder chooser. \`WatchService\` updates the shelf on create/delete/modify. The editor saves generated scripts directly into that folder; importing or native drag-and-drop adds links to local script files without executing them. Each entry shows its source (WATCHED/DROPPED), and can be previewed or copied into the editor. Execution always requires explicit review/confirmation for privileged remote shell commands.

This is a *local* script index. Dropped files are referenced, not silently copied. Only shell files (\`.sh\`, \`.bash\`, \`.zsh\`) currently have an execute action; \`.py\` files can be indexed for later support but are not assumed runnable in the T2 shell.

### Logs and diagnostics

- Text is selectable/copyable in preview panes and in the telemetry view; **Copy** copies filtered visible log lines.
- Dedicated SSH channel captures tunnel, host-key and command outcomes, and separate DEVICE events capture palera1n.
- A searchable and severity-filtered log stream is persisted under \`~/.burning-ibridge/logs\`.
- **Export .zip** produces a diagnostic bundle of activity text and recent run logs. Common identifiers and secrets are redacted best-effort, but the user must inspect the zip before sharing; no automatic external upload.
- Each work item progresses through QUEUED → RUNNING → SUCCESS/FAILED, with its own run folder. Four background workers keep GUI rendering interactive during transfers.

## Verification matrix

- Linux and macOS native packaging remain centralized in HereLiesAz/workflows.
- Automated unit tests cover USB parsing for Linux and macOS, basic log channel classification, and secret-redaction.
- Hardware behavior and drag-and-drop acceptance must still be verified on the actual installed packages. A successful CI package build is not a substitute for an on-device usability test.

## Design references

- [Raycast: A Fresh Look and Feel](https://www.raycast.com/blog/a-fresh-look-and-feel)
- [Raycast: Search and Actions](https://manual.raycast.com/search-bar)
- [Apple: How to revive or restore Mac firmware](https://support.apple.com/108900)
