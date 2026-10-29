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

**Also built, the rest of sending (2026-10-29):** video, voice
recording and arbitrary files. `sendImageAttachment` became a thin
wrapper over a shared private `sendAttachment(bytes, fileName,
mediaType, ...)`, the same shape PeelCore's own generic `sendAttachment`
is under every kind it sends — `sendVoiceMessage` and
`sendFileAttachment` are two more thin wrappers over it, not separate
mechanisms. A voice message is a real press-and-hold recording
(`VoiceRecorder`, built on `AudioRecord` — `MediaRecorder` has no
uncompressed-WAV output mode, and a plain WAV is exactly what every
Sphinx client sends and expects), 16 kHz mono 16-bit PCM, named
`"audio.wav"`/`"audio/wav"`, the one format that plays on iOS Sphinx's
44.1 kHz and the official Android app's 8 kHz alike. A video or any
other file goes through a new "File" attach option (the system
document picker) — there's no dedicated video picker, on this port or
on PeelCore's own iPhone app: a video reaches the send path through
the generic Files picker on both. `classifyOutgoingFile` ports
PeelCore's own `OutgoingFile.init` renaming rule for a video (always
`"video.mov"`/`"video/mov"`, whatever its real container, the
cross-client convention every Sphinx client uses for one); anything
else keeps its own name and declared type.

**Also built, slide-to-cancel:** the one real gap named when voice
sending first shipped. Sliding the held mic left or up 80dp
(PeelCore's own `VoiceMessage.cancelDistance`, taken as a direct dp
value) now discards the recording on release instead of sending it —
a custom pointer-event loop in the mic button tracks the live drag
(`detectTapGestures` only gives a press and a release, nothing about
where the finger went in between), and the recording indicator shows
"‹ Slide to cancel", switching to "Release to cancel" in red past the
threshold — PeelCore's own `VoiceRecordingIndicator`.

**Open:**

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
- No pricing on anything sent either — always free, the same
  simplification `Engine.sendMessage` already has for text in a paid
  tribe.
