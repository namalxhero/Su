import com.namalxhero.su.bootpatch.BootImageInspector
import com.namalxhero.su.bootpatch.BootImageLayout
import com.namalxhero.su.bootpatch.BootImageWriter
import com.namalxhero.su.bootpatch.NewcCpio
import com.namalxhero.su.bootpatch.RamdiskCodec
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

private fun putLE32(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
    bytes[offset + 2] = (value ushr 16).toByte()
    bytes[offset + 3] = (value ushr 24).toByte()
}

/** Synthetic vendor_boot v3 - the one vendor_boot version this writer
 * claims to support (v4's ramdisk table isn't parsed yet). Has BOTH a
 * vendor_ramdisk and a dtb section AFTER it, so a resize of the
 * (first) ramdisk must correctly push the (second) dtb to a new
 * offset - this writer's only test so far of "resize a section that
 * ISN'T the last one" for the vendor_boot code path specifically
 * (BootImageWriterSelfTest.kt's v4-boot and v1-recovery_dtbo cases
 * both exercise that same re-layout logic, but only for BOOT kind -
 * VENDOR_BOOT uses its own, separate inspectVendor()/field-offset path
 * in BootImageLayout/BootImageWriter, so it needs its own check). */
private fun buildSyntheticVendorBootV3(): ByteArray {
    val page = 4096
    val headerSize = 2112
    val initRc = "on early-init\n    start ueventd\n"
    val cpio = NewcCpio.build(listOf(NewcCpio.Entry("init.rc", 0x81a4, 0, 0, 1, initRc.toByteArray())))
    val ramdisk = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gz -> gz.write(cpio) } }.toByteArray()
    val dtb = ByteArray(800) { (it * 7 + 3).toByte() }

    fun alignTo(a: Int, v: Int) = if (v % a == 0) v else v + (a - v % a)
    val ramdiskOffset = alignTo(page, headerSize)
    val dtbOffset = alignTo(page, ramdiskOffset + ramdisk.size)
    val total = alignTo(page, dtbOffset + dtb.size)

    val img = ByteArray(total)
    "VNDRBOOT".toByteArray().copyInto(img, 0)
    putLE32(img, 8, 3)               // header_version = 3
    putLE32(img, 12, page)           // page_size
    putLE32(img, 24, ramdisk.size)   // vendor_ramdisk_size
    putLE32(img, 2096, headerSize)   // header_size
    putLE32(img, 2100, dtb.size)     // dtb_size
    ramdisk.copyInto(img, ramdiskOffset)
    dtb.copyInto(img, dtbOffset)
    return img
}

fun main() {
    val stock = buildSyntheticVendorBootV3()
    val quick = BootImageInspector.inspect(stock, stock.size.toLong())
    check(quick.kind == BootImageInspector.Kind.VENDOR_BOOT) { "quick inspector misidentified vendor_boot: ${quick.kind}" }
    val stockLayout = BootImageLayout.inspect(stock, stock.size.toLong())
    check(stockLayout != null && stockLayout.structurallyComplete) { "synthetic vendor_boot v3 should be structurally complete" }
    check(stockLayout.sections.map { it.name } == listOf("vendor_ramdisk", "dtb"))
    val origDtbOffset = stockLayout.sections.first { it.name == "dtb" }.offset
    println("[OK] synthetic vendor_boot v3 structurally complete (orig dtb offset=$origDtbOffset)")

    val rng = java.util.Random(7)
    val fakeKo = ByteArray(6000).also { rng.nextBytes(it) } // incompressible, forces real growth

    val result = BootImageWriter.injectAutoload(stock, fakeKo)
    val newLayout = BootImageLayout.inspect(result.image, result.image.size.toLong())
    check(newLayout != null && newLayout.structurallyComplete) { "patched vendor_boot image failed to re-validate" }
    println("[OK] patched vendor_boot image re-validates as structurally complete")

    val newRamdiskSection = newLayout.sections.first { it.name == "vendor_ramdisk" }
    val newDtb = newLayout.sections.first { it.name == "dtb" }
    check(newDtb.offset != origDtbOffset) { "dtb offset should have moved after vendor_ramdisk grew - got same offset $origDtbOffset" }
    check(newDtb.size == 800L) { "dtb size corrupted: ${newDtb.size}" }
    println("[OK] dtb correctly re-laid-out after resized vendor_ramdisk (new offset=${newDtb.offset})")

    val dtbBytes = result.image.copyOfRange(newDtb.offset.toInt(), newDtb.endExclusive.toInt())
    val expectedDtb = ByteArray(800) { (it * 7 + 3).toByte() }
    check(dtbBytes.contentEquals(expectedDtb)) { "dtb content corrupted after re-layout" }
    println("[OK] dtb content byte-for-byte intact at its new offset")

    val ramdiskBytes = result.image.copyOfRange(newRamdiskSection.offset.toInt(), newRamdiskSection.endExclusive.toInt())
    val entries = NewcCpio.parse(RamdiskCodec.decode(ramdiskBytes).cpio)
    check(entries.any { it.name == "su_core.ko" && it.data.contentEquals(fakeKo) }) { "su_core.ko missing/corrupted in vendor_ramdisk" }
    check(entries.any { it.name == "init.su_core.rc" }) { "init.su_core.rc missing from vendor_ramdisk" }
    println("[OK] su_core.ko + init.su_core.rc present in the patched vendor_ramdisk")

    println("\nWriterSelfTestVendorBoot: ALL CHECKS PASSED")
}
