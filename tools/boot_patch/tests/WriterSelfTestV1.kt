import com.namalxhero.su.bootpatch.BootImageLayout
import com.namalxhero.su.bootpatch.BootImageWriter
import com.namalxhero.su.bootpatch.NewcCpio
import com.namalxhero.su.bootpatch.RamdiskCodec
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

private fun putLE32(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
    bytes[offset + 2] = (value ushr 16).toByte()
    bytes[offset + 3] = (value ushr 24).toByte()
}
private fun putLE64(bytes: ByteArray, offset: Int, value: Long) {
    ByteBuffer.wrap(bytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(value)
}

/** v1 legacy header WITH a real recovery_dtbo section after "second" -
 * the exact field BootImageLayout's u64 fix and BootImageWriter's
 * recovery_dtbo re-layout both target. */
private fun buildSyntheticBootV1WithRecoveryDtbo(): ByteArray {
    val page = 2048
    val headerSize = 1648
    val initRc = "on early-init\n    start ueventd\n"
    val cpio = NewcCpio.build(listOf(NewcCpio.Entry("init.rc", 0x81a4, 0, 0, 1, initRc.toByteArray())))
    val ramdisk = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gz -> gz.write(cpio) } }.toByteArray()
    val kernel = ByteArray(300) { it.toByte() }
    val recoveryDtbo = ByteArray(150) { (it * 3).toByte() }

    fun alignTo(a: Int, v: Int) = if (v % a == 0) v else v + (a - v % a)
    val kernelOffset = alignTo(page, headerSize)
    val ramdiskOffset = alignTo(page, kernelOffset + kernel.size)
    val secondOffset = alignTo(page, ramdiskOffset + ramdisk.size) // second_size = 0, section still "occupies" this aligned slot conceptually
    val recoveryOffset = secondOffset // second is zero-size, so recovery starts right after ramdisk's aligned end
    val total = alignTo(page, recoveryOffset + recoveryDtbo.size)

    val img = ByteArray(total)
    "ANDROID!".toByteArray().copyInto(img, 0)
    putLE32(img, 8, kernel.size)     // kernel_size
    putLE32(img, 16, ramdisk.size)   // ramdisk_size
    putLE32(img, 24, 0)              // second_size
    putLE32(img, 36, page)           // page_size
    putLE32(img, 40, 1)              // header_version = 1
    putLE32(img, 1632, recoveryDtbo.size) // recovery_dtbo_size
    putLE64(img, 1636, recoveryOffset.toLong()) // recovery_dtbo_offset (real uint64 field)
    kernel.copyInto(img, kernelOffset)
    ramdisk.copyInto(img, ramdiskOffset)
    recoveryDtbo.copyInto(img, recoveryOffset)
    return img
}

fun main() {
    val stock = buildSyntheticBootV1WithRecoveryDtbo()
    val stockLayout = BootImageLayout.inspect(stock, stock.size.toLong())
    check(stockLayout != null && stockLayout.structurallyComplete) { "synthetic v1+recovery_dtbo image should be structurally complete" }
    check(stockLayout.sections.map { it.name } == listOf("kernel", "ramdisk", "second", "recovery_dtbo"))
    val origRecoveryOffset = stockLayout.sections.first { it.name == "recovery_dtbo" }.offset
    println("[OK] synthetic v1 image with recovery_dtbo is structurally complete (orig recovery_dtbo offset=$origRecoveryOffset)")

    // A su_core.ko bigger than the ORIGINAL ramdisk's compressed size -
    // pseudo-random (not a repeating ramp, which gzip would shrink back
    // down to nothing) so the ramdisk's on-disk footprint genuinely
    // grows across a page boundary, forcing recovery_dtbo's offset to
    // actually move, not coincidentally stay the same.
    val rng = java.util.Random(42)
    val fakeKo = ByteArray(6000).also { rng.nextBytes(it) }
    val result = BootImageWriter.injectAutoload(stock, fakeKo)

    val newLayout = BootImageLayout.inspect(result.image, result.image.size.toLong())
    check(newLayout != null && newLayout.structurallyComplete) { "patched v1+recovery_dtbo image failed to re-validate" }
    val newRecovery = newLayout.sections.first { it.name == "recovery_dtbo" }
    check(newRecovery.size == 150L) { "recovery_dtbo size corrupted: ${newRecovery.size}" }
    println("[OK] patched image re-validates; recovery_dtbo new offset=${newRecovery.offset} (moved: ${newRecovery.offset != origRecoveryOffset})")
    check(newRecovery.offset != origRecoveryOffset) { "recovery_dtbo offset should have moved since ramdisk grew across a page boundary - if this fails, the header field is stale/wrong" }

    // Content integrity: recovery_dtbo bytes themselves must be
    // byte-for-byte preserved at their NEW location.
    val recoveredBytes = result.image.copyOfRange(newRecovery.offset.toInt(), newRecovery.endExclusive.toInt())
    val expected = ByteArray(150) { (it * 3).toByte() }
    check(recoveredBytes.contentEquals(expected)) { "recovery_dtbo content corrupted after re-layout" }
    println("[OK] recovery_dtbo content byte-for-byte intact at its new, correctly-pointed-to offset")

    println("\nWriterSelfTestV1 (recovery_dtbo re-layout): ALL CHECKS PASSED")
}
