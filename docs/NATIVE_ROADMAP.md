# Nerqo native implementation status

Stable LAN/hotspot channel: **1.0.4** on `main`; **1.0.5** is being validated on `dev/native-5ghz-20261016`.
This document records implementation and validation separately. A green build is
not evidence of a successful transfer between physical Windows/Android devices.

## The nine agreed phases

The 1.0.5 candidate limits Firewall rule diagnostics to attributable executable
rules, prevents duplicate Windows instances, restores the running window on a
second launch, and exposes receiver startup errors with retry and explicit
diagnostic copying. The host smoke test reproduces an occupied TCP port and
retries the same receiver after releasing it. Existing older tray instances must
be exited once before the first launch of this update.

| Phase | Implemented | Remaining |
|---|---|---|
| 01 Native transport | In-process Kestrel HTTPS; native Android client; authenticated streaming APIs | Physical-device, sleep/resume and router interoperability |
| 02 Windows | WPF WinExe; tray; normal per-user installer; drag/drop files and folders; Transfers/Devices/Settings; light/dark appearance | Physical installation/upgrade and firewall acceptance |
| 03 Android | Kotlin/Compose; native document picker; Share intents; matching three-section UI; adaptive/themed launcher icon | Physical-phone lifecycle, accessibility and SAF-provider checks |
| 04 Connection | Offline QR with pinned TLS fingerprint, UDP discovery, manual private IP, network/QR refresh | DNS-SD and multi-interface physical network checks |
| 05 Files/folders | Both directions stream folders without ZIP; empty directories; chunk/final SHA-256; resumable uploads and seekable downloads; retained Android jobs; explicit Pause/Resume/Cancel; verified completed-member retry; transfer speed/ETA; owner-bound idempotent upload receipts | Physical interruption/process-lifecycle checks, overall multi-file upload ETA and large-directory benchmarking |
| 06 Text/links | Two-way text and links; copy; Android share target; explicit Paste button | Optional clipboard sync remains unimplemented; clipboard is read only on explicit Paste |
| 07 Security | Persistent Windows TLS identity; QR/manual identity verification; Android pinning; revocable peer tokens; path/reparse checks | Independent audit and broader adversarial/lifecycle testing |
| 08 Connection alternatives | Same LAN; normal phone hotspot; system-managed Android local-only hotspot with generated secure credentials | Native Wi-Fi Direct and Bluetooth transports are not implemented |
| 09 Delivery/updates | WPF installer; permanent Android certificate pin; stable-only native updater; full/delta reconstruction; coordinated native versioning; source/payload/signing checks; per-user installer 1.0.1 → 1.0.3 tested on a Windows CI runner | Physical in-place upgrade; Windows Authenticode publisher signature; broader physical-device acceptance |

## UI and identity (1.0.1)

The same teal, mint and paper palette is used by WPF and Compose. Both have
Transfers, Devices and Settings, independent persistent light/dark preferences,
visible operation feedback and connection guidance. Android preserves the API's
newest-first activity order rather than sorting random UUIDs. Windows stages and
hashes shares off the UI dispatcher, supports native file/folder drops and uses
the app's icon in the title bar, executable, installer and system tray.

`assets/brand/nerqo.svg` is the scalable brand source: two transfer paths surround
a file. Windows PNG/ICO and Android vector/adaptive/monochrome resources implement
the same mark. No browser, console, Python interpreter or cloud relay is launched
by the native apps.

## Saved transfers and folder recovery (1.0.3)

Android retains the active transfer job and document permissions. Pause closes
active requests and document streams; Resume reopens and verifies saved state.
Uploads require Windows transfer protocol 2 so a committed file whose finish
reply was lost is verified and acknowledged instead of uploaded a second time.
Another paired sender cannot claim or cancel the original sender's session.

Select the same Windows share and the same Android destination tree after an
interruption. Completed files are skipped only when a saved receipt belongs to
that transfer/destination **and** reading the current saved bytes reproduces the
manifest's size and SHA-256. Changed or unrelated existing files are preserved
and reported as conflicts. The active incomplete file resumes from the last
4 MiB checkpoint when the SAF destination is seekable; providers exposing only
pipes safely restart that member. A server ignoring Range causes a clean restart,
never appending a whole response to an existing prefix. Full-file SHA-256 must
match before folder members are renamed to their final names. Cancel cleans
tracked unverified partials and preserves verified members. The app shows overall
bytes for folder receiving. Receipts persist through app restarts.