- No preview-before-send, for any kind: picking, capturing or recording
  sends right away, with whatever was already typed as its caption (a
  voice message, never a caption at all) — there is no staging tray to
  add a caption after picking, remove something picked, or send
  several files at once (PeelCore's own multi-file `sendAttachments`).
- No upload progress bar, just a plain "Sending photo…"/"Sending
  file…"/"Sending voice message…" line — PeelCore reports bytes
  transferred as it goes; this port waits for the whole thing silently.
- No retry on a failed send, for any kind: a send that throws (upload
  refused, no connection, a file over the 100MB limit, etc.) shows the
  one-line error already used for a failed text send and whatever was
  picked or recorded is gone — nothing is kept to try again, unlike
  PeelCore's own draft-row-first design.
- The mic permission prompt is the bare system dialog, asked the
  moment the mic button is first pressed — PeelCore's own composer
  shows "Allow the microphone in Settings to record voice messages."
  once denied (`recorder.denied`); this port shows nothing further
  after a refusal, so pressing the mic again just re-prompts (or does
  nothing once Android stops asking, after a repeated denial) with no
  explanation of why.
- A file picked through "File" is read entirely into memory before its
  size is checked against the 100MB limit — PeelCore's own
  `sendAttachment` does the exact same thing (`resourceValues`'s
  cheaper check first, then `Data(contentsOf:)` and a second check),
  so this isn't a shortcut unique to this port, just a real memory
  cost either way for a file that turns out to be too large.
- No animated-GIF-vs-still-image split for a GIF picked through
  "File" (PeelCore's own `OutgoingFile.init` sends an animated one as
  `"image.gif"`/`image/gif`, unmodified bytes, keeping its motion; a
  still one as `"image.jpg"`). This port's own image-prep pipeline
  already flattens any picked image to a still JPEG before it's ever
  sent that way (Photo/Camera), so this only matters for a GIF picked
  through the generic file picker specifically — it keeps its own
  name and declared `image/gif` type (so it still renders, inline, as
  an image — `AttachmentKind` classifies by `mediaType` containing
  "image" or "gif" either way), it just isn't relabeled the exact way
  PeelCore's own picker would.

**Update (2026-10-29), from a full iOS-vs-Android audit:** two real
fixes in `VoiceRecorder.kt`, both about what happens right at the
boundary between one recording ending and the next one starting.

A fast release-then-immediate-second-press could silently drop the
second recording. `start()`'s own guard (`if (isRecording) return`)
read a flag that only flipped back to `false` once the *entire*
teardown finished — stopping and releasing the real `AudioRecord`, on
a background dispatcher, after the recording loop noticed
`stopRequested` and exited — not the instant `release()`/`cancel()`
was called. A second press landing inside that window was simply
ignored: no recording, no error, nothing. PeelCore's own equivalent
(`VoiceRecorder.end`) sets its own "held" flag `false` as its very
first, synchronous line, with no async gap at all. Fixed by splitting
the one flag into two: a new `sessionOpen`, closed synchronously the
instant `release()`/`cancel()` runs (this is what `start()` now
guards against), and the existing `isRecording` (Compose state, UI
only, still cleared once teardown actually finishes). A `start()`
landing while the previous session is still tearing down now queues
behind it (`previousTeardown?.join()`) instead of either colliding
with the still-open `AudioRecord` or being dropped.

`stopRequested`/`cancelled` were plain `var`s, written from the
gesture handler on the main thread and read in a tight loop on
`Dispatchers.IO` — nothing guaranteed the IO thread would ever
actually observe a write from the other one. Marked `@Volatile`.

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
- One generic `sendAttachment(bytes, fileName, mediaType, ...)` under
  every kind sent (image, voice, file), not a parallel function per
  kind — PeelCore's own `Engine.sendAttachment` is the same single
  function under all of its kinds; only the *classification* (what
  name/type a given file sends as) differs by kind, not the actual
  encrypt/upload/send mechanics.
- No dedicated "send a video" picker or function — PeelCore's own
  iPhone app has none either (`AttachButton`'s menu is Photos/Camera/
  Files, no fourth "Video" option; the photo picker itself is
  image-only, `matching: .images`). A video reaches the send path
  through the generic Files picker on both platforms, so Android's
  own `sendFileAttachment` covers it without a separate code path -
  confirmed by reading the real `Composer.swift` source, not assumed
  from "a chat app's attach menu usually has a Video option."
- `AudioRecord`, not `MediaRecorder`, for the voice recorder —
  `MediaRecorder` has no output format that is a plain, uncompressed
  WAV (its PCM-ish modes are still container formats other clients
  don't expect for this), and a plain 16-bit PCM WAV is exactly what
  every Sphinx client sends and plays. `AudioRecord` gives raw PCM
  samples directly, so the WAV header is written by hand (`VoiceRecorder`'s
  own `toWav`) — the same RIFF/`fmt `/`data` layout `peel`'s own
  `WavHeader(_:)` parses in reverse.
- The 80-point `cancelDistance` carries over as 80dp, not converted
  through a density calculation — the same "copy the number, don't
  derive it" choice this port already made for touch-target sizes
  elsewhere; a point and a dp aren't identical units physically, but
  nothing about this gesture needs pixel-exact parity with iOS, only a
  threshold that feels right on a phone screen.
- `isCancelling` lives on `VoiceRecorder` itself, not as local Compose
  state in the gesture handler — the composer's recording indicator
  and the mic button's gesture loop both need to read the same live
  value, and `VoiceRecorder` is already the one object both of them
  hold a reference to.

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

**Read the real Composer source before assuming "finish sending" meant
a dedicated video picker.** "No video, voice-recording or arbitrary-
file sending" sounds like three features on the composer's attach
menu; reading `Apps/iOS/Peel/Composer.swift` in full showed the real
app's own menu is Photos (images only)/Camera/Files, with video
reaching the send path only through Files, and the mic as its own
button beside the composer, not a fourth menu item. Building what the
reference app actually does — one new "File" option plus a mic button
— instead of inventing a video picker iOS itself doesn't have avoided
adding a feature that would have made this port *diverge* from the
thing it's porting, not just extend it.

**`MediaRecorder` looked like the obvious tool and wasn't the right
one.** Android's usual path for "record audio" is `MediaRecorder`, but
every one of its output formats compresses or containers the audio in
a way that isn't a plain WAV — and a plain 16-bit PCM WAV is
specifically what `VoiceMessage.swift` says every Sphinx client sends
and expects. `AudioRecord` (raw PCM samples, no container at all) plus
a hand-written WAV header turned out to be the actual match for the
format, not a step down from the "proper" API.

**`detectTapGestures` was the wrong tool once the feature needed to
know *where* the finger was, not just whether it was down.** The
original press-and-hold used `detectTapGestures(onPress =
...)`/`awaitRelease()` — fine for "start on press, finish on release,"
but it never surfaces a position during the hold, which slide-to-cancel
needs continuously, not just at the end. `awaitEachGesture` with a
manual `awaitFirstDown`/`awaitPointerEvent` loop is the lower-level
tool built for exactly this — reading every position change between
down and up — and it's not more code than a hand-rolled version of the
same loop `detectDragGestures` already wraps, just without the
"dragging" framing that doesn't quite fit "this is a press that also
happens to track movement."

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
  reference in the other direction. `AudioPlayer` and the
  picker/camera flow are not unit-tested — one wraps
  `android.media.MediaPlayer`, the other needs a real device's photo
  picker and camera app, both the same category of gap as `Engine`'s
  own untestable native-library calls.

**Also done (2026-10-29), the rest of sending:**

- `engine/Engine.kt`: `sendImageAttachment` refactored into a thin
  wrapper over a shared private `sendAttachment(chat, bytes, fileName,
  mediaType, caption, isTribe, replyTo, inThread)` (now also checks
  the 100MB limit, and writes the `<muid>.name` sidecar for any kind,
  not just images); `uploadFieldName(mediaType)` (PeelCore's own
  `OutgoingFile.field`); `sendVoiceMessage(chat, wav, isTribe, ...)`;
  `sendFileAttachment(chat, bytes, pickedName, declaredMediaType,
  caption, isTribe, ...)`.
- `engine/Attachment.kt`: `classifyOutgoingFile(fileName,
  declaredMediaType)` — PeelCore's own `OutgoingFile.init` renaming
  rule, trimmed (no animated-GIF detection — see Open).
- `ui/components/VoiceRecorder.kt`: a press-and-hold recorder on
  `AudioRecord` — 16 kHz mono 16-bit PCM, written as a plain WAV by
  hand (`toWav`); `start`/`release`/`cancel`, Compose state
  (`isRecording`/`elapsedMs`/`isCancelling`); auto-stops and sends at
  5 minutes (`MAXIMUM_DURATION_MS`), discards anything under 1 second
  (`MINIMUM_DURATION_MS`), both PeelCore's own `VoiceMessage` numbers;
  `updateCancelling` for the gesture handler's own live drag read.
- `ui/dashboard/ConversationScreen.kt`: a "File" attach menu item
  (`ActivityResultContracts.OpenDocument`, reading the picked file's
  bytes/name/declared type through its own `ContentResolver`); the
  composer's trailing button is now Send when there's text to send,
  else a press-and-hold mic that records and sends a voice message on
  release, or discards it on a slide-to-cancel release
  (`awaitEachGesture`/`awaitFirstDown`/`awaitPointerEvent`, tracking
  the drag against `VOICE_CANCEL_DISTANCE_DP` — PeelCore's own
  `cancelDistance`); the recording indicator (a red dot, the clock,
  "‹ Slide to cancel"/"Release to cancel" — PeelCore's own
  `VoiceRecordingIndicator`); the single `sendingImage: Boolean`
  composer param became `sendingLabel: String?`, set by whichever send
  path is in flight ("Sending photo…"/"Sending file…"/"Sending voice
  message…").
- `AndroidManifest.xml`: `RECORD_AUDIO`, requested at runtime the
  first time the mic button is pressed.
- Tests: `AttachmentTest` — `classifyOutgoingFile` relabels every
  recognised video extension to `"video.mov"`/`"video/mov"`, keeps
  anything else's own name, guesses a media type from the extension
  when none was declared, and falls back to
  `"application/octet-stream"` for neither. 71 tests passing.
  `VoiceRecorder` is not unit-tested — it wraps
  `android.media.AudioRecord`, which needs a real microphone, the same
  category of gap as `AudioPlayer`'s own `MediaPlayer` wrapper.

**Also done (2026-10-29), from a full iOS-vs-Android audit:**

- `ui/components/VoiceRecorder.kt`: split the one `isRecording` guard
  into `sessionOpen` (closed synchronously by `release()`/`cancel()`,
  what `start()` now checks) and `isRecording` (Compose state only,
  still cleared once teardown finishes); `start()` now tracks the
  previous session's teardown `Job` and joins it before opening a new
  `AudioRecord` rather than colliding with one still releasing the
  mic; `stopRequested`/`cancelled` marked `@Volatile`. No new tests —
  same `AudioRecord`/real-microphone gap as above.

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
- A phase's own "Open" list naming three remaining things ("video,
  voice-recording or arbitrary-file sending") described the *user-
  visible* gaps accurately, but the actual shape underneath them was
  one new generic send path plus a label for each kind, not three
  separate features — reading PeelCore's own `sendAttachment` first
  (one function under every kind it sends) rather than starting from
  the three-item list avoided building three parallel mechanisms that
  would have needed to stay in sync by hand.
- The "obvious" platform API for a task (`MediaRecorder` for "record
  audio") is worth checking against the *exact* format something
  downstream actually needs before reaching for it — the right tool
  here was the lower-level one (`AudioRecord`), because the task
  wasn't "record audio," it was "produce the specific uncompressed WAV
  bytes every other Sphinx client already expects."
- A gesture isn't just "press" or "drag" — this one was both at once
  (a press that starts something, which then also needs its live
  position while still held), and the Compose APIs that name
  themselves after one or the other (`detectTapGestures`,
  `detectDragGestures`) don't quite fit something that's genuinely
  both. The lower-level `awaitEachGesture` loop both of those
  convenience functions are themselves built on was the right level to
  reach for once the shape didn't match either convenience function's
  own assumption.
