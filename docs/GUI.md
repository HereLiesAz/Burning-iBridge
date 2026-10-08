# Burning-iBridge desktop GUI

**Early research preview — October 2026.** The desktop frontend is [burning_ibridge_gui.py](../burning_ibridge_gui.py). It is a Python/Tkinter application that orchestrates existing CLI tools through visible system terminals, with separate local log folders. It has not yet been tested end-to-end through its graphical interface on the physical T2.

## Requirements and launch

Python 3.10+ and Tkinter, on Linux or macOS. On Ubuntu, install python3-tk, usbmuxd, libusbmuxd-tools and libimobiledevice-utils using apt. macOS users can install libusbmuxd, libimobiledevice and libirecovery through Homebrew. Install the [palera1n CLI](https://github.com/palera1n/palera1n/releases) and [ipsw dyld tools](https://github.com/blacktop/ipsw/releases/latest) from their official projects. Ubuntu can install ipsw via snap, and macOS via the blacktop Homebrew tap.

To launch, run:

    python3 burning_ibridge_gui.py

This is a single source-file GUI. It does not bundle or automatically download third-party executables. Choose the palera1n executable in the GUI; it also looks for the path previously used on this Ubuntu laptop.

## Included CLIs and actions

| Tool | GUI action |
| --- | --- |
| palera1n --cli (rootful/debug options) | Launch interactive jailbreak session in a system Terminal |
| lsusb / system_profiler | Detect DFU/bridgeOS USB state |
| irecovery -q | Query DFU/Recovery device information |
| idevice_id -l | Enumerate usbmux devices |
| idevicerestore -h | Inspect help/availability, NOT perform a restore |
| iproxy local-port 44 | Start/stop an app-owned SSH tunnel |
| ssh | Run preset or custom root SSH commands in an interactive Terminal |
| scp | Download dyld main cache and companion cache files |
| ipsw dyld | Inspect cache metadata, image, symbols, load commands and candidate symbol addresses |
| Python SHA-256 | Hash selected Linux installer ISO |

A **Script builder** tab queues read-only presets or user-defined commands, allows preview and saving of a Bash script, and launches it over SSH. User-defined commands have bridgeOS root privileges and can damage devices if misused. Every launched operation creates a timestamped script and terminal log in the HOME/Burning-iBridge/logs folder.

## Starting experiments from the GUI

1. Click **Detect installed CLIs** and **Detect USB mode**.
2. For a T2 in DFU (observed USB 05ac:1227), select your palera1n binary and click **Run palera1n --cli**. Use the visible Terminal to follow prompts.
3. Once bridgeOS reappears (observed USB 05ac:8600), click **Start iproxy** and **SSH test**. Enter your SSH password in Terminal and inspect its host fingerprint before accepting it.
4. In **ipsw dyld**, choose a local dyld_shared_cache_arm64 file. Use **Fetch cache via SCP** if you do not have a copy. This may copy several hundred megabytes.
5. Click **Analyze EFI functions**. The CLI runs its inspections sequentially and writes a separate log for each test. Review each result rather than assuming a string match means a callable API.
6. Use the outputs to update the [bridgeOS API reference](bridgeos-api-reference.md).

## EFI symbol questions currently being investigated

A passive dyld scan of an iBridge2,8 device running Darwin 25.6.0 found the string setNVRAMVariable in cache file .03, .07.dyldlinkedit and .symbols. The GUI uses ipsw image, macho and symaddr to determine whether that symbol belongs to libMacEFIHostInterface and how it is represented. This does NOT establish an exported, callable or authorized EFI write operation.

Relevant ipsw operations include:

    ipsw dyld image CACHE libMacEFIHostInterface -V
    ipsw dyld macho CACHE libMacEFIHostInterface --symbols
    ipsw dyld macho CACHE libMacEFIHostInterface --loads
    ipsw dyld symaddr --image libMacEFIHostInterface CACHE setNVRAMVariable
    ipsw dyld symaddr CACHE setNVRAMVariable --all
    ipsw dyld symaddr CACHE getNVRAMVariable --all

The installed ipsw version may support additional options. Retain its output and exit codes to avoid false conclusions.

## OS installation limitations

The OS planning tab can select Ubuntu, Debian, Fedora or another Linux distribution, compute the ISO checksum and write an installation checklist. It does NOT flash install media, partition disks, reset activation, alter boot-policy settings, or install an OS. The current device's Activation Lock, Secure Boot policy and external boot permission remain independent blockers. Jailbroken bridgeOS root access alone does not solve them.

## Security and release notes

- Third-party binaries are not bundled.
- GUI-generated operations launch from explicit buttons; host sudo and SSH authentication occur in native Terminal.
- Stop iproxy only stops the app-managed process; it does not kill other listeners.
- The logs may contain serials, ECIDs, Wi-Fi details, EFI certificates and sensitive settings; redact before publishing.
- Do not redistribute Apple firmware or third-party binaries without verifying their licenses.
- This preview has not yet undergone cross-platform GUI hardware acceptance tests. Packaging, richer progress display and authorized OS installation remain planned enhancements.
