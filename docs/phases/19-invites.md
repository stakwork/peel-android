# Phase 19 — Invites and contacts

Status: built, compiled (`assembleDebug`) and unit-tested; **not run against a server or a device**
(no native core on the JVM, no Lightning-backed server in the pod). Ported from `peel`
(`Account.swift` `signup`, `Engine+Account.swift`, `Engine+Contacts.swift`,
`Engine+Messages.swift` `addContact`, `Contacts.swift`, `Storage+Writes.swift`).

## Built
- **Signup with an invite is the only way in for a new account.** Onboarding: Welcome → Invite →
  Name → PIN → confirm PIN → words → check (`ui/onboarding/OnboardingScreen.kt`). "Restore with
  recovery words" is unchanged. `account/Signup.kt` mirrors `Account.signup`: the server comes from
  the invite (`ServerAddress.named`, never the default config); tribe host from the public config,
  failure ignored; words sealed; settings written (name, `SERVER`, `SERVER_PINNED`, `INVITE`)
  **before** anything connects; then `Engine.start()`. Kept-or-deleted rule: `SignupRules.keepAccount`
  (delete only if not registered and no register can have reached the server).
- **Engine**: `connectAndRegister` registers with the stored invite while unregistered (and re-registers
  on each later connect), uses the invite's server, and for an invite account reconnects to the stored
  server (`Setting.SERVER_PINNED`; restores keep resolving the config default). The register wait is 30 s
  for an invite (10 s otherwise). `inviteMayBeRedeemed` is set just before the register publish.
  `afterConnect` runs `addInviterIfPending` (clear first, restore on not-connected; `InviterFlow`), then a
  background `fetchPaymentHistory` so the invite's credit shows in the balance.
- **Key exchange**: `Engine.addContact` (`engine/Invites.kt`) calls the core's `addContact` (5 sat) and
  publishes in PeelCore's `publishPlan` order (`PublishPlan`, `run(ordered = true)`: settle/register
  first, a new key's messages after 750 ms, messages after the async pay once the server took it or 10 s).
  Incoming contact-key messages (type 10): the core's reply (key confirmation + sats back) is now
  **published** (`Engine.answerKeyExchange`; it was dropped before), the contact is marked ready so it
  shows in the chat list, and a code in the sender's info marks our invite used (`Storage.insertMessages`).
- **Invites made here**: `invite` table (DB v10), `Engine.makeInvite` / `cancelInvite` / `forgetInvite`,
  statuses sending/ready/failed/cancelling/cancelled/used, funding payment status moves them
  (`InviteRules`, `Storage.markSendStatus`), sending ones are re-asked on connect (`pendingSendTags`).
  UI: `ui/invites/InvitesScreen.kt` (list, new invite with confirmation, QR/copy/share, cancel).
- **Add contact**: `ui/contacts/AddContactScreen.kt` (code / `share_contact` link / invite / bare pubkey +
  route hint), reached from the Chats "Add contact" row (and the empty chat list). `ContactCode` is the
  port of iOS `ContactCode`.
- Deep link: manifest `sphinx.chat://` VIEW filter (`launchMode=singleTask` so a link never starts a
  second engine) fills the invite step (`MainActivity`, `InviteLink.fromDeepLink`).
- `CodeScanner` generalises the invoice QR scanner (invite and contact scans use it).

## Not ported / open
- **Deep-link scheme**: iOS has no handler, so `sphinx.chat://` (the official apps' scheme) is a guess for
  Peel. Opening a link only fills the invite step; with an account already set up it is ignored.
- **Profile photo at signup**: iOS onboarding has none, and Android has no profile-photo upload at all yet.
- **Used/cancelled invite**: the mixer gives no reply, so it looks like a timeout: the account is kept
  unconfirmed ("Not confirmed yet…") and registers again on each connect; "Forget this account" in You
  removes it. No expiry is known for invites.
- Crash between clearing the stored invite and publishing the key exchange loses the inviter add (same
  as iOS). A core refusal (`SphinxException`) is the one failure that restores the invite.
- `handle` results are published only for a live key exchange (type 10, not while restoring); PeelCore
  publishes every handled result (settle of received payments, pings). Not widened here.
- Not ported: `contact.isNew` badge, `deleteContact`, tribe links in add-contact, waiting-sats accounting.
- Contact "ready" on Android comes from the core's `listContacts` (`Engine.markPeopleReady`), after an add
  and after a type 10/11 message.
- Tests: `InviteLinkTest`, `ContactCodeTest`, `InviterFlowTest` (clear-first/restore, keep/delete matrix,
  publish order), `InviteRulesTest`, `StorageInviteTest` (status moves, used-by-code, v9→v10 upgrade).
  Native-core paths (`Signup.create`, `makeInvite`, `addContact`) are untested.
