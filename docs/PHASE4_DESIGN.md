# Phase 4 — Magisk Module Support (OverlayFS)

Status: **design + initial skeleton, not yet built/tested.**

## Decision: OverlayFS, not ramdisk/boot.img patching

Three approaches were considered:

1. **Magic Mount** (old Magisk method) - recursive `mount --bind` per
   replaced file. Works, but a mount per file adds up fast with several
   modules installed, and it's harder for root-hiding to conceal.
2. **Ramdisk / boot.img patch** - inject an init script into the boot
   image itself. Rejected: boot.img is device/build-specific, requires
   re-flashing on every kernel update, and a bad patch risks a bootloop
   with no software-only recovery path. Directly conflicts with this
   project's goal #5 (device-agnostic, nothing hardcoded to one phone).
3. **OverlayFS** (what Magisk 20+, KernelSU and APatch all converged on)
   - each enabled module's `system/` tree becomes a single OverlayFS
   lowerdir stacked over the real partition. One mount per overlay
   instead of one per file, the real partition is never touched, and
   `module.prop`-format modules from the existing Magisk ecosystem work
   unmodified.

**Chosen: OverlayFS**, triggered from kernel space via the existing hook
infrastructure (`su_hook.c`) instead of any boot image modification.

## How the trigger works

Reusing the kprobe framework from Phase 2 (`su_register_hook()`) rather
than inventing a second hook mechanism:

1. A new kprobe hooks the kernel's own `/init` process the first time it
   execve's a known late-boot binary (exact symbol TBD per Android
   version - see `kernel/su_boot_trigger.c` TODO). This fires **once**
   per boot; a `static bool fired` guard prevents re-entry.
2. The handler calls `call_usermodehelper(sud_path, argv, envp,
   UMH_WAIT_PROC)` with `argv[1] = "post-fs-data"`. `UMH_WAIT_PROC`
   blocks the calling kernel context until `sud` exits - this is
   intentional and mirrors what Magisk's ramdisk-init-script approach
   achieves, just without touching the ramdisk: module mounts must be
   in place *before* the rest of boot continues, or apps can start
   reading the un-overlaid partition.
3. `sud post-fs-data` does the OverlayFS mounts (see below), then exits.
   The kernel hook returns, boot continues.
4. `sud service` is invoked the same way (no `UMH_WAIT_PROC` needed -
   service scripts don't need to block boot) right after step 3.
5. `boot-completed` is **not** handled by the kernel or `sud` at all -
   the manager app's existing `BootReceiver` (Phase 3) already listens
   for `ACTION_BOOT_COMPLETED`; module `boot-completed.sh` scripts are
   run from there, where full Android services (including the su
   allowlist) are already up.

## `sud` - the userspace daemon

Deliberately **not** part of the Gradle project: it needs to run before
most of Android's userspace is initialized, as a static-ish native
binary invoked directly by the kernel's `call_usermodehelper()` - a JVM
isn't remotely up yet at `post-fs-data`. Lives under `daemon/`, built
standalone (its own Makefile), pushed to `/data/adb/sud` as part of the
manager app's first-run setup (Phase 3's `AllowlistRepository`-adjacent
setup code - not yet wired, tracked below).

| File | Purpose |
|---|---|
| `daemon/sud.c` | Entry point, stage dispatch (`post-fs-data` / `service`) |
| `daemon/module_scan.c` | Walks `/data/adb/modules/*/`, parses `module.prop`, filters out `disable`/`remove`-flagged modules |
| `daemon/overlay_mount.c` | Builds and applies the OverlayFS mount for one module's `system/` dir |
| `daemon/sud.h` | Shared struct/decl header for the three files above |

### Module layout (Magisk-compatible)

```
/data/adb/modules/<id>/
  module.prop       # id=, name=, version=, versionCode=, author=, description=
  system/           # overlay lowerdir - mirrors the real partition layout
  post-fs-data.sh   # run synchronously during post-fs-data, before overlay... 
                     # (see ordering note below)
  service.sh         # run during service stage
  system.prop        # optional resetprop-style overrides (not yet implemented)
  skip_mount          # presence = don't overlay system/, scripts only
  disable / remove    # presence = module skipped entirely
```

**Ordering note:** Magisk actually runs each module's `post-fs-data.sh`
*before* mounting that module's overlay (a script may want to prepare
files the overlay will then expose). `sud post-fs-data` mirrors this:
per enabled module, run `post-fs-data.sh` (if present, with a timeout),
*then* mount its overlay, then move to the next module.

### OverlayFS mount shape

For a mount point `/system` with modules `A` and `B` both touching it
(lowerdir order matters - later modules win on conflicting files, same
as Magisk):

```
mount -t overlay overlay -o lowerdir=/data/adb/modules/B/system/system:\
/data/adb/modules/A/system/system:/system,ro /system
```

(`ro` for now - no upperdir/writable layer yet; see Known gaps.)

`overlay_mount.c` needs to, per real mount point actually touched by at
least one enabled module:
1. Collect every enabled module's matching subdirectory, most-recently
   enabled first (lowerdir order).
2. Append the real partition itself as the lowest layer.
3. `mount(2)` with `MS_RDONLY`, type `"overlay"`, the constructed
   `lowerdir=` option string.

## Known gaps (tracked for later, not blocking this skeleton)

- **No upperdir / writable overlay yet** - modules that need to persist
  runtime writes (rare, but some do) aren't supported. Needs a
  `upperdir=` + `workdir=` on a writable partition (`/data/adb/...`)
  once this is needed.
- **No `skip_mount` / `system.prop` handling** in `module_scan.c` yet -
  parsed but not acted on.
- **Exact late-boot execve target is a placeholder** -
  `kernel/su_boot_trigger.c`'s `SU_BOOT_TRIGGER_PATH` is currently
  `/system/bin/vold`, an unvalidated guess. It's wired in by piggybacking
  on the existing `do_execveat_common` kprobe from Phase 2
  (`su_hook.c`'s pre_handler now also calls `su_boot_trigger_check()`)
  rather than installing a second kprobe on the same function - but the
  actual trigger path/timing needs validating on a real device and will
  likely need the same "pluggable backend" treatment as the execve hook
  did in Phase 2 (different Android/kernel version combos may need
  different trigger points).
- **`sud` is not yet wired into the manager app's first-run setup** -
  something needs to `push`/copy the compiled binary to
  `/data/adb/sud` and make it executable; that's Phase 3/4 glue work,
  not done here.
- **No root-hiding / mount-namespace isolation** - out of scope for
  this phase per the original design doc (explicitly deferred to
  Phase 5: deny-list / root-hide controls).
