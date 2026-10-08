# Burning iBridge

Tested research hardware: Intel T2 MacBook Air A1932, MacBookAir8,1 (iBridge2,8).

## Burning-iBridge Linux / macOS GUI

**[Launchable Python GUI](burning_ibridge_gui.py)** · [GUI setup and operator guide](docs/GUI.md)

The GUI currently integrates installed command-line tools: palera1n --cli, ipsw dyld image/symbol inspection, iproxy, SSH/SCP, irecovery, idevice_id, and idevicerestore help; plus a script builder, USB/DFU detection, timestamped logs, and ISO checksum/OS preparation.

Linux / macOS prerequisites: Python 3.10+ with Tkinter; a system Terminal and the relevant external CLI programs. Open the file in a desktop Python environment or run:

    python3 burning_ibridge_gui.py

**Current limitations:** Early preview, not yet hardware-tested end-to-end through the new GUI. Third-party binaries aren't bundled. Activation Lock remains in force. No verified EFI write or automatic alternative OS installer is implemented. For technical evidence, see the [living API reference](docs/bridgeos-api-reference.md).



---------

This template deliberately contains **no executable GitHub Actions implementation**. Repository automation is selected from the central `HereLiesAz/workflows` catalog.

## Start here

1. Rename/update the repository metadata and this README.
2. Choose automation in `.github/workflow-request.yml`.
3. Reuse existing central workflows and secrets whenever possible.
4. If a capability is missing, submit a generalized workflow to `HereLiesAz/workflows`; do not create a one-off local implementation.
5. Keep `version.properties` as the canonical project version state unless the repository has an established compatible version contract.

For framework-specific projects, prefer one of the dedicated templates:

- `HereLiesAz/android-app-template`
- `HereLiesAz/compose-multiplatform-template`
- `HereLiesAz/react-app-template`
- `HereLiesAz/gradle-library-template`

## T2 research reference

- [bridgeOS API & interface reference](docs/bridgeos-api-reference.md) — a living, evidence-graded inventory of observed T2/bridgeOS services, symbols, EFI variables, and open research questions.
