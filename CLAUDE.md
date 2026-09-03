# Steam Pigeon — Android app

Part of the **Steam Pigeon** system (Locator + Receiver firmware + this app). The Locator
and Receiver run STM32 firmware; this app is Android / Kotlin / Jetpack Compose. A native
iOS app is under way as a second codebase (`steam-pigeon-ios`).

## System docs live in the Locator repo — read them first

All cross-system documentation is centralized at **`C:\STM32_Projects\Locator\docs\`**
(this repo carries only this pointer). Before non-trivial work, read:

1. **`C:\STM32_Projects\Locator\docs\SESSION_HANDOFF.md`** — the "resume here" map.
2. **`C:\STM32_Projects\Locator\docs\adr\README.md`** — the ADR index. Reference ADRs by
   **title, not number**. App-relevant ones include the MapLibre offline-maps decision, the
   iOS port + platform-parity decision, the BLE connection-health probe, and the locator
   connect-password.
3. **`C:\STM32_Projects\Locator\docs\SteamPigeon_SystemSummary.md`** §3.7 / §4.3 / §4.4
   (the Android⇄iOS parity matrix).

(Absolute paths assume the standard local layout; on a fresh clone elsewhere, open the
Locator repo to read its `docs/`.)

## The `/sp-*` commands

`.claude/commands/` here holds **pointers only**. The definitions live in
`C:\STM32_Projects\Locator\.claude\commands\` and must not be copied — they encode
rules the project learned the hard way, and two copies that can drift apart is the
problem the pointers exist to avoid. `sp-commit` and `sp-handoff` act on the Locator
repo's own files and should be run from there.

**`sp-docs` Gate 2 covers `app/src/main/res/values/strings.xml`.** A string is a claim
about the hardware, and changing behaviour under one silently makes it a lie — three
shipped that way in a single change before the gate said so.

**`sp-docs` Gate 3 covers `steam-pigeon-ios/docs/UI_PARITY.md`** — the one doc that does
NOT live in `Locator\docs\`, and so the one Gates 1 and 2 never look at. A change that
makes the two apps differ is recorded there or it is invisible: that file's rule is that
silence reads as parity. A whole new Android screen went unrecorded on 2026-08-31 while
both other gates passed honestly. `Scripts/sp-status.sh` now prints how many app commits
have touched `ui/` since that file last changed.

## App-specific load-bearing points

- **Wire format is hand-synced across three places** — this app's `app/src/test/.../WireLayoutTest.kt`
  must match the firmware `MessageProtocol.hpp` `static_assert`s (and the iOS copy, `WireLayoutTests.swift`).
  Touch a byte offset in `RocketState.kt` / `FlightDataRepository.kt` → update all copies in
  the same session.
- **Map is MapLibre**, not Google Maps (offline satellite for no-signal recovery). Offline
  regions render from an app-wide MapLibre DB; the tile-provider licensing for release is an
  **open blocker** — see the MapLibre ADR and GitHub issue #26 before shipping.
- **Secrets:** the Mapbox **public** token lives in gitignored `secrets.properties`
  (`BuildConfig.MAPBOX_TOKEN`); never commit a `sk.` secret token. `gradle.properties` is
  tracked — keep it clean. Scan `git diff --cached` for `sk.`/`pk.`/`AIza` before committing.

## The pre-commit hook

`.githooks/pre-commit` runs `:app:testDebugUnitTest` before any commit that stages a
`.kt`/`.java`/`.gradle.kts` change, and refuses the commit if the suite is red. It is
tracked rather than living in `.git/hooks/`, so **each clone installs it once**:

```
git config core.hooksPath .githooks
```

It skips instantly for docs-only commits — a hook that taxes every commit gets bypassed
by reflex. `git commit --no-verify` is the deliberate escape.

**A green hook is not "verified".** Nothing in the suite constructs `RocketViewModel`, so
an init-order fault that kills the app on launch passes it cleanly — that is exactly how
`069986f` shipped. ViewModel construction still needs an install-and-launch on the Pixel.

## Build / run

Toolchains are not on PATH; see the CLI build recipe in memory, or Android Studio.
`C:\STM32_Projects\Locator\Scripts\sp-status.sh` reports commit/push state across all three repos.
