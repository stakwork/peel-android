# Phase 16 — Push notifications and connection reliability

## Status (2026-11-02)

**Built, not yet compiled or run** (the pod this was written in has no JDK or
Android SDK; `./gradlew assembleDebug` has to be run before trusting it).

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
  `BuildConfig.PUSH_REGISTER_WITH_HUB` is the iOS flag: `-Ppeel.push.registerWithHub=`
  overrides, default on only with `google-services.json`.
- **Notifications** (`push/Notifications`, `MessageNotifier`): channels
  `messages`, `payments`, `calls`, `ongoing_call`; shown for messages,
  attachments, payment requests (type 2), sats (type 5) and calls (type 32)
  that arrive over MQTT while the app is not in front, past the read marker,
  not muted. Taps carry `EXTRA_CHAT`/`EXTRA_OPEN_PAYMENTS` to `MainActivity`
  (`singleTop`, `onNewIntent`) -> `DeepLink` -> `MainTabs`. POST_NOTIFICATIONS
  asked at launch on 13+.
- **Reliability** (`Engine`, `Transport`, `ConnectionWatcher`): 30 s MQTT
  keep-alive; an unexpected drop starts a reconnect loop with exponential
  backoff (1 s … 60 s + jitter); network change (`NetworkCallback`) and app
  start (`ProcessLifecycleOwner`) reconnect at once. `start`/`resume` share
  `establish`.
- **Calls** (`call/CallService`): foreground service `microphone|camera` while
  a call is active, with a Leave action; an incoming-call notification with
  `setFullScreenIntent` and a Join action (which opens the chat).

**Open / not ported**
- `setPushTokenForApp`'s `appId`/`pushEnvironment`: the bundled sphinxrs
  bindings only expose `setPushToken`. Regenerate the bridge to add them.
- Naming the chat in a push (child-index -> name map) — generic text only.
- No FCM-delivered ring/VoIP push; the full-screen call notification comes
  from the live MQTT connection, so a closed app only gets the generic push.
- The server must actually send FCM for Android tokens; needs mixer work.
- Join from the notification opens the chat; it does not auto-join.
- The process is not kept alive in the background without a call (no
  connection foreground service); FCM wakes it.
- Mention-only mute, notification summaries, tribe join requests.
- Nothing here has a unit test; the pure pieces (`PushKeyStore.isValid`,
  `PeelMessagingService.encryptedChild`) could.
