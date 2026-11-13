# Calls in the background — device verification checklist

**Nothing in this change was run on a device or emulator.** It compiles (`:app:compileDebugKotlin`, `:app:assembleDebug`) and the pure
foreground-type selection is unit-tested (`ForegroundTypesTest`, run with the rest of `:app:testDebugUnitTest`); the controller, the
service, the notification, the permission flow and every LiveKit/OS interaction below are unverified. Design: `docs/phases/15-call-background.md`.

Run each on a real phone (a second participant on the call, or Talk to Jamie, to hear audio both ways). Tick and note the Android version.

## Basics
- [ ] **Lock screen:** join an audio call, lock the phone for 5+ minutes. Audio continues both ways; the notification (clock, Mute, Leave) shows on the lock screen.
- [ ] **Home:** press Home, use another app for several minutes. Call continues; tapping the notification returns to the call's chat.
- [ ] **Swipe from Recents:** swipe Peel away while on a call. The call ends, the notification goes, the other side sees you leave.
- [ ] **Rotation:** rotate during a call (call screen and banner). The call does not drop, the call screen stays up, the clock keeps counting.
- [ ] **Relock:** let the PIN timeout lock the app during a call. The call continues; after unlocking the call screen/banner is back.
- [ ] **Leave / Mute from the notification:** Leave ends the call and removes the notification; Mute/Unmute flips the mic and the label, and matches the in-app state.
- [ ] **Forget this account** during a call ends it.

## Permissions
- [ ] **First-time microphone grant:** fresh install, start a call. The microphone dialog comes *before* connecting; allow, then the call connects and the service starts.
- [ ] **Microphone denied:** the call does not start, says why, and Try again asks again (or points to Settings after "don't ask again").
- [ ] **Video call, camera denied:** joins audio-only; the foreground notification is microphone-only.
- [ ] **Deny notifications (Android 13+):** the call still runs and a one-time toast explains; it does not repeat on the next call.
- [ ] **Audio-only vs video:** an audio call's service has only the microphone type (Settings › Apps › Special access, or `adb shell dumpsys activity services com.sphinx.peel`); a video call has microphone|camera.

## Camera
- [ ] **Video call → Home:** your camera light goes off in the background, the others see your tile freeze/off; the microphone keeps working.
- [ ] **Back to the app:** the camera comes back on by itself. If you had turned it off yourself, it stays off.
- [ ] **Turn camera on mid-call (audio call):** the service gains the camera type while Peel is on screen (no crash on Android 14+).

## Failure and recovery
- [ ] **Cellular call:** join on mobile data, lock the screen. Walk through a network change (Wi-Fi ↔ cellular); the call reconnects or, if it gives up while backgrounded, posts a "Call ended" notification and shows the banner when you open Peel.
- [ ] **Network lost (airplane mode) in the background:** same as above.
- [ ] **Process killed:** during a call, `adb shell am kill com.sphinx.peel` (or kill from the developer options). A "Call ended" notification appears when the process restarts and the banner shows on the next open.
- [ ] **Service refused:** start a call, then (if reproducible) block background starts; a red banner explains that the call will not continue in the background, and the call itself keeps going while Peel is open.
- [ ] **Talk to Jamie:** repeat lock screen, Home, swipe from Recents, rotation and the notification's Leave/Mute with Talk.

## Audio routes
- [ ] **Bluetooth headset:** connect before or during a call; audio moves to it, survives screen lock, and the headset's mic is used. Disconnect: back to earpiece/speaker.
- [ ] **Speaker / earpiece picker** still works with the screen off then on.

## Android versions
- [ ] **Android 12 (API 31):** background-start restrictions (`ForegroundServiceStartNotAllowedException` path); notification shown immediately.
- [ ] **Android 13 (API 33):** POST_NOTIFICATIONS flow.
- [ ] **Android 14 (API 34):** foreground-service types need their permissions; camera type only from the foreground.
- [ ] **Android 15 (API 35):** same, plus the changed background-start rules for foreground services; confirm no timeout ends a long (1 h+) call.
- [ ] Wake lock / Wi-Fi lock held during the call and released after (`adb shell dumpsys power | grep peel:call`, `dumpsys wifi`).
