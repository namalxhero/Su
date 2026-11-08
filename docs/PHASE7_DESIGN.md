# Phase 7 — Boot Image Patching (for non-loadable-module devices)

Status: **script + safety docs written, not yet run against a real
device.**

## Why this exists

Everything through Phase 6 assumed `kernel/` gets built as a loadable
module (`obj-m`) and `insmod`'d or auto-loaded (Phase 4's boot trigger,
or a vendor `modules.load` entry). That assumption fails completely on
a device where loadable modules aren't viable at all -
`CONFIG_MODULES` disabled in the stock kernel config, or module-loading
signature enforcement that only accepts OEM-signed `.ko` files. This is
exactly the situation this project started from: the 3.18-based Samsung
Exynos kernel (J7 Prime) at the very beginning of this project's history
is a real example of a device class like this.

The only way to get `su_core` onto a device like that is **built-in**
(`obj-y`) - compiled directly into the kernel `Image` itself - which
means getting a whole new kernel Image onto the device's boot partition,
not just loading a module into an already-running stock kernel.

## Scope: this patches the Image, not the module-loading story

This is NOT a reversal of Phase 4's decision to avoid ramdisk-based
*module* installation (OverlayFS still handles `/data/adb/modules` at
runtime exactly as designed). This tool only ever replaces the kernel
`Image` component of a `boot.img` - ramdisk, dtb, and every other
component are carried through byte-for-byte unchanged.

## Why AOSP's mkbootimg, not Magisk's magiskboot

`tools/boot_patch/patch_boot_img.sh` shells out to AOSP's own
`system/tools/mkbootimg` (`unpack_bootimg.py` / `mkbootimg.py`,
Apache-2.0, the same tool OEMs and AOSP itself build boot images with) -
cloned fresh from `android.googlesource.com` rather than vendored or
copied from any other root-manager project. `--format=mkbootimg` on
unpack captures the original header fields (version, cmdline, pagesize,
os version/patch level) so the script never reconstructs or guesses at
boot image header internals itself - a wrong guess there is exactly the
kind of mistake that produces a silently-unbootable image.

## The safety design: `fastboot boot` before `fastboot flash boot`

The script's own output, and `tools/boot_patch/README.md`, both lead
with this before anything else: boot the patched image for one boot via
`fastboot boot` (nothing written to the boot partition - a bad result
just means reboot and you're back on stock) before ever running
`fastboot flash boot` (persistent). This is the entire difference
between "this didn't work, reboot" and "this didn't work, now what" -
see the README for the full recovery flow, including restoring from the
stock backup the script makes automatically.

## Known gaps

- Not run against a real device or a real stock `boot.img` - reviewed,
  not proven, same standing caveat as the rest of this project's kernel
  work. This is precisely why the `fastboot boot`-first flow is
  mandatory guidance, not optional advice.
- Doesn't handle split `vendor_boot`/`boot` layouts (common on newer
  GKI 2.0 devices with newer boot image header versions) - scoped to
  the single combined `boot.img` shape that loadable-module-incapable
  (typically older/non-GKI) devices are more likely to have in the
  first place.
- Doesn't touch AVB/vbmeta verification state - `fastboot
  --disable-verification` (or equivalent) remains a one-time step the
  person does themselves, per their device's own documentation, not
  something this script performs.
- Depends on `--format=mkbootimg` being present in whatever AOSP
  `mkbootimg` revision gets cloned; older checkouts may lack it. The
  script fails loudly rather than falling back to a guessed header.

## Related, not done here: making `obj-y` actually worth it

Choosing built-in over loadable only pays off if the kernel-side code
also stops depending on loadable-module-only techniques. In particular,
`su_hook.c`'s `su_resolve_kallsyms()` trick and `su_mount_isolate.c`'s
`su_lookup_symbol()`-based resolution of `create_new_namespaces` /
`switch_task_namespaces` / `path_umount` exist only because a loadable
module has no other way to reach an unexported symbol - built-in code
compiled into `vmlinux` could reference them directly via `extern`
declarations instead, removing that entire class of runtime-resolution
risk. A `CONFIG_SU_CORE` Kconfig entry (default `y`) and that
kallsyms-to-extern rework are both still open, tracked separately from
this phase rather than bundled in - this phase is the delivery
mechanism (patch + flash), not the kernel-source changes that would
make full built-in mode the clearly-better default.
