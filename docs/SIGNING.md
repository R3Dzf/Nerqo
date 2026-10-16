# Nerqo Android identity and release signing

The public permanent certificate SHA-256 is in
`android/RELEASE_SIGNING_CERT.sha256`. The original PKCS12 key and its recovery
record are held privately outside this repository. No private keys, credentials
or recovery passwords may be committed.

The native development workflow compiles Debug and unsigned Release APKs.
Release packages are signed privately, verified against the pinned certificate,
and compared with the independent unsigned build. See
[the private signing procedure](OFFLINE_RELEASE_SIGNING.md).

The native Stable pipeline is `.github/workflows/release.yml`. It accepts a
privately signed public APK in `downloads/Nerqo-Android-vVERSION.apk`, verifies
its SHA-256 file, signer and payload against a fresh unsigned native build,
and publishes only from `main` after Windows and Android succeed. The older
Python/WebView release workflows are not used by the native branch.

Android's `stableRelease` Gradle signing config can also consume a deliberately
configured private `NERQO_SIGNING_FILE`, `NERQO_STORE_PASSWORD`, `NERQO_KEY_ALIAS`
and `NERQO_KEY_PASSWORD` in an authorized secure build environment. Supplying
partial credentials fails the build. The normal native CI does not export the
private key or read it from source.

Android update compatibility requires the original signer and an increasing
version code. Debug builds are separate development installations and must not
be presented as production upgrades. Back up the permanent identity: losing it
can prevent future updates. Android self-signing does not supply a Windows
publisher-trusted Authenticode signature.
