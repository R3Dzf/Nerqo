# Nerqo application delta updates

**Two distinct capabilities:** File transfer between paired devices works offline on a trusted LAN. Application updates come from GitHub Releases over HTTPS when Internet is available. No paid server or subscription is needed.

## User flow

Windows: launch packaged `Nerqo.exe`, open the local desktop interface, visit **Connect → Application updates**, press **Check for updates**, **Download update** and **Install & restart**. The Windows EXE can update itself without re-downloading the whole EXE if the current installed EXE has the precise expected SHA-256. The local Python source run can check but deliberately cannot self-overwrite.

Android: open the Nerqo companion, tap **Check for app updates** on the connection screen or from the three-dot menu. Confirm download, then approve the Android package installer. Android enforces the existing APK signing certificate on the upgrade; **a GitHub Actions debug APK, signed with a changing debug key, is not a reliable upgrade path**. Do NOT uninstall a version with data to make an incompatible signer install. Stable releases need the same private release signing key every time.

The updater chooses the smallest applicable delta (at least 10% smaller than the full package), verifies the downloaded patch's SHA-256, reconstructs the new APK/EXE from the exact original binary, and then validates the final SHA-256 and size. On missing/inapplicable/corrupt delta, it falls back to verified full download. The previous Windows EXE is kept as `.nerqo-backup` for recovery if the replacement cannot start. Installation is always opt-in; a background update does not change the program silently.

**No released packages yet?** GitHub's `releases/latest` will return 404 and the Windows UI treats that as up-to-date. A source-only commit does not publish an app upgrade.

## Release signing — required before production Android updates

1. Generate a **single, stable Android release keystore** on a trusted machine with Android's `keytool`, and keep an encrypted offline backup of that keystore AND credentials. Losing it means existing installations cannot be upgraded with the same app ID.
2. In GitHub repo Settings → Secrets and variables → Actions add:
   - `NERQO_ANDROID_KEYSTORE_BASE64`: base64 content of the keystore file, not its location.
   - `NERQO_ANDROID_STORE_PASSWORD`: keystore password.
   - `NERQO_ANDROID_KEY_ALIAS`: chosen key alias.
   - `NERQO_ANDROID_KEY_PASSWORD`: key password.
3. When ready to publish, increment **both** `nerqo/version.py` and `android/app/build.gradle.kts` (`versionName`, `versionCode`; `versionCode` always increases). Then push an annotated or lightweight tag `vX.Y.Z` to `main`.
4. `.github/workflows/release.yml` builds EXE plus stable-signed Android release APK and publishes them as GitHub Release assets, with a `update-manifest.json` and deltas from the previous release when worthwhile. If signing secrets are not supplied the release job fails **closed**, rather than publishing an APK with an inconsistent signing key.

Never add keystores, signing passwords, personal tokens, or `.env` files to source control. Do not use the automatic release process until there has been a device test of the previous and newer signed APKs. If Windows installs to a protected folder, its update requires write permissions or a separate privileged installer.

## Patch format

`NRQDEL01` format: 8-byte magic; unsigned 64-bit old/new sizes; two 32-byte SHA-256 digests; records `C` (copy `uint64` base offset and `uint32` count), `D` (uint32 decompressed byte count, uint32 compressed byte count, zlib bytes), then `E` terminator. Integer encoding is network/big-endian. Max output is 8 GiB; literal records are capped at 1 MiB. Python generates and applies; Java reconstructs Android binary directly from the installed APK path. All operations are bounded; no arbitrary executable commands appear in patches.

Releases include a manifest with version, base SHA-256, target SHA-256, patch SHA-256, and exact byte sizes. Asset URLs are constrained to `https://github.com/R3Dzf/Nerqo/releases/download/v<version>/...` and are found through the official GitHub Release API. Repo/TLS compromise remains a trust boundary: SHA-256 **is not** a signature on a manifest, and the Windows EXE has **no Authenticode publisher signature**. Android additionally enforces signing-key continuity.

## Tests

- `python -m unittest discover -s tests -v` tests binary shift/reuse, corruption rejection, source base verification, release manifest and cross-language Java patch application when JDK is available.
- Push to `main` triggers debug Android and Windows EXE builds in GitHub Actions; tag publishing uses a separate signed release workflow.
- Manually test installation, upgrades, recovery, offline LAN file sharing and disk capacity on real Windows and Android devices before public release.

CI and GitHub services are free within GitHub's plan limits; app updates require an Internet connection **only for the download**, not for everyday local transfer.
