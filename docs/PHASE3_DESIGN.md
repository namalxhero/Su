# Phase 3 — Manager App (Kotlin/Compose)

Status: **functionally complete skeleton, not yet validated on a real
device.** All previously-tracked gaps below are implemented; "validated
on device" is the next real milestone, not another round of stubs.

## What exists

| File | Purpose |
|---|---|
| `app/src/main/cpp/su_native.cpp` | JNI bridge: open/ioctl/read on `/dev/su_ctl`. Includes `generated/su_uapi.h` directly - no more hand-duplicated structs. |
| `app/src/main/java/.../native/SuNative.kt` | Kotlin `external fun` declarations for the JNI bridge |
| `app/src/main/java/.../db/` | Room entity (`AllowlistEntry`: uid, package name, label, grant type, timestamp), DAO, database |
| `app/src/main/java/.../util/AppInfoResolver.kt` | UID → app label/icon via `PackageManager#getPackagesForUid` |
| `app/src/main/java/.../daemon/AllowlistRepository.kt` | Room-backed allowlist, pushes grants into the kernel cache via `SuNative`, resolves label/icon at grant time |
| `app/src/main/java/.../daemon/SuDaemonService.kt` | Boot-time foreground service: pushes allowlist to kernel once, then blocking-reads `/dev/su_ctl` in a loop, with exponential backoff if the device node disappears |
| `app/src/main/java/.../daemon/BootReceiver.kt` | Starts the daemon on `BOOT_COMPLETED` |
| `app/src/main/java/.../ui/GrantPromptActivity.kt` | Allow/Deny prompt, now shows the resolved app name + icon instead of a bare UID |
| `app/src/main/java/.../ui/MainActivity.kt` | Compose UI: module status card (connected? API version? allowlist capacity?), granted-apps list with icons, revoke per row |
| `app/src/main/java/.../ui/theme/` | Material3 theme - AMOLED black surfaces, neon-green accent for "granted"/"active", red reserved for deny/revoke |
| `app/build.gradle.kts` (`syncUapiHeader` task) | Copies `kernel/include/su_uapi.h` into `cpp/generated/` before every native build, so the ABI header can never drift from the kernel module's own copy |
| `.github/workflows/manager-build.yml` | Builds the debug APK on every push touching `manager/` or the shared header - no committed Gradle wrapper, Gradle is provisioned by the CI action itself |

## Previously-tracked gaps - now closed

- ~~UIDs shown raw~~ → resolved to app name/icon via `AppInfoResolver` in both the grant prompt and the main list.
- ~~`su_native.cpp` hand-duplicates the ABI~~ → `syncUapiHeader` Gradle task + `#include "su_uapi.h"`.
- ~~`AllowlistRepository` is SharedPreferences-backed~~ → Room, with grant type (permanent/session) and a timestamp per entry.
- ~~No module/device status screen~~ → `StatusCard` in `MainActivity`, wired to `SuNative.getVersionInfo()`.
- ~~Not yet compiled or run, no CI~~ → `.github/workflows/manager-build.yml` assembles the debug APK on push.

## Known gaps (new, tracked as TODOs in the code)

- Still not run on a real device - `/dev/su_ctl` doesn't exist until Phase 2's kernel module is built, loaded, and actually creates it, so none of this has been exercised against a live kernel yet.
- `GrantPromptActivity`'s window needs `SYSTEM_ALERT_WINDOW` or a full-screen-intent notification to reliably appear over other apps on OEM skins - not yet handled, same TODO as before, just not re-solved here.
- No settings/about screen yet.
- Daemon notification uses a stock lock icon (`android.R.drawable.ic_lock_idle_lock`) - needs a real monochrome icon asset.

## Not in scope for Phase 3

- Root-hide / deny-list controls (Phase 5).
- Magisk-module management UI (Phase 4 - the mount system doesn't exist yet to manage).
- Release signing config (Phase 6 - debug builds only for now).
