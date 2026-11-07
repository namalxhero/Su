# Phase 5 — Root Visibility Control (hide su from specific apps)

Status: **design + initial skeleton, not yet built/tested.**

## Why not a Magisk/KernelSU-style denylist

Magisk's "DenyList" and KernelSU's denylist are both **opt-out**: every
app sees the rooted state by default, and the user has to manually add
each sensitive app (banking, Play Integrity-checking apps, etc.) to a
list. Forgetting to add a new app before opening it once is a real,
common failure mode - and it only takes one slip for a banking app to
flag the account.

## Decision: invert it — allowlist-based visibility

This project already has an allowlist (`su_allowlist.c`, Phase 1/2) of
UIDs that are granted su. Phase 5 reuses it as the **visibility**
boundary too, instead of building a second independent list:

> An app not in the su allowlist gets a clean (non-rooted-looking) view
> by default. Only apps that have actually been granted su see the real,
> modified system.

This removes the "forgot to hide it" bug class entirely - there's
nothing to remember, because hiding is the default and seeing root is
the opt-in (which already requires an explicit Allow tap, Phase 3).

## Three layers, in increasing order of difficulty/risk

### 1. Mount-namespace isolation (hides Phase 4's module changes)

`kernel/su_mount_isolate.c`. For a task whose UID is not in the
allowlist, give it a private mount namespace with the Phase 4 OverlayFS
mounts undone, so it sees the real, unmodified partitions instead of
module-modified ones.

Deferred via the same `task_work_add()` pattern used for credential
elevation in Phase 2 (mount/namespace operations can sleep; this can't
happen inside the execve kprobe's atomic pre_handler).

**Implemented (not just namespace creation - the overlay is actually
popped):** `su_mount_isolate.c` resolves `create_new_namespaces()` +
`switch_task_namespaces()` to give the task its own mount namespace,
then calls `path_umount()` once per entry in a mirrored copy of
`daemon/sud.c`'s overlay-target list. This works without walking the
mount tree because of how the overlay was applied in the first place:
`sud` mounts each overlay *at* the real mountpoint (e.g. `/system`)
rather than replacing it, so the original mount is still stacked
underneath - popping the top (overlay) entry via a plain `umount`
reveals it. "Undo the overlay" and "unmount once" are the same
operation; see `su_unmount_overlay_targets()`'s comment for the detail.

**Known high risk, called out explicitly:** every function this relies
on (`create_new_namespaces`, `switch_task_namespaces`, `path_umount`) is
an internal, typically **not `EXPORT_SYMBOL`'d** kernel symbol. Resolved
via the same `su_lookup_symbol()` kallsyms trick already used elsewhere
in this codebase (`su_hook.c`), which works only if kallsyms exposes
them and nothing (CFI, a kprobe blacklist, `lockdown`) blocks calling an
unexported symbol pointer this way. `path_umount()` itself is a
post-~5.9 refactor - older trees expose the equivalent as
`do_umount(struct vfsmount *, int)` with a different calling
convention, which this version does not attempt to support (degrades
gracefully: namespace isolation still happens, the overlay just isn't
popped, with a warning logged once at init). This remains the single
most likely piece of Phase 5 to need rework once there's a real device
to test on - written and reviewed, not proven.

### 2. Path-hiding hooks (hides the su binary/daemon/module files themselves)

`kernel/su_hide.c`. Even with mounts isolated, `su_client`,
`/data/adb/sud`, and `/data/adb/modules/` are files on the **real**
partition, not something a mount undo affects. A detection app doing
`stat("/system/bin/su_client")` would still see it.

A kretprobe on `vfs_statx` (covers the `stat`/`lstat`/`fstatat`-family
syscalls) checks the queried path against a kernel-side hidden-path
list; if it matches **and** the calling UID isn't in the allowlist, the
post (return) handler overrides the return value to `-ENOENT` - the app
sees "no such file".

**Why stop at `vfs_statx` for this skeleton:** `open`/`openat` and
`access`/`faccessat` resolve through different internal functions
depending on kernel version (same signature-variance problem Phase 2
hit with `do_execveat_common`), and covering all of them properly needs
the same kind of build-time signature detection Phase 2's Kbuild does
for execve. Doing that for three-plus functions at once was more than
this skeleton could responsibly commit to without a device to validate
against - `vfs_statx` alone covers the single most common detection
check (`stat()` on known root paths) and ships now; `open`/`access`
coverage is tracked as a known gap, not silently skipped.

### 3. Directory-listing consistency (`getdents64` filtering)

Also `kernel/su_hide.c`. Hiding `stat()` alone is not enough: an app can
`opendir("/system/bin")` + `readdir()` and see `su_client` **listed**
even if `stat()`-ing it directly returns ENOENT - a stat/listing
mismatch is itself a known Magisk-detection signal from years of cat-
and-mouse with root-hiding tools.

A kretprobe on the `getdents64` syscall wrapper re-scans the
already-filled user buffer after the real call completes, and splices
out any `linux_dirent64` entry whose name matches a hidden path inside
the directory being listed - so `ls /system/bin` and `stat
/system/bin/su_client` tell the same story.

## What this does NOT cover (explicitly out of scope for Phase 5)

- **Process list / `/proc` visibility** - `sud`'s or any running su
  session's PID is still visible via `ps`/`/proc/<pid>`. Hiding a
  *running process* (not just files) is a different, harder problem
  (would need `/proc` readdir filtering analogous to #3, keyed by which
  PIDs belong to hidden processes) - not attempted here.
- **Kernel module self-hiding** (`/proc/modules`, `lsmod`) - techniques
  exist (unlinking from the module list) but materially increase risk
  (debugging/unloading becomes unreliable) for a benefit outside this
  phase's stated goal (hiding root *from specific apps*, not hiding the
  module's existence from the device owner). Deliberately not done.
- **`ro.debuggable` / `ro.secure` / build-tag property spoofing** -
  these are Android property-service state, not filesystem or process
  state; a kernel module can't intercept `getprop` the way it can a
  syscall. Out of scope - would need an `init`/property-service-level
  component.
- **Timing-based detection** (measuring whether a "file not found"
  response is suspiciously slow vs. a genuine lookup) - a real risk
  against hook-based hiding in general, no credible kernel-side fix
  attempted here beyond noting it.

## Allowlist-check reuse

Every layer above gates on the exact same primitive:
`su_allowlist_check(uid, NULL)`. No second "denylist" array, no second
ioctl command set, no second manager-app UI - visibility and su grant
are the same decision, by design.
