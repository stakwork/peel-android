# Phase 52 — In-app updates

Status (2026-11-20): **built; JVM-tested (`update/*Test`); not run on a device, and the system installer hand-off has not been exercised.**
Open: a real end-to-end run (install a release containing this, publish a newer one); showing a notification for a found update (none: the prompt is in-app only).

## Why

People sideloaded `peel-<version>.apk` from GitHub Releases by hand on every release. GitHub Releases stays the only source of truth: no backend.

## Built

- **Pure core** (`update/`): `ReleaseVersion` (tag `vX.Y.Z` → code, same formula and limits as `release.yml`), `ReleaseParser` (latest-release JSON → `ReleaseInfo`; asset `peel-X.Y.Z.apk`, else the only `.apk`),
  `UpdateChecker` (headers `Accept: application/vnd.github+json`, `User-Agent`; maps offline, 429/403-with-no-requests-left → `RateLimited(resetAt)` from `Retry-After` / `X-RateLimit-Reset`, bad body, no asset yet),
  `UpdateSchedule` (12 h throttle, "Later" per version), `ApkDownloader` (OkHttp stream → `.part` → rename, size check, cancel/failure leave nothing), `ArchiveCheck` (package, signer set, versionCode), `UpdateController` (state machine).
- **Release with no APK yet** (no asset named exactly `peel-X.Y.Z.apk`; no fallback to another `.apk`) is silently "no update yet" for both the launch check and a tap (the checker maps `NoAsset` to up-to-date). `release.yml` uploads the asset after publishing, so this is the normal state for a few minutes.
- **Only release-workflow builds** (`BuildConfig.UPDATES_ENABLED` = release type and `-PVERSION_CODE` supplied) check on launch. On other builds the You row still works and says updates are only for release builds.
- **Throttle/Later in app-wide SharedPreferences** (`peel_update`), not `Storage`: Storage is one database per account, and an update has nothing to do with an account (and the check shouldn't depend on which one is open).
- **UI**: `ui/update/UpdateHost` beside the tabs in `MainActivity` (launch check after unlock; Update/Later dialog with scrollable plain-text notes; progress dialog with Cancel; the unknown-sources step, resumed on `ON_RESUME`), and You › Check for updates (installed `VERSION_NAME`, result, a rate limit says when to retry).
- **Download** to `cacheDir/updates/` (emptied first; stale files cleaned at launch); `file_paths.xml` has an `updates` cache-path on the existing FileProvider.
- **Before installing**: `getPackageArchiveInfo` (`GET_SIGNING_CERTIFICATES` on 28+, `GET_SIGNATURES` below) — same package, same signing-cert SHA-256 set, higher versionCode, else the file is deleted with a message.
- **Install**: `Intent.ACTION_VIEW` with the FileProvider URI. Chosen over `PackageInstaller` sessions because the system confirmation screen is needed anyway, there is no receiver/status plumbing to keep alive across the app being replaced, and a session adds nothing for a single file. If `canRequestPackageInstalls()` is false the person is sent to `ACTION_MANAGE_UNKNOWN_APP_SOURCES` for Peel and the install continues on return; if they return still refused, the offer stays with a retry. `ActivityNotFoundException` is a message, not a crash.

- **Test override**: `-Ppeel.update.force=true` enables the launch check in a debug build (UI testing; it cannot install over a release build, and says so), `-Ppeel.update.repo=owner/name` (`BuildConfig.UPDATE_REPO`) follows another repo.

## Not ported / limits

- Not in the Swift app (iOS updates through the store). No digest is published with a release, so integrity rests on HTTPS, the listed size and the signature check.
- Release APKs are arm64-v8a only; a device that can't run it is not checked for (all supported phones can).
- Unauthenticated GitHub API: 60 requests/hour/IP; the throttle keeps Peel far below it, but a shared NAT can hit it (the tap says when to retry).

## Journal

- 2026-11-20 Built the above; `testDebugUnitTest`, `lintDebug`, `assembleDebug` green.
