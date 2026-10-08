# Burning iBridge

Tested on Macbook Air T2 Intel 2019, model A1932.

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
