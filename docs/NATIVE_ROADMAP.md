# Nerqo native rebuild — Windows + Android

Branch: `dev/native-5ghz-20261016`. This preview is **not a production release**.

## Native architecture (replacing Python/WebView)

- Windows: `.NET 9 / WPF`, built as a **WinExe**. Internal ASP.NET Core Kestrel listener shares process with the UI. No Python, shell, terminal or browser is opened by Nerqo. Minimizes to tray after initial launch. Receives files into `%LOCALAPPDATA%/Nerqo/Received`.
- Android: Kotlin + Jetpack Compose, no WebView. Sends text, links, single/multiple files using the document picker, receives files using the system Save dialog. Automatic UDP LAN discovery + IP fallback.
- Same-network and hotspot use the same LAN interface, no WAN service. Both clients must be on the same subnet; AP/client isolation and Windows Firewall can prevent discovery or connections.
- UDP broadcast discovery: port `44889`, request `NERQO_DISCOVER_V1`. Replies include device name, port 44888, and (where detectable) Wi-Fi band. Does not reveal pairing PIN.
- TCP transport: HTTP on `44888`, paired bearer token, 4 MiB streaming chunks, SHA-256 chunk/file verification, resumable upload state. Windows-origin files are staged inside app data and mobile polls the shared item list. Android-origin files are saved in Windows Received. **Not TLS encrypted yet:** trusted private networks only.

## 5 GHz priority — the physical layer

Nerqo **does not control Wi-Fi radio frequency by opening a TCP socket**. Both apps detect the current radio band (when the OS allows) and recommend 5 GHz or 6 GHz. For typical phone hotspots, set 5 GHz in the Android system hotspot settings, if the hardware/region permits. On standard LANs, connect both devices to the 5 GHz SSID. Windows uses the WLAN interface's reported band/channel; Android uses `WifiInfo.frequency` and link speed. On Android API 36+ we attempt a local-only hotspot with 5 GHz requested via `SoftApConfiguration`, falling back to OS defaults where unsupported; this still cannot guarantee band selection. When a local Wi-Fi network has no Internet, Android binds Nerqo to the Wi-Fi network instead of defaulting to cellular. Some OS builds or privacy permissions report `Unknown` — never report 5 GHz without evidence.

The actual MB/s is limited by negotiated Wi-Fi PHY, airtime contention, AP hardware, storage, and signal strength; 5 GHz is not a guaranteed speed. On 2.4 GHz-only devices, use a safe fallback rather than breaking transfer.

## Nine-phase tracking

| Phase | Status | Evidence/remaining work |
|---|---|---|
| 01 Native transport protocol | Initial implementation | Kestrel+Android API; end-to-end device tests pending |
| 02 Windows WPF | Initial implementation | Windows CI compilation and firewall test pending |
| 03 Android Compose | Initial implementation | Android CI compilation and Android device test pending |
| 04 Discover and pairing | Initial implementation | Broadcast, local IP fallback, rate limited pairing; QR and DNS-SD still pending |
| 05 Files, resume, integrity | Initial implementation | 4 MiB chunks, SHA-256, 2 TB policy, crash recovery; pause/resume UI and folders pending |
| 06 Text and links | Initial implementation | Android Share intent, Windows UI, copy; clipboard opt-in pending |
| 07 End-to-end security | Not complete | Needs authenticated key agreement, encrypted file transport and revocation UI before public network use |
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
