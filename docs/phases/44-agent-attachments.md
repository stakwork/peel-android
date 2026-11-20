# Phase 44 — Attachments in the agent's DM

Status: **built; `./gradlew testDebugUnitTest` and `./gradlew assembleDebug` pass (JDK 17, SDK 36, in a hive pod); the Compose UI (PendingAttachments/ConversationScreen wiring), HEIC decode, and a real agent DM against a Mac have never been run on a device/emulator.** Port of peel PR #573 (final state). The Mac does the reading
(Vision OCR, PDFKit, Speech); Android only has to send the attachment correctly. Only the agent's own DM (`chat == engine.agentPubkey`) changes; every other chat sends on pick as before.

## Built
- **Pending files** (`ui/dashboard/PendingAttachments.kt`): in the agent DM, Photo / Camera / File add a chip (thumbnail for a picture, icon + name otherwise, an x) above the composer; the composer's text is the caption, Send is enabled with only pending files. Sent in order, the caption on the first file only; a refused first file leaves the text to go on its own; a refused file stays pending with the error shown and its switch restored. Held per chat (`remember(chat)`), every temp copy (`cacheDir/outgoing/pending-<id>`) dropped on removal, send and leaving.
- **Per-picture switch** `Let the model see this picture` (help text exact), default ON, only under pictures: pure `engine/ModelSight.kt` (state = the set turned off; `offers/allows/set/drop/take/restore`). The stale `docs/agent-on-strut.md` "off by default" is not followed: final PR #573 is on.
- **Metadata** `engine/AttachmentMetadata.kt`: `{"peel":{"v":1,"vision":true}}` merged with the time-zone metadata as one JSON object; dropped over `MessageMetadata.MAX_LENGTH` (64 KB). `Engine.sendAttachment/sendFileAttachment/sendImageAttachment(modelMaySee = false)`; the engine only adds `vision` for the agent's DM and `image/*`.
- **Pictures as JPEG**: `prepareImageBytes` (`ui/components/ImagePreparation.kt`) — first frame, upright, quality 85, no metadata, transparency flattened on white (opt-in, so other chats are unchanged); the agent path allows **4096 px** (other chats stay 2048). A picture from the File picker (heic/png/webp/tiff…) is converted too, named `*.jpg`, `image/jpg`; if it will not decode it goes as it is (peel). PDFs and others are never converted.
- **Size cap**: **32_000_000 bytes** (peel's `Engine.sendLimit = 32_000_000`, decimal, not MiB) — `AgentAttachments.SEND_LIMIT`; checked while copying a pick (refused with "<name> is too large to send. The agent takes files up to 32 MB.", never added) and again in the engine for the agent DM. Other chats keep 100_000_000.
- **Tool/request words**: `read_attachment` / `sphinx_attachment` → "Read an attachment", `asked(line, tool)` returns "" for it, `attachments.read` → "Read an attachment" (the phone never answers it).
- **Note** under the tray when any pending file is not a picture or its switch is off: `The agent reads files on your Mac, so Peel needs to be open there.`
- Voice notes: nothing gated the hold-to-record mic; sent as before (`audio.wav`, no caption, immediately). The 32 MB limit applies in the engine.
- Deleting a sent attachment is the normal message delete. No price, no cache clearing, no second store.

## Differences from iOS / open
- **Threads**: a thread composer now stages pictures like every chat (Phase 08 journal, "Staged photos"), with the agent rules (4096 px, 32 MB, model switch, Mac note) when the chat is the agent DM. Files and voice notes in a thread still send on pick (no `vision`); the agent's DM has no threads in practice.
- A pending file's size is checked on the original before conversion and again after.
- "Add more" is the existing attach (+) menu; the tray does not have its own add button.

## Tests
JVM/Robolectric: `ModelSightTest`, `AttachmentMetadataTest` (merge, both keys, off, drop over 64 KB, vision only agent+image), `AttachmentRulesTest` (32 MB, `jpegName`, Mac note), `ControlPlanTest` (tool titles, `asked`, request words).
### UNTESTED
- Everything in `PendingAttachments.kt` (copy cap, conversion, HEIC decode, thumbnails, tray, sending order, refusal restore), the `ConversationScreen` wiring, `prepareImageBytes`, the engine's use of the limit/metadata (needs the native core); a real agent DM against a Mac.
