# Nerqo

**Send anything. Stay offline.**

An open, local-first sharing app for **Windows & Android**. Send files, links, and text over a shared Wi-Fi connection or a phone hotspot — no Internet connection, cloud account, or subscription required.

`Windows` · `Android` · `Local Wi-Fi` · `Hotspot` · `Files` · `Links` · `Text`


Transfer **files, multiple files/folders, web links and plain text** over a local Wi-Fi network. No cloud, no accounts, and no Internet required when the two devices are already on the same Wi-Fi network or phone hotspot.

> **Current code: v0.2.0 (working LAN prototype plus opt-in delta update implementation, not production hardened).** The tested Python host and mobile browser are usable today. An Android WebView companion application is included as source and has a CI build configuration; the APK must be compiled and tested on a real device. This version **does not** offer Bluetooth, Wi-Fi Direct, automatic hotspot creation, LAN encryption or background Android clipboard synchronization.

## Start on Windows

1. Extract the project archive.
2. Use Python **3.10+** installed on Windows. No `pip install` needed.
3. Double-click `Start-Nerqo.bat` (or run `py -3 app.py`).
4. The local web application opens in your browser. The terminal displays the pairing PIN and available `http://IP:44888` addresses.
5. Keep the terminal open. Allow private-network traffic in Windows Firewall if prompted. If discovery does not work, enter the IP shown by the host.

From the **Android phone**, connect to the same Wi-Fi or enable the phone's hotspot and connect the laptop to it. In Chrome on the phone, open `http://LAPTOP-IP:44888` and enter the six-digit PIN shown on Windows. You can now choose files on either device, upload them, download them from **Transfers**, and send links or text instantly.

Note: The **phone does not need to install an APK** for this browser workflow. The browser and Windows both use the same local inbox. Files physically remain in `~/Nerqo-Received/files` on the laptop; phone downloads go to the normal Downloads folder. The Windows browser must stay open to immediately see new transfers (or reopen it to view the history).

## Optional native Android APK

An Android project is provided in `android/`, with device discovery over UDP, local-IP entry, Android share intents for plain text, file selection, and Downloads integration. It connects to the desktop host; it **does not run an independent file server on the phone**.

Use Android Studio to open `android/`, install Android SDK API 36, and build `app:assembleDebug`. The `.github/workflows/build.yml` workflow also builds an APK and Windows executable when the project is pushed to your own GitHub repository. Android build requires downloading the build tools/dependencies once; the **app's runtime transfers don't use Internet**.

On Android 17, the `ACCESS_LOCAL_NETWORK` permission is needed if targeting SDK 37+. This prototype targets 35, but update and test its permissions before retargeting. Bluetooth/Wi-Fi Direct need additional platform-specific implementation and permission handling.

## What works today

- Mobile-responsive light/dark web app, local share feed and device PIN pairing.
- Manual IP access + optional UDP LAN discovery (native Android client); no internet or router needed if hotspot connects the devices.
- Links (`http` / `https`) and plain text, with copy / open controls. Supported Android native text share intent.
- Multiple files, desktop folder picker (browser compatibility varies), streamed **2 MiB chunks**, crash-safe resume, progress/pause/retry when same file is reselected.
- Server-side relative-path sanitization, per-transfer ownership, upload bounds, range downloads, and manual deletion.
- Paired sessions expire after 30 days; pairing rate limit, revocation, same-site cookie protections.
- 100% local content: no external APIs or servers in the runtime path.

## Privacy & security caveats

This version uses **plain HTTP on a LAN**, not encrypted transport and **not E2EE**. Someone who can observe traffic on a hostile Wi-Fi network might see links/files or cookies. Use a **private, trusted network**, never public Wi-Fi for sensitive content. PIN pairing prevents unauthenticated access but is not a substitute for transport encryption.

The Windows host needs inbound TCP **44888**, and UDP **44889** only for discovery. For best safety, only allow these ports on **Private** networks; do not forward them on the router. If Windows Firewall blocks local connections, allow Python/Nerqo on the private profile only. No remote Internet access is built in.

There's no built-in quota: transfer sizes are limited by available disk space, browser/OS capabilities, and filesystem. Android browser folder picker support differs across versions. The full folder hierarchy is preserved in item names; download items individually at present.

## Configuration

```
py -3 app.py --port 44888 --data-dir "D:\\OfflineTransfers"
py -3 app.py --no-browser
```

## Test the host (Python 3.10+)

```
python -m unittest discover -s tests -v
```

## Delta updates (Windows + Android)

Nerqo 0.2.0 includes an optional shared binary-delta updater. Local file sharing
**does not need Internet**, but checking GitHub Releases for a newer application
does. Delta packages are only selected when the locally installed base binary
matches the expected SHA-256 and the patch is at least 10% smaller than the
full update. Otherwise the official full binary is downloaded. The
reconstructed EXE/APK is verified before installation.

- **Windows:** In the local desktop interface, open **Connect → Check for updates**.
  On the packaged EXE, choose **Download update**, then **Install & restart**.
  Self-replacement is not offered when running raw Python source.
- **Android APK:** Open **Check for app updates** on the connection screen or
  **Check for updates** in the native menu. Confirm the Android package installer;
  the APK must use the SAME signing certificate as the installed app.
- **Publishing releases:** See [docs/UPDATES.md](docs/UPDATES.md) for signing,
  release tags, manifest generation and troubleshooting. Requires an Android
  signing keystore in Actions secrets, never in the repository.

**A v0.1.0 installation has no self-update client.** Upgrade to a consistently
signed v0.2.0 package manually once, then later matching releases can use delta.
No official signed production release has been published merely by merging source.
Windows binaries are SHA-256-verified but not Authenticode-signed.

## Roadmap to 1.0

- Authenticated TLS with pinned device certificates / E2EE, key rotation.
- Native Android receive service / optional incoming transfer approval.
- Robust Wi-Fi Direct peer group creation, Bluetooth small payload fallback.
- Automated hotspot setup where OS policies allow it.
- Clipboard sync with opt-in and sensitive content filters.
- One-click folder export preserving structure.
- App installers, stable signed distribution, real Windows 11 / Android device testing.

No paid services or hosted infrastructure are required.

## Project structure

| Directory | Purpose |
|---|---|
| `nerqo/` | Local HTTP transport and persistent transfer store |
| `web/` | Desktop/mobile browser user interface |
| `android/` | Native Android companion and discovery |
| `tests/` | Python unit and HTTP integration tests |
| `.github/workflows/` | Windows and Android build automation |

## Status and contributions

Nerqo is an early, actively developed prototype. Security and device compatibility are still being improved. Please test on private local networks only; do **not** treat this release as a secure transfer tool for confidential material. Bug reports and reproducible test cases are welcome.

Maintainer: [R3Dzf](https://github.com/R3Dzf).

