# Phase 47 — The twelve words can wait

Status (2026-11-19): **built; JVM tests written (`AccountTest`); not run on a device or emulator.**

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

## Not built

- No reminder beyond the You row (iOS has none either).
- iPhone and Mac onboarding still require the check (`peel`'s `Onboarding.swift`, `OnboardingView.swift`).

## UNTESTED (needs a device)

- The two buttons on a real signup, the row and the check in You, and the check after the app is backgrounded mid-way.
