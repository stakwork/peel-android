# Phase 23 — Tribe management

Status: written **without a build**: not compiled, not run, not device-tested. Several wire details are
assumptions (below); check them against iOS (`peel` `Engine+Tribes.swift`, which was not readable from here) and the
core before trusting any of it.

## Built
- **Join** (`TribeLink`, `Tribes.kt` `lookupTribe`/`joinTribe`, `ui/tribes/JoinTribeScreen`): "Join a tribe" on the chat
  list. Input is parsed by the pure `TribeLink.parse`: a `sphinx.chat://?action=tribeV2&pubkey=…&host=…` link, the older
  `action=tribe&uuid=…`, `pubkey@host`, `pubkey/host`, `host/pubkey`, a bare pubkey, a bare uuid. A named host must be the
  account's own tribes host (`TribeLink.hostFor`) — a link never chooses where Peel connects. "Look up" reads the registry
  (`TribeInfo.lookup`, by pubkey or uuid) and shows the price; "Join" pays `ChatDetails.joinPrice` (1 sat minimum) through
  `checkPayment` (confirmation above the threshold) and calls the core's `join_tribe(pubkey, owner_route_hint, alias, msat,
  private)`. The tribe is stored as a ready tribe with its name and prices at once.
- **Create / edit** (`TribeForm`, `ui/tribes/TribeFormScreen`): name, description, picture (picked, uploaded with
  `uploadPicture`), price to join, price per message, escrow amount and hours, tags, unlisted, app URL, feed URL, "approval
  required" (= registry `private`). `TribeForm.toJson` is the body for `create_tribe`/`update_tribe` (sats typed, **msat**
  written, as `TribeInfo` reads them; escrow hours → `escrow_millis`). Edit is offered to the owner (`Engine.ownsTribe`:
  registry `owner_pubkey` is the account key, or the tribe was created here, `Setting.OWNED_TRIBES`) and is prefilled from
  the registry; the form is not shown until the registry has answered, so a save never overwrites unread fields.
- **Leave / delete**: in the tribe details. A member's Leave sends a type-15 message and removes the chat locally
  (`Storage.removeChat`); the owner's button is Delete (`delete_tribe`, then the same removal). That iOS deletes for the
  owner is an assumption (the relay does).
- **Members** (`TribeMembers`, `ui/tribes/TribeMembersScreen`): `list_tribe_members` (answer in `RunReturn.tribeMembers`,
  via `Engine.askAndAwaitOrNow`), merged with join requests found in the stored messages (type 19 with no later approve/reject
  naming it by `replyUuid`).
- **Approve / reject / kick**: type 20 / 21 / 16 messages sent to the tribe with `send` (the core has no dedicated call),
  bodies from `TribeMembers.answerBody` / `kickBody`. Approve/reject rows are stored so the request clears at once.
- **Pin / unpin** (owner): the registry's `pin` (message uuid) set through `update_tribe` with the rest of the tribe
  unchanged (`Engine.pinMessage`). The message menu offers Pin/Unpin; the chat shows a "Pinned: …" line under the header
  (when the message is among those loaded) with Unpin for the owner. Not shown in a thread view.

## Assumed (unverified)
- `create_tribe`/`update_tribe` JSON field names (the registry's: `name, description, tags, img, price_to_join,
  price_per_message, escrow_amount, escrow_millis, unlisted, private, app_url, feed_url, pubkey, uuid, pin`) and that the
  amounts are msat. If the core wants sats, change `TribeForm.toJson`/`from` and the two `updateTribeInfo` calls.
- `RunReturn.newTribe` is a JSON object with the new tribe's `pubkey`; `RunReturn.tribeMembers` is an array or an object
  with `members`/`pending` arrays of `{pubkey, alias|nickname, photo_url|img}`.
- The registry gives `owner_route_hint`, the route hint `join_tribe` needs; without it a join is refused.
- Message types 15 (leave), 16 (kick), 19 (member request), 20 (approve), 21 (reject) and their bodies (`content`,
  `pubkey`, `replyUuid`), and where a join request's asker's key is (`body.pubkey`).
- Pinning through `update_tribe`'s `pin` rather than a message type.
- The default tribe server (`get_default_tribe_server`) is the one all owned tribes live on.

## Not done
- Role handling beyond owner (admins approving), members' roles, transferring ownership.
- A tribe's `feed`/`app` use, tribe search/directory, the join screen's QR for a tribe the app itself shows.
- Local pending state while a private tribe's request waits (the tribe shows in the list at once).

## Tests
`TribeLinkTest`, `TribeFormTest` (body builder, validation, registry parse), `TribeMembersTest` (members parse, pending
requests, message bodies, ownership), `StorageRemoveChatTest`.
