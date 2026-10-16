# Signed Android release staging

These ordered binary parts reconstruct the public Nerqo 1.0.6 APK signed with
the permanent Android certificate. No signing key or password is included.
The Stable workflow concatenates them, verifies the full APK SHA-256, checks
the pinned signing certificate and compares every application ZIP entry with
an independently built unsigned release before publication.

The split upload accommodates the repository connector payload limit. Users
should install `Nerqo-Android.apk` from GitHub Releases, not these parts.
