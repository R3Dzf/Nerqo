# Nerqo native rebuild — Windows + Android

Branch: `dev/native-5ghz-20261016`. This preview is **not a production release**.

## Native architecture (replacing Python/WebView)

- Windows: `.NET 9 / WPF`, built as a **WinExe**. Internal ASP.NET Core Kestrel listener shares process with the UI. No Python, shell, terminal or browser is opened by Nerqo. Minimizes to tray after initial launch. Receives files into `%LOCALAPPDATA%/Nerqo/Received`.
- Android: Kotlin + Jetpack Compose, no WebView. Sends text, links, single/multiple files using the document picker, receives files using the system Save dialog. Automatic UDP LAN discovery + IP fallback.
- Same-network and hotspot use the same LAN interface, no WAN service. Both clients must be on the same subnet; AP/client isolation and Windows Firewall can prevent discovery or connections.
- QR pairing: Windows displays an offline QR containing the SHA-256 TLS fingerprint, private IPv4 candidate addresses and rotating pairing PIN. Android scans locally using bundled ZXing, verifies the live certificate matches the QR fingerprint **before sending credentials**, and pairs automatically. Camera-only; no Google Play Services or Internet required.\n- UDP broadcast discovery: port `44889`, request `NERQO_DISCOVER_V1`. Replies include device name, port 44888, and (where detectable) Wi-Fi band. Does not reveal pairing PIN.
- TCP transport: HTTPS/TLS on `44888`, paired bearer token, 4 MiB streaming chunks, SHA-256 chunk/file verification, resumable upload state. Windows-origin files are staged inside app data and mobile polls the shared item list. Android-origin files are saved in Windows Received. **TLS encrypted after verified pairing:** Android checks the full pinned certificate fingerprint, but independent security auditing is pending.

## 5 GHz priority — the physical layer

Nerqo **does not control Wi-Fi radio frequency by opening a TCP socket**. Both apps detect the current radio band (when the OS allows) and recommend 5 GHz or 6 GHz. For typical phone hotspots, set 5 GHz in the Android system hotspot settings, if the hardware/region permits. On standard LANs, connect both devices to the 5 GHz SSID. Windows uses the WLAN interface's reported band/channel; Android uses `WifiInfo.frequency` and link speed. Android creates a system-managed secured local-only hotspot, leaving radio-band selection to the OS; select 5 GHz in system hotspot settings where allowed. Do not downgrade hotspot security to try to force a band. When a local Wi-Fi network has no Internet, Android binds Nerqo to the Wi-Fi network instead of defaulting to cellular. Some OS builds or privacy permissions report `Unknown` — never report 5 GHz without evidence.

The actual MB/s is limited by negotiated Wi-Fi PHY, airtime contention, AP hardware, storage, and signal strength; 5 GHz is not a guaranteed speed. On 2.4 GHz-only devices, use a safe fallback rather than breaking transfer.

## Nine-phase tracking

| Phase | Status | Evidence/remaining work |
|---|---|---|
| 01 Native transport protocol | Initial implementation | Kestrel+Android API; end-to-end device tests pending |
| 02 Windows WPF | Initial implementation | Windows CI compilation and firewall test pending |
| 03 Android Compose | Initial implementation | Android CI compilation and Android device test pending |
| 04 Discover and pairing | Improved | Offline QR pair with TLS pin and PIN, UDP broadcast and manual IP fallback; DNS-SD and device-to-device test pending |
| 05 Files, resume, integrity | Initial implementation | 4 MiB chunks, SHA-256, 2 TB policy, crash recovery; pause/resume UI and folders pending |
| 06 Text and links | Initial implementation | Android Share intent, Windows UI, copy; clipboard opt-in pending |
| 07 Encrypted transport | In progress | TLS + visual fingerprint verification; peer revocation and adversarial security tests pending |
| 08 Hotspot / Wi-Fi Direct / Bluetooth | Partial | Android local-only hotspot started where supported; Wi-Fi Direct and Bluetooth transports not built |
| 09 Releases and Delta | Not complete | Keep prior release untouched; native Windows updater/installer, stable Android signed release, delta pipeline adaptation require testing |

## Acceptance tests

1. `Nerqo.exe` launches as a WPF window. No browser or console process.
2. On Android, discover the Windows device under same Wi-Fi (try 5 GHz), pair with code, refresh list.
3. Android → Windows text and file appear in Nerqo and `%LOCALAPPDATA%/Nerqo/Received`.
4. Windows → Android note and file appear in the Android list; Save creates byte-identical file (SHA-256).
5. Repeat steps 2–4 with **cellular data disabled**, and then with the phone's local-only hotspot.
6. Interrupt an upload, then retry same selected URI; server returns committed offset.
7. Observe Wi-Fi band; confirm the actual connection uses 5 GHz, and test that 2.4 GHz fallback still works.
8. Test antivirus/firewall, isolated Wi-Fi guest networks, sleep/resume, low storage, missing permissions and Windows tray exit.
9. Separate releases with Android certificate continuity and Windows installer/update tests; do not replace v0.3 until ready.

### Not yet complete

Neither Wi-Fi Direct nor Bluetooth transports, end-to-end encryption, QR pairing, large-folder layout, native Windows delta updater nor a signed production installer are present in this preview. No speed guarantee or claim of real-device testing is made.

### Changed Wi-Fi / hotspot after launch

Use **Refresh network and QR** in the Windows app after connecting to another Wi-Fi SSID or your phone hotspot. This recalculates the local addresses and regenerates the QR with the current addresses and the same pinned certificate identity; no restart, browser, terminal or Internet is required. When network isolation or firewall rules block incoming TCP 44888, the QR cannot bypass that restriction.

### Production hardening (October 2026)

- Require SHA-256 of every native upload chunk; reject missing or incorrect digests before committing an offset.
- Use NTFS hard links between Nerqo Storage and Windows Received to prevent duplicate disk usage for large Android uploads (copy fallback on unsupported volumes).
- Windows native updater checks only newer stable GitHub releases, validates the native Installer asset and its manifest before downloading, verifies size and SHA-256, and obtains explicit permission before starting setup. It currently downloads the full installer; native installer Delta reconstruction and real-world upgrade acceptance tests remain outstanding.
