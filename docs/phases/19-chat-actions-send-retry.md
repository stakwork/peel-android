# Phase 19 — Chat-list actions and Retry of failed sends

## Status (2026-11-05)

**Built** (compiles, unit-tested on the JVM; not run on a device):

- Chat-list row actions, ported from `peel`'s `ChatsTab.swift` `ChatLink`:
  swipe right = Mark read / Mark unread (`ReadToggle`), swipe left = Mute /
  Unmute, long-press menu = the same plus "Notify me about" (All messages,
  Mentions only in tribes, Nothing). `ui/dashboard/ChatRowActions.kt`. iOS has
  **no pin, archive or delete** for a chat, so Android has none either.
- Mute (`Engine.setMute`): sent to the server first (`Sphinxrs.mute`; the
  server uses it for push and the tribes server marks "mentions only"), stored
  only after the core took it, needs a connection (as iOS). Mark unread
  (`Engine.markUnread`): the marker goes just below the newest counted message
  from someone else (`Storage.setRead(allowBack = true)`), published with
  `Sphinxrs.read`; offline it stays `readPending` and is flushed like a read.
  Opening a chat still marks it read. `getReads`/`getMutes` answers skip chats
  changed here since they were asked (`readsSentSinceGetReads`,
  `mutesSetSinceGetMutes`).
- Badge rules match `ChatReadState.shownUnread`: All = unread, Mentions only =
  unread mentions (`ConversationRow.mentions`, worked out only when a chat is on
  that level), Muted = none.
- Retry/Discard of undelivered sends (`SendRetry`, `Engine.resendMessage`,
  `UndeliveredLine`): a send is undelivered when `FAILED`, or `PENDING` past 30 s
  (`Message.confirmationTimeout`) and unable to have paid. Retry reuses the same
  row (new uuid/tag/time, `firstSentAt` keeps the first), under a per-row guard
  and one conditional UPDATE (`Storage.commitResend`) that runs before the
  message is published, so a double tap or a racing reconnect sends once.
  Discard asks in place and deletes the local row (`Storage.discardUndelivered`).
- Auto-resend on (re)connect/startup (`Engine.resendRecent`, iOS `resendRecent`):
  text/attachments only, once (`autoResentAt`), first sent within an hour, no
  sats, never in a tribe, and only if `getTags` was asked about the tag and the
  server's records do not name it (a pending one only after 2 min).
- Schema v10: `message.firstSentAt`, `message.autoResentAt`. `chatState` needed
  nothing new (marker, `readPending`, `muteLevel` already there).

**Open / not ported:**

- Retry of payments, boosts, payment requests and a failed attachment *upload*
  (no draft row exists; `sendAttachment` throws). A failed call can be discarded.
- Retry/Discard are inline under the message, not in the long-press menu; Mark
  unread "from this message" is not offered.
- Mentions-only mute on the server needs the tribes server's cooperation: not
  checkable without a device and a tribe.
- No migration test from a real v9 file (Robolectric creates fresh v10 files).

## Journal

- iOS rules were read from `Message.swift` (`isRetryable`, `isAutoResendable`),
  `Engine+Messages.swift` (`resend`, `resendRecent`, `discard`, `markUnread`,
  `setMute`) and `ChatsTab.swift`. `Storage.commitResend` adds an age check iOS
  gets from its actor: without it a second Retry right after the first would
  find a fresh `PENDING` row eligible.
- `SELECT changes()` after `execSQL` is unreliable across pooled connections;
  new writes use `executeUpdateDelete`/`delete`'s return value. (The existing
  `markSendStatus` still uses the old pattern.)
- `selectionArgs` bind as text: compare with `CAST(? AS INTEGER)`.
