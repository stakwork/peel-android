# Phase 40 — Control messages of the agent's DM

Status: **built, compiled, unit-tested; UI compiled only, never run on a device.** An agent's control messages (the device channel's `metadata.peel` envelopes) are no longer plain bubbles: they are
a quiet line, a request's one row, or nothing, as on the iPhone. Reply tool rows are drawn above an agent's reply. Control messages are never unread and never notify.

iOS read from `main`: `ControlEnvelope.swift`, `Proposal.swift`, `Storage+Proposals.swift`, `PeelUI/ChatRow.swift` (`rows`, `foldingReads`, `AgentRequest`), `AgentReads.swift`, `Labels.swift` (`isControlLine`, `controlLine`),
`Apps/Shared/AgentToolTitle.swift`, `Apps/iOS/Peel/MessageRows.swift` (`ConversationRow` `.control`, `AgentRequestLine`, `AgentToolLines`), `Storage+Writes.swift`/`Storage+Migrations.swift` v27 (`isControl`),
`Storage+Reads.swift` (`countsAsUnreadSQL`, `conversationsSQL`), `NotificationPolicy.swift`, and the tests `ControlEnvelopeTests`, `ControlRowTests`.

## What iOS does, per kind of message (the iPhone)
- **Control message** (text, not deleted, `metadata.peel` an object with an `op`), in a direct message only (never a tribe: there the same metadata leaves a member's message a message), and not one of yours that failed (which keeps its bubble for Retry/Discard):
  never a bubble. Its line is `"<who>: <text>"` (plain text on one line, cut at 200 characters with `…`); a message with no words has no row.
  - `request`: one row on the newest copy of that request id: `"<Read your unread messages>  ✓"`, or `"<words> · <outcome>"` (`Declined`, `Too late, nothing done`, `Could not do it`, `Not sure it went`, `on your Mac…`, and for a send/approval/calendar change waiting on a person `Waiting for your approval on your Mac`: the phone never answers, decision 9). The outcome comes from this account's own `result` (not a failed send of it) naming the request; unanswered past `not_after` it turns "too late" by itself. Outside the agent chat a request is named for its sender (`Kate: Send a message`) and an unanswered one says `Not answered`.
  - `ack`, `result`, `cancel` (yours, or in the agent chat anyone's) and older copies of a request: no row. Anyone else's in a plain DM stay as their line. `status` is its line.
  - In the agent chat, reads asked back to back (≤ 2 min apart, one day, done or unanswered) fold into the first as one line: `Checked unread messages, 2 chats, threads and a search`.
  - An envelope this Peel cannot read (other version/op, fields that do not fit) is `unsupported`: still a line, never acted on.
  - Control messages are never unread (`isControl`, v27), never notify, never a quote or a thread row. A chat's preview/ordering still counts them (`conversationsSQL` has no `isControl` term), so the dashboard preview is unchanged.
- **Agent's reply** (agent chat, not yours): no quote; its tool rows above it, one quiet line each `"<title> · <what it was asked> · failed"` (the bridge's own `sphinx…` tools hidden, their requests have rows); its `metadata.peel.artifacts` under it (step 3).
- **Job** (`metadata.peel.job`): parsed by iOS into `ChatRow.agentJob` but **drawn by the Mac only** (`AgentViews.swift` `AgentJobLine`); the iPhone's `MessageRows` does not draw it.
- **Proposals**: Mac only (below).

## Built
- **`engine/ControlEnvelope.kt`** (pure): `ControlEnvelope` (strict decode as Codable: wrong type → unsupported; `v`, `op`, completeness per op), `ControlMessage.read` (the same cheap pre-filter, `peel` with an `op`), `MessageMetadata` (64 KB, hostile nesting caught), `ControlMessages` (`control`, `isControlLine`, `line`), `AgentToolLine`, `AgentJob`, `AgentExtras.tools/job` (talk extras only, no `op`).
- **`engine/AgentChat.kt`** (pure): `AgentRequest` (words, outcome, `outcomeElsewhere`, expiry), `AgentReads` (kinds, steps, summary, waiting), `AgentToolTitle` (`of`, `asked`, `summary`), `ControlRow`, `ControlPlan.of(messages, inTribe, agentDM, sender)` (which messages are a line, a request row, a fold of reads, or hidden).
- **Storage v15**: `message.isControl` (iOS v27), set by `insertMessages` (not in a tribe), backfilled by the migration; `conversations()` unread count skips it. `MessageNotifier` skips a control message (iOS `NotificationPolicy`). "New messages" never starts at a control line (`newMessagesIndex(isControl)`).
- **UI** `ui/dashboard/ControlRows.kt` (`ControlRowView`, `AgentRequestLine`, `AgentToolLines`) used by `ConversationScreen`: control rows in place of bubbles, tool lines above an agent's reply, no quote on the agent's replies or of a control line, runs broken around a control row.

## Differences from iOS
- Not ported, Mac-only: proposals (`Proposal.swift`, `Storage+Proposals.swift`, `ProposalWatch`, `strut.proposals` cards, approve/decline). Evidence: `AppModel.swift:1594` opens the proposal watch only `if agentHost != nil` (the Mac's `MacAgent`); its `readLocal` is installed by the Mac; `Apps/iOS` has no proposal view and `MessageRows` never draws `strutProposals`; `AGENTS.md`: "opened on the Mac only". So there is no proposal table, no approve/decline and no outgoing format to port; the phone never sends a `result` (`Engine+DeviceRequests` runs only when the host gives a handler, `Platform.deviceRequests`; decision 9).
- The job line is parsed, not drawn (the iPhone does not draw it either).
- `AgentDM.unanswered` ("writing…") is not ported (Mac agent chat UI).
- A read folded into an earlier row is not found by "jump to message" (iOS `holds`); `startsNew` never breaks a fold (a control row is never unread, so it cannot start "New messages" on iOS either).
- iOS also drops non-control blank rows and groups thread rows around control lines; Android's thread sheet still lists a control message that carries a `threadUuid` as text (its own `isThreadContent` has no tribe context).
- The row's time and quoted-reply plumbing differ only as Android's list does.

## Tests
JVM/Robolectric: `ControlEnvelopeTest` (10: iOS `ControlEnvelopeTests`' unreadable/other/malformed/escaped/only-text/hostile tables, tool rows, job, line), `ControlPlanTest` (11: `ControlRowTests`' quiet line, tribe, blank/long, failed own, plain DM contacts, agent DM requests, answered/denied/automation, reads folding and its breaks, tool titles), `StorageControlTest` (2: unread skips control in a DM but not a tribe; v14 → v15 backfill), `StorageWalletMigrationTest` and `StorageInviteTest` (their old-database fixtures get the tables v15 reads; version 15). Full suite: 1051 tests.
### UNTESTED (needs a device)
- The composables: `ControlRowView`, `AgentRequestLine` (the expiry redraw), `AgentToolLines`, and `ConversationScreen`'s runs/quotes around control rows; a real agent's DM; `MessageNotifier` skipping a control message (one `if`, no test).
