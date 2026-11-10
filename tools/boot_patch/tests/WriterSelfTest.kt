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

/** Builds a synthetic, structurally valid boot.img v4 (GKI 2.0's own
 * format) in memory: header + kernel + gzip'd-cpio ramdisk (containing
 * a top-level init.rc with one existing `import` line, like a real
 * AOSP-derived ramdisk) + a zero-length boot_signature. */
private fun buildSyntheticBootV4(): ByteArray {
    val page = 4096
    val headerSize = 1584
    val initRc = "import /init.usb.rc\n\non early-init\n    start ueventd\n"
    val cpio = NewcCpio.build(listOf(
        NewcCpio.Entry("init.rc", 0x81a4, 0, 0, 1, initRc.toByteArray()),
        NewcCpio.Entry("init.usb.rc", 0x81a4, 0, 0, 1, "".toByteArray()),
    ))
    val ramdisk = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gz -> gz.write(cpio) } }.toByteArray()
    val kernel = ByteArray(5000) { it.toByte() }

    fun alignTo(a: Int, v: Int) = if (v % a == 0) v else v + (a - v % a)
    val kernelOffset = alignTo(page, headerSize)
    val ramdiskOffset = alignTo(page, kernelOffset + kernel.size)
    val sigOffset = alignTo(page, ramdiskOffset + ramdisk.size)
    val total = alignTo(page, sigOffset) // zero-length signature

    val img = ByteArray(total)
    "ANDROID!".toByteArray().copyInto(img, 0)
    putLE32(img, 8, kernel.size)   // kernel_size
    putLE32(img, 12, ramdisk.size) // ramdisk_size
    putLE32(img, 16, 0)            // os_version
    putLE32(img, 20, headerSize)   // header_size
    putLE32(img, 40, 4)            // header_version
    putLE32(img, 1580, 0)          // signature_size = 0
    kernel.copyInto(img, kernelOffset)
    ramdisk.copyInto(img, ramdiskOffset)
    return img
}

fun main() {
    val stock = buildSyntheticBootV4()
    val stockLayout = BootImageLayout.inspect(stock, stock.size.toLong())
    check(stockLayout != null && stockLayout.structurallyComplete) { "synthetic v4 image itself should be structurally complete (BootImageLayout's own v4 fix)" }
    check(stockLayout.sections.map { it.name } == listOf("kernel", "ramdisk", "boot_signature"))
    println("[OK] synthetic boot v4 image is structurally complete (v4 fix verified)")

    val fakeKo = ByteArray(2048) { (it % 256).toByte() } // stand-in su_core.ko bytes
    val result = BootImageWriter.injectAutoload(stock, fakeKo)
    println("[OK] injectAutoload() produced ${result.image.size} bytes (stock was ${stock.size})")

    // The patched image must itself still be a structurally complete,
    // re-parseable boot image - not just "didn't crash".
    val newLayout = BootImageLayout.inspect(result.image, result.image.size.toLong())
    check(newLayout != null && newLayout.structurallyComplete) { "patched image failed to re-validate as structurally complete" }
    println("[OK] patched image re-validates as structurally complete")

    val newRamdiskSection = newLayout.sections.first { it.name == "ramdisk" }
    val newRamdiskBytes = result.image.copyOfRange(newRamdiskSection.offset.toInt(), newRamdiskSection.endExclusive.toInt())
    val decoded = RamdiskCodec.decode(newRamdiskBytes)
    val entries = NewcCpio.parse(decoded.cpio)
    val ko = entries.find { it.name == "su_core.ko" }
    val rc = entries.find { it.name == "init.su_core.rc" }
    val initRc = entries.find { it.name == "init.rc" }
    check(ko != null && ko.data.contentEquals(fakeKo)) { "su_core.ko missing or corrupted in patched ramdisk" }
    check(rc != null && "insmod /su_core.ko" in String(rc.data)) { "init.su_core.rc missing or wrong content" }
    check(initRc != null && "import /init.su_core.rc" in String(initRc.data)) { "init.rc wasn't wired to import init.su_core.rc" }
    check(initRc.data.toString(Charsets.UTF_8).contains("import /init.usb.rc")) { "original init.rc content (other imports) was lost, not preserved" }
    println("[OK] su_core.ko + init.su_core.rc present in patched ramdisk, init.rc correctly wired, original init.rc content preserved")

    // boot_signature (which came AFTER the ramdisk, so its offset must
    // have shifted since ramdisk grew) must still be present, zero-length,
    // and correctly located - proves downstream-section re-layout works,
    // not just "ramdisk got bigger".
    val sig = newLayout.sections.first { it.name == "boot_signature" }
    check(sig.size == 0L) { "boot_signature size corrupted" }
    check(sig.offset >= newRamdiskSection.endExclusive) { "boot_signature offset overlaps the resized ramdisk" }
    println("[OK] boot_signature correctly re-laid-out after the resized ramdisk (offset=${sig.offset})")

    // Re-patching an already-patched image should replace, not duplicate.
    val twicePatched = BootImageWriter.injectAutoload(result.image, fakeKo)
    val twiceLayout = BootImageLayout.inspect(twicePatched.image, twicePatched.image.size.toLong())
    check(twiceLayout != null && twiceLayout.structurallyComplete)
    val twiceRamdisk = twicePatched.image.copyOfRange(
        twiceLayout.sections.first { it.name == "ramdisk" }.offset.toInt(),
        twiceLayout.sections.first { it.name == "ramdisk" }.endExclusive.toInt(),
    )
    val twiceEntries = NewcCpio.parse(RamdiskCodec.decode(twiceRamdisk).cpio)
    check(twiceEntries.count { it.name == "su_core.ko" } == 1) { "re-patching duplicated su_core.ko instead of replacing it" }
    println("[OK] re-patching an already-patched image replaces su_core.ko/init.su_core.rc, doesn't duplicate")

    println("\nBootImageWriter self-test: ALL CHECKS PASSED")
}
