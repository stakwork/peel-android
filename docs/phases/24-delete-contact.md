# Phase 24 — Remove a contact

Status: written **without a build**: not compiled, not run, not device-tested (the pod had no JDK or Android SDK).
Ported from `peel`'s `Engine+Contacts.swift` `deleteContact`, `Engine.swift` `forgetContact`/`removeFiles`,
`Storage+Writes.swift` `deleteContact`, `Storage+Reads.swift` `hasPendingPayment`/`serverIndexes`,
`AppModel+Contacts.swift` `deleteContact` and `ContactDetailsView`.

## Built
- **`Engine.deleteContact(pubkey)`** (`engine/ContactRemoval.kt`): refuses a tribe or someone the core has no `c/` entry for
  (`ContactRemovalRefused` NOT_A_CONTACT), sats still on their way (`hasPendingPayment`: PAYMENT_PENDING), and offline
  (`NotConnectedException`; registered and a transport). Then the core's `delete_msgs(pubkey = null, msg_idxs = indexes)` for the
  chat's account-key messages (types 3, 5, 7, 29, 10, 11 with a server index) when the key exchange completed (`contact_key` in
  `get_contact`) and there are any; then always `delete_msgs(pubkey = <contact>, msg_idxs = null)`; both through `Engine.run`
  (published); then `forgetContact`. The order of refusals and calls is the pure `ContactRemoval.delete`, which takes the
  core, connection and storage as lambdas so it is JVM-tested.
- **`Engine.forgetContact`**: drops `c/<pubkey>` from the state, recomputes the person-key cache, in one transaction
  (`Storage.deleteContact`) removes the core state row, the chat's messages and chat state, and the contact row; clears the
  per-chat read/mute "sent since get" sets; deletes the decrypted cached attachment files (`<muid>`, `<muid>.name`).
- **`Storage`**: `deleteContact` (returns the attachment messages), `hasPendingPayment(chat)`, `serverIndexes(chat, types)`.
  Kept, as iOS: the chat's time zone switch, money tables (invoice, payment), tribes and other chats.
- **UI**: contact details has "Remove contact" (destructive) → dialog "Remove <name> and delete your chat?" with Remove / Cancel; the
  button shows "Removing…" and is disabled while it runs; a failure shows the exception's message under the button (the engine's
  refusals carry user-readable text, as `TribeRefused`/`NotConnectedException` do). On success `MainTabs` leaves a call in that chat,
  cancels its notification (`Notifications.cancelChat`) and returns to the chats list.

## Assumptions
- `get_contact`'s JSON has `contact_key` only once the key exchange completed (iOS `Contact.contactKey`, `CodingKeys`).
- Message types for `accountKeyTypes` and `moneySends` (direct payment 5, boost 29, purchase 7, purchase deny 9) are PeelCore's numbers.
- "Pending" is `message.status = 'PENDING'` / `invoice.status = 'PENDING'`, Android's spellings of iOS's `Delivery.pending`.
- `sphinxrs` already binds `deleteMsgs(seed, uniqueTime, state, pubkey?, msgIdxs?)`; no new wrapper was needed.

## iOS vs Android
- No child-index map is cached here (`contactChildIndexes()` reads the state on demand); `PushNameSync` rewrites its directory when
  `Storage.contactsVersion` moves, which `deleteContact` bumps. The person-key cache is the only thing recomputed.
- No outgoing-upload files by message id exist on Android, so only the decrypted cache is removed.
- Drafts, reply targets, an open thread, pending files and attachment prices are the conversation screen's own `remember(chat)` state
  here, not an app-model dictionary; they go when the chat closes. There is no `conversationNotices` store. A call in the chat is left
  by `MainTabs` (`callPhase`), notifications by `Notifications.cancelChat` (one notification per chat id).
- Phone-only navigation: the chat closes to the list; there is no sidebar selection to move.
- The engine's `deleteContact` itself needs the native library, so its wiring (not its logic) is untested on the JVM.

## Tests
`ContactRemovalTest` (call order and arguments, no-key-exchange / nothing-indexed variants, notAContact / notConnected /
paymentWithContactPending and their precedence, a failed core call forgets nothing), `StorageDeleteContactTest` (Robolectric: chat
removed and nothing else, `hasPendingPayment`, `serverIndexes`, chat leaves the list). Not covered: `Engine.forgetContact`'s cache
clearing and file deletion (need `Engine`).
