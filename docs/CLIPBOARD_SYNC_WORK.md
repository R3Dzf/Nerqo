# Clipboard sync and reliability work

## Implemented on this development branch
- Windows WPF application catches and stores startup, UI and background-task exception details in `%LOCALAPPDATA%\Nerqo\Logs\crash.log`. Existing user data is left untouched.
- Windows settings provide an explicit persisted opt-in for syncing *text* copied into the operating-system clipboard, including while minimized to the tray.
- Authenticated non-suspended Android peers can read/write a bounded clipboard snapshot via HTTPS, with the existing TLS fingerprint pinning and bearer token.
- Clipboard relay is in memory only (16 KiB UTF-8 limit), never placed in transfer history or saved state.
- Android settings provide explicit opt-in, with a foreground-only local clipboard listener, periodic polling, and echo suppression.

## Android platform limitation
Modern Android prevents ordinary apps from accessing other apps' clipboard while they are in the background. A long-running foreground service does not generally grant unrestricted clipboard access. A separate background-receive component is still needed to apply incoming Windows updates while the UI is closed, and must be checked on physical target devices. Do not claim background bidirectional automatic clipboard synchronization from this branch.

## Remaining release gates
1. Compile Windows WPF and Android release builds, run Windows native host smoke tests and Android instrumentation UI tests.
2. Reproduce reported Windows crash with `crash.log` (and Windows Event Viewer if needed), patch its actual cause, and rerun startup/upgrade smoke tests.
3. Verify that Android clipboard syncing works with the app foregrounded, stops promptly when toggled off or device suspended, and never replays previous text on opt-in.
4. Exercise reconnection after hotspot/Wi-Fi switching and foreground-service lifetime tests on a physical Android device.
5. Add real Android background-receive support (without unsupported background clipboard reads), opt-out notification, and power-management controls.
6. Verify CI succeeds before merging or tagging a Stable release.

No new release has been published from this work.
