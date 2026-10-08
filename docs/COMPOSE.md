# Burning-iBridge desktop — Compose Multiplatform GUI

## Architecture

**Compose Desktop / Kotlin JVM** is the active user interface for Linux and macOS. Orchestration is Kotlin/JVM rather than Python: it launches external tools, manages an `iproxy` process, downloads verified releases, collects logs, and uses JSch for password-authenticated SSH/SFTP with explicit host-key trust. Python scripts can still be run through the experiment builder; the earlier Tkinter entrypoint is retained for reference, but is not the supported front end.

- `src/main/kotlin/dev/hereliesaz/burningibridge/Main.kt`: Compose app tabs and log viewer.
- `Infrastructure.kt`: tool lookup, GitHub asset downloader, SHA-256 verification, archive extraction, package-manager bootstrap, timestamped logs.
- `Bridge.kt`: jailbreak terminal launcher, iproxy process lifecycle, SSH fingerprint/trust, remote shell presets, dyld SFTP and ipsw inspection.
- `build.gradle.kts`: Compose Desktop 1.12.1 / Kotlin 2.4.20 / JDK 21.

## Run and package

Use JDK **21**. The included `launch.sh` bootstraps Gradle 9.7.1 with a verified SHA-256 and starts the Compose GUI, or you can use an existing Gradle installation. In the repository root:

```sh
bash launch.sh
# Or, with an installed Gradle:
gradle run
gradle test
gradle packageDistributionForCurrentOS
```

Gradle is needed only to **build** the application. Generated native packages include the application JVM runtime. Linux builds can produce a `.deb`, and macOS builds a `.dmg`; build each on its own operating system. No release package has yet been built or hardware-tested end-to-end in this repository.

## In-app tool provisioning

1. Launch the GUI; select **Tools & setup**.
2. Click **Install / update palera1n**. It obtains `v3.0.0-beta.2` from **palera1n/palera1n** for the active architecture. Linux uses an official tarball; macOS mounts the official universal DMG and copies the application bundle.
3. Click **Install / update ipsw**. It obtains the latest **stable** official archive from **blacktop/ipsw**, as selected by GitHub's releases API.
4. Every downloaded archive is checked against the release asset's SHA-256 metadata. For ipsw, the official `checksums.txt` provides a fallback when the API digest is missing. Installation stops if no verified digest is available.
5. Click **Install USB helpers**. Linux Ubuntu/Debian uses graphical `pkexec apt-get install`; macOS uses an existing Homebrew installation. An administrator prompt may appear. Other distributions require supported package sources or manual installation.

Tool binaries are stored beneath `~/.burning-ibridge/tools` (or the system PATH when already installed). The app **does not distribute or silently copy** third-party binaries into Git: binaries come from upstream at runtime, and licenses and updates remain governed by upstream projects.

| Tool | Provisioning mechanism | Function |
| --- | --- | --- |
| palera1n CLI | Verified official release | T2 checkm8 / bridgeOS jailbreak in interactive terminal |
| blacktop ipsw | Verified official release | dyld Mach-O image/symbol inspection |
| iproxy / usbmuxd | apt/Homebrew | USB-to-bridgeOS port forwarding |
| irecovery / idevice_id / idevicerestore | apt/Homebrew | Device diagnostics; no automatic firmware restore |
| ssh / ssh-keyscan | Platform package / installed OpenSSH | Host-key inspection, SSH control |
| T2 shell scripts | Generated locally | Read-only presets and user-supplied experiments |

**Limitation:** The palera1n integration launches a terminal because DFU timing and elevation must remain interactive; password-authenticated T2 SSH commands run inside the GUI. The command line was tested in earlier manual experiments, but the new packaged GUI has not been validated against a live T2 yet. Do not equate root access on bridgeOS with persistent Intel boot-policy changes.

## SSH setup

1. The T2 should present as running bridgeOS (`05ac:8600`). If DFU (`05ac:1227`), use the interactive palera1n button.
2. Click **Start iproxy** (default `2233 -> 44`) or use an existing iproxy tunnel.
3. Click **Inspect SSH host key**. Compare the shown SHA-256 fingerprint before pressing **Trust displayed key**. The accepted key is stored in `~/.burning-ibridge/known_hosts`. Unknown or changed keys are rejected.
4. Enter the root SSH password (only in app memory), then **Test root SSH**. The app uses SSH/SFTP directly; remote commands and logs do not require sshpass.

