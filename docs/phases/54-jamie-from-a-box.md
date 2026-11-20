# Phase 54 — Jamie in a tribe, from a box

Status (2026-11-20): **built; JVM/Robolectric tests pass (the places client, the pick, the approval by key, the row drawn); not run
against a live box or on a device.** Item 7 of strut's `plans/jamie-in-tribes.md` (§4, "Linking a tribe"), Android's side. Strut's side
is stakwork/strut#787 (linked places, `specs/API.md` §13.4 and §13.6 "Places"); the box's Sphinx daemon follows them
(stakwork/sphinx#440, #441).

## What it is

A self-hosted Stadeum box has no Hive. Its Sphinx daemon is a **host** of the box's strut: it launches each `@jamie` turn through
strut's run API and posts the reply in the tribe. Which tribes it is in is strut's list of **linked places**, which the daemon
follows. Evan ruled (2026-11-20) that adding Jamie to a tribe is one tap in Peel, as with Hive, not a DM.

So a tribe's owner opens the tribe's details and finds **Jamie**: **Answers from** with a menu of the boxes Peel is linked to
(docs/phases/45-box-link.md) whose strut has a Sphinx host ready, and **No Jamie**. Picking a box links the tribe there as a place;
the box's Jamie joins it (or asks to, in a private tribe, and Peel approves it by the box's key); No Jamie takes it off.

## Built

**Engine, pure (`engine/BoxPlaces.kt`), JVM-tested (`BoxPlacesTest`):**

- `BoxPlaces`, `BoxPlace`: `GET /people/places`'s `{ hosts: [{ host, account }], places: Place[] }`. `jamie` is the Sphinx host's
  account key (`sphinx:<66 hex>`, lower case): the box's Jamie, null while no Sphinx host is ready. `place(tribe)` matches a key in
  any case. `linking`/`unlinking` are what a link's or an unlink's answer leaves.
- `PlacesClient(box, http)` over `BoxHttp`, with the person token Peel holds for the box (Bearer):
  - `read()`: `GET /people/places`. A strut from before places (`404`) is `BoxPlaces.NONE`: nothing offered from it.
  - `link(TribePlace)`: `POST /people/places { host: "sphinx", key, link, name }` → `{ place, account }`; the answer's `account` is
    the box's Jamie key, checked. `409 place_linked:` → "Someone else on <box> already added Jamie to this tribe."; `404 no_host:` → "<box> isn't ready for tribes yet."; `429` busy; strut's own words for the rest.
  - `unlink(key)`: `DELETE /people/places/sphinx/<key>` → `removed`.
  - A `401` is `BoxTokenRefused`, for `BoxesModel.asMember` to renew the token once (by signing again, as `home` does) and call again.

**The row's logic (`engine/BoxJamies.kt`), JVM-tested (`BoxJamiesTest`):**

- `TribeJamie` (pure): `options` (each box whose Sphinx host is ready, named by its host, in link order; a box the tribe is linked
  on even when its host isn't ready now; then No Jamie, id `""`), `pick` (the first box where the person linked the tribe; `""`
  once every box answered and none did; null until then), `shows` (a box offers Jamie or holds the tribe, or one could not be read),
  `jamies` (the keys of the boxes where the tribe is linked).
- `BoxJamies` (Compose state, main thread; `Boxes.jamies`): `read` (every box at once, one read at a time, joined; a box that fails
  keeps why), `pick(id, tribe)` (linked on the chosen box first, then taken off every other box it was on, so one Jamie answers; a
  refusal in `problems[tribe]`, nothing retried), and `requested(tribe, approver)` (below).
- `BoxesModel.asMember(box) { … }`: a call with the box's current link, renewed once on a `401`; a box that no longer links the
  key is let go, as `home` lets it go.

**Approving Jamie in a private tribe** (iOS `AppModel.approveJamie`, `Engine.approveWaitingMember`):

- `Engine.incoming` (a `SharedFlow` beside `onIncoming`, which `MessageNotifier` keeps): `BoxesHousekeeping` hands each join request
  (type 19) that arrives while the account is open to `BoxJamies.requested`. Only the owner is sent one.
- `requested`: the boxes read again unless read in the last minute, and again when none names the tribe; for each box where the
  person linked it, `Engine.approveWaitingMember(tribe, jamieKey)` up to three times, five seconds apart (the server may list a
  request a moment late); once per run of Peel per tribe and key; a failure said in the row.
- `Engine.approveWaitingMember`: the tribes server's member list (`tribeMembers`), the member waiting **by key**
  (`TribeMembers.waiting`; the request itself may carry an alias alone), with the stored request it answers by key or by alias.
- `TribeMembers.answerBody` now also names the member by key (`member`) when it is one, which the tribes server reads first
  (stakwork/sphinx `tribes/src/forward.rs`, `member_from_raw`; iOS sends `member` too). Before, an approval named only `replyUuid`
  (the server ignores `pubkey`), so a request Peel had not stored could not be approved.
- A request that came while Peel was locked (the boxes are closed then) is approved when the tribe's details open: the row checks
  for an unanswered stored request (`Engine.hasJoinRequest`) once the boxes are read.

**UI (`ui/details/TribeJamieSection.kt`; Robolectric, `TribeJamieSectionUiTest`):** in the tribe's details, after Members/Edit, for
the owner of a tribe with a link (`ChatDetails.tribeLink`, the account's tribes host): "Jamie", the "Answers from" row with the
picked box (or No Jamie) and a menu, the line under it in iOS's words with boxes for orgs ("Pick one of your boxes: Jamie joins
#<tribe> and answers @jamie there from that box, using your access to it." / "Anyone in #<tribe> can ask @jamie. Jamie answers from
<box>, using your access to it."), a refusal in its place. Nothing at all with no box linked, or none with a Sphinx host ready. "Asking
your boxes…" while they are read; a box that could not be read is said under the row (with Try again when no box could be).
Strings in `strings_people.xml`, Spanish too.

## Decided

- **Boxes only.** Hive's tribe linking (`jamieLinks`, `linkTribe`, `unlinkTribe`) stays unported (`Hive.kt`).
- **One Jamie per tribe.** A pick links the new box before unlinking the old, so a refused link changes nothing.
- **A box is named by its host** (`BoxLink.host`), as You › Boxes shows it.
- **Owner only**, as on iOS (`offersJamie`): strut checks nothing about the tribe's owner, so the row is the only gate.

## Not ported

- **"Knows who is speaking"** (iOS's switch, the tribes server's `ShowSenders` mark, sphinx `docs/tribe-sender-identity.md`):
  Android has no way to mark a member to see senders (no `showSenders` call, no `seesSenders` on `TribeMember`). Until it does,
  every turn in a box-linked tribe runs as the linker.
- **"Add Jamie from <org>"** (iOS's one-tap suggestion from the tribe's app or second brain): Hive's, no box equivalent.
- **Someone else's Jamie** (iOS `othersJamie`): on a box, another person's place is a `409` to the pick, said in the row.

## Not tried

- Against a live box: none runs strut `main` with the Sphinx daemon in `DAEMON_REPLY=strut` mode yet. A local strut
  (`npm run dev` with `STRUT_HOST_KEYS=sphinx:<token>`, a `PUT /hosts/me` as the host) would show a box in the row.
- On a device or emulator.

## Found, not fixed here

- `TribeMembers.kickBody` sends `pubkey`, but the tribes server reads a kick's member from `member` alone
  (`MsgType::GroupKick`, "member required"), so Kick in Members likely fails on Android.
