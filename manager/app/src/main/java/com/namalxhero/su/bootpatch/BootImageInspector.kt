package com.namalxhero.su.bootpatch

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Safe, read-only Android boot image header inspection. Does not modify images. */
object BootImageInspector {
    enum class Kind { BOOT, VENDOR_BOOT, UNKNOWN }
    data class Result(
        val kind: Kind, val headerVersion: Int?, val pageSize: Long?,
        val kernelSize: Long?, val ramdiskSize: Long?, val vendorRamdiskSize: Long?,
        val supportedForInspection: Boolean, val message: String,
    )

    fun inspect(header: ByteArray, totalSize: Long): Result {
        if (header.size < 44) return unknown("File is too small to contain an Android boot header")
        val magic = header.copyOfRange(0, 8).toString(Charsets.US_ASCII)
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        return when (magic) {
            "ANDROID!" -> {
                // header_version sits at the SAME offset (40) in every
                // version, which is why it alone is safe to read before
                // branching - but ramdisk_size and page_size are NOT at
                // the same offset across versions, and this previously
                // read both unconditionally from the legacy (v0-v2)
                // offsets even for v3/v4, silently reading v3/v4's
                // os_version field (offset 16) as "ramdisk size" and its
                // reserved[3] field (offset 36, always 0) as "page size"
                // - producing exactly the inconsistent-looking garbage
                // ("page size: 0", a huge bogus ramdisk size) this
                // panel is supposed to catch, on every real v3/v4 image
                // (which is every GKI 2.0 device's boot.img/init_boot.img
                // - this project's actual target format). Mirrors
                // BootImageLayout.kt's own, already-correct version
                // branching for these exact two fields instead of
                // re-deriving it a second time, to avoid these two
                // readers silently disagreeing again.
                val version = u32(b, 40).toInt()
                val kernel = u32(b, 8)
                val ramdisk = if (version <= 2) u32(b, 16) else u32(b, 12)
                val page = if (version <= 2) u32(b, 36) else 4096L
                if (version !in 0..4) return Result(Kind.BOOT, version, page, kernel, ramdisk, null, false, "Unknown boot header version $version")
                if (page == 0L || page > 65536L || kernel > totalSize || ramdisk > totalSize || (kernel == 0L && ramdisk == 0L))
                    return Result(Kind.BOOT, version, page, kernel, ramdisk, null, false, "Header fields are inconsistent; image may be truncated or malformed")
                Result(Kind.BOOT, version, page, kernel, ramdisk, null, true,
                    "Android boot-image container v$version detected (boot.img or init_boot.img; partition role is not encoded reliably in the header). Full layout validation is required before patching.")
            }
            "VNDRBOOT" -> {
                if (header.size < 32) return unknown("Vendor boot header is truncated")
                val version = u32(b, 8).toInt(); val page = u32(b, 12); val vendorRamdisk = u32(b, 24)
                if (version !in 3..4) return Result(Kind.VENDOR_BOOT, version, page, null, null, vendorRamdisk, false, "Unsupported vendor_boot header version $version")
                if (page == 0L || page > 65536L || vendorRamdisk > totalSize)
                    return Result(Kind.VENDOR_BOOT, version, page, null, null, vendorRamdisk, false, "Vendor boot header fields are inconsistent")
                Result(Kind.VENDOR_BOOT, version, page, null, null, vendorRamdisk, true,
                    "Vendor boot v$version detected. Ramdisk table/fragments require deeper validation before patching.")
            }
            else -> unknown("Unrecognized image magic: $magic")
        }
    }
    private fun u32(buffer: ByteBuffer, offset: Int): Long = buffer.getInt(offset).toLong() and 0xffffffffL
    private fun unknown(message: String) = Result(Kind.UNKNOWN, null, null, null, null, null, false, message)
}
