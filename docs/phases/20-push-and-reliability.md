# Phase 20 — Push notifications and connection reliability

## Status (2026-11-04)

**Built; compiled and unit-tested** (`testDebugUnitTest` 204 tests, `assembleDebug`
and `lintDebug` pass). Not yet run against a real FCM push or the hub.

**Forget account (2026-11-05):** `PushRegistrar.accountForgotten` also removes the stored
FCM token and calls `FirebaseMessaging.deleteToken()` (best-effort, async), so the mixer
drops the old token on its next push. The mixer-side unregister now exists
(sphinx-rs 0.8.42 `unregisterPushTokenForApp`): `Engine.unregisterPushToken()` is called by the
"Forget this account" confirm in `YouScreen` before `engine.cancel()` and any wipe (no engine
exists afterwards), inside `withTimeoutOrNull(3s)`; offline, failure or timeout is only logged and
the local cleanup above is the fallback. Lock (`accountClosed`) keeps the token. Android never
sends `setActive`.

**readAll (2026-11-06):** `ReadAllPolicy` (engine package): the core's `readAll` is sent only when
the LOCAL real unread total (sum of `ConversationRow.unread`, not `shownUnread`) is 0, connected,
no restore running, and at most once per 30 minutes (monotonic clock); a failed attempt does not
start the window. Triggered by `Engine.maybeReadAll()` at the end of `afterConnect` (after read
sync and catch-up) and after `markRead`. No UI, no setting.

**Badge-only pushes:** `PeelMessagingService.isBadgeOnly` (no child, blank title and body) returns
without posting; the reconnect decision before it is unchanged.

**Setup:** put the Firebase project's `google-services.json` in `app/`
(git-ignored; without it the plugin is not applied and push is inert). Debug
and release share one Firebase app.

