# Module auto-load (vendor_dlkm)

Makes `su_core.ko` load itself on every boot, with no daemon, no
init.rc edits, and no boot script of this project's own running at
boot time. This is the loadable-module equivalent of
`tools/boot_patch/` (which handles the *built-in*, `CONFIG_SU_CORE=y`
case) - if your device supports loadable kernel modules at all
(`CONFIG_MODULES=y`, no module-signature enforcement blocking
out-of-tree `.ko`s), this is the simpler path; reach for
`tools/boot_patch/` only if it isn't.

## How it actually works

GKI 2.0 devices (this project's only supported target - see the
top-level `README.md`'s "Project scope") already auto-load vendor
kernel modules on every boot, natively, through `init` itself:
`init`'s first-stage `LoadKernelModules()` reads
`/vendor_dlkm/lib/modules/modules.load` (or `/vendor/lib/modules/
modules.load` on the older combined-partition layout) very early in
boot - before zygote, before `/data` is even necessarily decrypted -
and `insmod`'s every `.ko` listed there, in order.

This script does nothing clever: it copies `su_core.ko` onto that
partition and appends its name to that same `modules.load` file that
every stock vendor module on the device already uses. From then on,
`init` loads it exactly like it loads any other vendor module - no
extra moving part this project has to keep running correctly at boot.

**This is deliberately not a script that calls `insmod` from a later
boot stage.** `kernel/su_boot_trigger.c`'s execve-based trigger (which
launches `sud` to mount module overlays) lives *inside* `su_core.ko`
itself - the module has to already be loaded before anything it hooks
can fire, so nothing this project's own userspace can run (`sud`, the
manager app's `BootReceiver`) is able to be what first loads the
kernel module. Only `init`'s native `modules.load` step, or compiling
`su_core` in as `obj-y` (`tools/boot_patch/`), can do that part.

## Relationship to the manager app's "Auto-start on boot" setting

That setting (Settings screen, backed by
`manager/.../daemon/AutoStartPrefs.kt`) only controls whether the
**manager app's own `SuDaemonService`** (notifications, the Allow/Deny
grant-prompt listener) restarts itself on `BOOT_COMPLETED`. It has no
effect on the kernel module - `su_core.ko` loading is a prerequisite
for that service to do anything useful in the first place, and is
exactly what this script separately makes automatic, one layer below
the manager app entirely.

## Usage

Build `su_core.ko` for your exact device kernel first (see
`../../kernel/`'s own build instructions + the top-level `README.md`'s
"Integration" section), then, **from a root shell on the device
itself** (not your host machine):

```
sh install_vendor_dlkm.sh /path/to/su_core.ko
```

Reboot, then confirm with `lsmod | grep su_core` - it should already
be loaded, with nothing manually `insmod`'d.

See the script's own header comment for the full prerequisites
(unlocked bootloader, a root shell to run it from, a `.ko` built for
the matching `KERNELRELEASE`) and what it deliberately does not do
(doesn't build the module, doesn't survive a factory reset/OTA - the
partition it writes to gets wiped/replaced by both, so re-run it
after either).

## Known gaps

- Not validated against a real device yet - same standing caveat as
  the rest of this project's kernel-adjacent tooling
  (`tools/boot_patch/`'s own README says the same). The split
  `vendor_dlkm`-vs-combined-`vendor` detection and the remount step
  are the two most likely things to need adjusting per-device.
- No uninstall script yet - removing the module from autoload today
  means manually deleting `su_core.ko` from the modules directory and
  its line from `modules.load`, both of which still need the same
  remount-read-write-first dance this script does.
- Doesn't verify the `.ko`'s `vermagic` against the running kernel
  before installing it - a mismatched build still gets copied into
  place and silently fails to load at the next boot (check
  `dmesg | grep su_core` after rebooting if `lsmod` comes up empty).
