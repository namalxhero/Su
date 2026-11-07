# Phase 3 — Manager App (Kotlin/Compose) Skeleton

Status: **skeleton written, not yet built/tested.**

## What exists

| File | Purpose |
|---|---|
| `app/src/main/cpp/su_native.cpp` | JNI bridge: open/ioctl/read on `/dev/su_ctl`, mirrors `kernel/include/su_uapi.h` |
| `app/src/main/java/.../native/SuNative.kt` | Kotlin `external fun` declarations for the JNI bridge |
| `app/src/main/java/.../daemon/AllowlistRepository.kt` | Persistent allowlist storage (SharedPreferences for now) + pushes grants into the kernel cache via `SuNative` |
| `app/src/main/java/.../daemon/SuDaemonService.kt` | Boot-time foreground service: pushes allowlist to kernel once, then blocking-reads `/dev/su_ctl` in a loop for unknown-UID events |
| `app/src/main/java/.../daemon/BootReceiver.kt` | Starts the daemon on `BOOT_COMPLETED` |
| `app/src/main/java/.../ui/GrantPromptActivity.kt` | The Allow/Deny prompt (Phase 1's manual-grant flow) |
| `app/src/main/java/.../ui/MainActivity.kt` | Compose UI: lists granted UIDs, lets you revoke, manual daemon-start button for testing |

## Known gaps (tracked as TODOs in the code, listed here for visibility)

- UIDs are shown raw in the UI/prompt - not resolved to app name/icon via `PackageManager`. Cosmetic but important before this is usable.
- `su_native.cpp` hand-duplicates the `su_uid_entry`/`su_version_info` structs and ioctl numbers from `kernel/include/su_uapi.h` instead of sharing the header directly. Needs a build-time sync step or a shared-header location before this drifts.
- `AllowlistRepository` is SharedPreferences-backed; fine for Phase 3 but will need Room/SQLite once grants carry more state (timestamps, session-vs-permanent, Phase 5's deny-list flags).
- No module/device status screen yet (hook installed? kernel API version?) - `SuNative.getVersionInfo()` already exists for this, just not wired into any UI.
- Not yet compiled or run - no CI, no local Android Studio build attempted. First real validation step for this phase.

## Not in scope for Phase 3

- Root-hide / deny-list controls (Phase 5).
- Magisk-module management UI (Phase 4 - the mount system doesn't exist yet to manage).
- Signing/CI pipeline (Phase 6).
