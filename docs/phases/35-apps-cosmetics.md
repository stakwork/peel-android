# Phase 35 — Apps and Talk cosmetics

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
- Only the lucide icons in `LucideNames.SUPPORTED` (~90, those with a Material counterpart, covering iOS's old table) draw; any other lucide icon is the grid (iOS draws all of lucide).
- History: a session of another home sharing a file-name prefix (`acme` vs `acme_two`) is left out (iOS matches the prefix only); writes go through a temp file + rename.
- Resume is a mic button, not a swipe action; History is a screen inside `AppsScreen` (Back returns to the list), not a navigation push.
- The transcript is saved on every hang-up, including Close after a failed or ended call, as long as it has lines (as iOS).

## Tests
`LucideNamesTest` (6), `AppIconsTest` (2; the table's keys are exactly `SUPPORTED`), `TalkHistoryTest` (7: empty not saved, newest first per home, safe file names, prefix
clash, unreadable files, JSON round trip, date/speaker words).
### UNTESTED
- `AppsScreen` (pull gesture, long-press menus, icon drawing), `TalkHistoryScreen`, the save on `TalkStore.hangUp` with a live room.
