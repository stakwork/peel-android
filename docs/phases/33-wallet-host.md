# Phase 33 — The wallet host

Status: **step 6a (logic) and 6b (UI) of the org-page port.** Built: `WalletHost` complete for everything iOS has beyond `buy_credit`, over `EngineWallet` (step 5) for every scope; the connect, payment and
sign-in sheets as data (`WalletConnectSheet`, `WalletConnectAnswer`, `WalletPaymentSheet`, `WalletPerson`); the notifications; `WalletWords`. All ten of strut's `scenarios` replay green. Compiles; tests pass.
**6b built (below):** the connect sheet, the payment sheet, You › Connected apps and the You row, `PhoneWalletSheets` handling both sheets, all over pure tested state holders. **Nothing has run on a device or emulator.** **Not ported: `buy_credit` (step 7).**

iOS read from `main` (1778b01): `WalletHost.swift` (all but credit), `WalletHostTests.swift` (the scripted wallet/person, `strutsScenario`, the beyond-scenarios cases), `AppModel+Wallet.swift` (`WalletWords`, `setOverLimit`,
`setSpendingLimit`, `revokeWalletConnection`, `walletUsage`), vectors `scenarios`.

## Built
- **`engine/WalletHost.kt`** (`WalletHost(connections, wallet: AppWallet, nowMs, sheetTimeoutMs)`): `capability` (the ten offered methods, every notification type, wallet `sphinx`, `connected` = a valid connection holding
  methods); `call` in iOS's order (`checkParams` → offered (`pay_lightning_address` is not) → `connect`/`get_info` → connection `UNAUTHORIZED` → method granted `RESTRICTED` → the method); `connect` (unoffered required methods
  `NOT_IMPLEMENTED` with their names, held covers → answered with no sheet, gesture `RESTRICTED`, `walletSheet`/`grant`: limits lowered never raised, 0 = none, optional methods the person kept, held methods and creation time
  kept, renewal and expiry from the request else the held, overBudget from the answer); `pay_invoice`/`pay_keysend` (decode and its refusals, `wallet.problem` before anyone is asked, inside the limit pays with no sheet, a
  budget lost to another payment meanwhile falls through to over-limit, over limit: `refuse` → `QUOTA_EXCEEDED` "That is over what you let this app spend.", `ask` → gesture rule then the payment sheet (payee name from contacts,
  memo/comment as blocks, used and limit), approved = counted not limited, declined `USER_REJECTED`); `make_invoice`, `lookup_invoice`, `lookup_payment`, `list_transactions` (from/until/type/unpaid/offset/limit,
  `total_count`), `get_budget` (empty with none, `renews_at` when it renews), `get_balance`, `get_info`; Sphinx's TLV (13377331, base64 text) as the keysend's data; one route hint of one hop.
