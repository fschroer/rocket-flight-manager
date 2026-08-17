# Steam Pigeon — Android app

Part of the **Steam Pigeon** system (Locator + Receiver firmware + this app). The Locator
and Receiver run STM32 firmware; this app is Android / Kotlin / Jetpack Compose. A native
iOS app is planned as a second codebase.

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

## App-specific load-bearing points

- **Wire format is hand-synced across three places** — this app's `app/src/test/.../WireLayoutTest.kt`
  must match the firmware `MessageProtocol.hpp` `static_assert`s (and the planned iOS copy).
  Touch a byte offset in `RocketState.kt` / `FlightDataRepository.kt` → update all copies in
  the same session.
- **Map is MapLibre**, not Google Maps (offline satellite for no-signal recovery). Offline
  regions render from an app-wide MapLibre DB; the tile-provider licensing for release is an
  **open blocker** — see the MapLibre ADR and GitHub issue #26 before shipping.
- **Secrets:** the Mapbox **public** token lives in gitignored `secrets.properties`
  (`BuildConfig.MAPBOX_TOKEN`); never commit a `sk.` secret token. `gradle.properties` is
  tracked — keep it clean. Scan `git diff --cached` for `sk.`/`pk.`/`AIza` before committing.

## Build / run

Toolchains are not on PATH; see the CLI build recipe in memory, or Android Studio.
`C:\STM32_Projects\Locator\Scripts\sp-status.sh` reports commit/push state across all three repos.
