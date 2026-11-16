# Phase 38 — Wallet parity: what iOS does beyond phases 32–35

Status: **built, compiled and unit-tested (JVM + Robolectric); the native-core paths never run.** Closes the wallet differences of phases 32–35 that apply to a phone, each
by reading iOS (`/workspaces/peel` main, 7c5e4ef): `Engine.swift` (`liveStatus`, `recordedStatuses`, `checkPendingSends`, `commitAndPublish`), `Engine+Wallet.swift`, `Engine+Payments.swift`
(`invoiceProblem`, `checkPayment`, `fetchBackToOpenSends`, `reconcileInBackground`), `Storage+Writes.swift` (`applyStatus`, `statuses(movingTo:)`, `reconcile`), `Storage+Reads.swift`
(`pendingSendTags`, `oldestOpenSend`, `waiting`), `PaymentHistory.swift` (`paidState`, `isOurRequest`, `ourPayment`), `Invoice.swift`, `PeelError.swift`.

## Built
- **(a) `UNSETTLED`** for every send tracked by tag. Messages already had it (`SendStatus`); now `InvoiceRecord.Status.UNSETTLED` (invoice we pay and keysend share the enum). `Storage.markSendStatus(tag, status, error)` is the one
  rule of iOS `applyStatus` over the four tables (message, invoice we pay, invite, keysend): pending → unsettled → failed → complete, a complete one never changes, and a FAILED after a failure keeps the first reason. COMPLETE stamps
  `paidAt` once and clears the reason. This changes two old Android rules to iOS's: a COMPLETE now moves a **failed** invoice/keysend (the server had it after all), and an unsettled live word (the mixer's last PENDING) is applied to
  invoices and keysends too (before it was dropped for them). `Engine.storeMessages` calls only `markSendStatus` (the preimage is kept first, so whoever sees COMPLETE sees its proof); `markInvoiceSendStatus` stays as the invoice/keysend-only form.
  `reconcileKeysends` is now iOS's outgoing `reconcile`: history rows settle (COMPLETE from pending/unsettled/failed) or fail (pending/unsettled only if no good row) money-send **messages** and keysends. UNSETTLED is pending to
  an app (`walletState`), `paying` in the lists (`PaymentItems`, `ChatMoney`, Send screen), never paid again (`paidState`), excluded from `waiting` and from the sends asked about on connect (as iOS).
- **(b) `pendingSends` / `oldestOpenSend`.** `Storage.pendingSends(textBefore)` returns `PendingSend(tag, createdAt, movesMoney)` over message / no-status message / invoice / invite / **keysend (PENDING)**; `pendingSendTags` is its tags.
  `oldestOpenSend(since)` includes keysends. `Engine.checkPendingSends` asks about keysend tags and fails a payment the server has no record of 30 minutes after it was sent (`SendStatus.unrecorded`, `failedForWantOfRecord`,
  `SendRetry.UNRECORDED_PAYMENT_TIMEOUT_MS`). `Engine.reconcileInBackground` (on coming online, one at a time, again if asked during one) = `refreshPaymentHistory()` then `fetchBackToOpenSends()` (30 days, 10 pages): iOS's
  `reconcilePayments`.
  - Found on the way: `pendingSendTags`' `COALESCE(timestamp, 0) < ?` bound the cutoff as text, so SQLite compared an integer to text and every pending text counted as old; now `CAST(? AS INTEGER)` (iOS: only text older than the cutoff).
- **(c) Unknown balance.** Read: iOS `invoiceProblem`, `checkPayment` and the wallet's `problem(paying:)`/`balance()` all refuse with `Failure.balanceUnknown` ("the balance is not known yet; connect first", `WalletError.OTHER`). Android's wallet path
  already did exactly this (phase 33's "Differences" line was about `Engine.checkPayment`'s one-tap **boost**, not the wallet), so nothing changes there. What did differ: `checkPayment` and `invoiceProblem` used the raw balance; they now use
  `Storage.spendableMsat()` (balance less what waits), as iOS.
