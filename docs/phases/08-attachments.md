# Phase 8 — Attachments

## Status (2026-10-29)

**Built:** an attachment message (type 6, from anyone, in a restore, a
catch-up or live) downloads, decrypts and shows itself the moment its
row appears — images inline, a voice message with its own Play/Pause
and a draggable progress bar, video/PDF/anything else as a named row
that opens in whatever app the person has for it. This needed three
things this port had none of before: the exact encryption format the
other Sphinx clients use (RNCryptor v3 — PBKDF2-HMAC-SHA1, AES-256-CBC,
HMAC-SHA256, ported byte-for-byte and checked against an independent
Python implementation, not just against itself), a small HTTP client
for the memes server (sign in with a signed challenge, download by
bearer token), and a local decrypted-file cache.

**Also built, once asked whether audio actually played in place or
just handed off like everything else:** it was the latter at first —
fixed with `AudioPlayer`, one `MediaPlayer` shared by every voice
message in a chat screen (playing one stops whatever was already
playing, same as any messaging app's voice notes), and
`AudioAttachmentRow`'s Play/Pause button and draggable `Slider`.

**Also built, sending's own first slice — images only, on the user's
explicit direction:** the composer's new "+" button offers Photo (the
system photo picker) or Camera (the system camera app, writing through
the same `FileProvider` receiving already needed); either one re-encodes
the picture, uploads it and sends it the moment it's picked, with
whatever was already typed as its caption. `Engine.sendImageAttachment`
is `MediaCrypto.encrypt`/`MemesServer.upload`/`sendMessage`'s own shape,
one call each, with no draft-row-then-resume complexity: a failure
throws, same as a text send, rather than leaving something to retry.
Video, voice recording and arbitrary files are still their own future
task, not started.

**Open:**

- No video, voice-recording or arbitrary-file sending — only images,
  by explicit choice, so a bigger "sending" task doesn't need to
  re-cover what images already settled (the upload/token/body shape).
- No tap-to-load gate: every attachment in a chat starts downloading
  the moment its row appears, images included. Fine for how little any
  one chat has so far; a very attachment-heavy tribe may want a gate
  (or at least a size check before committing to a download) before
  this matters.
- No overall cache-size eviction. PeelCore prunes its media cache at a
  500MB total as it writes; this port writes to Android's own cache
  directory and otherwise never deletes anything from it. The OS may
  clear that directory under storage pressure on its own, and every
  file here is always re-derivable from the server plus its key, so
  nothing is actually at risk — just not actively managed the way
  PeelCore's is.
- No video playback or PDF viewer in the app, no image viewer (full
  screen, zoom, swipe between a chat's images) — only audio plays in
  place; everything else but an inline image opens in whatever other
  app the person has installed for it (`Intent.ACTION_VIEW` through a
  `FileProvider`). An image shows inline, nothing more: no tap to view
  full size.
- No container-signature verification for video or audio (PeelCore
  checks `ftyp`/`moov`/RIFF/etc. headers before trusting the declared
  kind; this port trusts the declared `mediaType` for those two
  outright — `AudioAttachmentRow` finding out a file won't actually
  play would show as a stuck "0:00", not a clear error). Images are
  verified (`BitmapFactory` must actually decode them) and PDFs are
  (the `%PDF-` signature), since both were cheap to check with what
  Android already has built in.
- Only one voice message plays at a time, by design (`AudioPlayer`
  holds one `MediaPlayer`) — not something to fix, named so it reads as
  a decision, not an oversight.
- `AudioPlayer`'s position only updates while something is playing (a
  poll loop, since `MediaPlayer` has no position-changed callback) —
  dragging a *paused* voice message's own slider still seeks it
  correctly (nothing needs the poll loop for that); dragging one never
  played at all does nothing, since nothing is loaded yet to seek.
- No paid attachments. A free one (or a tribe's, which always keeps its
  key) shows normally; one that cost sats and this account never bought
  has no key at all here and shows as an error — correct, since nothing
  here can buy one, but also nothing here can tell the difference
  between "you'd need to pay for this" and any other reason the key
  might be missing.
