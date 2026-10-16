# Native Nerqo application updates

File sharing works offline over the local network. Checking and downloading app
updates uses GitHub Releases over HTTPS and requires internet access.

## Install and update

On **Windows**, open **Settings → Check for app updates**. Nerqo shows the version
and download size, downloads a verified native installer, then asks before
launching Setup. The per-user installer upgrades the installed WPF app and keeps
its existing data under `%LOCALAPPDATA%\Nerqo`. No browser, terminal or Python
runtime is part of the native installation.

On **Android**, open **Settings → Check for app updates**. Confirm the download,
then approve Android's package installer. Every release must use the original
permanent signing key and an increasing `versionCode`. Never uninstall an
existing installation just to accept an APK with a different signing key. Debug
APKs are test artifacts and cannot replace the permanent-key installation.

The updater accepts eligible newer stable releases. A source commit or a native
CI artifact does not publish an app update. The current development branch is
not yet an official Stable release, so installing its build does not make that
build appear in the in-app update checker.

## Smaller downloads

Both updaters choose an applicable delta only when it is at least 10% smaller
than the full package and the original bytes match the required SHA-256.

- Windows reconstructs the **installer** from a previously downloaded installer
  cached by its updater under `%LOCALAPPDATA%\Nerqo\Updates`. An initial manual
  installation may require a full download because that cache does not exist.
- Android reconstructs the new APK from the installed APK.
- The patch and final reconstructed package are checked for exact size and
  SHA-256. An inapplicable or corrupt delta falls back to the verified full asset.
- Installation stays opt-in. A background download does not install silently.

## Preparing an official native release

1. Keep WPF, Inno Setup and Android semantic versions aligned; increase Android's
   `versionCode`. Do not reuse a published version for different production code.
2. Pass the native build, transport, recovery, installer and device acceptance
   checks in [NATIVE_ROADMAP.md](NATIVE_ROADMAP.md).
3. Build the unsigned native Android Release APK, sign it privately with the
   **existing** permanent key and compare its payload with the independent build.
   Follow [OFFLINE_RELEASE_SIGNING.md](OFFLINE_RELEASE_SIGNING.md). Never generate
   a replacement identity for an installed application.
4. Stage only the signed public APK and its checksum under `downloads/` as
   documented by the signing procedure. Keep private keys and recovery records
   outside the repository.
5. Publish through `.github/workflows/release.yml` **from `main` only**. It builds
   the native Windows installer, independently rebuilds Android, verifies the
   permanent Android certificate and payload, and prepares both platform assets,
   `update-manifest.json` and useful deltas from the prior release. It refuses to
   overwrite an existing release.

The workflow expects a privately signed APK; repository signing secrets are not
required by this workflow. Publishing a tag on a development branch does not
publish a Stable version.

## Integrity and verification

Assets are restricted to the expected `R3Dzf/Nerqo` GitHub Release URLs. The
`NRQDEL01` patch format carries original/target sizes and SHA-256 hashes, bounded
copy/literal records and a terminator; it does not execute arbitrary commands.
Native C#, Android Java and Python implementations are checked by the available
transport/regression suites.

SHA-256 validates bytes against the release metadata; it is not a publisher
signature. Android also enforces signing-key continuity. Windows currently has
no publisher-trusted Authenticode certificate. Physical-device installation,
network and recovery acceptance remains separate from a green CI build.
