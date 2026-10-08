# bridgeOS API & interface reference

> **Living research notes — 2026-10-08.** An evidence-based inventory of T2/bridgeOS APIs and related interfaces observed on one device. This is **not** a private-API specification, proof of write authorization, or a validated procedure for modifying Secure Boot.

## Test environment

| Property | Observed |
| --- | --- |
| Mac | Intel MacBook Air A1932, `MacBookAir8,1` |
| BridgeOS board | `iBridge2,8` / `j140kap`, ARM64 T8010 |
| Kernel | Darwin `25.6.0`, `xnu-12377.161.13~124/RELEASE_ARM64_T8010` |
| Access | palera1n/checkm8; root SSH via `iproxy` to bridgeOS USB port 44 |
| Host | Ubuntu Linux |
| Evidence date | 2026-10-08 |

A successful `id` reported `uid=0(root)`. This establishes privileged bridgeOS userspace access, **not** permission to change Intel host Secure Boot, SEP policy, or Activation Lock.

## Evidence grades

- **Runtime confirmed** — directly shown by `ioreg`, `ps`, `uname` or a device command.
- **String observed** — literal text recovered from a binary; exports, signature, and execution remain unverified.
- **Inferred** — interpretation based on names/context, not directly tested.
- **Not established** — no supporting observation yet.

## IOKit service and user-client inventory

| Interface | Runtime evidence | Unknowns |
| --- | --- | --- |
| `MacEFIManager` | Registered IOKit service; bundle `com.apple.driver.MacEFIManager`; provider `AppleARMIODevice`; properties include `Variables`, `AppleSecureBootPolicy = 2`, `RecoveryPolicy = No`; user-client class `MacEFIManagerUserClient` | External method selectors, structures, entitlements, write access |
| `MacEFIManagerUserClient` | Two active instances: creators `pid 42, multiversed` and `pid 56, powerchimed`; `IOUserClientDefaultLocking = Yes` | Call signatures, whether any policy writes are authorized |
| `IODTNVRAM` | `options` node, ordinary `boot-args`, `auto-boot`, `bootdelay` and NVRAM diagnostics | How it relates to GUID-scoped Intel EFI variables |
| `IODTNVRAMVariables` | `options-common` node with ordinary NVRAM values | Not an inventory of the EFI-manager variable dictionary |
| `MacEFINvramManager` | Class name appears in `IOKitDiagnostics`; targeted `ioreg -c MacEFINvramManager` returned **no service** | Whether any instance is live on this firmware |
| `AppleCredentialManager` | Registered; bundle `com.apple.driver.AppleSEPCredentialManager`; advertises `AppleCredentialManagerUserClient` | Selectors, entitlements, credential handling |
| `AppleCredentialManagerUserClient` | User-client class name observed | No direct invocation |
| `AppleSEPManager` | Registered; bundle `com.apple.driver.AppleSEPManager`; `sep-booted = Yes`, `HasXART = Yes`; advertises `AppleSEPUserClient` | Command protocol, security authorization |
| `AppleSEPUserClient` | User-client class name observed | No direct invocation |
| `AppleSEPXARTService` | Child SEP service with `XartApToSep = Yes` | Protocol and semantics |
| `AppleFirmwareUpdateKext` | Child of `MacEFIManager` in observed tree; advertises `AppleFirmwareUpdateUserClient` | Firmware update API, verification, permissions |
| `AppleFirmwareUpdateUserClient` | User-client class name observed | No direct invocation |

Presence of a kernel class or a user client is **not** evidence of an unrestricted API or a security bypass.

## Userspace components

| Component | Observed fact | Status |
| --- | --- | --- |
| `/usr/libexec/powerchimed` | Running at PID 56; connected to `MacEFIManagerUserClient`; Mach-O arm64 | Runtime confirmed |
| `/usr/libexec/multiversed` | Running at PID 42; connected to `MacEFIManagerUserClient`; Mach-O arm64 | Runtime confirmed |
| `/usr/lib/libMacEFIHostInterface.dylib` | Referenced by `powerchimed`; not a regular on-disk file in tested image | String observed; library loading unverified |
| `/usr/local/lib/libMacEFIHostInterface.dylib` | Alternate path referenced by `powerchimed`; not a regular on-disk file | String observed; library loading unverified |
| `/System/Library/Caches/com.apple.dyld/dyld_shared_cache_arm64*` | Main cache plus subcaches present | Runtime confirmed; EFI library membership unverified |

The absence of a separate dylib does not establish that its code is absent; it may be in a dyld shared cache, or a dormant fallback.

### C-level symbol/signature candidates from powerchimed

These are **strings found in the `powerchimed` binary**. They are not confirmed exported symbols or validated prototypes.

| Symbol / name | Literal or indicative string | Status |
| --- | --- | --- |
| `libMacEFIHostInterfaceLibrary` | `void *libMacEFIHostInterfaceLibrary(void)` | String observed |
| `createNvramHostInterface` | `HNvramHostInterface *soft_createNvramHostInterface(const char *)` | String observed; wrapper signature only |
| `destroyNvramHostInterface` | `kern_return_t soft_destroyNvramHostInterface(HNvramHostInterface *)` | String observed; wrapper signature only |
| `getNVRAMVariable` | `kern_return_t soft_getNVRAMVariable(HNvramHostInterface *, char *, char **, uint32_t *)` | String observed; wrapper signature only |
| `IOServiceGetMatchingService` | Referenced by binary | String observed; standard IOKit call |
| `IOServiceOpen` | Referenced by binary | String observed; standard IOKit call |
| `IOConnectCallScalarMethod` | Referenced by binary | String observed; standard IOKit call |
| `IOConnectCallStructMethod` | Referenced by binary | String observed; standard IOKit call |

