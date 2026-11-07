# Su — Universal Root Solution for Android (from scratch)

A kernel-level root (`su`) solution and Magisk-module-compatible mount
system, written from scratch. Not a fork of KernelSU or Magisk — only
public, documented kernel concepts (kprobes, ftrace, OverlayFS) were used
as reference; all code here is original.

## Status

🚧 **Phase 3 (manager app) in progress.** Kernel module skeleton (Phase 2)
and manager app (Phase 3) both exist; neither has been validated on a
real device yet. See `docs/PHASE1_DESIGN.md` for the architecture
decisions and `docs/PHASE3_DESIGN.md` for the manager app's status.

## Project scope

- **Kernel support:** GKI 2.0 only (Linux 5.10+, Android 12+) for now.
- **This repo provides:**
  - `kernel/` — the out-of-tree kernel module source (su hook +
    allowlist + Magisk-module mount support, the latter not yet started).
  - `manager/` — the Kotlin/Compose manager app (JNI bridge to the
    kernel module, Room-backed allowlist, Allow/Deny grant prompt).
- **This repo does NOT provide:** a prebuilt kernel or a flashable image.
  Integrating `kernel/` into your own kernel source tree, building it,
  and flashing the result is up to you. See "Integration" below
  (to be filled in as the module stabilises).
- **CI:** `.github/workflows/manager-build.yml` builds the manager app's
  debug APK on every push to `manager/` (no Gradle wrapper needed - see
  `manager/README.md`).

## Repository layout

| Path | Purpose |
|---|---|
| `kernel/su_main.c` | Module init/exit |
| `kernel/su_hook.c` | Hook framework (kprobe-based) + execve interception |
| `kernel/su_allowlist.c` | Hybrid UID allowlist cache |
| `kernel/su_ioctl.c` | `/dev/su_ctl` character device (manager-app ↔ kernel channel) |
| `kernel/include/su_uapi.h` | ioctl ABI shared with userspace - single source of truth, synced into `manager/` at build time |
| `kernel/include/su_common.h` | Internal kernel-module-only declarations |
| `kernel/Kbuild` | Out-of-tree build file |
| `manager/` | Kotlin/Compose manager app - see `manager/README.md` |
| `docs/PHASE1_DESIGN.md` | Architecture decisions from the design phase |
| `docs/PHASE3_DESIGN.md` | Manager app status and remaining gaps |

## Integration (draft — will be expanded once the hook point is validated)

1. Copy `kernel/` into your Android kernel source tree.
2. Reference it from your tree's build config so it's built as an
   in-tree or out-of-tree module alongside your kernel.
3. `insmod su_core.ko` after boot (or build it into the boot image —
   TBD once Phase 2 is validated on a real device).

## License

Kernel module code links against GPL-exported kernel symbols
(`commit_creds`, etc.) and is licensed **GPL-2.0** accordingly
(`MODULE_LICENSE("GPL")` in `su_main.c`). License for the manager app
(once added) will be decided separately and noted here.

## Disclaimer

Rooting a device can break OTA updates, trip hardware attestation (Play
Integrity / SafetyNet), and void warranties. Use on devices you own and
control. This project does not provide, and will not provide, exploit
code for unpatched vulnerabilities — only a root mechanism for kernels
you build and control yourself.
