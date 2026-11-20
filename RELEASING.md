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
| `GOOGLE_SERVICES_JSON` | Contents of the Firebase project's `google-services.json` (optional — see below) |

### 3. Push notifications (optional)

`GOOGLE_SERVICES_JSON` enables FCM push in the release APK. Without it, the release still builds and installs fine — it just has no push, same as a local build with no `app/google-services.json` (see `app/build.gradle.kts`). The workflow writes the secret's value to `app/google-services.json` before building and passes `-Ppeel.push.registerWithHub=true`, so a release built with this secret set registers its FCM token with the hub (`docs/phases/19-push-and-reliability.md`).

## Cutting a release

1. Tag format must be `vMAJOR.MINOR.PATCH` (e.g. `v1.2.3`). Minor and patch must be <= 99.
2. `versionName` = `MAJOR.MINOR.PATCH`; `versionCode` = `major*10000 + minor*100 + patch` (v1.2.3 -> 10203).
3. Create and **publish** a GitHub release for the tag. The workflow builds from that tag and uploads `peel-<version>.apk` to the release.

The release APK is arm64-v8a only (debug builds keep x86_64 for emulators). CI verifies the prebuilt `libsphinxrs.so` checksums (`bash scripts/verify-sphinxrs-checksums.sh`); provenance is recorded in `sphinxrs/SPHINXRS_PROVENANCE.md`.

## Play Store (later)

To also produce an AAB for Google Play, uncomment the "Build AAB" step (`./gradlew bundleRelease`) in `release.yml` and add an upload step for `app/build/outputs/bundle/release/*.aab`.

## In-app updates

Peel updates itself from GitHub Releases (`docs/phases/52-in-app-updates.md`); there is no other server.

- The app asks `GET /repos/stakwork/peel-android/releases/latest` and offers a release whose tag code (`major*10000 + minor*100 + patch`) is above its own `versionCode` **and** that has the asset `peel-<version>.apk`. The workflow uploads that asset a few minutes after the release is published; until then the app quietly offers nothing.
- An update installs over the old app only if it is signed with the **same key**. Every release must be signed from the same `ANDROID_KEYSTORE_*` secrets; a release signed with another key is refused by the app (and by Android) and strands everyone on the old version.
- Only builds made by this workflow check for updates (`BuildConfig.UPDATES_ENABLED`: release build with `-PVERSION_CODE`). The first release that contains this feature must still be installed by hand; local, debug and `.dev` builds can't update in place.
- Don't delete or rename the APK asset of the latest release, and don't mark a release as pre-release if people should get it (`/latest` skips drafts and pre-releases).
