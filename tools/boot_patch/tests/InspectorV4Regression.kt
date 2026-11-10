import com.namalxhero.su.bootpatch.BootImageInspector
import com.namalxhero.su.bootpatch.BootImageLayout
import com.namalxhero.su.bootpatch.NewcCpio
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

private fun putLE32(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
    bytes[offset + 2] = (value ushr 16).toByte()
    bytes[offset + 3] = (value ushr 24).toByte()
}

fun main() {
    // Mirrors the real field from the phone screenshot that exposed
    // this bug: a v4 image with a non-trivial os_version value and a
    // REAL (non-empty) ramdisk - the exact case that previously made
    // BootImageInspector read os_version as "ramdisk size" and
    // reserved[3] as "page size" instead.
    val page = 4096
    val headerSize = 1584
    val cpio = NewcCpio.build(listOf(NewcCpio.Entry("init.rc", 0x81a4, 0, 0, 1, "on early-init\n".toByteArray())))
    val ramdisk = ByteArrayOutputStream().also { GZIPOutputStream(it).use { gz -> gz.write(cpio) } }.toByteArray()
    val kernel = ByteArray(5000) { it.toByte() }

    fun alignTo(a: Int, v: Int) = if (v % a == 0) v else v + (a - v % a)
    val kernelOffset = alignTo(page, headerSize)
    val ramdiskOffset = alignTo(page, kernelOffset + kernel.size)
    val total = alignTo(page, ramdiskOffset + ramdisk.size)

    val img = ByteArray(total)
    "ANDROID!".toByteArray().copyInto(img, 0)
    putLE32(img, 8, kernel.size)         // kernel_size
    putLE32(img, 12, ramdisk.size)       // ramdisk_size (v3/v4 offset)
    putLE32(img, 16, 0x7A120011.toInt()) // os_version - a big, nonzero,
                                           // "looks like garbage if misread
                                           // as a size" value, same shape
                                           // as the screenshot's bogus
                                           // 503316901 ramdisk-size reading
    putLE32(img, 20, headerSize)         // header_size
    putLE32(img, 40, 4)                  // header_version
    putLE32(img, 1580, 0)                // signature_size
    kernel.copyInto(img, kernelOffset)
    ramdisk.copyInto(img, ramdiskOffset)

    val quick = BootImageInspector.inspect(img, img.size.toLong())
    val layout = BootImageLayout.inspect(img, img.size.toLong())
    println("[quick]  page=${quick.pageSize} ramdisk=${quick.ramdiskSize} msg=${quick.message}")
    println("[layout] page=${layout?.pageSize} ramdisk=${layout?.sections?.find { it.name == "ramdisk" }?.size}")

    check(quick.pageSize == 4096L) { "BUG STILL PRESENT: quick inspector's page size wrong for v4: ${quick.pageSize}" }
    check(quick.ramdiskSize == ramdisk.size.toLong()) { "BUG STILL PRESENT: quick inspector's ramdisk size wrong for v4: ${quick.ramdiskSize} (expected ${ramdisk.size})" }
    check(quick.supportedForInspection) { "quick inspector incorrectly flagged a valid v4 image as inconsistent" }
    check(layout != null && layout.sections.find { it.name == "ramdisk" }?.size == ramdisk.size.toLong())
    println("\n[OK] BootImageInspector and BootImageLayout now AGREE on v4 page size and ramdisk size - regression fixed")
}
