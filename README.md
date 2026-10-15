# Nerqo — Native Windows + Android (Development Preview)

**Move freely. Stay local.** Transfer files, links, and text between a Windows laptop and Android without an internet connection. Connect both devices to the same Wi-Fi network, or use a mobile hotspot.

> **Branch `dev/native-5ghz-20261016`: active native rebuild, not a stable release.** The existing `main` branch and published `v0.3.0` remain unchanged. Do not replace your installed signed Android app with this ephemeral CI debug build.

## Native apps, not browser wrappers

| Platform | Implementation | Build |
|---|---|---|
| Windows | C# .NET 9, WPF window, embedded ASP.NET Core transfer host, tray integration; no console or browser | `dotnet publish desktop/Nerqo.Desktop/Nerqo.Desktop.csproj -c Release -r win-x64 --self-contained true` |
| Android | Kotlin, Jetpack Compose, native document pickers and share intents; no `WebView` | `cd android && gradle :app:assembleDebug` |

Preview builds: [Nerqo native CI](https://github.com/R3Dzf/Nerqo/actions/workflows/native-preview.yml). Windows host smoke tests verify pairing/authentication, notes, chunk upload and file round-trip. The native preview workflow also builds a per-user Windows installer and stages an unsigned Android release APK for private offline signing.

## Transfer and pairing

1. Run **Nerqo.exe** on Windows. No terminal or browser should open.
2. Start Nerqo on your Android phone while both devices are connected to the same Wi-Fi or Android hotspot; disable mobile data to confirm offline operation.
3. Tap **Find nearby Windows device**, select your PC, then enter its six-digit pairing code.
4. Send text/links/files from either device. On Android, choose **Save** to download Windows-origin files. Android-origin files arrive in `%LOCALAPPDATA%/Nerqo/Received`.

If discovery fails, check Windows Firewall allows the app on your **private** network and that your Wi-Fi AP doesn't isolate wireless clients. Enter the private Windows IP in Android as a fallback. UDP 44889 handles discovery; TCP 44888 handles transfers. **No links or IP addresses need to be opened in a browser.**

## 5 GHz performance

Nerqo detects Wi-Fi frequency where Android/Windows permissions allow. Prefer **5 GHz**, or **6 GHz** if supported by both devices and hotspot/router; the local TCP transport automatically uses the established Wi-Fi radio. Android uses a secure system-selected local-only hotspot; select 5 GHz in system hotspot settings where available, rather than opening an unsecured custom AP. On other Android versions, select 5 GHz in the system hotspot settings if available.

**No Wi-Fi frequency is guaranteed by software.** 2.4 GHz still works as a fallback. Actual speed depends on your hotspot/router, Wi-Fi chipset, distance, channel width, signal and storage. 4 MiB file chunks, streamed reads/writes and resumable uploads avoid buffering whole files in memory.

## Security and roadmap

This is an **early preview for trusted private networks only**. PIN pairing and authenticated bearer tokens work, but LAN transport is currently **HTTP without end-to-end TLS encryption**. Don't use an untrusted public Wi-Fi hotspot. The nine-phase tracker and real-device acceptance tests are in [docs/NATIVE_ROADMAP.md](docs/NATIVE_ROADMAP.md).

Pending before stable release: QR pairing and advanced diagnostics, pause/folder UI, encrypted mutual authentication, Wi-Fi Direct and Bluetooth transports, native Windows delta updater and installer, and stable signed release/regression testing. The permanent Android signing certificate from `v0.3.0` must remain unchanged; private keys are never checked into Git.

Legacy `app.py` and `web/` are retained temporarily for migration and historical tests; neither is part of the native Windows executable.
