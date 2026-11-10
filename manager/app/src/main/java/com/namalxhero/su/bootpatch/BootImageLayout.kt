package com.namalxhero.su.bootpatch

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Bounds-checked structural layout reader for Android boot image headers.
 * This is deliberately an inspector, not a patcher: it never writes or flashes.
 * A layout is marked patchable only when every section boundary can be proven
 * from the header and the supplied file length.
 */
object BootImageLayout {
    data class Section(val name: String, val offset: Long, val size: Long) {
        val endExclusive: Long get() = offset + size
    }

    data class Layout(
        val kind: BootImageInspector.Kind,
        val version: Int,
        val pageSize: Long,
        val sections: List<Section>,
        val structurallyComplete: Boolean,
        val notes: List<String>,
    )

    fun inspect(header: ByteArray, fileSize: Long): Layout? {
        if (fileSize < 8 || header.size < 44) return null
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        return when (ascii(header, 0, 8)) {
            "ANDROID!" -> inspectBoot(b, header.size, fileSize)
            "VNDRBOOT" -> inspectVendor(b, header.size, fileSize)
            else -> null
        }
    }

    private fun inspectBoot(b: ByteBuffer, available: Int, fileSize: Long): Layout? {
        val version = u32(b, 40)?.toInt() ?: return null
        if (version !in 0..4) return null
        val page = if (version <= 2) u32(b, 36) ?: return null else 4096L
        if (!validPage(page)) return null
        val kernelSize = u32(b, if (version <= 2) 8 else 8) ?: return null
        val ramdiskSize = u32(b, if (version <= 2) 16 else 12) ?: return null
        val headerSize = when (version) {
            0 -> 1632L
            1 -> 1648L
            2 -> 1660L
            3 -> 1580L
            else -> 1584L
        }
        if (available < minOf(headerSize, 4096L).toInt()) return null
        if (version >= 3) {
            val declared = u32(b, 20) ?: return null
            if (declared < headerSize || declared > page) return null
        } else if (version >= 1) {
            if (available < 1648) return null
        }

        var cursor = align(page, headerSize)
        val sections = mutableListOf<Section>()
        fun add(name: String, size: Long): Boolean {
            if (size < 0 || cursor > fileSize || size > fileSize - cursor) return false
            sections += Section(name, cursor, size)
            cursor = align(page, cursor + size)
            return cursor <= fileSize || sections.last().endExclusive == fileSize
        }
        if (!add("kernel", kernelSize)) return incomplete(BootImageInspector.Kind.BOOT, version, page, sections, "Kernel section exceeds file bounds")
        if (!add("ramdisk", ramdiskSize)) return incomplete(BootImageInspector.Kind.BOOT, version, page, sections, "Ramdisk section exceeds file bounds")
        if (version <= 2) {
            val second = u32(b, 24) ?: return null
            if (!add("second", second)) return incomplete(BootImageInspector.Kind.BOOT, version, page, sections, "Second stage exceeds file bounds")
            if (version >= 1) {
                val recoverySize = u32(b, 1632) ?: return null
                // recovery_dtbo_offset is a uint64 field in the real header
                // (not uint32 - was previously misread with u32() here,
                // silently truncating any offset >= 4GiB; harmless on every
                // real boot.img today since none are remotely that large,
                // but not spec-correct, so read all 8 bytes properly).
                val recoveryOffset = u64(b, 1636) ?: return null
                if (recoverySize > 0) {
                    if (recoveryOffset < cursor || recoveryOffset > fileSize || recoverySize > fileSize - recoveryOffset)
                        return incomplete(BootImageInspector.Kind.BOOT, version, page, sections, "Recovery DTBO offset/size is invalid")
                    sections += Section("recovery_dtbo", recoveryOffset, recoverySize)
                    cursor = align(page, recoveryOffset + recoverySize)
                }
            }
            if (version >= 2) {
                val dtb = u32(b, 1648) ?: return null
                if (!add("dtb", dtb)) return incomplete(BootImageInspector.Kind.BOOT, version, page, sections, "DTB exceeds file bounds")
            }
        } else if (version == 4) {
            // v4's own header (boot_img_hdr_v4, AOSP system/tools/mkbootimg/
            // include/bootimg.h) is exactly v3's header plus one trailing
            // uint32 signature_size field at offset 1580 (= v3's own
            // headerSize, confirming v4's headerSize=1584 above) - the
            // boot_signature section it describes is laid out sequentially
            // after ramdisk, page-aligned, same as every other section here.
            // This is a real device target (GKI 2.0's boot.img/init_boot.img
            // are both v4 - see the top-level README's "Project scope") so
            // this is bounds-checked the same way as every other section,
            // not treated as a special case - a zero-length signature
            // (common on devices that don't populate it) still round-trips
            // through the same add() path with size 0.
            val sigSize = u32(b, 1580) ?: return null
            if (!add("boot_signature", sigSize)) return incomplete(BootImageInspector.Kind.BOOT, version, page, sections, "Boot signature section exceeds file bounds")
        }
        return Layout(BootImageInspector.Kind.BOOT, version, page, sections, true,
            listOf("Header and declared section bounds validated", "Partition role (boot vs init_boot) is not reliably encoded in the image header", "No payload was injected; this is structural inspection only"))
    }