## Running experiments

**Scripts** queues read-only presets or user commands. Click **Save queue** to generate a standalone `.sh`, or **Run queue** to send it to the T2 over SSH. Custom commands are fully privileged and can damage firmware/data; no remote command should be run without inspecting it.

**dyld research** can fetch the T2 shared cache and subcaches to a timestamped local log directory. Select the main cache and click **Find EFI image / symbols** to perform:

```sh
ipsw dyld image <local-cache> libMacEFIHostInterface -V
ipsw dyld macho <local-cache> libMacEFIHostInterface --symbols
ipsw dyld symaddr <local-cache> setNVRAMVariable --all
ipsw dyld symaddr <local-cache> getNVRAMVariable --all
```

This is read-only static analysis. Presence of a symbol name is not proof of a callable write API, correct ABI, entitlement, or authorization.

**OS planning** generates an installation checklist and calculates a local ISO checksum. **There is no OS-flashing/installing implementation yet**. Disk partitioning and host boot-policy writes are not automated. Activation Lock is not removed by the GUI, palera1n, or a firmware restore.

## Logging, privacy and recovery

Logs, generated scripts, and downloaded diagnostic archives are in `~/.burning-ibridge/logs/<task>-<UTC timestamp>/`. Inspect and redact identifiers and network data before publishing. The app doesn't upload logs. Third-party installers and dyld archives can contain sensitive information; don't commit or redistribute extracted Apple system binaries without reviewing applicable rights.

If SSH resets, use the USB state detector: `05ac:1227` indicates DFU and the jailbreak needs recovery; `05ac:8600` confirms bridgeOS running but not necessarily SSH. **Do not run idevicerestore as a connection-repair step**.

## Linux package compatibility and installation troubleshooting

Release `0.1.0.25` was built on an Ubuntu 24.04 runner; its `.deb` declares dependencies on `libasound2t64` and `libpng16-16t64`, which cannot be satisfied by Ubuntu 22.04's ordinary repositories. It also had a static native package version of `1.1.0`, preventing reliable build-to-build upgrade ordering. The `0.1.1` patch release changes the Linux build runner to Ubuntu 22.04 and gives every native package a unique `1.1.<build>` installer version. It also publishes a Linux portable `.tar.gz` application directory alongside the `.deb`.

Install the appropriate **latest** Linux package with APT so the package manager resolves dependencies and reports any real errors:

```sh
sudo apt install ./Burning-iBridge-*-linux-amd64.deb
```

If it fails, copy the exact apt output; to inspect dependencies without installing, use `dpkg-deb -f Burning-iBridge-*-linux-amd64.deb Depends`. To verify the installed version: `dpkg-query -W burning-ibridge`. On an older operating system with incompatible libraries, use an appropriate compatible environment; do not force package installation with `--force-depends`.

For the portable archive, extract it into a user-writable directory, then launch the executable in the extracted `bin/` subdirectory. It includes its own JVM runtime but still requires compatible Linux system libraries.

On macOS, mount the appropriate `.dmg` (Intel versus Apple Silicon), drag the app into Applications, and use **System Settings → Privacy & Security → Open Anyway** if the unsigned app is blocked. Do not disable Gatekeeper globally.

## Remaining integration work

- Run Gradle build/tests and exercise the native Linux and macOS bundles on both architectures.
- Live-test tool provisioning, palera1n launch, SSH host-key approval, and per-command logging.
- Inspect dyld symbols and update `docs/bridgeos-api-reference.md` with exported signatures and versioned evidence.
- Evaluate an installer only after verifying security-policy authorization, T2Linux compatibility and rollback/recovery paths.

## Upstream projects

- https://github.com/palera1n/palera1n
- https://github.com/blacktop/ipsw
- https://github.com/libimobiledevice/libusbmuxd
- https://blacktop.github.io/ipsw/docs/guides/dyld/
