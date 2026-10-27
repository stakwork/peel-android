# Peel for Android: what it is for

Peel is one product on three platforms. Its guiding principles live in one
place, the `peel` repository's `docs/VISION.md`, and this file does not
repeat them: read that page first. What follows is what changes, and what
doesn't, for the Android build specifically.

## What doesn't change

1. **Privacy is a human right.**
2. **Personal agents will be a powerful helper to humans.**
3. **They must be controlled fully by the single human.**
4. **Security and backups apply the lessons of bitcoin self-custody. Losing
   data or a security fault is not allowed.**

These hold exactly as written for Mac and iPhone. An account's twelve
words, once this build can make or restore one, are exactly as sensitive
on a Pixel as on a Mac, and the PIN-sealing in `account/SecretStore.kt`
exists for the same reason `SecretStore.swift` does.

## What's specific to Android

- **GrapheneOS is a first-class target, not an afterthought.** Peel is a
  privacy-focused client; a device that ships without Google Play Services
  is where it should work best, not where it degrades. Concretely: no
  dependency on Play Services for anything load-bearing (push, maps,
  billing), and the account engine (MQTT, `engine/Transport.kt`) is the
  push mechanism, not FCM. See the chat log that led to this project
  (asked 2026-10-27) for the reasoning.
- **"Parity" means the same capabilities, not the same screens**, exactly
  as `peel`'s PLAN.md says for Mac/iPhone vs. the official apps. Here that
  cuts one level deeper too: Peel for Android doesn't have to look like
  Peel for Mac/iPhone, screen for screen, only give the same capabilities
  in a way that fits a phone. Colours and type so far are taken straight
  from `Apps/Shared/Theme.swift` for a consistent first impression; a
  deliberate redesign is open, not decided against.
