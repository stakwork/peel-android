# Money parity with the iPhone app

Every money feature in `peel` (iOS/Mac), with the file that holds it and
where Android stands. "Built" means in this branch, compiled and the pure
parts unit-tested; nothing here has been run against a live server (no
device or Lightning-backed server in the pod). Last checked against `peel`
at `70997cb`.

## What iOS does not have (so Android does not either)

- **Fiat.** No currency conversion, no exchange-rate source, anywhere in
  `peel` (the only `USD` in the repo is an agent-cost label in
  `Apps/Mac/Peel/AutomationsView.swift`). Amounts are sats, whole, the rest
  of a sat dropped (`Sources/PeelCore/Sats.swift`). Android is the same
  (`engine/Sats.kt`). Adding fiat would need a rate source decision first.
- **LNURL / Lightning addresses / on-chain.** Only BOLT 11 is paid
  (`Sources/PeelCore/Invoice.swift`). Android recognises LNURL, Lightning
  addresses and `bitcoin:` URIs without a `lightning=` fallback so it can say
  what they are, and pays only BOLT 11 (`engine/Invoice.kt`, `PaymentInput`).
- **Channels / wallet / node views.** None: the server's node holds the
  funds and the app has one balance (`newBalance` from any core answer).
- **A deposit flow other than an invoice.** Receive is `request_invoice`.

## Features