- No `mediaKeyForSelf`-only row gets anything beyond what `ownKey`
  already covers (PeelCore's own fallback) — a device that bought an
  attachment on someone else's behalf (the seller side) is not a case
  this port needs, since receiving never had to buy one either.
- No pricing on a sent image either — always free, the same simplification
  `Engine.sendMessage` already has for text in a paid tribe.
- No preview-before-send: picking or capturing a photo sends it right
  away, with whatever was already typed as its caption — there is no
  staging tray to add a caption after picking, remove a picked photo,
  or send several at once (PeelCore's own multi-file `sendAttachments`).
- No upload progress bar, just a plain "Sending photo…" line — PeelCore
  reports bytes transferred as it goes; this port waits for the whole
  thing silently.
- No retry on a failed send: a send that throws (upload refused, no
  connection, etc.) shows the one-line error already used for a failed
  text send and the photo is gone — nothing is kept to try again,
  unlike PeelCore's own draft-row-first design.

## Decided

- The memes host is the one hardcoded fallback (`memes.sphinx.chat`),
  never a setting — there is no onboarding step here that lets an
  account record its own (PeelCore's `Setting.memesHost`), and this
  port's server is never a local test one, so the fallback is simply
  the value, not a default waiting to be overridden.
- The decrypted cache lives in Android's own cache directory
  (`Context.cacheDir`, via `Storage.attachmentCacheDir`), not the files
  directory: it is data the OS is explicitly allowed to reclaim, and
  every file in it is reconstructible (server plus key) rather than
  authoritative, which is exactly what the cache directory is for.
- A file's own name is cached alongside it in a sidecar `<muid>.name`
  text file rather than a new storage column or table — the one place
  that needs it (`AttachmentView`, by way of `downloadAttachment`) is
  already keyed by `muid` for the content itself, and a one-line file
  is simpler than a migration for data this disposable.

## Journal

**Checked whether the encryption scheme could possibly be skipped or
simplified before committing to porting it.** It can't: an attachment's
bytes are genuinely encrypted at rest and in transit with a
password-based scheme (RNCryptor v3) that has nothing to do with the
Sphinx protocol's own onion encryption — the media key travels inside
the ordinarily-encrypted message body, but the file itself needs its
own decrypt. Read `MediaCrypto.swift` in full before writing a line of
Kotlin, confirmed the exact header layout (version/options, two 8-byte
salts, a 16-byte IV, then ciphertext, then a 32-byte HMAC) and the two
separate PBKDF2 derivations (one key for AES, a different one for the
HMAC, from the same password, different salts).

**Verified the port the same way PersonHue and the PIN work were: a
from-scratch second implementation, not a self-check.** Built a Python
reference using `cryptography` (not Kotlin's `javax.crypto`, not
CommonCrypto) that produces an RNCryptor-v3 blob from a known key and
plaintext, then asserted the Kotlin `decrypt` recovers that exact
plaintext from that exact blob. A bug shared between "my Kotlin
encrypt" and "my Kotlin decrypt" would never have shown up testing
one against the other; a independently-built blob can't hide that kind
of mistake.

**PBKDF2 hand-rolled again, same reasoning as the PIN.** `derive` could
have used `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")` with a
`PBEKeySpec`, standard and shorter. Didn't, for the same reason
`phases/03-restoring.md` gives for the PIN's own PBKDF2: a `PBEKeySpec`
password is a `char[]`, and the provider's own conversion from that to
bytes is not guaranteed to be the UTF-8 encoding CommonCrypto used on
the other side that produced the ciphertext this needs to match. A
real media key is always ASCII, where the two would likely agree
anyway — but the explicit, byte-level version costs a few more lines
and removes the question entirely rather than relying on it not
mattering in practice.

**The memes host's security check matters more than it looks like it
would.** `MediaToken` carries its own `host` — the sender's claim about
where the file lives. Trusting that blindly would mean any message
could point this device's bearer-token-bearing HTTP client at a server
of the sender's choosing. PeelCore's own `downloadAttachment` guards
this with one line (`token.host == memes.host`); ported the same
check, for the same reason Phase 4's tribe detection already had to
reason about which `host` a message is allowed to name versus which
one it actually gets to use.

**"Showing" an audio attachment turned out to mean something more
specific than it does for everything else.** The first version treated
audio the same as video/PDF/anything else — a named row, opened
externally. Asked directly whether that was really "playing it,"
which it wasn't: handing a voice message to some other app the person
may not even have one suited for is a materially worse experience than
a one-line in-app player, and voice messages are common enough in
Sphinx chats that this was worth the extra piece rather than deferring
it into "sending." `MediaPlayer` reads a file by its decrypted bytes'
own container signature, not the file's name or extension, so the
cache's extension-less `<muid>` files needed no change at all to work
with it.

**A media token is signed, not just two strings joined with a dot.**
Expected to build an outgoing `mediaToken` by hand the way it's
*parsed* on the receiving side (`host`, `muid`, nothing else needed to
read it back) — but sending needs to *produce* one the server and any
other Sphinx client will accept, and that turned out to require its
own signature over `host`/`muid`/the recipient/an expiry, made only by
a call that holds the seed (`Sphinxrs.makeMediaToken`, already in the
Kotlin bindings). Read `uploadAndDeliver` in full before assuming the
token was just string formatting — it wasn't, and skipping that check
would have shipped a token no real client or server would accept.

**Reused the receiving side's `FileProvider` rather than building a
second one.** A photo taken with the camera needs a `content://` URI
to write into, the same requirement attachments already solved for
opening a *received* file externally. One more `<cache-path>` entry
(`outgoing/`, never shared for reading by another app, only written to
by the camera intent) in the same `file_paths.xml` was the whole
addition.

## Done

- `engine/MediaCrypto.kt`: RNCryptor v3 decrypt (PBKDF2-HMAC-SHA1 ×2,
  AES-256-CBC/PKCS7, HMAC-SHA256), hand-rolled PBKDF2.
- `engine/MemesServer.kt`: `challenge`/`verify`/`download` over plain
  `HttpURLConnection`, matching this project's existing
  `ServerConfig`/`TribeInfo` style; a size-capped reader that never
  buffers past `limit`; `Jwt` (just enough to read a bearer token's
  `exp`).
- `engine/Attachment.kt`: `AttachmentFields` (an attachment message's
  own JSON), `MediaToken` (host + muid, from the token string),
  `AttachmentKind`, `DownloadedAttachment`.
- `engine/Engine.kt`: `downloadAttachment(row)` (cache lookup, sign-in,
  download, decrypt, verify, cache); `withMemes`/`memesSignIn`
  (PeelCore's own retry-once-on-401 shape); `verifiedKind`.
- `storage/Storage.kt`: `attachmentCacheDir()`.
- `ui/dashboard/ConversationScreen.kt`: `AttachmentView` (loading/
  error/loaded states), `AttachmentContent` (inline image, an in-app
  audio player, or a named row that opens externally via
  `FileProvider`); `AudioAttachmentRow` (Play/Pause, a draggable
  `Slider`, the position/duration label).
- `ui/components/AudioPlayer.kt`: one `MediaPlayer`, shared by a chat
  screen's voice messages — `toggle`/`seekTo`/`release`, Compose state
  (`isPlaying`/`positionMs`/`durationMs`) a screen polls while playing
  (`MediaPlayer` has no position-changed callback of its own);
  `formatDuration`.
- `engine/MediaCrypto.kt`: `newKey`/`encrypt`, the inverse of `decrypt`.
- `engine/MemesServer.kt`: `upload` — the same multipart shape as
  PeelCore's own, over `HttpURLConnection`.
- `engine/Engine.kt`: `sendImageAttachment(chat, jpeg, caption, isTribe,
  replyTo)` — encrypt, upload, `makeMediaToken`, `send`, store, cache
  the plaintext under its own `muid` (no download round trip needed
  for this device's own sent copy).
- `ui/components/ImagePreparation.kt`: `prepareImageForSending` — EXIF-
  upright, re-encoded, downscaled, metadata gone, the same "only the
  picture goes" rule PeelCore's own picker follows.
- `ui/dashboard/ConversationScreen.kt`: the composer's "+" button and
  its Photo/Camera menu; `PickVisualMedia`/`TakePicture` launchers;
  "Sending photo…" while an upload is in flight.
- `AndroidManifest.xml`/`res/xml/file_paths.xml`: a `FileProvider`
  scoped to the attachment cache directory, now also an `outgoing/`
  path for a photo on its way from the camera.
- Tests: `MediaCryptoTest` — decrypts a blob from an independent Python
  RNCryptor-v3 implementation, refuses the wrong key, refuses a
  too-short or wrong-version blob; `encrypt` round-trips through
  `decrypt`, never repeats itself for the same input, and a
  Kotlin-encrypted blob was checked by hand against the same Python
  reference in the other direction. 60 tests passing. `AudioPlayer`
  and the picker/camera flow are not unit-tested — one wraps
  `android.media.MediaPlayer`, the other needs a real device's photo
  picker and camera app, both the same category of gap as `Engine`'s
  own untestable native-library calls.

## Learned

- A doc comment's own confident description of a mechanism ("the one
  status...") is still worth checking against what actually triggers
  it before relying on it as the only path (Phase 6's own lesson,
  encountered again in planning this phase before it was even
  scoped) — not repeated here since it didn't end up mattering for
  attachments specifically, but the instinct to check first is the
  same one that found the memes-host security requirement early
  rather than after shipping without it.
- Porting a cryptographic format is exactly the kind of work where
  "it compiles and the output looks plausible" is not evidence of
  correctness — the independent-implementation test is not optional
  diligence here, it is the only thing that actually distinguishes a
  correct port from a self-consistent wrong one.
- Scoping receiving separately from sending (the user's own call) made
  this phase considerably smaller than the two together would have
  been: no `MediaCrypto.encrypt`, no multipart upload, no outgoing-file
  management, no "sell it" price path — all genuinely large pieces
  that a receive-only phase never has to touch.
- "Showing" an attachment is not one behavior across every kind — a
  file a person can open in another app and a voice message they
  expect to just tap and hear are different enough expectations that
  treating them identically (both "a named row") undersold what the
  second one actually needed, even though both are "attachments" by
  the same message type.
- "Receiving" and "sending" a format are not symmetric in effort even
  when the data shape is the same — parsing a token someone else made
  needed two fields; producing one a server will accept needed a
  signed call this port had no prior reason to touch. The parse side
  being simple doesn't mean the construct side is.
- A second infrastructure piece (the `FileProvider`) built for one
  purpose (handing a received file to another app) was exactly what a
  later, seemingly unrelated need (the camera's own output) turned out
  to require — worth checking what's already in place before assuming
  a new feature needs new plumbing.
