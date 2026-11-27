# Phase 36 — Apps and Talk cosmetics

Status: **step 8: built, compiled and unit-tested (JVM + Robolectric); the composables compiled only, nothing run on a device.**
iOS read from `main`: `Sources/PeelUI/WorkspaceAppIcon.swift` (+ `WorkspaceAppIconTests`), `Apps/iOS/Peel/AppsScreen.swift`, `TalkHistoryScreen.swift`,
`Sources/PeelUI/TalkHistory.swift`, `TalkStore.swift`.

## Built
- **App icons**: `engine/LucideNames.kt` (pure) — iOS's name rules: trimmed, lower-cased, punctuation ignored (`shieldCheck` = `shield-check`), the renamed-icon
  aliases (`home` → `house`, `bar-chart-3` → `chart-column`, …), anything else is `layout-grid` (`FALLBACK`). `ui/apps/AppIcons.kt`: the table lucide name →
  Material icon (`material-icons-extended` was already a dependency) and `WorkspaceAppIcon`; the Apps row uses it with the secondary tint, as iOS.
- **Pull to refresh**: `PullToRefreshBox` over the Apps list, same read as the Refresh button (`refresh++` → the one `LaunchedEffect`); the indicator stays up until
  the read ends (`reading`), as iOS's `refreshable` waits. A pull while a read is on does nothing. Texts that said "Refresh to try again" now say "Pull to try again" (iOS words).
- **Talk row long-press menu**: "New conversation" (starts the call) and "History". App rows keep their long-press "Share link".
- **History** (`engine/TalkHistory.kt`, `ui/talk/TalkHistoryScreen.kt`): iOS history is **local files, not a server endpoint**: `TalkHistoryStore` saves a call's transcript on
  hang-up (`TalkStore.hangUp`, only for a call that reached the room and has lines; off the main thread) as `<files>/talk_history/<homeId>_<ISO8601>.json`
  (`{id, homeId, homeName, date, lines[{id, speaker you|agent, text, isFinal}]}`, same shape as iOS), listed newest first per home. Screen "Conversations": date + two-line
  preview ("No transcript"), tap opens the transcript ("You"/"Jamie", selectable), a mic button on a row/transcript and in the bar starts a new call; empty state copy as iOS.

## Differences from iOS
- (Superseded by phase 37: every lucide icon now draws.)
- History: a session of another home sharing a file-name prefix (`acme` vs `acme_two`) is left out (iOS matches the prefix only); writes go through a temp file + rename.
- Resume is a mic button, not a swipe action; History is a screen inside `AppsScreen` (Back returns to the list), not a navigation push.
- The transcript is saved on every hang-up, including Close after a failed or ended call, as long as it has lines (as iOS).

## Tests
`LucideNamesTest` (6), `AppIconsTest` (2; the table's keys are exactly `SUPPORTED`), `TalkHistoryTest` (7: empty not saved, newest first per home, safe file names, prefix
clash, unreadable files, JSON round trip, date/speaker words).
### UNTESTED
- `AppsScreen` (pull gesture, long-press menus, icon drawing), `TalkHistoryScreen`, the save on `TalkStore.hangUp` with a live room.

## Pinned apps on the Dashboard
> **Superseded (2026-11-27):** Android no longer pins apps. The Apps row, `PinnedAppRow`, Pin to Chats/Unpin and the row's pin are gone;
> Apps shows Recent instead, and `SidebarLayout` keeps `pinnedApps` read only (docs/phases/58-chats-one-list.md). What follows is the history.
Built, compiled and unit-tested (SidebarLayout and the rows under Robolectric); not run on a device. A port of the Mac sidebar's pins and the iPhone's "Pinned apps on Chats" (peel `ChatSections.Layout`): an app can be pinned under the Dashboard's Apps row, sharing the Swift apps' saved state.
- `SidebarLayout` now holds `pinnedApps` (app keys `@<workspace>/<name>`, in pin order) and `appsOpen` (the fold, default true) in the same `Setting.SIDEBAR_LAYOUT` JSON, other fields carried through; `isPinned`, `withPinned` (the first pin reopens a closed fold, re-pin never duplicates) and `pinned(apps)` are `Layout`'s. `SidebarLayoutState.of(storage)` is the one observable holder, so Apps and the Dashboard see a pin at once.
- `Stadeum.apps`, `readingApps`, `unreadAppIds` and `suspend readApps()` (keeps a workspace's last apps when it does not answer; reads one at a time) moved out of `AppsScreen`, which still reads on open, on a workspace change, on Refresh and on a pull.
- Dashboard: `AppsRow` has a chevron ("Pinned apps", Expanded/Collapsed) only when a listed app is pinned; open, `PinnedAppRow` per pinned app A–Z (`· @workspace` with several), a tap opens it as Apps does, long-press says Unpin. Pinned apps are read when there are saved pins and Stadeum is live, without opening Apps. Searching shows every app that matches, whose menu says Pin to Chats or Unpin. Apps: the long-press menu always opens ("Pin to Chats"/"Unpin from Chats", then "Share link"), and a pinned row has an accent pin.
- Tests: `SidebarLayoutTest` (Swift JSON round-trip, order, duplicates, unpin, fold), `PinnedAppsUiTest`, `AppsRowsUiTest` (menu, pin icon).
- Not tested: `Stadeum.readApps` (needs a paired workspace), the Dashboard list itself.
