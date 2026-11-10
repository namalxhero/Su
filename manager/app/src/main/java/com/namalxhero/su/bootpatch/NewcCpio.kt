package com.namalxhero.su.bootpatch

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/** Strict newc/CRC-newc archive reader and writer. No filesystem extraction occurs. */
object NewcCpio {
    data class Entry(
        val name: String,
        val mode: Long,
        val uid: Long,
        val gid: Long,
        val mtime: Long,
        val data: ByteArray,
        val inode: Long = 0,
        val nlink: Long = 1,
        val devMajor: Long = 0,
        val devMinor: Long = 0,
        val rdevMajor: Long = 0,
        val rdevMinor: Long = 0,
        val checksum: Long = 0,
    )

    fun parse(bytes: ByteArray, maxEntries: Int = 100_000): List<Entry> {
        val entries = ArrayList<Entry>()
        val names = HashSet<String>()
        var p = 0
        while (p + 110 <= bytes.size) {
            val magic = ascii(bytes, p, 6)
            require(magic == "070701" || magic == "070702") { "Unsupported or corrupt CPIO magic at $p" }
            val f = LongArray(13) { i -> hex(bytes, p + 6 + i * 8) }
            val namesize = f[11]
            val filesize = f[6]
            require(namesize in 1..4096 && filesize <= Int.MAX_VALUE.toLong()) { "Invalid CPIO entry sizes" }
            val nameStart = p + 110
            val nameEnd = checkedEnd(nameStart, namesize, bytes.size)
            require(bytes[nameEnd - 1] == 0.toByte()) { "CPIO name is not NUL terminated" }
            val name = String(bytes, nameStart, (namesize - 1).toInt(), StandardCharsets.UTF_8)
            p = align4(nameEnd)
            val dataEnd = checkedEnd(p, filesize, bytes.size)
            if (name == "TRAILER!!!") return entries
            validateName(name)
            require(names.add(name)) { "Duplicate CPIO path: $name" }
            val data = bytes.copyOfRange(p, dataEnd)
            if (magic == "070702") {
                val sum = data.fold(0L) { acc, byte -> (acc + (byte.toInt() and 0xff)) and 0xffffffffL }
                require(sum == f[12]) { "CPIO CRC mismatch for $name" }
            }
            entries += Entry(name, f[1], f[2], f[3], f[5], data, f[0], f[4], f[7], f[8], f[9], f[10], f[12])
            require(entries.size <= maxEntries) { "CPIO entry limit exceeded" }
            p = align4(dataEnd)
        }
        throw IllegalArgumentException("CPIO trailer missing or archive truncated")
    }

    fun build(entries: List<Entry>, crcFormat: Boolean = false): ByteArray {
        require(entries.size <= 100_000) { "Too many CPIO entries" }
        val out = ByteArrayOutputStream()
        val names = HashSet<String>()
        entries.forEach { e ->
            validateName(e.name)
            require(e.name != "TRAILER!!!") { "Trailer entry is generated automatically" }
            require(names.add(e.name)) { "Duplicate CPIO path: ${e.name}" }
            writeEntry(out, e, crcFormat)
        }
        writeEntry(out, Entry("TRAILER!!!", 0, 0, 0, 0, byteArrayOf(), nlink = 1), crcFormat)
        return out.toByteArray()
    }

    private fun writeEntry(out: ByteArrayOutputStream, e: Entry, crc: Boolean) {
        val name = e.name.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0)
        val checksum = if (crc) e.data.fold(0L) { a, b -> (a + (b.toInt() and 0xff)) and 0xffffffffL } else 0L
        val fields = longArrayOf(e.inode, e.mode, e.uid, e.gid, e.nlink, e.mtime, e.data.size.toLong(), e.devMajor, e.devMinor, e.rdevMajor, e.rdevMinor, name.size.toLong(), checksum)
        out.write((if (crc) "070702" else "070701").toByteArray(StandardCharsets.US_ASCII))
        fields.forEach { value ->
            require(value in 0..0xffffffffL) { "CPIO field out of range" }
            out.write(String.format(java.util.Locale.ROOT, "%08x", value).toByteArray(StandardCharsets.US_ASCII))
        }
        out.write(name); pad4(out)
        out.write(e.data); pad4(out)
    }

    private fun validateName(name: String) {
        require(name.isNotEmpty() && !name.startsWith("/") && '\\' !in name) { "Unsafe CPIO path" }
        val parts = name.split('/')
        require(parts.none { it == ".." || it.isEmpty() }) { "Unsafe CPIO path component" }
        require(name.length <= 4095) { "CPIO path too long" }
    }
    private fun hex(b: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 8 <= b.size) { "Truncated CPIO header" }
        return String(b, offset, 8, StandardCharsets.US_ASCII).toLong(16)
    }
    private fun ascii(b: ByteArray, offset: Int, len: Int) = String(b, offset, len, StandardCharsets.US_ASCII)
    private fun checkedEnd(start: Int, length: Long, limit: Int): Int {
        require(length <= Int.MAX_VALUE && start >= 0 && length <= limit.toLong() - start) { "CPIO archive truncated" }
        return start + length.toInt()
    }
    private fun align4(value: Int) = (value + 3) and -4
    private fun pad4(out: ByteArrayOutputStream) { while (out.size() % 4 != 0) out.write(0) }
}
