# Boot image patcher

For devices where a loadable kernel module (`obj-m`) isn't viable -
`CONFIG_MODULES` disabled, module-loading signature enforcement, or any
other reason the stock kernel won't `insmod` an out-of-tree module. The
only way to get `su_core` onto a device like that is to build it
**built-in** (`obj-y`) into a full kernel Image, then get that Image
onto the device's actual boot partition.

This tool does the second half: swap a stock `boot.img`'s kernel Image
for your freshly-built one, carrying the ramdisk/dtb/everything else
through unchanged. It does **not** build the kernel itself (see
`../../kernel/`'s own build instructions) and does **not** unlock your
bootloader, extract your stock `boot.img` for you, or touch AVB/vbmeta
verification - all three are prerequisites you handle yourself, through
your device's own official process, before this is useful.

## Before you touch any of this

- **Bootloader must already be unlocked.** A locked one will reject
  this image outright - that's what locked means. This tool cannot and
  does not attempt to bypass that.
- **Back up your stock `boot.img`.** The script does this automatically
  (`stock_boot.img.bak` next to its output) - but also keep a copy
  somewhere else. It is your only way back if a patched image doesn't
  boot.
- **Get the right stock `boot.img`** - from your device's current
  firmware/OTA, not a different model or a different Android version's.
  A mismatched ramdisk/ABI is a bootloop risk this tool cannot detect
  for you.

## Usage

```
./patch_boot_img.sh <stock_boot.img> <new_kernel_Image> <output_dir>
```

`<new_kernel_Image>` is the `Image` (or `Image.gz`, matching whatever
your stock boot.img itself used - check `repack_args.txt` after
unpacking if unsure) your own kernel build produced, with
`CONFIG_SU_CORE=y` (built-in) set.

## The safety order: `fastboot boot` BEFORE `fastboot flash boot`

This is the entire reason a bad boot.img patch doesn't have to mean a
bricked device:

1. **`fastboot boot patched_boot.img`** - boots the patched image for
   this ONE boot only. Nothing is written to the boot partition. If it
   doesn't boot, works wrong, or you just want to go back: reboot the
   device normally (or hold the reboot-to-bootloader combo if it's
   stuck) and you're back on your **untouched, still-stock** boot
   partition, same as before you ran anything here.
2. Only once step 1 has actually booted and you've confirmed `su`
   requests work as expected, consider
   **`fastboot flash boot patched_boot.img`** - this one DOES write to
   the boot partition, persistently, surviving reboots.
3. If you ever need to go back after flashing: `fastboot flash boot
   stock_boot.img.bak` (the backup the script made) restores stock.

Skipping straight to step 2 is the single biggest way this kind of
patching goes wrong on a device you can't easily recover. There's no
good reason to skip step 1 - it costs one extra reboot and catches
nearly every kind of mistake (wrong kernel config, wrong stock image,
wrong device) before it's written anywhere permanent.

## Known gaps

- `--format=mkbootimg` (used to capture the original header fields
  for repacking) needs a reasonably recent AOSP `mkbootimg` checkout;
  an older one may not support it - the script fails loudly rather than
  guessing at header fields if so (see its own comments).
- Only tested in review, not against a real device/boot.img - same
  standing caveat as the rest of this project's kernel work. The
  `fastboot boot`-first flow above exists precisely because of that.
- Vendor boot image formats that split `vendor_boot`/`boot` (common on
  newer Android/GKI 2.0 devices) aren't specifically handled - this
  assumes a single combined `boot.img` containing the kernel, which is
  the shape loadable-module-incapable (typically older/non-GKI)
  devices are more likely to actually have.

## `inject_ramdisk_autoload.sh` - the loadable-module case, patched into boot.img instead of vendor_dlkm

This directory's other script, `patch_boot_img.sh` above, is for the
**built-in** (`CONFIG_SU_CORE=y`) case. If your device *does* support
loadable modules, `../module_autoload/`'s `install_vendor_dlkm.sh` is
the simpler option - no boot.img patching or (re)flashing at all. Use
`inject_ramdisk_autoload.sh` instead only when that doesn't fit (a
vendor_dlkm layout that doesn't cooperate, or you'd rather have the
autoload baked into a boot.img you flash once). See the script's own
header comment for the full "when to use this instead" reasoning - not
repeated twice here.

It embeds `su_core.ko` directly into the boot image's own ramdisk and
adds an `init.su_core.rc` that calls init's own built-in `insmod`
command at `early-init` - no vendor partition write, no userspace
daemon or boot script of this project's own needs to run at boot for
it. Same `fastboot boot` BEFORE `fastboot flash boot` safety order as
`patch_boot_img.sh` above applies unchanged; it shares that script's
backup/unpack/repack plumbing and produces the same `patched_boot.img`
output name.

```
./inject_ramdisk_autoload.sh <stock_boot.img> <su_core.ko> <output_dir>
```

Additional known gaps specific to this script (on top of the ones
above, which both scripts share):

- Ramdisk compression is auto-detected for gzip and lz4 (both legacy
  and modern LZ4 frame magic) only - anything else (e.g. a raw/
  uncompressed ramdisk, or zstd) isn't handled and the script fails
  loudly rather than guessing.
- Needs `cpio` on the machine running it (not part of this repo's
  other dependencies) - install it if missing, the script checks and
  says so rather than failing deep into the unpack with a confusing
  error.
- Same split `vendor_boot`/`boot` caveat as `patch_boot_img.sh` above:
  only a single combined ramdisk is handled right now.
- Assumes the ramdisk has a top-level `/init.rc` it can append an
  `import` line to - true for every AOSP-derived ramdisk this was
  written against, but not verified against a real OEM ramdisk yet.