- **Sheets**: one at a time across every page (`RATE_LIMITED`), given up after the timeout (`RESTRICTED` "You did not answer in time.", the person's coroutine cancelled = the sheet taken down). `sign_in` now uses the same
  `walletSheet`/`grant` as `connect` (the `asked` map: the wallet a page's `capability` asked for rides on the sign-in's sheet, ONE sheet; `asksWallet` false for a sign-in alone; the held `signIn` key is kept) and
  `WalletPerson.connect`; the old `askSignIn` is gone (`PhoneWalletSheets` maps a sign-in-only sheet to its dialog).
- **Notifications**: `start()` collects `wallet.updates()`; a transaction's settle/fail/receive becomes a `WalletPush` (once per state, only for a valid connection that asked for the type, `app` = `{name, peer?}` from the
  record's `<peer>/<name>`), held until the answer of the call that made it (`paid`), never before.
- **`engine/WalletWords.kt`**: all of iOS's non-credit copy (methods, limit by renewal, over-limit, sign-in, connect titles and answers, signed-in, who) — pure, tested. `Storage` already had `setSpendingLimit`, `setOverLimit`,
  `deleteWalletConnection`, `walletUsage` (step 4) = `AppModel`'s non-UI actions.
- **`Engine.walletHost`** = `WalletHost(StorageWalletConnections(storage), engineWallet()).also { it.start() }`; the step 2 stub is gone. **`WalletRecord.fromPayment`** (iOS `init?(payment:)`).

## Differences from iOS
- **Scopes**: only `Org`/`Origin` (the Mac's `.app`/`.remote` do not exist), so the `capability` "that is not the app this page shows" check is not needed; an org's `get_info`, connect etc. work for any of its apps.
- **No `stillOpen`** ("a yes once Peel locked is a no"): that is in iOS's `PhoneWalletSheets` (UI); on Android a lock closes the page (`PublicPageRouter.locked`, `MainTabs` leaving), which cancels the pending sheet.
- **No withdrawal** (`withdrawnBy`) of a sheet by the page: credit's (step 7).
- **A cancelled sheet**: iOS's task group cancels `present()`; here `withTimeoutOrNull` cancels the person's coroutine, which is the sheet taken down.
- **`now` is `nowMs() / 1000`** (iOS has `now` and `nowMs`); connections' times are seconds.
- Synchronisation is a lock and an `AtomicBoolean` (iOS is an actor); state is touched only at the points iOS does.

## Tests
`WalletHostScenarioTest` (strut's 10 scenarios replayed, one database each, with their pushes checked by `notificationProblems` and results by `resultProblems`; plus iOS's cases: no sheet without a tap, one sheet at a time and the
timeout, an approved payment over budget is counted and its sheet says what, over limit ask/refuse, an invoice's decoded amount, a payment the wallet cannot make asks nobody, what the core cannot send, isolation
between orgs and one connection per org, what the host refuses, connecting again asks only for more, limits lowered never raised, expiry and revoke, periods renew, the answer before the notification once, and lists,
lookups, make_invoice, get_info), `WalletHostTest` (PR #74's sign-in cases, now through the shared sheet; capability/calls updated), `WalletWordsTest`, `ScriptedWallet`/`ScriptedPerson`/`PushLog` fixtures.
### UNTESTED (6a)
- Anything on a device: the page ↔ host path with a real strut app, `Engine.walletHost` over the real `EngineWallet` (native core), notifications reaching a page from a real payment.


## Step 6b — the wallet UI
iOS read from `main`: `Apps/iOS/Peel/OrgWallet.swift` (`PhoneWalletPerson`, `PhoneWalletSheets`, `WalletConnectPhoneSheet`, `WalletPaymentPhoneSheet`, `ConnectedAppsScreen`, `ConnectedLimitRow`), `PayControls.swift` (`TouchPayButton`), `PaymentAttempt`, `YouTab.swift`.

### Built
- **`engine/WalletSheetState.kt`** (pure, JVM-tested): `ArmingGate` (700 ms = `TouchPayButton.settle`; `yes` runs only once armed, so a late recomposition cannot let a tap through; `open()` restarts it), `PayAttempt`/`PayOutcome` (iOS `PaymentAttempt`: armed
  amount, working, problem; not twice while one is on its way), `WalletPayStep` (the payment sheet's two-step: first tap arms the sheet's amount, the second only after another 700 ms pays exactly it; `ARMED_NOTE` "That's over this
  app's spending limit. Check it, then confirm."), `WalletConnectForm` (limit text from the asked limit in sats, `limitMsat` = typed sats lowered never above the asked, optional toggles in sheet order, over-limit from the held connection else ask,
  identity = first key; `canConfirm(settled)`; `answer()` → `WalletConnectAnswer`), `ConnectedAppRow` (title, place, `WalletWords.signsIn`, limit sentence, over-limit "Ask me"/"Refuse", `canSave`, "N spent this period" only under a limit, the You row's
  "None"/count, the empty text), `WalletSheet` (+ iOS's ids) and `WalletSheetAnswer`.
- **`ui/page/PhoneWalletSheets.kt`** (was `SignInSheet.kt`): iOS `PhoneWalletSheets` + `PhoneWalletPerson` in one — `connect`/`pay` both ask one sheet at a time (a second while one is up, or after close, is a no), `shown`, `answer`, `dismiss`, `close()`.
  `stillOpen` is true until `close()`: a yes after the page closed or Peel locked is judged when given and counts as no. The page host calls `close()` on dispose (closing the page, or a lock which removes `MainTabs`' page), which answers the waiting sheet
  as a no; the host cancelling the coroutine (timeout) takes the sheet down. The sign-in-only dialog is gone: a sign-in alone is the same connect sheet without the wallet sections (iOS has no other).
- **`ui/page/WalletSheets.kt`**: `WalletSheets` (draws `shown`, keyed by its id), `WalletConnectPhoneSheet` (title `connectTitle`, who line, `sharedConnection`, "Sign in" key radio list with the selected key's note, "It may" required methods + optional switches + "Hear when its
  own payments land", "Spending limit" field in sats + sentence, "Over the limit" Ask me each time/Refuse, the footer "Inside it, apps pay without asking you. Change it or take it back in You › Connected apps.", `connectAnswers` buttons, yes off until 700 ms and also
  guarded by `ArmingGate`), `WalletPaymentPhoneSheet` (title, who, big amount, "To name · key" / "key, not a contact", "Memo:"/"Message:", fee line, "N of its M limit spent", "The app says: “…”", Pay → Cancel + orange "Pay N sats" after 700 ms, Decline). Full-screen
  `Dialog`s: tap outside does nothing, Back = decline / don't connect.
- **You**: `ConnectedAppsScreen` in `ui/you/YouScreen.kt` replaces the sign-in-only list (live from `Storage.walletConnectionsVersion`; per connection: title, place, sign-in line, and for a wallet the limit sentence, over-limit choice (saved at once through `setOverLimit`), the limit field with Save
  (`setSpendingLimit`, empty clears), "spent this period" from `walletUsage`, Disconnect (destructive, `deleteWalletConnection`)); the "Connected apps" row (None/count) sits after Confirm payments, as in iOS.
- **Notifications (step 3)**: `OrgPageScreen` collects `Engine.walletHost.notifications` and calls `OrgWalletBridge.push`, which sends only those of the page's own scope key into the WebView's main frame. Tests (already in `OrgWalletBridgeTest`, re-run green): a push for the page's connection reaches the
  script call and one for another does not (`a push for this page's connection goes in and one for another does not`), and a notification published by a real `WalletHost` reaches the script call, the other org's not (`a notification published by the host reaches the web view's script call`).

### Differences from iOS
- Dialogs, radios and switches for iOS's `Form`/`Picker`/`Toggle` (the inline picker is a radio list); Back is the no.
- The connect sheet's optional methods are answered in the sheet's order (iOS's `Array(Set)` is unordered).
- A connection with no title shows its place (as step 4's list did); iOS shows an empty heading.
- `stillOpen` is "the page has not closed": Android has no separate lock flag; a lock closes the page, which calls `close()`.
- No haptic on a sent payment.
- Connected apps' limit field keeps only digits (iOS number pad).

### Tests (6b)
`WalletSheetStateTest` (17: arming, the two-step incl. ignored early second tap, cancel, no double run, form limits/toggles/identity/confirm, Connected rows, ids), `PhoneWalletSheetsTest` (8: connect/pay answers, one at a time, close = no and nothing after, a late yes = no,
host cancel frees the slot, toast words, sign-in rides the connect sheet). `OrgWalletBridgeTest` (8) and `WalletHostTest` (29) re-run green.
### UNTESTED
- Every composable (`WalletSheets`, both sheets, `ConnectedAppsScreen` and the You row): compiled only; nothing was run on a device or emulator, so layout, the Dialog's Back behaviour, keyboard, TalkBack and the real 700 ms feel are unchecked.
- A real page asking for a connect and a payment through the WebView, and a real payment's notification arriving in the page.
- The closing-the-page/lock cancellation is tested on `PhoneWalletSheets` only, not through `OrgPageScreen`'s `DisposableEffect`.
