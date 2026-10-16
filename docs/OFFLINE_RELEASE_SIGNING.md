# Permanent private Android signing

Native APKs are built unsigned in GitHub Actions and signed privately with the
original Android identity pinned in `android/RELEASE_SIGNING_CERT.sha256`.
Private PKCS12 keys and recovery passwords are never committed or attached to
public CI artifacts. Keep the original private identity and recovery record.

## Reproducible signing procedure

1. Bump the native WPF, Inno Setup and Android versions together; increase the
   Android `versionCode`. Do not reuse a published version for different code.
2. Build the native CI on `dev/native-5ghz-20261016`. Download its unsigned Release
   APK and `Nerqo-Android-Signing-Tool` (the SDK's public `apksigner.jar`).
3. In a private environment run `tools/sign_release_offline.py` with `--unsigned`,
   `--apksigner-jar`, `--keystore`, `--recovery` and a new `--output` path. The tool
   reads passwords locally, checks the pinned certificate before signing, signs
   with v1/v2/v3 schemes, then verifies the resulting certificate and v2 scheme.
4. Run `python tools/verify_published_apk.py UNSIGNED SIGNED` to compare the signed
   APK's ZIP payload against the independent unsigned build. Record its SHA-256.
5. For an official release, put only the signed public APK at
   `downloads/Nerqo-Android-vVERSION.apk` with its `.sha256` file. After acceptance,
   the native `.github/workflows/release.yml` builds Windows, independently
   rebuilds Android, checks the permanent certificate and payload equality,
   and publishes coordinated native assets/optional deltas **from main only**.

The private key was recovered and matched to the pinned identity for 1.0.1.
A development build signed with that key is still not evidence that all native
hardware acceptance criteria have passed. Do not label it Stable solely because
signing and CI succeeded.

Android updates require the same signing identity and a higher version code.
A debug-signed APK cannot update a permanent-key installation. Do not uninstall
the permanent-key installation to test a debug APK. Android signing is separate
from Windows Authenticode publisher signing, which is not currently present.

## Unattended releases

Private signing during this authorized development session uses the recovered
original identity. Future unattended signing requires a secure automated signer
or deliberately configured GitHub Actions secrets. The GitHub connector in this
environment cannot write repository secrets. Never move passwords or private
keys into source files as a workaround. Current native CI deliberately produces
an unsigned Release APK, and normal debug artifacts are not upgrade packages.