**Decided (2026-11-04)**
- **Own app id.** Registration is `setPushTokenForApp(seed, time, state, token,
  pushKey, appId, "production")` with appId `com.sphinx.peel.android`
  (`BuildConfig.PUSH_APP_ID`, `push/PushApp`). Not `sphinx` (the official Android
  app's: would overwrite its token on the same account) and not `com.sphinx.peel`
  (the `applicationId`, equal to the iOS bundle id). FCM has no sandbox, so the
  environment is always `production`.
- **Registration flag `PUSH_REGISTER_WITH_HUB`.** Off for the release build type. On only
  with `-Ppeel.push.registerWithHub=true`, or for the debug build type when `app/google-services.json` exists. `-Ppeel.push.registerWithHub=false`
  also forces it off.
- **Data-only pushes.** The server (`fcmMode=data`) sends no `notification` block,
  only data: `child` (hex of 36 bytes: 12 nonce + 8 index + 16 tag, sealed with
  this install's push key), `title` "Peel", `body` "You have new messages".
  `PeelMessagingService` posts the notification itself. Badge-only pushes are
  dropped server-side.
- **Names, like iOS.** `push/PushNames`: `decryptChildIndex(child, pushKey)` ->
  index -> `{"contact-<idx>": name, "tribe-<idx>": name}` -> "You have new
  messages from NAME" / "... in TRIBE"; any failure is "Peel / You have new
  messages". The map is `PushNameStore`: JSON sealed with a Keystore AES-GCM key
  in `filesDir/push-names.v1`, readable while locked with no engine (no PIN, no
  core state). Android extension in the same blob: `chat-contact-<idx>` /
  `chat-tribe-<idx>` -> chat id, so the tap opens the chat without core state.
  `PushNameSync` rewrites it (debounced 2 s) on open, when a restore finishes,
  on every reconnect and when a `contact` row changes (`Storage.contactsVersion`).
  Child indexes are the core's `my_idx` from `listContacts`
  (`Engine.contactChildIndexes`). Cleared on "Forget this account".
- **No duplicates (`PushPlan.decide`).** No engine: post. Engine open: `resume()`
  if down; in the background also post the resolved notification under the chat's
  id (`chat.hashCode()`, else one generic id), which `MessageNotifier`'s real
  notification replaces (it also cancels the generic one). In front: nothing.

**Uncertain:** whether the core's `listContacts` includes tribes and what exact
JSON type `my_idx` has (parsed as number or string). If tribes are absent, tribe
pushes fall back to the generic line until that is confirmed on a device.

- **iOS reference** (`peel`): `Engine+Push.swift`, `PushToken.swift`,
  `Apps/iOS/Peel/PushRegistration.swift`, `PeelPush/PushNotificationContent.swift`,
  `Notifier.swift`, `NotificationPolicy.swift`. Registration goes *through the
  core over MQTT*, not an HTTP endpoint: `setPushTokenForApp(seed, uniqueTime,
  state, pushToken, pushKey, appId, pushEnvironment)`, signed like any core call
  and published on the account's connection. `pushKey` is 32 random bytes as
  64 hex chars the app keeps; the token is sent as lowercase hex. It is gated
  by `PeelPushFlags.registerWithHub` (off by default: the server keeps one
  token per account, so registering overwrites the official app's). The push
  carries no text: `aps.custom_data.child` is the contact/tribe child index
  sealed under the push key; the iOS notification extension decrypts it
  (`decryptChildIndex`) and names the chat from a sealed name map, else shows
  "Peel / You have new messages". No CallKit/PushKit exists in `peel` yet; a
  call notifies as "Started a call" (`NotificationPolicy.Kind.call`).
- **FCM** (`push/PushRegistrar`, `PeelMessagingService`, `PushKeyStore`):
  `firebase-messaging` always compiled in; the `google-services` plugin is
  applied only if `app/google-services.json` exists. Without it there is no
  `FirebaseApp` and push is a no-op. Token re-registered on `onNewToken`, after
  unlock/login and after every reconnect; once per token per engine.
  `BuildConfig.PUSH_REGISTER_WITH_HUB` is the iOS flag (see Decided above).
- **Notifications** (`push/Notifications`, `MessageNotifier`): channels
  `messages`, `payments`, `calls`, `ongoing_call`; shown for messages,
  attachments, payment requests (type 2), sats (type 5) and calls (type 32)
  that arrive over MQTT while the app is not in front, past the read marker,
  not muted. Taps carry `EXTRA_CHAT`/`EXTRA_OPEN_PAYMENTS` to `MainActivity`
  (`singleTop`, `onNewIntent`) -> `DeepLink` -> `MainTabs`. POST_NOTIFICATIONS
  asked at launch on 13+.
- **Reliability** (`Engine`, `Transport`, `ConnectionWatcher`): 30 s MQTT
  keep-alive; an unexpected drop (`Transport`'s disconnect listener) starts
  main's `reconnectLoop` (backoff 1 s … 30 s, `ConnectionState.Offline`); a
  failed first connect starts it too. Network change (`NetworkCallback`) and app
  start (`ProcessLifecycleOwner`) call `resume()` at once. `start`, `resume` and
  the loop share `afterConnect` (reads/mutes, restore or catch-up, then
  `onConnected` for push registration). `Engine.isOnline` is the push's "is the
  connection up" (`isConnected` only says a transport exists).
- **Calls** (`call/CallService`): foreground service `microphone|camera` while
  a call is active, with a Leave action; an incoming-call notification with
  `setFullScreenIntent` and a Join action (which opens the chat).

**Open / not ported**
- No client unregister in sphinx-rs (a signed-out token stays on the hub).
- Hive links in notifications.
- No FCM-delivered ring/VoIP push; the full-screen call notification comes
  from the live MQTT connection, so a closed app only gets the named/generic push.
- Mention-only mute (any mute is quiet), notification summaries, tribe join requests.
- Join from the notification opens the chat; it does not auto-join.
- The process is not kept alive in the background without a call (no
  connection foreground service); FCM wakes it.
- Tests: pure JVM/Robolectric in `app/src/test/.../push/` (key validation,
  `encryptedChild`, name directory, `resolve`, app id, `PushPlan`, sealed store).
  The service, `PushNameSync` and Keystore use are only exercised on a device.

### 2026-11-05 review fixes
- `PUSH_REGISTER_WITH_HUB` is now set per build type (debug: json present;
  release: false), not from the requested task names (`build`/`assemble` used to
  count as debug). `-P` still overrides.
- `KeepConnected` (watcher, push registrar, notifier) now also runs while the app
  is relocked (the engine lives on behind the lock), at one composition spot so
  Home <-> Relock does not restart it.
- "Forget this account" also drops the push key (`PushRegistrar.accountForgotten`).
- A push for a muted chat posts nothing; a push no longer starts a second
  connect while one is running.
- Open: tribe child indexes (core `listContacts` has persons only), mention-only
  mute levels, mute check needs the (PIN-less) chatState table.
