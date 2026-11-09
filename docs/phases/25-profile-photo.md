# Phase 25 — Own profile photo upload

Status: built; see "Verified" for what was and wasn't run. Ported from `peel`'s `Engine+Account.swift`
(`setPhotoURL`, `uploadPhoto`, `uploadPicture`), `Files.swift` (`ProfilePhoto`), `MemesServer.swift` (`upload`,
`publicURL`), `AppModel.removePhoto` and the Mac `AccountView` identity section (the iPhone You tab has no photo
picker; Android follows the Mac's flow: choose, upload at once, remove after confirming).

## Built
- **`engine/ProfilePhoto.kt`** (pure, JVM-tested): `MAX_PIXELS` 1024, `JPEG_QUALITY` 50 (iOS 0.5), `MAX_FILE_BYTES`
  (iOS `attachmentLimit`), `clean` (trim, empty = none, https only), `fit`, `refusal`, `message`.
- **`Engine.setPhotoUrl(url)`**: stores `Setting.PHOTO_URL` (empty = removed). **`Engine.uploadPhoto(jpeg)`**: the existing
  `uploadPicture` (memes server `/public`, bearer sign-in through `withMemes`, link `https://<host>/public/<muid>`)
  then `setPhotoUrl`.
- **You tab**: "Add/Change photo" opens the system photo picker (`PickVisualMedia`, images); the pick is redrawn as an upright,
  metadata-free JPEG ≤ 1024 px at quality 50 (`prepareImageForSending`), uploaded, then stored; the avatar changes only after
  everything worked. "Remove photo" confirms, then stores an empty URL. Failures show under the avatar.
- **Propagation / caching**: as iOS, nothing is broadcast on its own. Every later message, payment, invite and call ticket reads
  `Setting.PHOTO_URL` and carries it as `img`, so contacts see it from the next message. Tribe messages use the per-tribe photo
  if set (phase 23). Images are cached by Coil like every other avatar; the new link is a new muid, so no invalidation is needed.
  A stored empty value wins over `recoverOwnProfile` after a restore (it only fills a never-set photo).

## Assumptions
- No in-app crop: iOS has none either (it only downscales); the system picker is the only choice step.
- Photo uses the same upload field name (`image`) the tribe photo already uses on Android; the server ignores it for `/public`.
- Quality 50 / 1024 px match iOS; iOS's decoded-pixel guard (`ImageDecoding.isTooLarge`) is approximated by the file-size
  limit plus catching `OutOfMemoryError` while decoding.
- An orphaned old upload is not deleted from the server (iOS doesn't either).

## Verified
- `ProfilePhotoTest` (JVM). `Engine`/UI glue needs the native lib / device and isn't unit-tested. See the final session
  summary for which Gradle tasks could run.
