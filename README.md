# Burning iBridge

Tested research hardware: Intel T2 MacBook Air A1932, MacBookAir8,1 (iBridge2,8).

## Burning-iBridge — Compose Desktop (Linux / macOS)

**[Compose GUI source](src/main/kotlin/dev/hereliesaz/burningibridge/Main.kt)** · **[Setup and operator guide](docs/COMPOSE.md)** · [bridgeOS API reference](docs/bridgeos-api-reference.md)

The main application is a **Kotlin Compose Desktop GUI** targeting Linux and macOS with a JVM/Kotlin orchestration backend. The previous [Tkinter Python preview](burning_ibridge_gui.py) is preserved for reference, but is no longer the primary UI.

Features currently implemented in source:

- Runtime tool manager: downloads platform/architecture-specific **palera1n v3 CLI** and **ipsw** releases from upstream, verifies SHA-256, and installs them into a per-user tools directory.
- Installer for USB/SSH dependencies through Ubuntu/Debian `apt` + `pkexec` or macOS Homebrew, with explicit user authorization.
- Graphical DFU/USB detection, interactive palera1n launch, managed `iproxy`, SSH host-key fingerprint inspection/trust, password-authenticated SSH.
- Experiment script queue, custom root commands, per-command logs, dyld cache/subcache retrieval via SFTP, and symbol discovery through `ipsw dyld`.
- OS image checksum and an installation preparation checklist.

**To run from source:** JDK 21, then `bash launch.sh` (downloads a SHA-256-verified Gradle distribution automatically) or `gradle run` if Gradle is already installed. To package: `gradle packageDistributionForCurrentOS`. See [docs/COMPOSE.md](docs/COMPOSE.md) for prerequisites, supported tools and the operator workflow.

**Status:** First implementation committed; **native packages, Gradle tests and live GUI hardware integration have not yet been verified**. Third-party binaries are downloaded **at runtime**, not included in the Git repository. Host package setup requires administrator approval. Alternative OS installation, Intel Secure Boot policy writes, and Activation Lock removal are **not implemented**.



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
