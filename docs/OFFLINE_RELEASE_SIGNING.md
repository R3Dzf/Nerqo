# Permanent offline signing without GitHub repository secrets

Nerqo can use GitHub Actions to build the Android APK **unsigned**, download it into a private build environment, and sign it with the pinned permanent release identity (`android/RELEASE_SIGNING_CERT.sha256`). GitHub never receives the private `.p12` keystore or its password.

## Current Android v0.3.0

The APK at [`downloads/Nerqo-Android-v0.3.0.apk`](../downloads/Nerqo-Android-v0.3.0.apk) was privately signed using the owner's original long-lived PKCS12 key. Its public signature can be independently checked with `apksigner verify --verbose --print-certs`, and its integrity is recorded in the adjacent `.sha256` file.

## Further releases

1. Update the code and increase both `nerqo/version.py` and Android `versionCode`/`versionName`.
2. Build an **unsigned** Release APK via GitHub Actions. Download the unsigned APK and the Android SDK `apksigner.jar` tool into a **private** environment.
3. Use `python tools/sign_release_offline.py --unsigned ... --apksigner-jar ... --keystore ... --recovery ... --output ...` locally. It verifies the pinned certificate, signs using v2/v3 schemes, verifies the result, and prints the artifact SHA-256.
4. Check the signed ZIP payload matches the CI unsigned ZIP using `python tools/verify_published_apk.py UNSIGNED SIGNED`.
5. Commit only the **signed public APK** at `downloads/Nerqo-Android-vVERSION.apk` and its adjacent `.sha256` file; never commit the private key or recovery file. GitHub workflow `publish-locally-signed.yml` recompiles the same source, verifies payload equality and certificate fingerprint, builds the Windows EXE, and publishes a GitHub Release with update metadata.

This arrangement enables a signed, verifiable release **without ever exposing the secret to GitHub Actions**. Unlike encrypted GitHub Secrets-based signing, **future releases still require an authorized offline signing step**; complete unattended future signing is not possible without entrusting a signing key to a secure, automated signer. This is intentional protection for the permanent private key.

The first debug-signed Android installation may require a one-time uninstall/reinstall before installing a stable-release-signed APK. Back up user data first. No publisher-trusted Windows Authenticode signing is provided by the Android key.