- **(d) `WalletRecord.fromPayment`** (iOS `init?(payment:)`) already existed since phase 34 (the scripted wallet uses it); the phase-32 "not ported" line is stale. iOS uses it only for a scripted wallet's answer in tests; no production caller exists in iOS either.
- **(e) `isOurRequest`.** `Storage.isOurRequest(hash, pubkey)` (our type-2 message, `fromMe`, whose sender JSON's `pubkey` is ours; parsed in Kotlin, no `json_extract`). Asked, as iOS, only of an invoice **our own server made**
  (`payee == contact-info server`), by `Engine.invoiceProblem` and `AppPayments.invoiceProblem`; refuses as "own invoice". `Storage.paidState(hash)` (iOS `paidState`/`ourPayment`) now answers both: own, pending (pending/open/underpaid/unsettled),
  paid (including a failed row the history shows paid from another device, or no row and a good history row).
- **(f) Events.** iOS's `emit.yield(.coreError)` / `.sendStatus` have no Android stream; the wallet path's equivalent: `Published.None` (the connection had gone before the first publish: the row is failed with "not connected" at once, so it can be paid
  again, and `pay` throws `NOT_CONNECTED`; for keysends and invoices) and `Published.Unknown` (it may have gone: stays pending, and `Engine.coreErrors` (SharedFlow) carries iOS's `mayHaveGone` words). `NotConnectedException` is open; a loss **after**
  the async pay's first publish is `ConnectionLostAfterPayException` (unknown, as iOS's `.unknown`), not none. The core's own error words are kept as the `PAYMENT_FAILED` message (iOS `PeelError.readable`), no longer prefixed "Could not pay:". `sendStatus`
  needs no event: screens observe storage.
- **(g) Lock / dispose.** `OrgPageScreen` closes its `PhoneWalletSheets` in `DisposableEffect.onDispose` (before the web view); verified by `OrgPageDisposeTest`: the real composable in a Robolectric activity, a sheet waiting, then the window going away, and
  the page leaving the composition (what a lock does): the waiting sheet is answered no, `stillOpen` is false, nothing more is asked. `OrgPageScreen` takes an optional `sheets` (default: made inside) for this.
- **(h)** Remaining items of 32–35: see Differences.

## Differences from iOS (kept, with reasons)
- **Boost with an unknown balance** (`Engine.checkPayment(unasked = true)`) still goes through; iOS refuses. Outside the wallet; Android's boost has always gone without a known balance. Not changed.
- **Unrecorded payments fail after every reply is in**, not on the first reply: Android asks `getTags` in chunks, so a tag can only be called unnamed once all answers arrived (`resendGate.recorded`); a reply that never comes fails nothing.
- `reconcileInBackground` runs on coming online only; iOS also runs it when the balance rises and when someone's payment comes, and marks others' payments not arrived (`markNotArrived`), which this port does not have.
- Message-row `UNSETTLED` display in chat lists is untouched here (messages had it already).
- Skipped, as not applying to a phone: the Mac's `.app`/`.remote` scopes (phases 34, 35). Amounts are `Long`, the invoice insert rule and `now`-in-seconds differences of phases 32–34 are deliberate and unchanged.
- `Engine.Failure` as a type does not exist: `AppFailure` carries the cases the wallet reaches.

## Tests
`StorageSendStatusTest` (11: the forward-only rule on keysends and messages with unsettled, reasons kept, only money sends unsettled, waiting/pending/oldest-open exclusions, hash required, `pendingSends` with createdAt/movesMoney and the text cutoff, the unrecorded failing,
history settling/failing messages and keysends, a good row beating a failed one, `paidState`), `AppPaymentsTest` (+7: never-left keysend/invoice failed at once and payable again, may-have-gone stays pending and is told in iOS's words, unsettled invoice, history-paid after failure,
our own request from another device), `OrgPageDisposeTest` (2), `StorageWalletTest` adapted (a COMPLETE after a failure moves it).
### Untested
- Everything through the native core: `Engine.paymentRunner` mapping of `NotConnectedException`/`ConnectionLostAfterPayException`/`SphinxException` to none/unknown/the core's words (the core's real error text), `checkPendingSends` against a real server (chunked
  answers, the unrecorded failing), `reconcileInBackground` against a real history, the live `sentStatus` UNSETTLED path.
- `isOurRequest` against a real echoed request's sender JSON (a fixture of the shape is used).
- A real device: the dispose path with a real WebView and a real lock.
