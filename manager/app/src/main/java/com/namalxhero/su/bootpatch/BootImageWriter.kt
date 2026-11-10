package com.namalxhero.su.bootpatch

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Actually writes a patched boot image - the in-app counterpart to
 * tools/boot_patch/inject_ramdisk_autoload.sh, reusing [BootImageLayout],
 * [NewcCpio] and [RamdiskCodec] (already covered by PatchCoreSelfTest.kt's
 * own round-trip tests) instead of re-implementing any of that parsing.
 *
 * SAME MECHANISM AS THE SHELL SCRIPT, DELIBERATELY NOT MAGISK'S: this
 * embeds su_core.ko plus one init.rc service entry that calls init's OWN
 * built-in `insmod` at early-init. The stock /init binary is never
 * replaced or wrapped, there's no magiskinit-style boot process
 * injection, no overlay/mount system, and nothing of this project's own
 * needs to run as a daemon for the module to load - init does that
 * natively, the same way it loads every other kernel module. The only
 * thing this adds to the ramdisk is one small .ko and one small .rc file.
 *
 * SCOPE - what this does NOT claim to patch: only images
 * [BootImageLayout] can already account for every byte of
 * ([BootImageLayout.Layout.structurallyComplete] == true) are accepted;
 * everything else is refused with the same reason BootImageLayout itself
 * gives, not guessed at. As of this writer: legacy boot v0-v2, boot v3,
 * boot v4 (GKI 2.0's own boot.img/init_boot.img format - see this
 * writer's own fix to BootImageLayout for why v4 is now included), and
 * vendor_boot v3. vendor_boot v4 (vendor ramdisk table + bootconfig)
 * still isn't - that one has real per-fragment structure BootImageLayout
 * itself doesn't parse yet, not a gap specific to this writer.
 */
object BootImageWriter {
    private const val INIT_RC_NAME = "init.su_core.rc"
    private const val KO_NAME = "su_core.ko"
    private const val FILE_MODE = 0x81a4L // S_IFREG | 0644

    class UnsupportedImageException(message: String) : Exception(message)

    data class PatchResult(
        val image: ByteArray,
        val ramdiskFormat: RamdiskCodec.Format,
        val notes: List<String>,
    )

    /**
     * @param stockImage the untouched source boot.img/init_boot.img/
     * vendor_boot.img bytes.
     * @param koBytes su_core.ko, already built for this exact device's
     * kernel (this writer has no way to verify that match itself - same
     * standing caveat as install_vendor_dlkm.sh and
     * inject_ramdisk_autoload.sh's own "KERNELRELEASE match" note).
     */
    fun injectAutoload(stockImage: ByteArray, koBytes: ByteArray): PatchResult {
        val layout = BootImageLayout.inspect(stockImage, stockImage.size.toLong())
            ?: throw UnsupportedImageException("Not a recognised Android boot image (bad magic or truncated header)")
        if (!layout.structurallyComplete) {
            throw UnsupportedImageException(
                "This image's layout isn't fully validated yet (${layout.notes.firstOrNull() ?: "unknown reason"}) " +
                    "- refusing to patch rather than guess at section boundaries that could silently corrupt the image."
            )
        }
        val ramdiskName = when (layout.kind) {
            BootImageInspector.Kind.BOOT -> "ramdisk"
            BootImageInspector.Kind.VENDOR_BOOT -> "vendor_ramdisk"
            BootImageInspector.Kind.UNKNOWN -> throw UnsupportedImageException("Unknown image kind")
        }
        val ramdiskSection = layout.sections.find { it.name == ramdiskName }
            ?: throw UnsupportedImageException("No $ramdiskName section in this image")
        if (ramdiskSection.size == 0L) {
            throw UnsupportedImageException(
                "This image's $ramdiskName is empty - nothing to inject su_core.ko's autoload into. " +
                    "(A boot.img with an empty generic ramdisk alongside an init_boot.img is the normal GKI " +
                    "split; patch init_boot.img instead.)"
            )
        }

        val ramdiskBytes = stockImage.copyOfRange(ramdiskSection.offset.toInt(), ramdiskSection.endExclusive.toInt())
        val decoded = RamdiskCodec.decode(ramdiskBytes) // throws on LZ4 / unknown - same honesty as everywhere else here

        val entries = NewcCpio.parse(decoded.cpio).toMutableList()
        val initRcIndex = entries.indexOfFirst { it.name == "init.rc" }
        if (initRcIndex < 0) {
            throw UnsupportedImageException(
                "No top-level init.rc in this ramdisk - this writer only knows how to wire autoload " +
                    "into a ramdisk shaped like a standard AOSP-derived one."
            )
        }

        // Re-patching an already-patched image replaces, not duplicates
        // (NewcCpio.build rejects duplicate paths outright otherwise).
        entries.removeAll { it.name == KO_NAME || it.name == INIT_RC_NAME }
        val now = System.currentTimeMillis() / 1000
        entries += NewcCpio.Entry(KO_NAME, FILE_MODE, 0, 0, now, koBytes)
        entries += NewcCpio.Entry(INIT_RC_NAME, FILE_MODE, 0, 0, now, initRcContent().toByteArray())

        val initRc = entries[initRcIndex]
        val initRcText = String(initRc.data, Charsets.UTF_8)
        if ("import /$INIT_RC_NAME" !in initRcText) {
            val importLine = "import /$INIT_RC_NAME\n"
            val firstImport = Regex("(?m)^import .*$").find(initRcText)
            val patchedText = if (firstImport != null) {
                initRcText.substring(0, firstImport.range.last + 1) + "\n" + importLine +
                    initRcText.substring(firstImport.range.last + 1)
            } else {
                importLine + initRcText
            }
            entries[initRcIndex] = initRc.copy(data = patchedText.toByteArray())
        }

        val newCpio = NewcCpio.build(entries)
        val newRamdisk = RamdiskCodec.encode(newCpio, decoded.format)

        val notes = mutableListOf(
            "Embedded $KO_NAME (${koBytes.size} bytes) and $INIT_RC_NAME into the ramdisk, wired via one new " +
                "import line in init.rc - same mechanism as tools/boot_patch/inject_ramdisk_autoload.sh.",
            "Ramdisk: ${ramdiskBytes.size} -> ${newRamdisk.size} bytes (format: ${decoded.format}).",
        )
        if (layout.kind == BootImageInspector.Kind.BOOT && layout.version in 0..2) {
            notes += "Legacy header's id[] hash field is NOT recomputed - informational only on " +
                "every device this project targets (GKI 2.0, header v4), listed for completeness on older headers."
        }

        val image = rebuild(stockImage, layout, ramdiskSection, newRamdisk)
        return PatchResult(image, decoded.format, notes)
    }

    /** Rebuilds the full image with [ramdiskSection] replaced by
     * [newRamdisk], re-laying-out every section that came after it
     * (second stage / recovery_dtbo / dtb / boot_signature / vendor
     * dtb - whichever this [layout]'s kind actually has) at new,
     * correctly page-aligned offsets, and patching every header field
     * that encodes a size or offset so the output is internally
     * consistent - not just the ramdisk_size field. */
    private fun rebuild(
        stockImage: ByteArray,
        layout: BootImageLayout.Layout,
        ramdiskSection: BootImageLayout.Section,
        newRamdisk: ByteArray,
    ): ByteArray {
        val page = layout.pageSize
        val headerRegionSize = ramdiskSection.let { _ ->
            // The header region is everything before the FIRST section
            // (kernel for boot, vendor_ramdisk for vendor_boot) -
            // layout.sections is already in on-disk order.
            layout.sections.first().offset
        }
        val header = stockImage.copyOfRange(0, headerRegionSize.toInt())
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // Pass 1 (no bytes written yet): recompute every section's NEW
        // offset, sequentially, exactly like BootImageLayout's own
        // add()/align() - only ramdiskSection's size actually changes,
        // but every section after it shifts because of that.
        data class Placed(val name: String, val newOffset: Long, val size: Long)
        val placed = mutableListOf<Placed>()
        var cursor = headerRegionSize
        for (section in layout.sections) {
            val size = if (section.name == ramdiskSection.name) newRamdisk.size.toLong() else section.size
            placed += Placed(section.name, cursor, size)
            cursor = alignTo(page, cursor + size)
        }
        val finalSize = cursor

        // Pass 2: patch every header field that encodes a size/offset,
        // using Pass 1's recomputed values - not just ramdisk_size.
        writeU32(buf, ramdiskSizeFieldOffset(layout), newRamdisk.size.toLong())
        placed.find { it.name == "recovery_dtbo" }?.let { writeU64(buf, 1636, it.newOffset) }

        // Pass 3: assemble the actual output bytes using Pass 1's offsets.
        val out = java.io.ByteArrayOutputStream()
        out.write(header)
        fun padTo(target: Long) { while (out.size().toLong() < target) out.write(0) }
        for (p in placed) {
            padTo(p.newOffset)
            val bytes = if (p.name == ramdiskSection.name) newRamdisk
                else stockImage.copyOfRange(
                    layout.sections.first { it.name == p.name }.offset.toInt(),
                    layout.sections.first { it.name == p.name }.endExclusive.toInt(),
                )
            out.write(bytes)
        }
        padTo(finalSize)

        val body = out.toByteArray()
        // Header bytes were mutated in-place in `header` (via `buf`,
        // which wraps the same array) - splice the patched header back
        // over the front of the assembled body, which still has the
        // ORIGINAL (pre-patch) header bytes at its start from `out.write(header)` above.
        header.copyInto(body, 0)
        return body
    }

    private fun ramdiskSizeFieldOffset(layout: BootImageLayout.Layout): Int = when {
        layout.kind == BootImageInspector.Kind.VENDOR_BOOT -> 24
        layout.version <= 2 -> 16
        else -> 12
    }

    private fun alignTo(alignment: Long, value: Long): Long {
        if (value == 0L) return 0L
        val remainder = value % alignment
        return if (remainder == 0L) value else value + (alignment - remainder)
    }

    private fun writeU32(buf: ByteBuffer, offset: Int, value: Long) {
        buf.putInt(offset, value.toInt())
    }
    private fun writeU64(buf: ByteBuffer, offset: Int, value: Long) {
        buf.putLong(offset, value)
    }

    private fun initRcContent(): String = """
        # init.su_core.rc - installed by Ghostly's in-app boot patcher
        # (BootImageWriter.kt) or tools/boot_patch/inject_ramdisk_autoload.sh -
        # identical content either way, so the two stay interchangeable.
        #
        # `insmod` is one of init's own built-in commands (AOSP
        # system/core/init/builtins.cpp) - no shell, no busybox, nothing
        # else needs to be up yet for this to run.
        on early-init
            insmod /su_core.ko
    """.trimIndent() + "\n"
}
