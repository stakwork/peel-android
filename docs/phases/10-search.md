# Phase 10 — Search

## Status (2026-10-29)

**Built:** the fourth bottom-bar tab, previously a placeholder
("Search isn't built yet"), is now real: a text field that filters the
same chat list the Chats tab shows, by name. Typing nothing shows an
empty state ("Search chats" / "Tribes and people by name."); typing
something shows every Unread/Tribes/Direct match, every section forced
open, no "Show all" row, no chevrons, no actions (Join a tribe, Add
contact, …) — PeelCore's own `searching` mode. No matches shows "No
results for '…'".

**What this is not:** PeelCore has a second, much bigger search
mechanism — `Storage+Search.swift`'s FTS5 index over every message's
text and attachment captions, with `from:name` and quoted-phrase
syntax, snippets, jump-to-thread. That's the Mac's own ⌘F
find-within-a-chat (`AppModel+Search.swift`'s `ChatSearch`), a
genuinely different feature from the bottom-tab Search this phase
built. Checked `docs/design/iphone.md` before assuming the tab needed
that: its own "To design" list still carries "Search results across
chats, people and messages" — not built, not even designed, on iOS
itself yet. What iOS's actual `SearchTab` does today (`Apps/iOS/Peel/
MainTabs.swift`'s `SearchTab`, `Apps/iOS/Peel/ChatsTab.swift`'s
`ChatList`) is exactly the name filter this phase ported — confirmed
by reading the real view code, not the design doc's aspirational list.

**Open:**

- No message-content search at all — the FTS5 mechanism above. Not a
  gap in this phase's own scope (iOS's own Search tab doesn't do it
  either), but worth naming as a real future feature, distinct from
  "finish Search" — it would need its own SQLite FTS table and a
  decision about FTS5's availability on this app's minSdk 26 (the same
  kind of risk `json_extract` already turned out not to be worth
  taking, see Phase 9's "Update").
- No "Threads"/"Mentions" nav rows in the list (PeelCore's `ChatList`
  shows them above Unread, hidden while searching) — neither screen
  exists on Android yet, nothing to link to.
- Searching re-queries `storage.conversations()` on its own 3-second
  poll, independent of the Chats tab's own poll — both exist, but
  `MainTabs` only ever composes one tab's content at a time, so they
  never actually run concurrently in practice.

## Decided

- `ChatListBody`, not a second copy of `ChatsScreen`'s list — the
  Chats tab and Search tab render the exact same component, PeelCore's
  own `ChatList` shared by `ChatsTab` and `SearchTab`. `ChatsScreen`
  just supplies its own title row and an empty `query`; `SearchScreen`
  supplies "Search", a text field, and whatever was typed.
- `ChatSections.of` gained `query` rather than a second function —
  every section's filtering (by name, case-insensitive) and the
  "every match, no trim" rule while searching reuse the exact same
  unread/tribes/direct split the non-searching path already does;
  PeelCore's own `ChatSections.init` filters before classifying a
  conversation at all, same shape.
- No debounce on the text field — `storage.conversations()` is a
  single indexed query over however many contacts exist, cheap enough
  that filtering it on every keystroke (via `remember` keyed on the
  computed sections, not the database) costs nothing worth guarding.

## Journal

**Read the design doc's own "To design" list before assuming "Search"
meant full message content.** The temptation was to treat the bottom
tab's placeholder as license to build whatever "search" usually means
in a chat app — full-text search over everything ever said. Reading
`docs/design/iphone.md` surfaced that even PeelCore's own design
explicitly defers that ("Search results across chats, people and
messages" under "To design"), and reading the actual `SearchTab`/
`ChatList` source confirmed what's really built today is a simple name
filter reusing the sidebar's own sectioning. Building the simpler,
*actually shipped* thing instead of the larger, still-undesigned one
avoided inventing a feature iOS itself hasn't committed to the shape
of yet.

**A second entry point into an existing list is usually "parametrize
it," not "copy it."** `ChatsScreen`'s list rendering (sections, rows,
show-all, action rows) was already everything Search needed, just
missing the filter and the "every match, no chevron" mode while
filtering. Extracting the already-built body into `ChatListBody` and
threading one new `query` parameter through `ChatSections.of` reused
the entire rendering path, rather than building a parallel one for
Search that would've had to stay in sync with it by hand.

## Done

- `ui/dashboard/ChatSections.kt`: `of(...)` gained `query: String =
  ""`; `searching` (non-blank query) field; `matches(name, query)`
  (public, so the agent row can use it too); `shown(...)` shows every
  match with no trim or count while searching.
- `ui/dashboard/ChatsScreen.kt`: the list rendering extracted into
  `ChatListBody` (owns the data loading/polling, so either caller can
  mount on its own); `ChatsScreen` now just supplies its title row and
  calls it with an empty query; new `SearchScreen` (title, `SearchField`,
  `EmptySearch`, or `ChatListBody` with the typed query);
  `NoSearchResults` for a query with nothing matching.
- `ui/dashboard/MainTabs.kt`: the Search tab now renders `SearchScreen`
  instead of the placeholder.
- Tests: `ChatSectionsTest` gained cases for a blank query not
  searching, a query filtering every section case-insensitively,
  searching showing every match with no trim/count, and searching with
  no matches returning empty sections. 66 tests passing.

## Learned

- A tab bar entry drawn as a placeholder is a question ("what should
  this actually be?"), not a spec — the design doc and the real source
  both had to be read to find out the honest, current answer rather
  than the first guess a chat app's "Search" tab usually implies.
- The same "filter the item out / compute a lookup / decorate" shape
  Phase 4, 6 and 9 kept reaching for doesn't apply everywhere — this
  one was closer to "the existing view, plus one new parameter it
  didn't have a reason to take before." Worth checking which shape a
  new feature actually is before reaching for the one that's been
  working lately.
