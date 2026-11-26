# Phase 47 — The twelve words can wait

Status (2026-11-19): **built; JVM tests written (`AccountTest`); not run on a device or emulator.** Later the same day,
onboarding stopped showing the words at all (Update below).

## Why

A new account's last onboarding step showed the twelve words, then asked three of them back before Peel opened, with no way past.
Many people signing up (a worker from an invite, phase 41) don't know what recovery words are yet, and stall there. Evan,
2026-11-19: the 3-word test has to be skippable; the words can be backed up later.

## Built

- **Onboarding** (`OnboardingScreen.kt`): "Write down your twelve words" has "Back up later" under "I've written them down", and a
  line saying where to find them again; "Check what you wrote" has "Skip for now" under "Open Peel". Both open Peel the way a passed
  check does (`openPeel`: an unconfirmed signup resumes, then `onAccountReady`), leaving `Setting.WORDS_CONFIRMED` at the "0"
  `Signup` wrote.
- **You › Twelve words** (`YouScreen.kt`), iOS `WordsScreen`'s branch phase 11 trimmed: the row says "Not checked yet"; the screen
  says "Not checked against your paper yet." with "Check words" (the PIN, then three words asked back, `WordsStage.CHECK`) above
  "Show words". A passed check writes "1"; leaving the check, or Peel leaving the foreground, drops the words.
- **One check** (`Account.wrongPlaces`, `wrongPlacesProblem`, `wordsChecked`): both screens compare the same way and say the same
  "Words 4, 12 don't match.". Only "0" reads as not checked: an account restored before restores wrote "1" has no value, and typed
  all twelve.

## Update (2026-11-19): no words at signup

Evan: a new account doesn't need the twelve words at all for now; revisit once an account holds enough to lose (its app balance
past some amount). Onboarding's "Write down your twelve words" and "Check what you wrote" steps are gone: a new account is invite,
name, PIN (3 steps), then Peel opens (`OnboardingScreen.kt`: an unconfirmed signup resumes, then `onAccountReady`, as
`openPeel` did). `Signup.create` returns the `Engine` (`Signup.Created` held the words for that screen only); an unconfirmed
register still says `SignupRules.NOT_CONFIRMED`, on the connection line instead of the words screen. You › Twelve words is
unchanged: "Not checked yet", Check words, Show words. Restore is unchanged.

## Not built

- No reminder beyond the You row (iOS has none either), and none tied to the balance yet.
- iPhone and Mac onboarding still show the words and require the check (`peel`'s `Onboarding.swift`, `OnboardingView.swift`).

## UNTESTED (needs a device)

- A real signup going straight from the PIN to Peel, the row and the check in You, and the check after the app is backgrounded
  mid-way.

## Update (2026-11-26): Back up account

A QA pass (SQA-01) found no clear backup after signup: the row was the only sign. Now, until three words are typed back
(`WORDS_CONFIRMED` "1"), You opens with a notice (what is at stake, "Back up now"), the row is "Back up account" with
"Not backed up", and the You tab has an accent "!" badge (`MainTabs.BottomBar`, read again on `Storage.settingsVersion`,
which moves on any commit that writes a setting). The screen explains the words in four points, then "Back up now": the PIN,
the words, "I've written them down", three typed back; "Show words again" from the check. Passing removes the notice, the
status and the badge; there is no Dismiss. "Check words" (straight to the check) is gone: the backup always shows the words
first. iOS does the same (peel, `YouTab.swift`), and its signup no longer shows the words either.

Not run: the screens on a device or emulator; no Android SDK on the Mac it was written on, so CI's unit tests, lint and
assembleDebug are the first build.
