# Su Manager (app module)

Kotlin/Compose root manager app. See the repo root `README.md` for the
project as a whole and `docs/PHASE3_DESIGN.md` for what this module
implements.

## Building

No Gradle wrapper is committed on purpose: this project is developed and
built entirely from a phone (no desktop, no Android Studio), so there is
no local JDK/Android SDK to wrap for. `.github/workflows/manager-build.yml`
builds the debug APK on every push that touches `manager/` or the shared
`kernel/include/su_uapi.h`, using `gradle/actions/setup-gradle` to provide
Gradle itself - no wrapper jar needed. Download the APK from the workflow
run's Artifacts.

If you do have a desktop/CI environment and want to build locally instead:

```
cd manager
gradle :app:assembleDebug   # any Gradle 8.7+ on your PATH
```

## Module layout

| Path | Purpose |
|---|---|
| `app/src/main/java/.../ui/` | Compose screens: main list + status card, grant prompt |
| `app/src/main/java/.../ui/theme/` | Material3 color scheme + typography (AMOLED black + green accent) |
| `app/src/main/java/.../daemon/` | Boot-time foreground service, allowlist repository |
| `app/src/main/java/.../db/` | Room entity/DAO/database for the persistent allowlist |
| `app/src/main/java/.../util/` | `AppInfoResolver` - UID → app label/icon via `PackageManager` |
| `app/src/main/java/.../native/` | `SuNative` - JNI bridge Kotlin surface |
| `app/src/main/cpp/` | `su_native.cpp` - the actual `ioctl()`/`read()` calls against `/dev/su_ctl` |
| `app/src/main/cpp/generated/` | **Not committed.** `su_uapi.h` copied here at build time from `kernel/include/`; see the `syncUapiHeader` task in `app/build.gradle.kts` |
