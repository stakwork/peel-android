# Phase 57 — Jamie's screen as the live app

Status (2026-11-24): **built; JVM/Robolectric tests pass; the tile checked on an emulator with a stand-in Jamie (its `screen`
attribute faked) against a local strut with the canvas package and a local LiveKit (see "Checked"). Not run against a real voice
service: the `screen` attribute and `record` on `GET /calls` are sphinx-voice's work item 11, not released yet.**
Android's side of stadeum `plans/call-canvas.md` work item 13 (§6.5 and §8; merged in stakwork/stadeum#114). iOS/Mac is item 12, a
parallel session in `peel` building the same behaviour. Builds on phase 56 (docs/phases/56-box-group-calls.md).

## What it is

A box's Jamie shows the canvas to a call as a screen-share video, which nobody can scroll. For a person linked to that box, Peel puts
the app itself in Jamie's screen tile instead of the video, so the canvas is part of the call and live: scroll it, open a card.
Everyone else keeps the video.

## Built

**Where Jamie's screen is (`engine/JamieScreen.kt`, pure).** The voice service sets one participant attribute on Jamie, `screen`:
strut's deep link to the app on the box, `https://strut.<host>/?app=<name>&input=<query>`, empty when Jamie shares nothing.
`JamieScreen.link(name, attributes)` reads it, only from Jamie (`CallRoomMember.isJamie`: named Jamie *and* the `tts` attribute only
the voice service sets), at most 4096 characters. `JamieScreen.place(link, links)` finds the linked box it is on: the box's own page
(`BoxLink.home`) must serve the address (same origin, strut's UI folder), and the link must be strut's app link
(`StrutAppLink.parse`, `Source.Org`: an app name strut takes, no `@peer/`, `app` and `input` once each). Anything else is null, and the
tile keeps the video.

**The page.** `OrgPageRequest.app(link, title)` is the box's page at that app alone (`StrutUI.page`, `&solo=1`) with the same token:
the bearer on the first request and the hand-over into strut's UI storage, never in the address. Before it loads, the box takes the
token as for any of its pages (`BoxesModel.home`: checked, renewed once on a 401, a box that no longer links let go). While that runs,
or when it fails, the tile shows the video.

**The tile (`ui/call/CallScreen.kt`).** `CallTile.screen` carries Jamie's link on Jamie's screen tile (`CallSession.screenTile`; never
your own). When that tile is the big one, `jamieScreenPage` finds the place and asks the box, then `LiveScreenTile` shows
`CallScreen`'s `livePage`: `MainTabs` gives the one page host, `OrgPageScreen(..., bare = true)` (no bar, no Back of its own; the
wallet's sheets and the page's prompts as on any page). Keyed on the box's address and the app, so a renewed token does not reload it.
Taps and scrolls are the page's. A small button in its corner, "Show everyone", goes back to the grid. In the grid and the strip,
Jamie's screen is the video; tapping it there makes it the big tile again, live.

**The grid (`CallSession.showGrid`, `gridScreens`, `focus`).** Android's big tile was the pinned one, else the first screen share, and
a tap on a big screen share pinned it: there was no way back to the grid while anyone shared. Now, as on iOS, a tap on the big tile
(or the live tile's button) shows the grid: nothing pinned, and every screen shared now kept out of the big spot until it is tapped
(pinned) or shared anew (a share that stops leaves `gridScreens`).

**The layout.** With a big tile, the row of people is above it and the tile under them (plans/call-canvas.md §6.5, as the iPhone's
`IOSCallStage` now does). For every big tile, not just Jamie's screen.

**"Record on the canvas" only when the box can (§8).** `BoxCallStatus.record` is `GET /voice/calls`'s new `record`: false when no
package says what a call records onto. Absent (a voice service before it) is as before: offered. `BoxCalls.recordable(box)` gates
the menu item; `records(chat)` is false with a box that can't, so a choice left from before is not sent.

## Found

- **The attribute is not encrypted.** Attributes go through LiveKit's server, outside the call's key, and anyone in a call can name
  themselves Jamie (only `tts` tells the voice service's Jamie apart, and a participant with the right grant can set its own
  attributes). So `screen` is only ever a name: it opens nothing but one of strut's app links on a box the person already linked, the
  same page a link to that app would open, with their own token. A link to anywhere else keeps the video.
- **A web view in a call tile needs nothing new from the page host** beyond `bare`. The live check found no layout or focus problem.
- **Under a Compose test rule the call's running time does not tick** (its `delay` runs on the test's clock): the screenshots below
  show 0:04 throughout. Not a problem in the app.

## Tests

- `JamieScreenTest` (4, JVM): Jamie's attribute and nobody else's; a link on a linked box is its app at the view named (case, default
  port); everything else keeps the video (no box, another box, lookalike host, scheme, port, a file, an artifact, a refused app name, an
  org's app, `app` twice); the page alone with the token as bearer and hand-over, never in its address.
- `BoxCallsTest` +1: `record` read (true, false, absent, odd), `recordable` and `records` gated by it, a box not asked not recordable.
- `CallJamieItemsUiTest` +1 (Robolectric): Record on the canvas only with a box that can.
- `CallSessionTest` +1: the big tile (pinned, first screen not in the grid, none).
- `androidTest/ui/call/JamieScreenLiveTest`: the live check below; skipped unless a box is named.

## Checked

On an emulator (API 35 arm64, AVD `peel-box-calls`) with `JamieScreenLiveTest`: `livekit-server --dev --bind 0.0.0.0` and strut from
`main` @11517b34 with the canvas package installed (the `voice-screen-live` worktree sphinx-voice's screen-share session left), a
person token from `POST /peers/tokens`:

```
adb reverse tcp:3077 tcp:3077; adb reverse tcp:7880 tcp:7880; adb reverse tcp:7881 tcp:7881
adb shell am instrument -w -e class com.sphinx.peel.ui.call.JamieScreenLiveTest \
  -e boxStrut http://127.0.0.1:3077 -e boxToken <strut-person.…> com.sphinx.peel.test/androidx.test.runner.AndroidJUnitRunner
```

A stand-in Jamie joined with the voice service's attributes in its token (`tts`, `recording`, `screen` =
`http://127.0.0.1:3077/?app=stadeum%2Fcanvas%2Fcanvas&input=present%3Dcall-live`) and shared the emulator's camera as its screen.
Ada, linked to the box: Jamie's screen took the big tile under the row of people; the tile loaded
`…/?app=stadeum%2Fcanvas%2Fcanvas&input=present%3Dcall-live&solo=1` with her token in strut's UI storage and not in the address, and
the canvas's presenter view drew ("Taking notes"); "Show everyone" brought the grid back with Jamie's screen as video. Passed.

Not checked: a real voice service setting `screen` (not released), a real recording's canvas following its notes, a real phone.

## Open

- **Follow.** Once the person scrolls or opens a card, the presenter view stops following and shows Follow (§6.5). That is the canvas
  app's (work item 9); nothing here.
- **The page reloads** each time the tile becomes the big one again (a new page, a new web data profile): its scroll position is lost
  after Show everyone. Keeping one page alive across grid and big tile would need it out of the tile's composition.
