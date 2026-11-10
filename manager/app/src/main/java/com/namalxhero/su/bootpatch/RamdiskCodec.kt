package com.namalxhero.su.bootpatch

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Bounded ramdisk codec. Unsupported formats fail closed instead of being copied as if decoded. */
object RamdiskCodec {
    enum class Format { CPIO_NEWC, GZIP_CPIO, LZ4_FRAME, LZ4_LEGACY, UNKNOWN }
    data class Decoded(val format: Format, val cpio: ByteArray)

    private const val MAX_UNCOMPRESSED = 128 * 1024 * 1024

    fun detect(bytes: ByteArray): Format = when {
        bytes.size >= 6 && bytes[0] == '0'.code.toByte() && bytes[1] == '7'.code.toByte() && bytes[2] == '0'.code.toByte() && bytes[3] == '7'.code.toByte() && bytes[4] == '0'.code.toByte() -> Format.CPIO_NEWC
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() -> Format.GZIP_CPIO
        bytes.size >= 4 && le32(bytes) == 0x184D2204 -> Format.LZ4_FRAME
        bytes.size >= 4 && le32(bytes) == 0x184C2102 -> Format.LZ4_LEGACY
        else -> Format.UNKNOWN
    }

    fun decode(bytes: ByteArray): Decoded {
        return when (val format = detect(bytes)) {
            Format.CPIO_NEWC -> { NewcCpio.parse(bytes); Decoded(format, bytes.copyOf()) }
            Format.GZIP_CPIO -> {
                val out = ByteArrayOutputStream()
                GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= MAX_UNCOMPRESSED) { "Ramdisk expands beyond safety limit" }
                        out.write(buffer, 0, n)
                    }
                }
                val cpio = out.toByteArray()
                NewcCpio.parse(cpio)
                Decoded(format, cpio)
            }
            Format.LZ4_FRAME, Format.LZ4_LEGACY -> throw UnsupportedOperationException("${format.name} detected but no verified LZ4 decoder is bundled; refusing to modify this ramdisk")
            Format.UNKNOWN -> throw IllegalArgumentException("Unknown ramdisk compression or archive format")
        }
    }

    fun encode(cpio: ByteArray, format: Format): ByteArray {
        NewcCpio.parse(cpio)
        return when (format) {
            Format.CPIO_NEWC -> cpio.copyOf()
            Format.GZIP_CPIO -> ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(cpio) } }.toByteArray()
            Format.LZ4_FRAME, Format.LZ4_LEGACY -> throw UnsupportedOperationException("LZ4 encoding is not available in this build")
            Format.UNKNOWN -> throw IllegalArgumentException("Cannot encode an unknown ramdisk format")
        }
    }

    private fun le32(b: ByteArray): Int = (b[0].toInt() and 0xff) or ((b[1].toInt() and 0xff) shl 8) or ((b[2].toInt() and 0xff) shl 16) or ((b[3].toInt() and 0xff) shl 24)
}
