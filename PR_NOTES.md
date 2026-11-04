# Message confirmations (green bolt) on Android

## How iOS does it (`peel` repo)
- **Data**: `message.status` (`Message.Delivery`: PENDING / COMPLETE / FAILED / UNSETTLED) and `message.tag`
  (`Sources/PeelCore/Message.swift`). Statuses only move forward (`Storage.applyStatus`, `Storage+Writes.swift`).
- **Confirmation received**: the live `RunReturn.sentStatus` (`Engine.liveStatus`) and the `getTags` reply
  `RunReturn.tags` (`Engine.recordedStatuses`, only COMPLETE/FAILED), both in `Engine.swift` (~L1060).
- **Bolt**: `RunHeaderLine` / `DeliveredBolt` in `Apps/iOS/Peel/MessageRows.swift`: green (`Theme.confirmed`) `bolt.fill`
  after the time on the first row of a run, only for `fromMe && delivery == .delivered`. Pending is shown faint
  (`isSending`), failed/stale pending offers Retry (`isRetryable`). UNSETTLED shows no bolt.
- **Triggers for status fetches (`getTags`)**:
  1. `Engine.cameOnline` (`Engine.swift` ~L1578): `checkPendingSends` on every connect, before history resumes (covers reconnect and foreground).
  2. `Engine.historyEnded` (`Engine+History.swift` ~L141): `checkPendingSends` after a restore (restored sends have no status).
  3. `AppModel.selectedChat` didSet (`AppModel.swift` ~L134): `checkOwnMessageStatus(in:)` when a chat is entered.
  4. `AppModel.becameActive` (~L847): same, on app foreground.
  5. `AppModel` `.history` event (~L1650): same, on every history page.
  `checkOwnMessageStatus` (`Engine.swift` ~L1628): per-chat 60 s rate limit, 50 tags per call, skips tags the server did not know last time, only once `cameOnlineHere`.

## Android (this change)
- `engine/SendStatus.kt` (new): status enum, forward-only rules, `live` / `recorded` mapping, money types.
- `storage/Storage.kt`: `markSendStatus(tag, SendStatus)` (forward-only, also applies to rows with no status, i.e. restored ones),
  `unconfirmedTags(chat)`, `pendingSendTags(textBefore)`. No schema change: `status`, `tag` and the `message_tag` index already exist
  (v4 / v1), so no migration. `UNSETTLED` is a new value of the existing text column.
- `engine/Engine.kt`:
  - `storeMessages` now applies the live `sentStatus` through `SendStatus.live` and the `getTags` reply (`result.tags`).
  - `checkOwnMessageStatus(chat)` and `checkPendingSends()` (ports above, same constants).
  - Hooks: `afterConnect` (start, resume = foreground, reconnect loop) runs `checkPendingSends` before history, then sets `cameOnlineHere`
    and checks the open chat; `runRestore` finish runs `checkPendingSends`; `page()` checks the open chat after each history page.
  - `Engine.openChat` is set by the conversation screen.
- `ui/dashboard/ConversationScreen.kt`: sets `engine.openChat`, and calls `checkOwnMessageStatus` on entering a chat.
- UI bolt: already present (green bolt after time on the run header for `fromMe && DELIVERED`, faint pending, "Not delivered" on failed), same rules as iOS; unchanged.
- Test: `SendStatusTest`. `./gradlew --offline :app:testDebugUnitTest` passes (ANDROID_HOME=/opt/android-sdk).

## Differences / limitations
- Not ported from `checkPendingSends`: failing a payment the server has no record of after 30 min (`unrecordedPaymentTimeout`),
  the auto-resend gate (`resendRecent`), and invites (no invite table on Android).
- No Retry affordance after 30 s of pending (`isRetryable`): pending stays faint; failed shows "Not delivered" as before.
- Android has no `UNSETTLED` UI line ("Payment not settled"); such a row simply shows no bolt and is not faint.
- `getTags` is fire-and-forget; answers are applied by the inbound listener (`storeMessages`), as on iOS.
- Engine/Storage paths need the native sphinxrs library / SQLite, so only the pure `SendStatus` rules are unit-tested; the JSON parsing can't load on a plain JVM.
