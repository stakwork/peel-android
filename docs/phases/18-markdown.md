# Phase 18 — Markdown in message text

**Status:** built, JVM-tested, not device-tested (no emulator in the pod).

Built: `engine/MessageMarkup.kt` (port of peel's `MessageMarkup.swift`:
`blocks`, `spans`, `inlines`, `plainText`, `linkLabel`, `host`, `firstLink`),
`engine/Mentions.kt` (`mentions`, `tribeAlias`, own aliases + "all"),
`ui/components/MessageText.kt` (the renderer, after `Apps/Shared/MessageText.swift`),
palette tokens `link`, `mention*`, `codeFill` in `Theme.kt`. Conversation bubbles
draw text with `MessageText`; reply quote, composer "Replying to" and the
thread's last-reply line use `plainText`; "Copy link" uses `firstLink`.
Tests: `MessageMarkupTest` (iOS tests ported + link/mention edges).

Formats: paragraphs, `- `/`• `/`* ` items, `1.`/`1)` lists, `#`–`######`
headings (+6/+3/+1 sp for levels 1/2/3+, semibold), `>` quotes with a bar,
fenced code (language name dropped), `` `code` ``, https links (bare and
`[text](https://…)` shown as "text (host)"), `@mentions` (gold when they name
you or @all), `**`/`__` bold, `*`/`_` italic, `~~strike~~` with CommonMark-like flanking.

Not matched / open:
- No `NSDataDetector`: `MessageMarkup.findLinks` is a small scanner (https only;
  no IPv6 hosts, no `user@`; trailing punctuation, unbalanced `)` and emphasis
  marks trimmed). Odd addresses the detector accepts may stay text here.
- Offsets are UTF-16, not grapheme clusters (flanking uses code points).
- `codeRanges` (iOS mention scan) not ported.
- Mention highlighting knows only the account alias and "all": no per-tribe alias
  or aliases a tribe echoed back. No @-mention chips for search matches (no
  search highlight in message text), no agent-message links, no text selection.
- Long press on a link opens the link-less row menu only outside the link.

## Apps section (Dashboard "Apps" row)

Status: built `engine/WorkspaceApps.kt` (WorkspaceApp parser/sort/filter/shareLink, `GET {strut}/apps` with bearer token, `AppPage.opening`) and `ui/apps/AppsScreen.kt` (search, source filter, Refresh, long-press Share link, in-app WebView with the token in an Authorization header). Open: Android has no Stadeum pairing, so `Stadeum.live` is empty and the Apps row is hidden (iOS gate); no pull-to-refresh; Talk to Jamie rows start the iOS-style Talk call (`docs/phases/26-talk-to-jamie.md`). Not compiled/tested in the pod (no Android SDK/JDK).
