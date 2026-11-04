# Phase 16 — Money

## Status (2026-11-02)

**Built:** the Payments tab (balance, history by day with status and paging,
detail), Receive (invoice, QR, copy, share, live Paid), Send (paste, scan,
pay an invoice; send to a contact), in-chat payments (type 5), payment
requests (type 2) with Pay from the chat, the confirmation threshold and its
setting, boost presets and a confirmation over the threshold, a balance check
on boosts. Full feature list with iOS evidence: `../money-parity.md`.

**Open:** sats waiting for offline people, arrival notifications, templates,
paid attachments, the history reconcile's reach-back and not-arrived verdict.
Nothing here has run against a live server.

**Decided:** BOLT 11 only and no fiat, as the iPhone. QR with ZXing (pure
Java) and CameraX, not ML Kit, so no Play Services (docs/VISION.md).
Unknown balance lets a one-tap boost go (it always has) and refuses every
other payment, where PeelCore refuses both.

## Journal

- Ported `Engine+Payments.swift` as `engine/Payments.kt` (extension
  functions on `Engine`), `Invoice.swift` as `engine/Invoice.kt`, `Sats.swift`
  as `engine/Sats.kt`. `Engine.run` gained `beforePublish` so a payment's
  pending record is written with the state, before anything is published.
- Storage v9: `invoice`, `payment`. `Storage.saveInvoice` refuses to replace a
  pending or paid invoice.
- Answers to `fetchPayments`/`requestInvoice` arrive through the listener
  (`storeMessages` → `storeMoney`); one such request at a time (`moneyLock`).
- Tests: `SatsTest`, `PaymentRulesTest`, `PaymentInputTest`, `InvoiceTest`
  (Robolectric only for `org.json`), `StorageMoneyTest`, `PaymentItemsTest`.
