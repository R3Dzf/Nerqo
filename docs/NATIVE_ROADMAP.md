# Nerqo native implementation status

Development branch: `dev/native-5ghz-20261016`. Native version: **1.0.1**.
This document records implementation and validation separately. A green build is
not evidence of a successful transfer between physical Windows/Android devices.

## The nine agreed phases

| Phase | Implemented | Remaining |
|---|---|---|
| 01 Native transport | In-process Kestrel HTTPS; native Android client; authenticated streaming APIs | Physical-device, sleep/resume and router interoperability |
| 02 Windows | WPF WinExe; tray; normal per-user installer; drag/drop files and folders; Transfers/Devices/Settings; light/dark appearance | Physical installation/upgrade and firewall acceptance |
| 03 Android | Kotlin/Compose; native document picker; Share intents; matching three-section UI; adaptive/themed launcher icon | Physical-phone lifecycle, accessibility and SAF-provider checks |
| 04 Connection | Offline QR with pinned TLS fingerprint, UDP discovery, manual private IP, network/QR refresh | DNS-SD and multi-interface physical network checks |
| 05 Files/folders | Both directions stream folders without ZIP; empty directories; chunk/final SHA-256; resumable uploads; verified completed-member retry on Android folder receiving; overall folder download progress | Mid-file Android download resume, explicit pause/cancel, overall upload ETA and large-directory benchmarking |
| 06 Text/links | Two-way text and links; copy; Android share target; explicit Paste button | Optional clipboard sync remains unimplemented; clipboard is read only on explicit Paste |
| 07 Security | Persistent Windows TLS identity; QR/manual identity verification; Android pinning; revocable peer tokens; path/reparse checks | Independent audit and broader adversarial/lifecycle testing |
| 08 Connection alternatives | Same LAN; normal phone hotspot; system-managed Android local-only hotspot with generated secure credentials | Native Wi-Fi Direct and Bluetooth transports are not implemented |
| 09 Delivery/updates | WPF installer; permanent Android certificate pin; stable-only native updater; full/delta reconstruction; coordinated native versioning; source/payload/signing checks; per-user installer 1.0.0 → 1.0.1 tested on a Windows CI runner | Physical in-place upgrade; Windows Authenticode publisher signature; final acceptance before Stable |

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

## Folder receive retry

Select the same Windows share and the same Android destination tree after an
interruption. Completed files are skipped only when a saved receipt belongs to
that transfer/destination **and** reading the current saved bytes reproduces the
manifest's size and SHA-256. Changed or unrelated existing files are preserved
and reported as conflicts. The active incomplete file starts again; this is
whole-folder retry at file boundaries, not mid-file byte resume. The app shows
overall bytes for folder receiving. Receipts persist through app restarts.

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
- Android 35 emulator UI smoke navigates the actual Compose screens and captures light/dark output, including system-bar contrast.
- Android JVM tests exercise the saved-member integrity decision with exact,
  empty, truncated, oversized and same-size altered content.
- The Stable workflow only publishes from `main`, with both platforms successful
  and a permanent-key-signed APK matching the independently compiled payload.
- Installer upgrade smoke installs the previous native 1.0.0 build, upgrades to 1.0.1, and checks the persistent TLS identity, authorized device token, shared note and received file. This runs on a disposable Windows CI runner, not the user’s PC.
- A debug APK is for development testing. It cannot update the permanent-key
  installation; do not uninstall the stable installation to try it.
- The signing private key remains outside the public repository. Builds here do
  not create a replacement key. Windows has no trusted Authenticode signature.

Before Stable, test QR pairing; files/text/folders both ways with hashes; network
interruption/retry; cellular disabled; 5 GHz and 2.4 GHz; local hotspot; firewall;
permissions; low storage; screen sleep; tray exit; install and in-place upgrade
on the actual Windows PC and Android phone. Wi-Fi Direct/Bluetooth and clipboard
sync stay marked incomplete until implemented and tested.
