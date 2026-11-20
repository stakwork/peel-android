# Phase 52 — Spanish (es), following the device locale

Status (2026-11-20): **built in part; compiled, lint-clean and JVM/Robolectric-tested; not run on a device or emulator (none here).**
Onboarding is fully extracted; the lock screen, main tab bar, Chats list and the camera scanners are too. Everything else still
has hard-coded English (list below). Spanish is neutral Latin-American, "tú".

## Decided

- Follow the system locale only: **no in-app language picker.** `res/xml/locales_config.xml` (en, es) is referenced from the
  manifest (`android:localeConfig`) so Android 13+ per-app language settings list Spanish; keep it in step with `values-XX/`.
- User text lives in `res/values/strings.xml` (English, the default) and `res/values-es/strings.xml`. Positional placeholders
  (`%1$d`), `<plurals>` with `one` + `other` for counts, "Peel" and protocol examples (`sphinx.chat://…`, `translatable="false"`) untranslated.
- Terms: invite = invitación, recovery words = palabras de recuperación, PIN = PIN, contact = contacto, tribe = tribu, sats = sats.
- **Pure engine/account code keeps its English messages** (it is JVM-tested and has no `Context`). `ui/ErrorText.kt` maps the
  known messages (`InviteProblem`, `SignupRules.NOT_CONFIRMED`, `Signup.Failed` texts, `ConnectionState.Failed`, restore failure) by
  exact text to a string resource and shows any other message as it came (a server's or the system's words). `ui/lock/LockoutText.kt`
  is `LockoutPolicy.describe` over plurals. If the engine changes one of those English messages, update the key in `ErrorText`.
- Tests: `LocalizationTest` (Robolectric) checks `values/` and `values-es/` have the same keys and placeholders, plurals have
  one/other, and renders a few strings under `@Config(qualifiers = "es")`.

## Still hard-coded English (next)

You (`YouScreen`, `YouOptions`), `ConversationScreen`, payments (`ReceiveSendScreens`, `ChatMoney`, `PaymentsScreen`,
`PaymentDetailScreen`), `ChatDetailsScreen`, `WalletSheets`, `CallScreen`/`CallChatPanel`, invites/tribes/contacts screens,
apps/talk/threads/mentions, diagnostics, `ChatSearchBar`, the dashboard's row actions/undelivered/attachments/control rows,
`BoxWords`, `CallDevices` strings used outside `MainTabs`, `CallController` notices, push notifications (`Notifications`,
`MessageNotifier`), toasts raised in engine code, relative times and other formatting in pure helpers.

## Journal

- 2026-11-20 — first pass. `:app:assembleDebug`, `:app:lintDebug` (0 errors) and `:app:testDebugUnitTest` (1375 tests, 0 failures) ran.
  Lint's `LocalContextGetResourceValueCall` flags `context.getString` in callbacks: use `Context.str` (`ui/Strings.kt`).
