#!/usr/bin/env bash
# inject_ramdisk_autoload.sh - alternative to ../module_autoload/'s
# vendor_dlkm approach: embeds su_core.ko directly in the boot.img's
# own ramdisk and adds an init script that `insmod`'s it at
# early-init, via a PATCHED BOOT IMAGE rather than writing anything to
# a vendor partition at runtime.
#
# WHEN TO USE THIS INSTEAD OF ../module_autoload/:
#   - Devices with a combined/non-split vendor layout, or any vendor_dlkm
#     oddity that makes modules.load unreliable.
#   - You'd rather the autoload be baked into a boot.img you flash once
#     (survives factory reset the same way a flashed boot partition
#     does) than something installed into a live, already-booted
#     device's vendor partition (which module_autoload/'s script does,
#     and which a factory reset or OTA wipes).
#   - You're already using ../patch_boot_img.sh for the built-in
#     (CONFIG_SU_CORE=y) case on a DIFFERENT device and want one
#     consistent "patch a boot.img" workflow instead of two different
#     install mechanisms across your devices.
# Prefer ../module_autoload/'s script when it applies - it's simpler,
# needs no boot.img patching/flashing, and is the native mechanism the
# device already uses for every other vendor module. This script exists
# for the devices/cases where that one doesn't fit.
#
# WHAT THIS SCRIPT DOES:
#   1. Unpacks a stock boot.img (same AOSP mkbootimg tooling as
#      ../patch_boot_img.sh - shares its clone of that tool via
#      MKBOOTIMG_DIR).
#   2. Decompresses the ramdisk cpio archive (format auto-detected -
#      gzip or lz4, the two this project has seen in practice; see
#      "Known gaps" if yours is neither).
#   3. Copies su_core.ko into the ramdisk root as /su_core.ko, and adds
#      /init.su_core.rc:
#          on early-init
#              insmod /su_core.ko
#      `insmod` is one of Android init's own built-in commands (see
#      AOSP system/core/init/builtins.cpp) - no shell, no busybox, no
#      userspace helper needed; it runs as part of init itself, before
#      almost everything else.
#   4. Re-imports that new .rc file from the ramdisk's own /init.rc
#      (appends one `import` line - see step 4's own comment below for
#      exactly how/why).
#   5. Recompresses the ramdisk with the SAME algorithm it came in
#      with (mismatching this is a silent bootloop - init can't even
#      start decompressing its own ramdisk).
#   6. Repacks boot.img the same way ../patch_boot_img.sh does -
#      reusing unpack_bootimg.py's captured header/repack arguments so
#      only the ramdisk's contents actually changed.
#
# WHAT THIS SCRIPT DOES NOT DO: same exclusions as ../patch_boot_img.sh
# - doesn't unlock your bootloader, doesn't touch AVB/vbmeta, doesn't
# extract your stock boot.img for you. Read ../patch_boot_img.sh's own
# header and this directory's README.md before using this on a real
# device - the same `fastboot boot` BEFORE `fastboot flash boot` safety
# order applies here, unchanged.
#
# USAGE:
#   ./inject_ramdisk_autoload.sh <stock_boot.img> <su_core.ko> <output_dir>
#
# Produces <output_dir>/patched_boot.img - same output filename as
# ../patch_boot_img.sh, deliberately: it's a drop-in alternative output
# for the same `fastboot boot` / `fastboot flash boot` flow, not a
# separate artifact type to document twice.

set -euo pipefail

STOCK_BOOT="${1:?usage: inject_ramdisk_autoload.sh <stock_boot.img> <su_core.ko> <output_dir>}"
SU_CORE_KO="${2:?usage: inject_ramdisk_autoload.sh <stock_boot.img> <su_core.ko> <output_dir>}"
OUT_DIR="${3:?usage: inject_ramdisk_autoload.sh <stock_boot.img> <su_core.ko> <output_dir>}"

MKBOOTIMG_DIR="${MKBOOTIMG_DIR:-$(dirname "$0")/mkbootimg}"

if [ ! -f "$STOCK_BOOT" ]; then
	echo "error: stock boot.img not found: $STOCK_BOOT" >&2
	exit 1
fi
if [ ! -f "$SU_CORE_KO" ]; then
	echo "error: su_core.ko not found: $SU_CORE_KO" >&2
	exit 1
