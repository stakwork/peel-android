# Phase 48 — A Tasks tab for a worker's employers

Status (2026-11-19): **built; JVM/Robolectric-tested; CI's three Gradle tasks pass locally; not run on a device or emulator.**
Strut's half (stakwork/strut `feature/one-invite`) makes every worker invite open the Tasks app; nothing here waits on it, since
a Tasks share link already opens `strut/worker` today.

## Why

A worker's employer runs a Stadeum box whose Tasks app (strut's `strut/worker`, the page `<box>/?app=strut%2Fworker&solo=1&public=1`)
is their one work page: every kind of work across that box's worker apps, with earnings and withdraw. Custom apps open from
inside it, in the same web view, and are not recorded. Until now that page sat under Chats › Apps › "Apps you opened" (phase 41),
titled by whichever app first signed in on that host, two taps and a scroll from the tab bar. Evan, 2026-11-19: a Tasks tab,
shown only to accounts that have work; one employer is the norm and its page is the tab itself; for several, a short list. The
first-time experience matters most.

## Built

- **The rule** (`engine/TasksTab.kt`, pure): a Tasks page is a public app whose `?app=` is `strut/worker`, on any seller, at any
  address. The account has the tab once it has a `public_app` row of one (`Storage.openedApps()`, phase 41); one row per employer
  (the table keeps one per seller and app). `TasksTab.content` decides what the tab shows: no row, no tab; one, that page; several,
  the list, or the page picked from it (or opened by a link). A picked page whose row is forgotten (Disconnect) goes with it.
- **The tab** (`ui/dashboard/MainTabs.kt`, `ui/dashboard/TasksTab.kt`): `MainTab.TASKS` comes first in the pill (Tasks, Chats,
  Payments, You; Search beside it) while a Tasks row exists, and is not the tab Peel opens on: Peel opens on Chats on every
  launch and unlock, whether or not the Tasks tab exists (a Tasks link still opens it). Its last row forgotten while it is in front: Chats. `MainTabs` reads the rows again as one is kept or forgotten, or a
  sign-in names one (`publicAppsVersion`, `walletConnectionsVersion`).
- **One employer: the tab is the page.** The same page host every public page uses (`OrgPageScreen` over `PublicPage`, phase 30),
  inside the tab, with the bar under it. No Done (it is a tab); Back steps back in the page, and at its first address is not the
  page's (Peel goes to the background, as from any tab). Back and Reload are the host's toolbar, as everywhere else (no title since
  2026-11-20: phase 29, "Title").
- **Kept alive behind the other tabs** (`KeptPage`): the page stays composed while another tab, a chat, Threads, Apps, an invite or
  the call screen is in front, and is only not placed. Compose neither draws nor routes touches to a child it does not place
  (`AndroidViewsHandler` draws nothing itself and passes no touch on: checked in Compose UI 1.11.4), and the web view stays attached
  to the window at the same size, so a tab switch never reloads it, detaches it or resizes it. A page not in front is treated as
  not showing (`OrgPageScreen(shown = false)`: the controller's `setVisible(false)` pauses it and withdraws a location prompt, its
  Back is off) and gives up the keyboard. It is also not in front under a page over everything (an org page, a public page).
  To keep its place, the tab area is now one `Box` holding the kept page first and then whatever is open (the call screen, an
  invite, a route, a chat, or the tab), the bar under it when nothing is open over the tabs. The screens look as they did.
- **Several employers: a short list** (`TasksList`): one row each, last opened first, titled by the row's connection title, else
  the host, with the host under it. A tap opens that page in the tab (alive behind the other tabs the same way); Done or Back at
  its first address returns to the list (and closes that page). Long-press › "Remove from Tasks" takes a row off (its sign-in
  stays in You › Connected apps), as "Remove from Apps" did.
- **A Tasks link lands on the tab** (`PublicPageRouter.tasksPage`): a link whose app is `strut/worker` no longer opens over
  everything. Whichever way it comes (a worker's invite `a=` waiting for signup and opened at `openWaitingLink`, the same invite
  tapped with an account already here, a `peel://open` link), the router keeps the row as before (`opened`), closes the pages over
  everything as any `peel://` link does, and hands the link to `MainTabs`, which closes whatever was open over the tabs (a chat,
  a route, an invite; a call goes on, on its banner), brings Tasks to the front and shows that page (a new address of the same
  employer loads it there). After a new worker's signup, Peel lands on Tasks with their employer's page, not a page over Chats. A
  lock or a reset drops a Tasks link not yet taken.
- **Apps keeps every other public app** (`ui/apps/AppsScreen.kt`, `ui/dashboard/ChatsScreen.kt`: `TasksTab.others`): "Apps you
  opened" no longer lists Tasks pages, and the Chats › Apps row (and its "No chats yet" twin) shows for a worker only when another
  public app remains, as phase 41's gate did when nothing was listed.
- **The keyboard has the bar's room on Tasks**: while the keyboard is up on the Tasks tab the bar hides, so a task's form keeps
  the space (`WindowInsets.isImeVisible`). Other tabs are unchanged.

## Not built / open

- **Talk to Jamie** replaces the whole screen and closes the Tasks page; it opens again after (fresh). A worker has no workspace,
  so no Talk.
- A lock closes the page (the tabs go); the next unlock opens it again, fresh, as every page does.
- The one employer's page has no way to remove its row in Peel but Disconnect in You › Connected apps (only if it signed in).
- Tapping Tasks again on a page picked from the list does not return to the list (Back and Done do).
- System Back from Chats, Payments or You does not go to Tasks first; it leaves Peel, as before.
- iOS is out of scope: iPhone keeps the Tasks page under Apps (and keeps no `public_app` rows at all).

## Tests

JVM: `TasksTabTest` (which links are a Tasks page; the gate; Apps' split; none / one / one at a new address / a forgotten choice /
several / a picked one / an unknown one; title and caption), `TabStateTest` (the pill's order with and without Tasks; the start tab;
Tasks gone falls back to Chats; **after an invite signup** the waiting link reaches the router, not over everything, and the tab
shows its page in front; a second employer's link, Back to the list, a pick). Robolectric: `PublicPageRouterTest` (a Tasks link
waiting for signup opens in the tab and is kept; it closes both pages over everything while another public app still opens over
everything; a lock or reset drops one not taken), `StorageTasksTabTest` (over real rows: a Tasks row shows the tab and none hides it;
one is its page, several the list in order, titled by the connection; Apps keeps the rest and its row hides with only Tasks rows;
Disconnect takes an employer's page), `TasksTabUiTest` (the list's rows, tap, long-press remove; **a page hidden behind another tab
keeps the same web view and its wallet slot, is not displayed and has no Back, and comes back without a load**; the tab page has
no Done and no Back at its first address; a picked page's Done and Back return).

### UNTESTED (needs a device)

- The bar with four items and Search; Peel opening on Tasks; the page in the tab (its height above the bar, the bar hiding while
  typing in it); a tab switch and back with a real web view (no reload, no blank page, scroll position kept); a page loading while
  out of sight (a deep link to a chat at launch).
- A new worker's whole path: the invite, the APK, signup, landing on Tasks with the employer's page; a second employer's invite
  turning the tab into the list.
- TalkBack on a page kept out of sight (its web view stays in the view tree, unplaced).
- A sign-in or a payment sheet from the page in the tab (the same host as over everything, so expected to behave the same).
