# Nerqo release signing (Android)

The permanent Android release signing certificate is pinned in `android/RELEASE_SIGNING_CERT.sha256`. The private PKCS12 keystore is held **outside** this public repository. Treat its recovery password as a high-value secret, keep two offline backups, and never upload it to issues, commits or support chats.

## Install the release key in GitHub Actions

Repository Settings → Secrets and variables → Actions → New repository secret.

Create all four secrets:

| Secret | Value |
| --- | --- |
| `NERQO_ANDROID_KEYSTORE_BASE64` | Base64 of the original private `.p12` file (not a path) |
| `NERQO_ANDROID_STORE_PASSWORD` | Password supplied in the private recovery record |
| `NERQO_ANDROID_KEY_ALIAS` | `nerqo-release` |
| `NERQO_ANDROID_KEY_PASSWORD` | Password supplied in the private recovery record |

On Windows, `tools/configure-android-signing.ps1` can set these secrets using authenticated GitHub CLI, while keeping passwords out of your command history. **This chat's GitHub connector cannot write repository secrets**; a tagged release will fail closed until these are configured.

Tag `v0.3.0` only when these secrets exist. The workflow compares the keystore's SHA-256 certificate fingerprint to the pinned fingerprint before signing, then verifies the signed APK with Android `apksigner`.

## Compatibility, permanence and recovery

Android only permits a package update when the installed and incoming APKs have compatible signing certificates, and a higher `versionCode`. An earlier debug-signed install cannot be updated in place by our independently signed production release. The transition from debug to production therefore requires a one-time reinstall; export any data that should be retained first. Once installed from the stable release key, future APKs **must** use the same identity. Store the keystore and passwords permanently, or updates may become impossible.

Self-signing is sufficient for sideloaded Android APK continuity, but does not provide the Windows publisher reputation offered by a publicly trusted Authenticode certificate. Windows release binaries are currently unsigned; they still use SHA-256 package checks for delta updates.
