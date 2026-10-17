# Stable Android signing: preserve the installed application's identity

A stable Android release **must** be signed with the SAME keystore and key alias used for the currently published v1.0.9 APK. GitHub Releases cannot use the debug signing key and cannot generate a replacement. Otherwise the Android installer rejects updates with a signature mismatch.

## One-time private signing setup (GitHub)

The owner must configure these four encrypted **repository Actions secrets** in **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `NERQO_ANDROID_KEYSTORE_BASE64` | The **Base64 encoding** of the original, private release keystore file (NOT the public certificate or APK). |
| `NERQO_ANDROID_STORE_PASSWORD` | Password for that keystore. |
| `NERQO_ANDROID_KEY_ALIAS` | Alias of the permanent signing key within it. |
| `NERQO_ANDROID_KEY_PASSWORD` | Password for that alias/key. |

To encode the *existing private keystore file* locally in PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\\private\\nerqo-release.jks")) | Set-Clipboard
```

Replace the example path with the actual local keystore path. Paste the copied value **only** into GitHub's encrypted secret editor. Never paste private keys, keystore passwords or the Base64 value into a chat, issue, PR or commit. Protect and back up the original key.

The release workflow uses these secrets at runtime only. It builds the Android release APK, aligns/signs it, verifies the exact SHA-256 signing certificate in `android/RELEASE_SIGNING_CERT.sha256`, compares payload with the unsigned build, and then packages it with the verified Windows setup and compatible delta updates.

The `Nerqo permanent signing readiness` workflow exposes only a pass/fail result, not the key. Once all four secrets are configured with the original key, rerun the release tests. **Do not tag 1.0.10 Stable unless every CI gate is green.**

If the original signing key is no longer available, an in-place Android upgrade is cryptographically impossible; making a different key would require a distinct package identity or user uninstall and must not be silently represented as an update.