| # | Feature | iOS evidence | Android |
|---|---|---|---|
| 1 | Balance (sats), from any core answer | `Engine+Payments.swift` `balanceMsat`; `Apps/iOS/Peel/PaymentsTab.swift` `BalanceHeader` | **Built.** `Engine.balance` (a `StateFlow`), `ui/payments/PaymentsScreen.kt`; You screen shows it too |
| 2 | Sats waiting for people to come online | `Storage+Reads.swift` `waitingMsat`/`spendableMsat`; `BalanceHeader` | **Not built.** Needs the async-payment bookkeeping (pending-ping rows, `asyncpayTag`) Android does not store. Balance checks use the whole balance |
| 3 | Payments tab: history by day, newest first, paging, refresh | `PaymentsTab.swift`, `PaymentsFeed.swift`, `PaymentPaging.swift` | **Built.** `PaymentsScreen.kt`; `fetchPayments` paged 50 at a time; refresh on connect/balance change and by button |
| 4 | Payment status (pending greyed, failed struck through, never red) | `PaymentItem+Display.swift`, `PaymentRow` | **Built.** `PaymentItems.kt` (`PaymentItem.build`) |
| 5 | Payment detail | `Apps/iOS/Peel/PaymentDetail.swift` | **Partial.** Amount, who, when, note, status, hash. No routing fee ("Fee not known") and no boosted-message link |
| 6 | Sats received shown and notified, rows lit | `MoneyArrivals.swift`, `Notifier.swift`, `PaymentsTab.swift` | **Not built.** No notification layer on Android yet (the push work is a separate PR); the list updates as history arrives |
| 7 | Receive: make an invoice, QR, copy, share, live Paid, expiry, underpaid | `Apps/iOS/Peel/ReceiveSheet.swift`, `Engine.createInvoice`/`checkServerInvoice` | **Built.** `ReceiveSendScreens.kt`, `Engine.createInvoice` (`requestInvoice` through the core, answer checked for payee, amount, memo, expiry) |
| 8 | Pay an invoice: paste, scan, checks, confirm, outcome | `SendSheet.swift`, `Engine.payInvoice`/`invoiceProblem`/`pay` | **Built** (see "Left" for two checks not ported). Route through `payContactInvoice` / `pay` (server's own node) / `payInvoice` with a fall back to `pay` when there are no route hints, as iOS |
| 9 | Never pay twice: pending written before publishing, in-flight set | `Engine.pay`, `commitAndPublish(invoices:)` | **Built.** `Engine.run(beforePublish=…)` writes the pending record under the engine lock before anything is published; `Storage.saveInvoice` refuses to overwrite pending/paid |
| 10 | Confirmation threshold (default 100 sats, 0 = always), balance and fee-reserve checks | `Engine.checkPayment`, `feeReserveMsat`, `YouTab.swift` `PaymentConfirmationRow` | **Built.** `PaymentRules`, `Engine.checkPayment`, You screen "Confirm payments" |
| 11 | QR scanner for invoices | `Apps/iOS/Peel/InvoiceScanner.swift` (VisionKit) | **Built.** `InvoiceScanner.kt`: CameraX frames to ZXing; no Play Services. `CAMERA` was already declared (calls); asked at runtime |
| 12 | QR display | `Apps/Shared/QRCode.swift` | **Built.** `QrCode.kt` (ZXing, level M, upper-cased invoice) |
| 13 | Send sats to a contact in chat (type 5) with note | `Engine.sendPayment`, `PayControls.swift`, `SendSheet.swift` `SendToContactForm` | **Built.** Chat "+" menu "Send sats" and Send tab "Or send to a contact" |
| 14 | In-chat payment rows (type 5) | `MessageRows.swift` `PaymentRowView` | **Built.** `ChatMoney.kt` `PaymentMessage` |
| 15 | Request payment in chat (type 2) | `Engine.requestPayment`, `RequestRowView` | **Built.** Chat "+" menu "Request sats"; `RequestMessage` |
| 16 | Pay a request from the chat (type 2 → type 3 to the payee) | `Engine.payRequest` (Mac `PaymentChatRows.swift`; the iPhone defers it) | **Built.** `RequestMessage` Pay button → `Engine.payRequest`; type-3 messages are hidden as rows and flip the request to Paid |
| 17 | Request state: open, paying, paid, underpaid, expired, wrong payee | `RequestRowView.status` | **Built** (same words) |
| 18 | Boost: one tap, tip amount + tribe price, once per message | `Engine.quickBoost`, `MessageMenu.swift` | **Already built** (`Engine.sendBoost`, phase 09). Now also checks the balance first |
| 19 | Tip amount setting, synced as `tip_amount` | `Preferences.swift`, `PreferencesFile.swift`, `YouTab.swift` `BoostAmountScreen` | **Already built** (`Engine.setBoostAmount`/`syncBoostAmount`). Added: presets (10/50/100/500/1000) and the second-tap confirmation over the threshold. Default stays 10 sats (a deliberate choice in `Attachment.kt`; iOS's is 100) |
| 20 | Boost chips under a message | `MessageRows.swift` | **Already built** (`BoostChip`) |
| 21 | History reconcile: settle invoices from history rows, fail pending sends by their rows | `Storage.reconcile`, `reconcileInBackground` | **Partial.** `Engine.storeMoney` settles invoices we made and our pending payments from each history page and type-3 messages. Not ported: reaching back 30 days for stale pending sends (`fetchBackToOpenSends`), the "not arrived" verdict (`markNotArrived`), unsettled status |
| 22 | Payment templates (picture on a payment) | `PaymentTemplate.swift`, `Engine+Attachments.swift` | **Not built.** Needs the memes `/templates` list and media tokens for payments; the token call exists (`coreMakeMediaToken`), the UI does not. Incoming templates show their note only |
| 23 | Paid attachments (types 7, 8, 9: price, purchase, accept/deny) | `PaidAttachmentViews.swift`, `Engine+Attachments.swift` | **Not built.** Buying/selling needs a key-owed-after-stop flow. Purchase messages (7/8/9) are hidden as rows so they do not show as blank text |
| 24 | Tribe message price / escrow on sends | `Tribe.paymentPriceMsat` | **Already built** (3-sat floor, price + escrow) |
| 25 | Pay-someone-in-a-tribe refusal | `Failure.paymentToTribe`, `requestToTribe` | **Built** (`requirePerson`) |
| 26 | Diagnostics redaction of money | `PeelDiagnostics/Redactor.swift` | **Not applicable** (no diagnostics layer on Android) |
| 27 | Agent tools that pay (`SendTool`, `Approval`) | `Sources/PeelAgent/`, `AgentKit/Approval.swift` | **Not applicable** (agent runs on the Mac) |

## Core bindings on Android

`sphinxrs/src/main/java/uniffi/sphinxrs/sphinxrs.kt` exposes everything
iOS uses for money: `requestInvoice`, `makeInvoice`, `payInvoice`,
`payContactInvoice`, `pay`, `parseInvoice`, `paymentHashFromInvoice`,
`fetchPayments`, `send` (with an amount and a type), `getTags`,
`makeMediaToken*`, and `RunReturn.newBalance`/`invoice`/`payments`/
`paymentsTotal`/`sentStatus`/`asyncpayTag`. No API was missing, so nothing
is stubbed behind an interface. What is not used yet: `getTags` (asking
the server about a tag, for stale pendings), `asyncpayTag`/`ping`, `route`
and `lspHost`.

## Left

- Sats-waiting-for-offline-people (#2), arrival notifications (#6), the
  stale-pending reach-back and not-arrived verdict (#21), templates (#22),
  paid attachments (#23).
- Two `invoiceProblem` checks: an invoice another of our devices made
  through the same server (`isOurRequest`), and the waiting-sats
  subtraction.
- No fee shown after a payment (the history row has none; iOS derives it).
- Nothing was run on a device or against a live Lightning-backed server:
  the engine paths (`createInvoice`, `pay`, history paging) are compiled
  but untested end to end, as on iOS ("Not tested locally" in
  `peel/docs/phases/04-money.md`).

## Config

- Permissions: `CAMERA` (already in the manifest), requested when the
  scanner opens; refused, the scanner says so and points to paste.
- New dependencies (`gradle/libs.versions.toml`): `com.google.zxing:core`
  3.5.3 and CameraX 1.4.2 (`camera-core`, `-camera2`, `-lifecycle`,
  `-view`). No Play Services, no API keys.
- Server: the Lightning side is the account's own server (`request_invoice`
  needs a server with a node). No fiat/rate endpoint.
- Storage: schema v9 adds `invoice` and `payment` tables (migration is
  additive); new setting `paymentConfirmationMsat`.
