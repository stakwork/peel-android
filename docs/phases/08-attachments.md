# Phase 8 — Attachments (receiving)

## Status (2026-10-28)

**Built:** an attachment message (type 6, from anyone, in a restore, a
catch-up or live) downloads, decrypts and shows itself the moment its
row appears — images inline, video/audio/PDF/anything else as a named
row that opens in whatever app the person has for it. This needed three
things this port had none of before: the exact encryption format the
other Sphinx clients use (RNCryptor v3 — PBKDF2-HMAC-SHA1, AES-256-CBC,
HMAC-SHA256, ported byte-for-byte and checked against an independent
Python implementation, not just against itself), a small HTTP client
for the memes server (sign in with a signed challenge, download by
bearer token), and a local decrypted-file cache. Scoped to receiving
only, on the user's explicit direction — sending is its own task.

**Open:**

- No sending at all — this is the whole phase's scope, not an
  afterthought. `Engine.sendMessage` still only ever sends plain text.
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
- No video/audio playback in the app, no PDF viewer in the app, no
  image viewer (full screen, zoom, swipe between a chat's images) —
  everything but an inline image opens in whatever other app the
  person has installed for it (`Intent.ACTION_VIEW` through a
  `FileProvider`). An image shows inline, nothing more: no tap to view
  full size.
- No container-signature verification for video or audio (PeelCore
  checks `ftyp`/`moov`/RIFF/etc. headers before trusting the declared
  kind; this port trusts the declared `mediaType` for those two
  outright). Images are verified (`BitmapFactory` must actually decode
  them) and PDFs are (the `%PDF-` signature), since both were cheap to
  check with what Android already has built in.
- No paid attachments. A free one (or a tribe's, which always keeps its
  key) shows normally; one that cost sats and this account never bought
  has no key at all here and shows as an error — correct, since nothing
  here can buy one, but also nothing here can tell the difference
  between "you'd need to pay for this" and any other reason the key
  might be missing.
- No `mediaKeyForSelf`-only row gets anything beyond what `ownKey`
  already covers (PeelCore's own fallback) — a device that bought an
  attachment on someone else's behalf (the seller side) is not a case
  this port needs, since it never sends.

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
  error/loaded states), `AttachmentContent` (inline image, or a named
  row that opens externally via `FileProvider`).
- `AndroidManifest.xml`/`res/xml/file_paths.xml`: a `FileProvider`
  scoped to just the attachment cache directory.
- Tests: `MediaCryptoTest` — decrypts a blob from an independent Python
  RNCryptor-v3 implementation, refuses the wrong key, refuses a
  too-short or wrong-version blob. 57 tests passing.

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
