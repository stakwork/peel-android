# Phase 22 — Tribe details parity with iPhone, time zone sharing

Status: built, **not compiled and not run** (written without a build; review the Kotlin and run
`:app:testDebugUnitTest`). Source: `peel` `Apps/iOS/Peel/ChatDetails.swift`, `Sources/PeelCore/Preferences.swift`
(`TimeZoneSharing`), `Sources/PeelCore/TimeZones.swift` (`TimeZoneOffset.format`) — described to this session,
the iOS source itself was not read.

## Built
- **Tribe link**: centred "Tribe link" label over a white rounded QR of the link (`QrCodeImage`, the Receive
  screen's ZXing helper), then Copy link / Share ("Share link" renamed). The selectable monospace text stays beneath.
- **Hero subtitle** "Private tribe · Created <date>" (`ChatDetails.heroSubtitle`; "Public tribe" for a public one — the
  public wording is an assumption), **To join** fact (`price_to_join`), **Seen here** row with the member count (the
  old "Members" fact moved there). New `TribeInfo` fields: `isPrivate` (`private`), `created`, `priceToJoinMsat`.
- **Time zone** (`engine/TimeZoneSharing.kt`): per-chat switch "Share my time zone with <chat>" (default on; tribes read
  "#<name>") and an always-visible "Time zone" picker row (searchable dialog: "Automatic · <device id>" first, then
  `TimeZone.getAvailableIDs()`, each with its GMT offset); a pick saves at once. Offered in direct chats and **private**
  tribes only. A tribe's privacy comes from the registry (`Setting.TRIBE_PRIVACY`, filled by the details screen,
  `refreshTribeNames` and `refreshTribePrivacy` at connect); unknown counts as public, i.e. nothing is sent.
- **Storage/sync**: local `Setting.CHAT_TIME_ZONES` (one JSON map) and the account preferences file item
  `key "timezone"`, `identifier <chat pubkey>`, `value {"timezone_enabled":"true|false","timezone_identifier":"<IANA>" or ""}`
  (legacy `timezoneEnabled`/`timezoneIdentifier` read; a value held as JSON text read). `PreferencesFile` gained
  `timeZone/setTimeZone/chatIdentifiers` (per-chat items; the account-wide API is untouched, other items still pass through
  byte for byte); `Engine.syncTimeZones` runs inside `syncPreferences` with the same newest-date rule
  (`TimeZoneSharing.decide`). The identifier of a per-chat item being the chat pubkey is an assumption from the
  account-wide `"0"` convention; verify against a file written by iOS.
- **Sending**: `Engine.timeZoneMetadata` adds `"metadata": "{\"tz\":\"GMT+1\"}"` (a JSON string, format `GMT±H[:MM]`, e.g.
  GMT+5:30, GMT+0) to the JSON body passed to the core's `send` for text, attachment and direct-payment messages
  (`sendMessage`, `sendAttachment`, `sendPayment`), when on and offered. Boosts, calls, deletes are not given one.
- Tests: `ChatDetailsTest`, `TimeZoneSharingTest` (offset format, options, merge, file round trip).

## Gap: sending is unverified
The `sphinxrs` bridge `send(..., msgJson, ...)` has no metadata parameter; the metadata is put in `msgJson` like
`mediaKey`/`replyUuid` are. Whether sphinx-rs v0.8.41 forwards an unknown `metadata` key in the message JSON could not be
checked here (no native build). If it drops it, only the bridge can fix it. A received `tz` is **not** parsed or shown.

## Remaining differences from iOS tribe details
Leave tribe; "How you appear in #<tribe>" (photo + name, editing); members screen; QR button (save/share the image);
contact details' QR; "Members"-style owner/edit actions. Other differences noticed: the registry is fetched on every
open (no cache); a tribe's description/tags are hidden when empty; the time-zone picker is a dialog, not a sheet.