fi
if ! command -v cpio >/dev/null 2>&1; then
	echo "error: 'cpio' not found - install it (e.g. apt install cpio /" >&2
	echo "       brew install cpio) and re-run." >&2
	exit 1
fi

if [ ! -d "$MKBOOTIMG_DIR" ]; then
	echo "AOSP mkbootimg tools not found at $MKBOOTIMG_DIR - cloning (Apache-2.0, android.googlesource.com)..."
	git clone -q --depth 1 https://android.googlesource.com/platform/system/tools/mkbootimg "$MKBOOTIMG_DIR"
fi

mkdir -p "$OUT_DIR"
UNPACK_DIR="$OUT_DIR/unpacked"
mkdir -p "$UNPACK_DIR"

echo "== backing up stock boot.img (NEVER skip this) =="
cp -n "$STOCK_BOOT" "$OUT_DIR/stock_boot.img.bak"

echo "== unpacking stock boot.img =="
python3 "$MKBOOTIMG_DIR/unpack_bootimg.py" \
	--boot_img "$STOCK_BOOT" \
	--out "$UNPACK_DIR" \
	--format=mkbootimg > "$UNPACK_DIR/repack_args.txt"
# See ../patch_boot_img.sh's own comment on --format=mkbootimg for why
# this is required (not optional) and what an older tool checkout does
# instead (fails loudly here too, same check, same reasoning).
if [ ! -s "$UNPACK_DIR/repack_args.txt" ]; then
	echo "error: unpack_bootimg.py did not produce repack arguments - see" >&2
	echo "       ../patch_boot_img.sh's own comment on --format=mkbootimg" >&2
	echo "       for why this script won't guess a header instead." >&2
	exit 1
fi

RAMDISK_SRC="$UNPACK_DIR/ramdisk"
if [ ! -f "$RAMDISK_SRC" ]; then
	# Vendor_boot/boot split devices (common on newer GKI 2.0 devices -
	# same caveat ../patch_boot_img.sh's README already flags under its
	# own Known gaps) may unpack as ramdisk00/vendor_ramdisk00 instead
	# of a single `ramdisk` file. Not guessing which one is "the" one
	# to patch on a split layout - that needs a real device to confirm
	# against, same standing caveat as the rest of this project's
	# kernel-adjacent tooling.
	echo "error: $RAMDISK_SRC not found after unpack - this looks like a" >&2
	echo "       split vendor_boot/boot image layout, which this script" >&2
	echo "       does not yet handle (see this script's own 'Known gaps'" >&2
	echo "       in README.md). Only a single combined ramdisk is" >&2
	echo "       supported right now." >&2
	exit 1
fi

echo "== detecting ramdisk compression format =="
RAMDISK_MAGIC=$(head -c4 "$RAMDISK_SRC" | od -An -tx1 | tr -d ' ')
case "$RAMDISK_MAGIC" in
	1f8b*)
		RAMDISK_FORMAT=gzip
		;;
	02214c18|04224d18)
		# 02214c18: LZ4 legacy frame magic (0x184C2102, as stored
		# little-endian) - what AOSP's own mkbootfs/repack tooling
		# actually produces for ramdisks (hence this script's own
		# recompress step using `lz4 -l`, below).
		# 04224d18: modern LZ4 frame magic (0x184D2204) - handled too
		# in case a given device/toolchain's ramdisk uses it instead;
		# `lz4 -dc` decodes either transparently.
		RAMDISK_FORMAT=lz4
		;;
	*)
		echo "error: unrecognised ramdisk compression (magic: $RAMDISK_MAGIC)" >&2
		echo "       - only gzip and lz4 are handled; see this script's" >&2
		echo "       'Known gaps' in README.md." >&2
		exit 1
		;;
esac
echo "   -> $RAMDISK_FORMAT"

RAMDISK_WORK="$OUT_DIR/ramdisk_root"
rm -rf "$RAMDISK_WORK"
mkdir -p "$RAMDISK_WORK"

echo "== decompressing + extracting ramdisk cpio =="
case "$RAMDISK_FORMAT" in
	gzip)
		gzip -dc "$RAMDISK_SRC" > "$OUT_DIR/ramdisk.cpio"
		;;
	lz4)
		if ! command -v lz4 >/dev/null 2>&1; then
			echo "error: ramdisk is lz4-compressed but the 'lz4' CLI is not" >&2
			echo "       installed - install it (e.g. apt install lz4 /" >&2
			echo "       brew install lz4) and re-run." >&2
			exit 1
		fi
		lz4 -dc "$RAMDISK_SRC" > "$OUT_DIR/ramdisk.cpio"
		;;
