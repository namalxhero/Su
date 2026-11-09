#!/system/bin/sh
# install_vendor_dlkm.sh - make su_core.ko load itself on every boot,
# the same way every other GKI vendor kernel module auto-loads: via
# Android init's own native modules.load mechanism, not a hand-rolled
# boot script or daemon.
#
# WHY THIS AND NOT A SCRIPT THAT CALLS insmod:
#   On GKI 2.0 (this project's only supported target - see ../../README.md
#   "Project scope"), `init` itself reads /vendor_dlkm/lib/modules/
#   modules.load (or /vendor/lib/modules/modules.load on older combined
#   vendor layouts) very early in first-stage init, BEFORE zygote, and
#   insmod's every .ko listed there in order. That is exactly how
#   KernelSU and every stock vendor .ko on a GKI device auto-load - no
#   userspace daemon, no custom init.rc service, nothing racing boot.
#   A script that insmod's the module from a later boot stage (e.g. a
#   post-fs-data script) would also be circular for this project
#   specifically: su_boot_trigger.c's execve trigger (which launches
#   `sud`) is itself INSIDE su_core.ko - the module has to already be
#   loaded before anything it triggers can run, so `sud`/a boot script
#   can never be what loads su_core.ko in the first place. Only
#   init's own native modules.load step, or building su_core in as
#   obj-y (tools/boot_patch/), can.
#
# PREREQUISITES (all one-time, manual, on YOUR device):
#   - Bootloader already unlocked - same prerequisite as
#     tools/boot_patch/, same reason (locked bootloader = AVB rejects
#     anything not stock, this script included).
#   - A root shell to run this from (adb shell on a userdebug build,
#     or temp root via manual `insmod su_core.ko` + this project's own
#     su mechanism once, bootstrapping itself - see ../../README.md
#     "Status"). This script does not and cannot grant root itself.
#   - su_core.ko already built for this exact device's kernel
#     (KERNELRELEASE match - a .ko built for the wrong kernel version
#     will fail to insmod with "invalid module format", and init will
#     silently skip it at boot the same way).
#
# WHAT THIS SCRIPT DOES NOT DO:
#   - Does not build su_core.ko (see ../../kernel/ + ../../README.md
#     "Integration").
#   - Does not survive a factory reset or OTA - /vendor_dlkm is wiped/
#     replaced by both. Re-run this after either.
#   - Does not work at all if CONFIG_SU_CORE was built =y (built-in) -
#     there is no .ko to load in that case; see ../boot_patch/ instead.
#   - Does not remount anything read-write for you beyond the one
#     `mount -o remount,rw` this script issues on the specific
#     partition it needs - it does not touch /system, /product, or
#     anything else.
#
# USAGE (from an adb/root shell on the device itself, not the host):
#   sh install_vendor_dlkm.sh /path/to/su_core.ko

set -eu

KO_SRC="${1:?usage: install_vendor_dlkm.sh <path-to-su_core.ko>}"
MODULE_NAME="su_core.ko"

if [ "$(id -u)" != "0" ]; then
	echo "error: must run as root (got uid $(id -u)) - see this script's" >&2
	echo "       PREREQUISITES comment for how to get a root shell first." >&2
	exit 1
fi

if [ ! -f "$KO_SRC" ]; then
	echo "error: $KO_SRC not found" >&2
	exit 1
fi

# Prefer /vendor_dlkm (split vendor_boot/vendor_dlkm layout - most
# GKI 2.0 devices from Android 12+), fall back to /vendor/lib/modules
# (older/combined layout). Picking whichever actually exists rather
# than assuming one, same "don't assume device layout" stance as
# daemon/sud.c's overlay_targets list.
if [ -d /vendor_dlkm/lib/modules ] || mount | grep -q ' /vendor_dlkm '; then
	MODULES_DIR=/vendor_dlkm/lib/modules
elif [ -d /vendor/lib/modules ] || mount | grep -q ' /vendor '; then
	MODULES_DIR=/vendor/lib/modules
else
	echo "error: neither /vendor_dlkm nor /vendor/lib/modules found on" >&2
	echo "       this device - this script only handles the standard GKI" >&2
	echo "       vendor-partition module layout. Not guessing a path." >&2
	exit 1
fi

MOUNT_POINT=$(echo "$MODULES_DIR" | grep -o '^/[^/]*')
echo "== remounting $MOUNT_POINT read-write =="
mount -o remount,rw "$MOUNT_POINT"

mkdir -p "$MODULES_DIR"
echo "== copying $KO_SRC -> $MODULES_DIR/$MODULE_NAME =="
cp "$KO_SRC" "$MODULES_DIR/$MODULE_NAME"
chmod 644 "$MODULES_DIR/$MODULE_NAME"

LOAD_LIST="$MODULES_DIR/modules.load"
if [ -f "$LOAD_LIST" ] && grep -qx "$MODULE_NAME" "$LOAD_LIST"; then
	echo "== $MODULE_NAME already listed in modules.load, leaving order as-is =="
else
	echo "== appending $MODULE_NAME to modules.load =="
	# Appended, not prepended/sorted: load order in this file is also
	# insmod order, and other vendor modules already listed may be
	# dependencies of nothing here, but this project's module has no
	# known dependency on any other vendor .ko either - append is the
	# safe default absent a reason to order it earlier.
	echo "$MODULE_NAME" >> "$LOAD_LIST"
fi

echo "== remounting $MOUNT_POINT back to read-only =="
mount -o remount,ro "$MOUNT_POINT" || \
	echo "warning: remount read-only failed - not fatal, but leaves $MOUNT_POINT writable until next reboot" >&2

echo ""
echo "== done =="
echo "$MODULE_NAME will now be insmod'd by init on every boot, before"
echo "zygote starts - same mechanism as every other vendor .ko on this"
echo "device. Reboot to verify: after reboot, 'lsmod | grep su_core'"
echo "should show it loaded with no manual insmod."
