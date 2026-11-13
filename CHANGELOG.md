# Changelog

## 1.1.0 — 2026-11-13
- **Debugging Service** (new): `ServiceDiagnosis.kt` runs 26 checks (storage, memory, battery, network, display, security, etc.) in one ADB pass, scores 0-100, maps each failing check to a fixId. Exposed as `runServiceDiagnosis` → `DebuggingServiceScreen` with diagnose → one-tap fix → re-diagnose verify loop.
- **Terminal fixing side**: `TerminalCommands` now has `fixCatalog` (62 entries) separate from `catalog` (77). `resolve()` vs `resolveFix()` guarantees read can never mutate. Fix commands carry `risk`/`isFix`, require confirmation, are history-logged. Groups: Network(12), Storage(5), Power(4), Display(10), System(9), Apps(15), Audio(3), Restart(3).
- **RepairServices**: `44 → 68` services — mirrors for `fix-app-*` (uninstall/restore/updates-remove/compile/settings/standby/bg-allow) plus professional extras: `fix-nfc`, `fix-hotspot-off`, `fix-data-roaming-on`, `fix-location-on`, `fix-volume-reset`, `fix-mute-off`, `fix-vibrate-on`, `fix-doze-off`, `fix-launcher-reset`, `fix-unknown-sources-off`, `fix-adb-wifi-off`, `fix-dark-mode-off/on`, `fix-night-light-off`, `fix-screen-lock-timeout`, `fix-slow-phone`, `fix-notifications`. All routed to `runAppService` vs `runDeviceService` correctly.
- **Build fix**: `MainActivity.Reply.error` 2-arg correction, moved 7 app-targeted fixes to correct handler (fixes 8x `Unresolved reference 'target'`).

## 1.0.0 — 2026-11-12
- Initial workstation: Connection (USB/Wi-Fi), Diagnostics, Repair Tools (34), Hardware Check, App Workbench, Bootloader, Reset & Wipe, Update from ADB, Apps, File Manager, Security Center, Command Terminal (77 read), Repair History.
