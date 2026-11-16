# Phase 31 — Wallet storage and the budget ledger

Status: **step 4 of the org-page port.** Built: Android's v14 migration (iOS v33's wallet tables and columns, on top of PR #74's v13), the full
`WalletConnection`, the wallet enums, `KeysendRecord`, `WalletRecord` (+ `payment`/`transaction`/`lookup` shapes, `entry`), `WalletBudget` (period/used/guard),
the storage API of iOS `Storage+Wallet.swift`, the budget check inside `Storage.Commit`, the preimage rule and the history settling a keysend. Compiles;
`:app:testDebugUnitTest` passes. **Never run on a device.** **Nothing pays or makes an invoice for an app yet** (step 5): no producer writes a keysend or a tagged
invoice, so on a device these tables stay empty until then; the connections list works as before (sign-in only).

iOS read from `main` (1778b01): `Storage+Wallet.swift`, `WalletLedger.swift`, `Storage+Migrations.swift` v33/v34, `Storage+Writes.swift` (`Commit.walletBudget`,
`reconcile`, `applyStatus`, `insert`), `AppModel+Wallet.swift` (`setSpendingLimit`, `setOverLimit`, `revokeWalletConnection`, `walletUsage`), `WalletProtocol.swift`
(`WalletMethod`, `WalletNotificationType`, `WalletRenewal`, `verifyPreimage`), and `WalletHostTests`/`WalletEngineTests`/`WalletProtocolTests` + strut's vectors.

## iOS → Android
| iOS (v33/v34) | Android (v14) |
|---|---|
| table `walletConnection` | table `wallet_connection` (PR #74's v13 name, kept) with its v34 `signIn` already there; v14 adds `notificationTypes TEXT NOT NULL DEFAULT '[]'`, `maxAmountMsat INTEGER`, `renewal TEXT NOT NULL DEFAULT 'never'`, `expiresAt INTEGER`, `overBudget TEXT NOT NULL DEFAULT 'ask'`; `methods` stays JSON text (wire names, as iOS) |
| `createdAt` unix **seconds** | v13 wrote **milliseconds** (and `WalletHost` now writes seconds): v14 divides rows with `createdAt >= 1e11` by 1000, so a renewal counts from the right day |
| keys `org:<peer>` / `origin:<origin>` | unchanged |
| table `keysend` (+ unique `keysend_tag`, partial `keysend_connection`) | the same table, columns and indexes (`async` is `INTEGER NOT NULL`, not `BOOLEAN`); statuses are `InvoiceRecord.Status` raw values (`OPEN`/`PENDING`/`COMPLETE`/`FAILED`/`UNDERPAID`) |
| `invoice.preimage`, `.connection`, `.app`, partial index `invoice_connection` | the same columns and index on Android's `invoice` table (`InvoiceRecord` gains `preimage`, `connection`, `app`) |
| `Storage.Commit.{invoices,keysends,payments,preimages,walletBudget}` | `Storage.Commit` gains the same five (`WalletBudgetCheck(connection, amountMsat, guard)`); `apply` runs history rows → budget check → invoices → keysends → preimages in one transaction, and a refusal throws and leaves the database as it was |
| `Engine.Failure.overBudget` | `OverBudgetException` (the contract's `QUOTA_EXCEEDED`; the wallet host maps it in step 5) |
| `Storage.walletConnection(key:)`, `walletConnections()`, `walletConnectionUpdates()`, `saveWalletConnection`, `deleteWalletConnection` | `walletConnection`, `walletConnections` (oldest first, as iOS; v13 listed newest first), `walletConnectionUpdates()` (Flow, `distinctUntilChanged`), `saveWalletConnection`, `deleteWalletConnection`; `walletConnectionsVersion` stays for You › Connected apps |
| `AppModel.setSpendingLimit/setOverLimit/revokeWalletConnection/walletUsage` | `Storage.setSpendingLimit(key, msat)` (≤ 0 or null = none), `setOverLimit`, `deleteWalletConnection`, `walletUsage(key, now)` → `WalletUsage(usedMsat, renewsAt)` |
| `walletRecords(connection:)`, `walletRecordUpdates()` | `walletRecords(connection?)`, `walletRecordUpdates()` (a Flow re-read whenever an invoice, keysend, history row, status or preimage is written) |
| `keysend(tag:)`, `keysend(paymentHash:)`, `keepPreimage` | `keysend(tag)`, `keysendByHash`, `keepPreimage(preimage, tag)` |
| `applyStatus` (a send's COMPLETE/FAILED by tag, also on `keysend`) | `markInvoiceSendStatus(tag, delivered, error)` now moves a keysend by the same forward-only rule |
| `reconcile` (history rows settle or fail a keysend by hash) | `Storage.reconcileKeysends`, run by every history write (`insertPayments`, `Commit.payments`); fees are read from the `payment` table by `walletRecords` (what left beyond the amount, plus the 1-sat ping for an async keysend) |

## Differences from iOS
- **No `UNSETTLED` status** (Android has none for invoices or messages), so "a PENDING leaves a keysend unsettled" is not ported; a keysend is open (`PENDING`) until COMPLETE or FAILED.
- **No `waitingMsat`/`spendableMsat`/`pendingSendTags`/`oldestOpenSend` over keysends**: Android has no such readers over messages and invoices either; they come with the keysend producer (step 5).
- **Amounts are `Long`**: `WalletBudget.used` saturates at `Long.MAX_VALUE` where iOS's `UInt64` sum wraps; `admits` is false past it.
- **Invoice insert rule** in a commit is Android's (`saveInvoice`: a record that is not failed or open is never written over; throws `IllegalStateException`), iOS's is `ON CONFLICT … WHERE status = 'FAILED'`.
- **`WalletRecord.init?(payment:)`** (reading a record back from a scripted wallet's answer) is not ported: no Android wallet adapter exists yet.
- **Wallet reply validation** (`resultProblems`, `notificationProblems`) is not ported (step 5); the record shapes are held to strut's `results`/`notifications` vectors in `WalletModelTest` (a copy of those sections in `src/test/resources/wallet-vectors.json`).

## Tests
`WalletModelTest` (iOS periods and budget tests with more boundaries, the grant, `isExpired`, method flags, the vectors for `payment`/`transaction`/`lookup`, the preimage vectors),
`StorageWalletTest` (18: a connection in full, unknown names left out, order, limit/over-limit, the flows, usage, preimage rules, a send moving by the server's word and the history settling one with fees,
records of keysends and invoices, the budget counted in the payment's own write, a refused commit writing nothing, a repeated keysend, an invoice being paid), `StorageWalletMigrationTest` (v13 → v14 with data; a fresh
database equals a migrated one), `StorageWalletConnectionTest` (adapted), `WalletHostTest` (adapted to the full model).
### UNTESTED
- A real device upgrading from v13 (the test uses Robolectric's SQLite; note it can serve the first read after an upgrade from a connection opened before it, with the old columns — the app's first statement is
  the engine's own settings read, so the wallet is never first).
- The producers: nothing writes a keysend, a tagged invoice or a budget check outside the tests (step 5).