    private fun inspectVendor(b: ByteBuffer, available: Int, fileSize: Long): Layout? {
        if (available < 2112) return null
        val version = u32(b, 8)?.toInt() ?: return null
        if (version !in 3..4) return null
        val page = u32(b, 12) ?: return null
        if (!validPage(page)) return null
        val ramdiskSize = u32(b, 24) ?: return null
        val headerSize = u32(b, 2096) ?: return null
        if (headerSize < 2112 || headerSize > page) return null
        var cursor = align(page, headerSize)
        val sections = mutableListOf<Section>()
        if (ramdiskSize > fileSize - cursor) return incomplete(BootImageInspector.Kind.VENDOR_BOOT, version, page, sections, "Vendor ramdisk exceeds file bounds")
        sections += Section("vendor_ramdisk", cursor, ramdiskSize)
        cursor = align(page, cursor + ramdiskSize)
        val dtbSize = u32(b, 2100) ?: return null
        if (dtbSize > fileSize - cursor) return incomplete(BootImageInspector.Kind.VENDOR_BOOT, version, page, sections, "Vendor DTB exceeds file bounds")
        sections += Section("dtb", cursor, dtbSize)
        val notes = mutableListOf("Vendor boot header and primary sections validated", "Vendor ramdisk fragments are not yet individually decoded")
        if (version == 4) {
            notes += "v4 ramdisk table and bootconfig section need dedicated table-entry validation before modification"
            return Layout(BootImageInspector.Kind.VENDOR_BOOT, version, page, sections, false, notes)
        }
        return Layout(BootImageInspector.Kind.VENDOR_BOOT, version, page, sections, true, notes + "Inspection only; no patch output created")
    }

    private fun incomplete(kind: BootImageInspector.Kind, version: Int, page: Long, sections: List<Section>, reason: String) =
        Layout(kind, version, page, sections.toList(), false, listOf(reason))

    private fun validPage(value: Long) = value in 512L..65536L && value and (value - 1L) == 0L
    private fun align(alignment: Long, value: Long): Long {
        if (value == 0L) return 0L
        val remainder = value % alignment
        return if (remainder == 0L) value else value + (alignment - remainder)
    }
    private fun u32(b: ByteBuffer, offset: Int): Long? = if (offset >= 0 && offset <= b.limit() - 4) b.getInt(offset).toLong() and 0xffffffffL else null
    private fun u64(b: ByteBuffer, offset: Int): Long? = if (offset >= 0 && offset <= b.limit() - 8) b.getLong(offset) else null
    private fun ascii(bytes: ByteArray, offset: Int, length: Int) = String(bytes, offset, length, Charsets.US_ASCII)
}
