# Nerqo

**Move freely. Stay local.**

Nerqo is a native Windows and Android application for transferring files, folders, links and text over a local network. A router does not need internet access; the devices can also use an Android hotspot.

> **Development branch:** `dev/native-5ghz-20261016`. Native 1.0.3 adds saved transfer sessions, Pause/Resume/Cancel, byte-range download recovery and idempotent upload completion to the matching Windows/Android interface introduced in 1.0.1. Native CI artifacts are development builds; no official Stable release is claimed. Physical-device acceptance remains required. The `main` branch and older v0.3.0 release are unchanged.

## Native applications

| Windows | Android |
|---|---|
| .NET 9 + WPF, normal desktop window | Kotlin + Jetpack Compose |
| Per-user installer, Start Menu shortcut, system tray | Native document picker and system Share integration |
| In-process .NET transport host | Native HTTPS client with certificate pinning |
| No browser or visible command window | No WebView, browser or cloud relay |

The old `app.py` / `web/` implementation is retained for migration tests only. It is **not** shipped as the new native Windows app.

## A calmer workspace

Both apps organize sharing into **Transfers**, **Devices** and **Settings**, with matching teal/mint identity and persistent light/dark appearance. Windows accepts files and folders dragged into its window. Android keeps operation progress visible across sections and reads the clipboard only when you tap **Paste**. The same scalable file-exchange mark appears in the Windows executable, installer and tray, and the Android adaptive/themed launcher.

## Getting connected

1. Put Windows and Android on the **same private Wi-Fi**, even if it has no internet. Alternatively, turn on the phone's hotspot and join it from Windows.
2. Open Nerqo on Windows. Its local receiver starts inside the app (TCP 44888), and the connection screen displays a pairing QR.
3. In Nerqo on Android, scan the QR. The app checks the Windows TLS certificate before sending the pairing code.
4. Confirm the computer's security identity on both devices and approve pairing.
5. Send files, links or text. Android-received files can be saved using Android's Save dialog; Android-to-Windows files arrive in `%LOCALAPPDATA%\Nerqo\Received`.

**No IP address needs to be opened in a browser.** If discovery fails, use the QR or the manual private LAN address. Check whether Windows Firewall blocks TCP 44888 or UDP 44889, or whether the Wi-Fi router has client isolation enabled.

## File and folder transfers

- File uploads: 4 MiB chunks, per-chunk SHA-256 and mandatory sender-verified full-file SHA-256 before publication, and resumable uploads after a disconnection.
- Windows → Android: share one or more files or a complete folder **directly**, preserving nested directories and empty folders without ZIP staging.
- Android → Windows: send files with the document picker or Android's Share menu; use **Send folder directly** to transfer an SAF-selected folder file by file, without temporary ZIP staging.
- Both folder directions stream files over HTTPS with verified SHA-256. Windows lists folder members in a manifest, and Android creates a folder tree using the system document provider.
- Android → Windows direct folder transfers persist each file’s resumable upload session. Windows → Android saves members directly to the selected Android folder without ZIP staging. Android folder receiving rehashes completed members before skipping them, and resumes the active member from a saved byte checkpoint where the document provider supports seeking.
- **Pause / Resume / Cancel:** Android saves the active job and source/destination permissions. Reopen the app to resume an interrupted job. Downloads checkpoint every 4 MiB, rehash the saved prefix and verify the complete file. Storage providers without seeking restart the active file safely. Cancel removes tracked unverified partial documents and attempts remote upload cleanup while preserving completed files.
- A lost upload-completion reply can be retried without publishing a duplicate file. Windows binds upload/folder sessions and completion receipts to the paired sender. Update Windows and Android together to 1.0.3 for the new upload protocol.

## Wi-Fi / hotspot performance

Prefer a **5 GHz** network when both devices and the access point support it. **6 GHz** can be faster under suitable conditions. Nerqo reports the actual available radio band where the OS exposes it; it cannot guarantee or forcibly change bands on every device. The network works on 2.4 GHz as a fallback. No mobile data or internet bandwidth is consumed by the local file transfer.

## Security

Nerqo uses local HTTPS/TLS, a persistent Windows certificate identity, Android certificate-fingerprint pinning, a six-digit pairing code and revocable device tokens. This is not a substitute for a full independent security audit. Use trusted private networks while the project is in development.

Android release signing uses a permanent, pinned public certificate fingerprint. **Private signing keys and passwords must never be committed.** Windows currently does not carry a publisher-trusted Authenticode signature.

## Builds and releases

- [Native Windows + Android CI](https://github.com/R3Dzf/Nerqo/actions/workflows/native-preview.yml)
- [Native implementation roadmap](docs/NATIVE_ROADMAP.md)
- [Offline Android release signing](docs/OFFLINE_RELEASE_SIGNING.md)

The native CI compiles WPF and Android, runs encrypted transport and integrity smoke tests, checks a per-user Windows installer upgrade from 1.0.1 to 1.0.3 with preserved pairing/data, and produces build artifacts (some CI artifact names retain the historical Preview suffix). The native Stable workflow is gated on `main`: it accepts only a native Windows installer and an Android APK signed with the permanent key, whose payload must match the independently built unsigned package. It does **not** publish the old Python/browser app.

The Android 35 emulator smoke passed five TLS/SAF transport checks, including interrupted file/folder recovery, a lost upload-finish reply and cancellation. It then restored a saved download, used the actual Compose Pause/Resume controls and verified the completed file against its source. Navigation and light/dark screens passed too. Version 1.0.3 keeps automatic list refresh from taking the worker while a saved transfer needs recovery. WPF screens are captured from the running Windows app.

Verified 1.0.3 runs: [native builds, transport and Windows upgrade](https://github.com/R3Dzf/Nerqo/actions/runs/38873724896) · [Android transport and native UI recovery](https://github.com/R3Dzf/Nerqo/actions/runs/38873592044).

**Release-validation limits:** Wi-Fi Direct/Bluetooth fallbacks (where supported), real Wi-Fi/hotspot/5 GHz transfer benchmarking, testing on physical Windows/Android devices, and upgrades on the user’s physical devices have not been completed. Passing CI validates the build and emulated transport, not every router or phone. The Windows installer has no Authenticode publisher certificate.