esac
( cd "$RAMDISK_WORK" && cpio -idm < "$OUT_DIR/ramdisk.cpio" )

echo "== injecting su_core.ko + init.su_core.rc =="
cp "$SU_CORE_KO" "$RAMDISK_WORK/su_core.ko"
chmod 644 "$RAMDISK_WORK/su_core.ko"

cat > "$RAMDISK_WORK/init.su_core.rc" <<'EOF'
# init.su_core.rc - installed by tools/boot_patch/inject_ramdisk_autoload.sh.
# `insmod` here is one of init's own built-in commands (AOSP
# system/core/init/builtins.cpp), not a shell command - no /system/bin/sh,
# no busybox, nothing else needs to be up yet for this to run.
#
# early-init is deliberately as early as init's own trigger system goes:
# before selinux is set to enforcing in some init flows, certainly before
# zygote. su_core.ko's own su_hook.c registers against a kernel tracepoint
# on load - there's no reason to delay that registration any later than
# this.
on early-init
    insmod /su_core.ko
EOF

# Wire init.su_core.rc into the boot sequence: AOSP's own /init.rc
# already does `import /init.${ro.hardware}.rc` and similar
# early, but which exact file is guaranteed to exist and be imported
# this early varies by device/vendor ramdisk - not assuming one here.
# Instead, append a direct `import` line to the ramdisk's own /init.rc
# itself, right after its own first `import` line if it has one (so
# ordering stays close to "as early as everything else"), or as the
# first line otherwise.
INIT_RC="$RAMDISK_WORK/init.rc"
if [ ! -f "$INIT_RC" ]; then
	echo "error: $RAMDISK_WORK/init.rc not found - this ramdisk doesn't" >&2
	echo "       have the layout this script expects (a top-level" >&2
	echo "       init.rc). Not guessing a different location." >&2
	exit 1
fi
if grep -qx "import /init.su_core.rc" "$INIT_RC"; then
	echo "   (init.rc already imports init.su_core.rc, leaving it as-is)"
else
	if grep -q '^import ' "$INIT_RC"; then
		# Insert right after the FIRST existing `import` line.
		awk '1; !done && /^import / { print "import /init.su_core.rc"; done=1 }' \
			"$INIT_RC" > "$INIT_RC.tmp"
	else
		{ echo "import /init.su_core.rc"; cat "$INIT_RC"; } > "$INIT_RC.tmp"
	fi
	mv "$INIT_RC.tmp" "$INIT_RC"
fi

echo "== recompressing ramdisk ($RAMDISK_FORMAT) =="
( cd "$RAMDISK_WORK" && find . | cpio -o -H newc ) > "$OUT_DIR/ramdisk_new.cpio"
case "$RAMDISK_FORMAT" in
	gzip)
		gzip -9 -c "$OUT_DIR/ramdisk_new.cpio" > "$UNPACK_DIR/ramdisk"
		;;
	lz4)
		# -l: legacy frame format, matching AOSP's own mkbootfs/
		# repack convention for ramdisks (plain `lz4 -9` without it
		# produces a frame some older bootloaders' decompressors
		# reject - see AOSP build/tools/releasetools comments on this
		# exact flag for ramdisk repacking).
		lz4 -l -9 -c "$OUT_DIR/ramdisk_new.cpio" > "$UNPACK_DIR/ramdisk"
		;;
esac

echo "== repacking patched boot.img =="
# shellcheck disable=SC2046
python3 "$MKBOOTIMG_DIR/mkbootimg.py" \
	$(cat "$UNPACK_DIR/repack_args.txt") \
	--ramdisk "$UNPACK_DIR/ramdisk" \
	-o "$OUT_DIR/patched_boot.img"

echo ""
echo "== done: $OUT_DIR/patched_boot.img =="
echo ""
echo "Same safety order as ../patch_boot_img.sh - DO NOT flash yet:"
echo "    fastboot boot \"$OUT_DIR/patched_boot.img\""
echo "Only after that boots and 'lsmod | grep su_core' shows it loaded"
echo "with no manual insmod, consider:"
echo "    fastboot flash boot \"$OUT_DIR/patched_boot.img\""
echo "(stock_boot.img.bak in $OUT_DIR restores stock if anything is wrong.)"
