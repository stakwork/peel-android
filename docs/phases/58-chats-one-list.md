# Phase 58 — Chats as one list, Recent in Apps

Status (2026-11-27): **built; not compiled or run here (no Android SDK on the Mac this was written on), so CI
(`.github/workflows/pr-checks.yml`: unit tests, lint, assemble) is the judge. JVM/Robolectric tests written for the pure logic.
Not run on a device.**
A port of the iPhone's 2026-11-27 change in `peel` (branch `ios-chats-one-list`: `Apps/iOS/Peel/ChatsTab.swift`,
`AppsScreen.swift`, PeelCore's `ChatSections.latest`, `Layout.pinnedPages`/`setPagePinned`, `Layout.recentApps`/`noteOpened`,
PeelUI's `AppModel.recentApps`/`noteOpenedApp`; peel's `docs/design/iphone.md`, "### 1. Chats", and `docs/phases/10-v1-extras.md`,
"iPhone, round three"). Tom's decisions, after the Mac's rail.

## Built

**Chats is one list, newest first (`ui/dashboard/ChatSections.kt`, `ChatsScreen.kt`).** The pending invites first, then Peel's own
row (the agent's DM, always there when its chat exists, unread or not), then every other chat newest first, as
`Storage.conversations` reads them (`ChatSections.items`, `latest`). Unread chats stay in place, bold with their counts
(`ChatRow`, unchanged). Gone: the Unread, Tribes and Direct messages sections with their headers and chevrons, "Show all/fewer",
the Separated/Recent switch, the Apps row and the pinned apps under it (`PinnedApps.kt` deleted), the Threads and Mentions rows,
the "+" rows at the end, and search (the magnifying glass, the field, "No results").

**The bar.** ••• on the left (`ChatsMoreMenu`, content description "Mentions and Threads"): Mentions and Threads only, each with
its icon, "N new" (`ppl_new_count`) and opening its existing page (`DashboardRoute.MENTIONS`/`THREADS`). The title, the connection bolt exactly as it was, and
New chat (a plus, `NewChatMenu`): Add contact, Invite someone, Join a tribe, Create a tribe, each the `DashboardRoute` the old "+"
rows opened. No floating button.

**Pinned pages.** Each page pins itself from its own bar (Tom, 2026-11-27, "A: pin from the page"): `PagePinButton` at the trailing
end of the Mentions and Threads bars, an outlined pin in the secondary colour ("Show on Chats") or, pinned, the accent pin ("Remove
from Chats"); a tap toggles the page in `pinnedPages`. A pinned page shows as a chip above the list (`PinnedPages`, over the new shared `ui/components/CapsuleChip.kt`):
its icon, title and new count in the accent; a tap opens it, a long press offers Remove from Chats. Chips go in the enum's order
(Mentions, Threads), as the iPhone's `ChatsPage.allCases.filter`.

**Empty state.** With no chats: "No chats yet", "Add someone or join a tribe, and they show here.", and Add contact (filled), Join a
tribe (outlined), Invite someone (text button); the pending invites and Peel's row still follow it. While restoring, the restore
words as before. The first read still shows the spinner, never the placeholder.

**Apps (`ui/apps/AppsScreen.kt`).** Recent: the apps opened last that are still listed, newest first, at most 5 of the 8 kept
(`SidebarLayout.recent`, `RECENT_APPS_SHOWN`, `RECENT_APPS_KEPT`), a row of chips that scrolls sideways, one tap opens; only with no
search and no source filter, and then the list is headed "All apps". Every workspace app opened from Apps (row or chip) is noted
first (`SidebarLayout.withOpened`) when it opens a page. Pin to Chats / Unpin and the row's pin are gone; Share link stays, and a row
with no link has no long press (the menu would be empty).

**The saved layout (`ui/dashboard/SidebarLayout.kt`).** `pinnedPages` (keys `mentions`, `threads`, in the order pinned;
`withPagePinned`: last on pin, out on unpin, a repeat the same layout) and `recentApps` (`withOpened`), in the same
`Setting.SIDEBAR_LAYOUT` JSON as PeelCore's `Layout`. A layout saved before them decodes with both empty; any non-string entry is
skipped. `combinedView`, `allRecent` and `appsOpen` are no longer read (only the removed sections used them) but, like every field
this port does not set, stay in the JSON as they were. `pinnedApps` is still decoded, read only: Stadeum's launch records
(`PinnedAppRecord`) follow it as on the iPhone, where `Layout.pinnedApps` stays for the Mac; nothing on Android shows or changes
it, so `Stadeum.pinsChanged` went.

**Strings** (`values/` and `values-es/`): added `chats_pages`, `chats_show_on_chats`, `chats_new_chat`, `chats_remove_from_chats`,
`misc_apps_recent`, `misc_apps_all_apps` (the iPhone's Spanish); `chats_none_detail` reworded; removed the unused section, search,
"Show all", layout-switch and pin strings (`chats_unread`, `chats_recent`, `chats_separated`, `chats_tribes`, `chats_direct`,
`chats_show_all_*`, `chats_show_fewer`, `chats_no_results`, `chats_search_hint`, `chats_clear`, `dash_expanded`, `dash_collapsed`,
`dash_apps`, `dash_pinned_apps`, `dash_app_with_source`, `dash_unpin`, `dash_pin_to_chats`, `misc_apps_pinned`, `misc_apps_unpin`,
`misc_apps_pin`).

## Tests

- `ChatSectionsTest` (JVM): every chat but the agent's newest first with unread in place, never trimmed; invites, then Peel's row
  (even unread), then the chats; no agent chat means no Peel row.
- `SidebarLayoutTest` (Robolectric, for `org.json`): defaults and garbage; a layout saved before pages and recent apps (with the
  Swift apps' fields and old pins) decodes to the defaults and keeps every old field on encode; pages pin last, unpin, repeat;
  opened apps first, once, capped at 8 (also when decoding a longer list); Recent keeps listed apps only, newest first, at most 5.
- `AppsRowsUiTest`: the pin assertions removed; `PinnedAppsUiTest` deleted with `PinnedApps.kt`.

## Not ported / open

- `PinnedAppRecord.following` is now used only by its test (`Stadeum.pinsChanged` was its one caller); kept as PeelCore's.
- `Engine.chatsRecentView`/`setChatsRecentView` (the synced `peel.chats_recent_view`) are no longer read by Chats; the preference
  still syncs for the Mac.
- Not compiled here, no Compose UI test of the new Chats bar, menus or chips (Chats reads `Engine`), and nothing run on a device.

## Journal

- 2026-11-27: ported from `peel` `ios-chats-one-list` as above, in `android-chats-one-list`. Written without an Android SDK;
  read for compile errors by hand.
- 2026-11-27 (Tom, "A: pin from the page"): ••• lists only Mentions and Threads; "Show above your chats" and its checkboxes are gone
  (`chats_show_above` removed). Pinning moved to a pin button on each page's own bar (`PagePinButton`, "Show on Chats" /
  "Remove from Chats"); the chip and its long-press Remove from Chats are unchanged. Not compiled here either.
