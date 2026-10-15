# Nerqo

**Move freely. Stay local.**

Nerqo is a local-first file, folder, link and text transfer companion for Windows and Android. Share over the same Wi-Fi network or phone hotspot without consuming your internet plan.

## Experience

- Redesigned desktop and mobile-responsive interface with a balanced dark palette, light mode, accessible controls and transfer progress
- Native Android pairing screen, automatic LAN discovery and "Share" support for links and text
- Resume large transfers, pair devices with a six-digit code, and access shared items on either device
- Delta updates with SHA-256 checks and full-package fallback

## Run on Windows

Run `Start-Nerqo.bat` or `python app.py` with Python 3.11+; the locally hosted UI opens in your browser. Connect your Android device through the same trusted local network. Android can use the app or a web browser.

## Build and releases

GitHub Actions automatically tests and builds Windows EXE and **debug Android APK** for ordinary pushes. The **tagged release** pipeline requires the permanent Android release signing key held outside the public repository in GitHub Actions secrets; it verifies the certificate against `android/RELEASE_SIGNING_CERT.sha256`. The private keystore and recovery credentials must never be committed.

See [docs/SIGNING.md](docs/SIGNING.md) for steps and the important Android update-signature compatibility rule; see [docs/UPDATES.md](docs/UPDATES.md) for the delta protocol.

## Security and maturity

Nerqo is still an early prototype. Transfers currently use HTTP over a **trusted private local network**, not end-to-end encryption. There is no Bluetooth or Wi-Fi Direct implementation yet, and Android/Windows installers have not been end-to-end tested on real devices. **A Windows EXE is not Authenticode-signed**; Android release signature and Windows publisher signing are different mechanisms.

MIT license not yet selected; all rights reserved unless a license file is added.
