# Phase 32 — App payments: the engine and the protocol

Status: **step 5 of the org-page port.** Built: the complete port of iOS `WalletProtocol` (every check, run against the vector lists iOS runs: params, results, notifications, connection_requests, preimages; the `scenarios` list is not replayed), the real `WalletRequest`/`WalletApp`,
the engine's side of an app's payments (`AppPayments`: keysend, invoice paid, invoice made; the budget in the payment's own write), `AppWallet`/`EngineWallet`, the `waiting`/`spendable`
readers, the preimage kept with the status. Compiles; the full `:app:compileDebugKotlin :app:testDebugUnitTest` run passes (827 tests, 0 failures, 3 skipped). **The paths that need the native core have never run** (list below). The wallet **host** still
answers only `host_capability`/`sign_in`/`capability`/`call` as in step 2: wiring it to `EngineWallet` (connect, pay, lookups, notifications, scenarios) is step 6.

iOS read from `main` (1778b01): `Engine+Wallet.swift`, `WalletProtocol.swift` (all), `WalletHost.swift` (`AppWallet`, `WalletInvoice`, `WalletPayment`, `WalletRequest`, `WalletApp`),
`Engine+Payments.swift` (`checkPayment`, `invoiceProblem`, `feeReserveMsat`, `pay`), `Storage+Reads.swift` (`waiting`), `Storage+Writes.swift`, `WalletProtocolTests`, `WalletEngineTests`, `vectors.json`.

## Built
- **`engine/WalletProtocol.kt`**: `checkParams(method, params)` → `Result<JSONObject>` / `WalletRefusal(BAD_REQUEST, <iOS's words>)` for all eleven methods (invoice shape, `lightning:`, amounts, hex shapes, Sphinx
  addresses split into key and route hint, route hints, TLV records (`≥ 65536`, never 5482373484, never twice), metadata ≤ 4096 as JSON counted without `/` escapes, lookups' exactly-one, list limits, Lightning
  addresses and LNURLs); `connectionRequestProblems`; `resultProblems`; `notificationProblems`; `SphinxAddress`; `routeHint`; `invoiceNamesAmount`; `isHash` is lower-case only, as iOS's; `WalletRefusal` (the code and the
  words; `body` is the JSON-RPC error).
- **`engine/WalletHost.kt`**: `WalletRequest.read` (null for none or anything the contract refuses) and `WalletApp` (key `<peer>/<name>`) replace step 2's "any object is a request".
- **`engine/AppPayments.kt`**:
  - `WalletTag(connection, app, budget)`.
  - `AppFailure`: iOS's `Engine.Failure` cases the wallet reaches, with their words and the contract's code.
  - `PaymentRunner`: the seam for the native core.
  - `AppPayments.keysend` (amount, key, self, route-hint shape; balance less what waits, less the fee reserve and the ping for an async one, **not** the person's threshold; connected; written pending, with its tag,
    connection and app, **in the core's own write** with the budget check; `Published.Unknown` keeps it pending and never sends again).
  - `AppPayments.payInvoice` (`invoiceProblem` first: no amount, expired, own, pending, paid, balance), `AppWallet`, `WalletInvoice`, `WalletPayment`, `InvoiceMaker`, and `EngineWallet` (network, decode, `problem(paying:)`,
    `pay` (waits up to the settle wait for the server's word, so a payment answered at once is told settled with its proof), `makeInvoice`, `records`, `balance`, `payeeName`, `signVisitor`, `signsPerDomain`, `updates()`).
- **`Storage`**: `waitingMsat` (pending money sends to people and async keysends, ≤ a week old), `spendableMsat`, `contactAlias`, `invoiceByTag`.
- **`Engine`**: `run(commitWith = …)` writes an app's rows and budget check **in the same transaction as the core's state**, and moves the in-memory state only after that write succeeds (iOS `commitAndPublish`): a refusal
  writes nothing, moves nothing, publishes nothing. `paymentRunner()` (the core's `keysend`, `pay_invoice`/`pay` with the fall-back `Engine.pay` has), `appPayments()`, `engineWallet()`; `createInvoice(wallet =)` tags an
  invoice made; the mixer's COMPLETE keeps its preimage (only if it hashes to the payment hash) **before** it marks the payment paid, so whoever sees it paid sees its proof.

## Differences from iOS
- **Balance unknown refuses** an app's payment (`BALANCE_UNKNOWN`); Android's `checkPayment` lets a one-tap boost through, an app's never.
- **`PaymentRunner` is the seam** where iOS has the engine itself, so the logic is JVM-tested; the real one is `Engine.paymentRunner()`.
- **An invoice for an app is paid through the same two core calls as `Engine.pay`** (`pay_invoice`, then `pay` when there is no route and no hop hints; the server's own node's invoices through `pay`); there is no contact path.
- **`isOurRequest`** (our own request made through our server on another device) is not checked, as in `Engine.invoiceProblem`; nor `paidState`'s message-borne cases beyond the invoice rows and the history.
- **A publish that fails after the commit** surfaces as `Published.Unknown` only for I/O errors; other errors propagate with the payment already written pending (never sent again either way).
- **No `emit.yield(.coreError)`/`sendStatus` events**: Android has no such event stream; a payment that never left is `NOT_CONNECTED` before the core is asked.
- **No `UNSETTLED`** (as step 4); `awaitFinal` waits for COMPLETE or FAILED.
- **The wait is real time** (`System.nanoTime`), not the account's clock.
- The vectors' `scenarios`, `host_capabilities`, `credit_*` and `sign_in_*` lists are not replayed here: scenarios are the host's (step 6), the rest were step 2's or are out of scope (`buy_credit`).

- **Not ported:** the host's use of all this (connect, pay, lookups, budgets, notifications: step 6); `WalletRecord.init?(payment:)`; `Engine.Failure` events; `pendingSendTags`/`oldestOpenSend` over keysends; paying a Lightning address or LNURL (iOS's `WalletPayment` has none either); `buy_credit`.

## Tests
`WalletProtocolTest` (12 tests, two of them PR #74's number-reading tests; vectors per list: params 31, results 14, notifications 5, connection_requests 6, preimages 4; the `scenarios` list is only counted (10), the file being byte-for-byte iOS's), `AppPaymentsTest` (15: a keysend through the fake core, refusals before anything
moves, the limit in the payment's own write for keysends and invoices, waiting/spendable, an invoice paid unasked and tagged, `invoiceProblem` cases, a payment settled at once or pending, the contract's errors in the engine's
words, problems found before anyone is asked, balance/payee/network/signing, an invoice made, the state stream), plus step 4's storage tests, `WalletModelTest` and `WalletHostTest` (its `request` helper now names a real request).
### UNTESTED (needs the native core, `libsphinxrs`, which cannot load on the JVM)
- `Engine.paymentRunner()`: the core's `keysend`, `pay_invoice` and `pay` calls, reading the tag and hash from the core's message, `Engine.run(commitWith)` against a real state, and the publish (ordered) after the write.
- `Engine.commitLocked`'s new order (state applied after the write) on a real device; the live `sentStatus` path keeping the preimage; `Engine.decodeInvoice` (`parse_invoice`); `createInvoice(wallet =)` against a real server;
  `Engine.engineWallet()`'s wiring. The write itself (state + rows + budget in one transaction) is tested through `Storage.apply`.
