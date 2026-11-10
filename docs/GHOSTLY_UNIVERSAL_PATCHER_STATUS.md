# Ghostly universal patcher - implementation status

This document distinguishes working source components from the full image patch pipeline. No success state is simulated.

## Implemented in this source update

- Ghostly app label and custom ghost adaptive-icon foreground.
- Patch destination in the manager UI. Flashing is not a permanent navigation destination.
- Android Storage Access Framework image selection and read-only header inspection.
- Header intake for Android boot image versions 0-4 and vendor_boot v3-v4, with unsupported layouts reported explicitly.
- Bounds-checked structural section inspection for legacy boot v0-v2, boot v3, **and boot v4** (GKI 2.0's own boot.img/init_boot.img format - the boot_signature trailer this version adds is bounds-checked the same way as every other section); vendor_boot v3 primary sections.
- `recovery_dtbo_offset` is read as the real 64-bit field the header actually declares it as (was previously misread as 32-bit - harmless on any real-world boot.img today, since none are near 4GiB, but not spec-correct; fixed).
- Vendor_boot v4 explicitly remains incomplete until ramdisk table entries and bootconfig boundaries are parsed.
- Strict `newc` / `crc-newc` CPIO parser and writer with path traversal and duplicate-path rejection.
- Bounded gzip ramdisk decode/encode, validating the contained CPIO before accepting it.
- LZ4 frame and legacy LZ4 signatures are recognized but deliberately rejected because no verified LZ4 codec is bundled.
- **Boot image repack (`BootImageWriter.kt`), for every structurally-complete layout above** (legacy boot v0-v2, boot v3, boot v4, vendor_boot v3): embeds `su_core.ko` and an `init.su_core.rc` into the ramdisk's cpio (replacing, not duplicating, on a re-patch), wires a new `import` line into the ramdisk's own `init.rc`, and rebuilds the full image with every downstream section (second stage, recovery_dtbo, dtb, boot_signature) correctly re-laid-out and page-aligned at its new offset - not just the ramdisk. Same mechanism as `tools/boot_patch/inject_ramdisk_autoload.sh` (init's own built-in `insmod` at `early-init`, no boot script of this project's own, no Magisk-style `/init` replacement or overlay system), now reachable from the manager app's own UI instead of only a host-machine shell script.
- Manager UI: pick a boot image, pick `su_core.ko`, patch, save the result via Storage Access Framework (`CreateDocument`) - gated on the image's layout actually being structurally complete first, same fail-closed stance as the rest of this file.
- Self-test for CPIO round-trip, gzip round-trip, traversal rejection, duplicate-path rejection, **and** (new) full patch+rebuild+re-parse round-trips for both a synthetic boot v4 image (verifying the boot_signature trailer re-lays-out correctly after the ramdisk grows) and a synthetic legacy v1 image with a real `recovery_dtbo` section (verifying that offset moves to the correct new location and its content survives byte-for-byte).

## Not implemented - do not advertise as available

- Vendor_boot v4 ramdisk table and fragment mutation/repack.
- Verified LZ4 frame/legacy decoder and encoder - a ramdisk compressed as LZ4 is still refused outright (not silently mishandled) by both the inspector and the writer.
- SU payload manifest, architecture/KMI compatibility resolver and real SU payload packaging. The writer takes whatever `su_core.ko` the user hands it on faith (same standing caveat as `install_vendor_dlkm.sh`/`inject_ramdisk_autoload.sh` - it cannot itself verify the `.ko` was built for the target device's exact kernel).
- Device-compatible kernel binary or kernel module compilation. The repository has kernel source but no target kernel build tree/config or compiled module artifact.
- Legacy header `id[]` hash field (v0-v2 only) is not recomputed by the writer - informational only, and not a field any GKI 2.0 (header v4) device - this project's actual target - even has.
- Independent full-image verification (AVB/vbmeta chain, signature validity) after patching - the writer's own self-test checks *structural* correctness (every section lands where the header says it does, content survives intact), not that a locked-bootloader device would accept the result. `fastboot boot` before `fastboot flash` remains the safety net for that gap, same as `tools/boot_patch/`'s own README already says.
- Flash backend. The Flashing UI must only appear while a real, explicitly confirmed flash operation is running; no such operation is wired yet.

## Build and test notes

- The standalone Kotlin patch-core self-tests run with `tools/boot_patch/tests/run-self-test.sh` when `kotlinc` is installed - now three separate test programs (`PatchCoreSelfTest`, `WriterSelfTest`, `WriterSelfTestV1`), each compiled and run in its own step since each defines its own top-level `main()`.
- This environment has no Gradle executable or Gradle wrapper, Android SDK/NDK toolchain, or target kernel build tree. The Android app and kernel module have therefore not been built here - the self-tests above compile and run the patch-core `.kt` files directly with `kotlinc`, independent of the Gradle/Android build, which is how they can be verified at all in this environment.
- A structurally valid image header is not proof that a given device/partition accepts that image. The app must fail closed for unknown formats, incompatible payloads and unverified layouts.
