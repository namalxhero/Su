#!/usr/bin/env bash
# patch_boot_img.sh - swap a stock boot.img's kernel Image for one built
# from this project's kernel/ tree (obj-y / built-in su_core), for
# devices where loadable modules aren't viable (CONFIG_MODULES off,
# signature-enforced module loading, etc. - e.g. the kind of 3.18
# Samsung Exynos tree this project started against).
#
# Uses AOSP's own system/tools/mkbootimg (unpack_bootimg.py /
# mkbootimg.py, Apache-2.0) - the same tool OEMs/AOSP itself use to
# build boot images - NOT Magisk's magiskboot. This script only
# substitutes the kernel Image; the ramdisk, dtb, second-stage, and
# every other boot.img component are carried through byte-for-byte
# unchanged.
#
# WHAT THIS DOES NOT DO:
#   - Does not unlock your bootloader. A locked bootloader will refuse
#     this image outright (AVB/dm-verity chain-of-trust failure) - this
#     tool cannot and does not try to work around that. Unlock first,
#     through your OEM's own official method, or stop here.
#   - Does not touch AVB/vbmeta verification flags. Most unlocked
#     devices still need `fastboot --disable-verification` (or
#     flashing a vbmeta.img with verification disabled) done ONCE,
#     separately, by you, following your device's own instructions -
#     not something this script does on your behalf.
#   - Does not extract your stock boot.img for you. Get that from your
#     device's own firmware/OTA package, or `dd` it off a device you
#     already have root on. A boot.img pulled from the WRONG firmware
#     version (mismatched ramdisk/ABI) is a bootloop waiting to happen
#     - make sure it's actually your device's current boot.img.
#
# USAGE:
#   ./patch_boot_img.sh <stock_boot.img> <new_kernel_Image> <output_dir>
#
# Produces <output_dir>/patched_boot.img. See README.md in this
# directory for the fastboot-boot-before-fastboot-flash safety flow -
# read it before using the output of this script on a real device.

set -euo pipefail

STOCK_BOOT="${1:?usage: patch_boot_img.sh <stock_boot.img> <new_kernel_Image> <output_dir>}"
NEW_KERNEL="${2:?usage: patch_boot_img.sh <stock_boot.img> <new_kernel_Image> <output_dir>}"
OUT_DIR="${3:?usage: patch_boot_img.sh <stock_boot.img> <new_kernel_Image> <output_dir>}"

MKBOOTIMG_DIR="${MKBOOTIMG_DIR:-$(dirname "$0")/mkbootimg}"

if [ ! -f "$STOCK_BOOT" ]; then
	echo "error: stock boot.img not found: $STOCK_BOOT" >&2
	exit 1
fi
if [ ! -f "$NEW_KERNEL" ]; then
	echo "error: new kernel Image not found: $NEW_KERNEL" >&2
	exit 1
fi

if [ ! -d "$MKBOOTIMG_DIR" ]; then
	echo "AOSP mkbootimg tools not found at $MKBOOTIMG_DIR - cloning (Apache-2.0, android.googlesource.com)..."
	git clone -q --depth 1 https://android.googlesource.com/platform/system/tools/mkbootimg "$MKBOOTIMG_DIR"
fi

mkdir -p "$OUT_DIR"
UNPACK_DIR="$OUT_DIR/unpacked"
mkdir -p "$UNPACK_DIR"

echo "== backing up stock boot.img (NEVER skip this - it is your only"
echo "   recovery path if the patched image doesn't boot) =="
cp -n "$STOCK_BOOT" "$OUT_DIR/stock_boot.img.bak"

echo "== unpacking stock boot.img =="
python3 "$MKBOOTIMG_DIR/unpack_bootimg.py" \
	--boot_img "$STOCK_BOOT" \
	--out "$UNPACK_DIR" \
	--format=mkbootimg > "$UNPACK_DIR/repack_args.txt"
# --format=mkbootimg (recent AOSP tool versions): writes a ready-to-use
# mkbootimg.py argument list capturing header version, cmdline,
# pagesize, os version/patch level, etc. so this script never has to
# parse/reconstruct the boot image header itself - CAVEAT: older
# checkouts of this tool may not support --format=mkbootimg; if the
# clone above pulls one that doesn't, this step fails loudly rather
# than silently producing a malformed header (that failure mode - a
# header mismatch producing an unbootable image - is exactly the kind
# of mistake this script is structured to avoid making quietly).

if [ ! -s "$UNPACK_DIR/repack_args.txt" ]; then
	echo "error: unpack_bootimg.py did not produce repack arguments -" >&2
	echo "       this script's AOSP tool version may be too old for" >&2
	echo "       --format=mkbootimg. Not proceeding with a guessed" >&2
	echo "       header rather than risk an unbootable image." >&2
	exit 1
fi

echo "== substituting kernel Image =="
cp "$NEW_KERNEL" "$UNPACK_DIR/kernel"

echo "== repacking patched boot.img =="
# repack_args.txt already names --kernel/--ramdisk/etc. relative to
# $UNPACK_DIR with the original header fields preserved; only the
# kernel file's CONTENTS changed (same filename, just overwritten
# above), and we override just the output path.
# shellcheck disable=SC2046
python3 "$MKBOOTIMG_DIR/mkbootimg.py" \
	$(cat "$UNPACK_DIR/repack_args.txt") \
	--kernel "$UNPACK_DIR/kernel" \
	-o "$OUT_DIR/patched_boot.img"

echo ""
echo "== done: $OUT_DIR/patched_boot.img =="
echo ""
echo "DO NOT run 'fastboot flash boot' yet. First:"
echo "    fastboot boot \"$OUT_DIR/patched_boot.img\""
echo "This boots the patched image WITHOUT writing it to the boot"
echo "partition - if anything is wrong, you just reboot normally and"
echo "you're back on your stock, untouched boot partition. Only once"
echo "that boots and su works as expected should you consider"
echo "'fastboot flash boot \"$OUT_DIR/patched_boot.img\"' to make it"
echo "persistent - see README.md in this directory."
