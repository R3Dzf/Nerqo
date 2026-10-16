# Nerqo

**Move freely. Stay local.**

Nerqo is a native Windows and Android application for transferring files, folders, links and text over a local network. A router does not need internet access; the devices can also use an Android hotspot.

> **Development branch:** `dev/native-5ghz-20261016`. The native rebuild is not yet a stable release. The `main` branch and the older v0.3.0 release are unchanged.

## Native applications

| Windows | Android |
|---|---|
| .NET 9 + WPF, normal desktop window | Kotlin + Jetpack Compose |
| Per-user installer, Start Menu shortcut, system tray | Native document picker and system Share integration |
| In-process .NET transport host | Native HTTPS client with certificate pinning |
| No browser or visible command window | No WebView, browser or cloud relay |

The old `app.py` / `web/` implementation is retained for migration tests only. It is **not** shipped as the new native Windows app.

## Getting connected

1. Put Windows and Android on the **same private Wi-Fi**, even if it has no internet. Alternatively, turn on the phone's hotspot and join it from Windows.
2. Open Nerqo on Windows. Its local receiver starts inside the app (TCP 44888), and the connection screen displays a pairing QR.
3. In Nerqo on Android, scan the QR. The app checks the Windows TLS certificate before sending the pairing code.
4. Confirm the computer's security identity on both devices and approve pairing.
5. Send files, links or text. Android-received files can be saved using Android's Save dialog; Android-to-Windows files arrive in `%LOCALAPPDATA%\Nerqo\Received`.

**No IP address needs to be opened in a browser.** If discovery fails, use the QR or the manual private LAN address. Check whether Windows Firewall blocks TCP 44888 or UDP 44889, or whether the Wi-Fi router has client isolation enabled.

## File and folder transfers

- File uploads: 4 MiB chunks, per-chunk SHA-256 and full-file verification, and resumable uploads after a disconnection.
- Windows → Android: share one or more files, or select a folder. Folder structures (including empty folders) are preserved in a downloadable ZIP.
- Android → Windows: send files with the document picker or Android's Share menu; use **Send folder (ZIP)** to transfer a SAF-selected folder.
- Both folder actions show archive preparation progress. Archives are files, **not automatically extracted** on the destination.
- ZIP folder staging on Android currently needs enough temporary phone storage. If interrupted, a folder ZIP restarts; individual file uploads already support resume. A direct, no-staging folder transport is planned before the stable release.

## Wi-Fi / hotspot performance

Prefer a **5 GHz** network when both devices and the access point support it. **6 GHz** can be faster under suitable conditions. Nerqo reports the actual available radio band where the OS exposes it; it cannot guarantee or forcibly change bands on every device. The network works on 2.4 GHz as a fallback. No mobile data or internet bandwidth is consumed by the local file transfer.

## Security

Nerqo uses local HTTPS/TLS, a persistent Windows certificate identity, Android certificate-fingerprint pinning, a six-digit pairing code and revocable device tokens. This is not a substitute for a full independent security audit. Use trusted private networks while the project is in development.

Android release signing uses a permanent, pinned public certificate fingerprint. **Private signing keys and passwords must never be committed.** Windows currently does not carry a publisher-trusted Authenticode signature.

## Builds and releases

- [Native Windows + Android CI](https://github.com/R3Dzf/Nerqo/actions/workflows/native-preview.yml)
- [Native implementation roadmap](docs/NATIVE_ROADMAP.md)
- [Offline Android release signing](docs/OFFLINE_RELEASE_SIGNING.md)

The native CI compiles WPF and Android, runs transport tests and produces preview artifacts. The native Stable workflow is manual and gated: only a checked Windows Installer and a release-signed Android APK with matching, independently rebuilt payloads can be published from `main`. It does **not** publish the old Python/browser app.

**Still pending for v1.0 Stable:** tested Wi-Fi Direct/Bluetooth fallbacks (where supported), safe direct folder transfer without ZIP staging, broader network diagnostics, real-device testing on Windows and Android, and verified in-place upgrades. Preview build success does not equal production readiness.