## Wi-Fi performance

Prefer **5 GHz**, or **6 GHz** where supported, using router/hotspot settings.
The apps report OS-provided band information and do not claim to force the radio
band. 2.4 GHz remains a fallback. There is no internet requirement for LAN
transfer, including QR scanning with bundled ZXing. Checking/downloading app
updates requires internet. No throughput claim has been verified on physical
hardware.

## Validation and release gate

- Native CI builds WPF, runs the authenticated LAN/integrity/Delta smoke suite,
  captures real WPF screens, and builds Android Debug and unsigned Release APKs.
- Android 35 emulator smoke passed all five actual TLS/SAF transport checks: interrupted file download, folder retry with empty directories, a deliberately lost upload-finish reply, cancellation before the first byte and mid-file, and saved-job setup. The subsequent native UI smoke restored that job, paused it, resumed it to completion and verified the saved bytes against the source SHA-256. Navigation and light/dark capture passed. The SAF fixture exists only in the Debug build and is excluded from Release.
- Android JVM tests exercise the saved-member integrity decision with exact,
  empty, truncated, oversized and same-size altered content.
- The 1.0.3 CI JVM run passed all 15 tests: saved-member integrity,
  byte-range resume, malformed/ignored ranges, altered/truncated content,
  nonseekable storage and responsive asynchronous stop. The Python regression
  suite passed all 36 tests in CI (the local JRE-only run skipped one Java
  compilation check). These do not replace physical-device acceptance.
- The Stable workflow only publishes from `main`, with both platforms successful
  and a permanent-key-signed APK matching the independently compiled payload.
- Installer upgrade smoke installs the previous native 1.0.1 build, upgrades to 1.0.3, and checks the persistent TLS identity, authorized device token, shared note and received file. This passed on a disposable Windows CI runner, not the user’s PC.
- A debug APK is for development testing. It cannot update the permanent-key
  installation; do not uninstall the stable installation to try it.
- The signing private key remains outside the public repository. Builds here do
  not create a replacement key. Windows has no trusted Authenticode signature.

Validated native build source: `13a790c27e89bf4cd3d6bc621a9788a8694083f4`,
[CI 38873724896](https://github.com/R3Dzf/Nerqo/actions/runs/38873724896).
The emulator used the identical production sources at
`6297e432dac868509403c9affa1a2634d1ae65a6`,
[UI 38873592044](https://github.com/R3Dzf/Nerqo/actions/runs/38873592044).
Only a CI path-filter configuration differs between those commits. Both runs
passed; this records emulated transport and build acceptance. It does not
measure physical radio throughput. The Stable release gate repeats automated QA.

For broader physical-device acceptance, test QR pairing; files/text/folders both ways with hashes; network
interruption/retry; cellular disabled; 5 GHz and 2.4 GHz; local hotspot; firewall;
permissions; low storage; screen sleep; tray exit; install and in-place upgrade
on the actual Windows PC and Android phone. Wi-Fi Direct/Bluetooth and clipboard
sync stay marked incomplete until implemented and tested.

## Stable scope

The 1.0.3 Stable channel covers implemented native LAN/hotspot sharing and recovery.
It does not close all nine roadmap phases. Wi-Fi Direct/Bluetooth, independent
audit and the physical-device matrix remain explicitly open. See
[release notes](releases/v1.0.3.md) for supported scope and limitations.

## Windows permission and appearance (1.0.4)

Windows checks private-network Firewall rules before starting the listener. First
use offers a consent dialog and native UAC approval for executable-specific TCP
44888 / UDP 44889, Private profile, LocalSubnet scope. No firewall protection,
public-network access, explicit blocking rules or network classification is
changed. A persistent card supports later approval/recheck and Windows settings.
The native helper adds no terminal or browser dependency.

The desktop appearance control now has an accessible theme-aware switch template,
keyboard focus and persistent light/dark state. CI exercises the actual Toggle
provider and color/persistence changes, captures Settings in both themes, and
creates/verifies/removes narrow Firewall rules on a disposable Windows runner.
The secure-desktop UAC interaction is still a physical-user acceptance item.