Additional names found: `HNvramHostInterface`, `macEFIConnection`, `nvramHost`, `setMacEFIConnection:` and `setNvramHost:`. These suggest a read-side NVRAM host interface used by `powerchimed`; the available strings **do not establish a set-variable function**. The basic `multiversed` strings scan gave no comparable EFI-specific matches.

## GUID-qualified EFI variables (data, not API functions)

Values are **observations from one device**, not recommended values or universal defaults.

| GUID | Variable | Raw data |
| --- | --- | --- |
| `94B73556-2197-4702-82A8-3E1337DAFBFB` | `AppleSecureBootPolicy` | `02` |
| same | `AppleSecureBootUefiPolicy` | `02` |
| same | `AppleSecureBootManagedPolicy` | `00` |
| same | `AppleSecureBootWindowsPolicy` | `00` |
| same | `AppleSecureBootWindowsManagedPolicy` | `00` |
| same | `AppleSecureBootFailureReason` | `00` |
| same | `AppleSecureVariableWriteInRecoveryTest` | `00` |
| `5EEB160F-45FB-4CE9-B4E3-610359ABF6F8` | `StartupManagerPolicy` | `00` |
| same | `StartupManagerManagedPolicy` | `00` |
| `7870DBED-151D-63FE-F588-7C69941CD07B` | `FirmwareSecurityMitigationsPolicy` | `0a000000` |
| `60B5E939-0FCF-4227-BA83-6BBED45BC0E3` | `BootState` | `01` |

`MacEFIManager` also reported the registry property `AppleSecureBootPolicy = 2`. The exact relationship to the GUID variable remains unverified.

**Negative test:** `/usr/sbin/nvram 94B73556-2197-4702-82A8-3E1337DAFBFB:AppleSecureBootPolicy` returned `(iokit/common) data was not found` even though the variable was visible in `MacEFIManager.Variables`. Thus the standard `nvram` utility is **not** a confirmed reader of that EFI namespace; no policy write was attempted.

A device-tree `secure-boot = <01000000>` and `cs-system-policy = "default"` were also observed. Neither can be assumed to equal Intel host boot-policy settings.

## Reproducible read-only inspection

Run these **inside bridgeOS**. On Linux use an `iproxy LOCALPORT 44` tunnel, then `ssh -p LOCALPORT root@127.0.0.1`.

~~~sh
id
uname -a
ps -A -o pid,comm | grep -E 'multiversed|powerchimed'
ioreg -p IOService -r -c MacEFIManager -l -w 0
ioreg -p IOService -r -c AppleCredentialManager -l -w 0
ioreg -p IOService -r -c AppleSEPManager -l -w 0
ioreg -p IOService -r -c IODTNVRAM -l -w 0
nvram -p
~~~

**Privacy warning:** Complete `MacEFIManager` dumps may include ECIDs, Mac serials, board identifiers, EFI certificates, boot UUIDs, and network data. Redact before publishing.

Linux USB observations: `05ac:1227` was DFU mode; `05ac:8600` was a running iBridge. The T2 temporarily returned to DFU during experimentation and was subsequently booted into bridgeOS again. This alone is not evidence of a permanent brick.

## Open questions / work queue

1. Search dyld cache images for `libMacEFIHostInterface`; verify any extracted symbols.
2. Confirm ABI, ownership, return values, and invocation behavior of `createNvramHostInterface` / `getNVRAMVariable` / `destroyNvramHostInterface`.
3. Identify read-only `MacEFIManagerUserClient` selectors actually used by the observed services.
4. Determine whether a policy write interface exists and which authorizations it requires. **Not established.** Root access does not imply SEP authorization.
5. Verify any EFI value-to-policy interpretation against versioned sources before recording it as fact.
6. For every new API record: **name, provenance (binary/service/version), observed signature, tested call/result, confidence, authorization/security caveats**.

## Source artifacts (private, not committed)

- Initial registry logs: `01_identity.log`, `02_nvram_boot_fields.log`, `03_efi_manager.log`, `04_efi_nvram_manager.log`, `05_nvram_service.log`, `06_credential_manager.log`, `07_sep_manager.log`, `08_boot_properties.log`.
- User-client inspection: `01_running_clients.log`, `02_efi_userclient_connections.log`, `04_binary_metadata.log`, `05_multiversed_efi_strings.log`, `05_powerchimed_efi_strings.log`.
- Dylib inspection: `01_connection.log`, `02_library_locations.log`, `03_loader_locations.log`, `04_local_tools.log`, `08_powerchimed_interfaces.log`.

**Publication rule:** Include observed names and reproducible, redacted evidence; never include unredacted device IDs, Wi-Fi data, credentials, unique serials, or user data. Keep observations distinct from guesses and untested hypotheses.

---

*Living document — add entries and revise confidence as stronger evidence arrives.*
