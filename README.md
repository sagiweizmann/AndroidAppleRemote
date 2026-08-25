# Android Apple Remote Bridge — MVP 0.1

Goal:

`iPhone Control Center Apple TV Remote -> Companion Link -> Android TV 10 -> AccessibilityService`

This repository is the first implementation step, not a finished Companion Link server.

## What works in this MVP

- Android TV / Android 10 compatible app skeleton (`minSdk 29`).
- Foreground service opens a TCP listener.
- Native Android DNS-SD/mDNS advertisement as `_companion-link._tcp`.
- Apple-TV-like Companion TXT records based on `atvr4samsung`.
- Logs the first incoming TCP bytes, so we can prove that the iPhone has discovered and reached the TV.
- Accessibility bridge for Android TV 10.

## What is deliberately not implemented yet

The iPhone will not complete pairing yet. Stage 2 must port Pair-Setup / Pair-Verify, encrypted transport, OPACK framing and HID/button dispatch from `atvr4samsung`.
