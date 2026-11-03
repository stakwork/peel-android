# Releasing Peel

Publishing a GitHub release triggers `.github/workflows/release.yml`, which builds a signed APK and attaches `peel-<version>.apk` to that release.

## One-time setup

### 1. Keystore

Generate a release keystore (skip this if you already have one — **always reuse the existing keystore**, since app updates must be signed with the same key):

```sh
keytool -genkeypair -keystore peel-release.jks -alias peel -keyalg RSA -keysize 2048 -validity 10000
```

**Back up the keystore and its passwords somewhere safe.** If they are lost, you can't ship updates to existing installs. Never commit the keystore (`*.jks` and `*.keystore` are gitignored).

### 2. Repository secrets

Add these under **Settings > Secrets and variables > Actions**:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Output of `base64 -w0 peel-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias (e.g. `peel`) |
| `ANDROID_KEY_PASSWORD` | Key password |

## Cutting a release

1. Tag format must be `vMAJOR.MINOR.PATCH` (e.g. `v1.2.3`). Minor and patch must be <= 99.
2. `versionName` = `MAJOR.MINOR.PATCH`; `versionCode` = `major*10000 + minor*100 + patch` (v1.2.3 -> 10203).
3. Create and **publish** a GitHub release for the tag. The workflow builds from that tag and uploads `peel-<version>.apk` to the release.

## Play Store (later)

To also produce an AAB for Google Play, uncomment the "Build AAB" step (`./gradlew bundleRelease`) in `release.yml` and add an upload step for `app/build/outputs/bundle/release/*.aab`.
