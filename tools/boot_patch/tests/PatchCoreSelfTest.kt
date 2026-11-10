import com.namalxhero.su.bootpatch.BootImageInspector
import com.namalxhero.su.bootpatch.BootImageLayout
import com.namalxhero.su.bootpatch.NewcCpio
import com.namalxhero.su.bootpatch.RamdiskCodec

/** Run with kotlinc against the four bootpatch core Kotlin sources. */
fun main() {
    val entries = listOf(
        NewcCpio.Entry("init", 0x81ed, 0, 0, 1, "init-payload".toByteArray()),
        NewcCpio.Entry("etc/init/ghostly.rc", 0x81a4, 0, 0, 2, "service ghostly".toByteArray()),
    )
    val cpio = NewcCpio.build(entries)
    val parsed = NewcCpio.parse(cpio)
    check(parsed.size == 2)
    check(parsed[0].name == "init" && String(parsed[0].data) == "init-payload")
    check(parsed[1].name == "etc/init/ghostly.rc")

    val gzip = RamdiskCodec.encode(cpio, RamdiskCodec.Format.GZIP_CPIO)
    val decoded = RamdiskCodec.decode(gzip)
    check(decoded.format == RamdiskCodec.Format.GZIP_CPIO)
    check(NewcCpio.parse(decoded.cpio).size == 2)

    check(runCatching { NewcCpio.build(listOf(NewcCpio.Entry("../escape", 0, 0, 0, 0, byteArrayOf()))) }.isFailure)
    check(runCatching { NewcCpio.build(listOf(entries[0], entries[0])) }.isFailure)

    val bootHeader = ByteArray(4096)
    "ANDROID!".toByteArray().copyInto(bootHeader, 0)
    put32(bootHeader, 8, 100)
    put32(bootHeader, 16, 50)
    put32(bootHeader, 36, 2048)
    put32(bootHeader, 40, 0)
    val bootLayout = BootImageLayout.inspect(bootHeader, 6144)
    check(bootLayout != null && bootLayout.structurallyComplete)
    check(bootLayout.sections.map { it.name } == listOf("kernel", "ramdisk", "second"))

    val vendorHeader = ByteArray(4096)
    "VNDRBOOT".toByteArray().copyInto(vendorHeader, 0)
    put32(vendorHeader, 8, 3)
    put32(vendorHeader, 12, 4096)
    put32(vendorHeader, 24, 64)
    put32(vendorHeader, 2096, 2112)
    put32(vendorHeader, 2100, 32)
    val vendorLayout = BootImageLayout.inspect(vendorHeader, 12288)
    check(vendorLayout != null && vendorLayout.structurallyComplete)
    check(vendorLayout.sections.map { it.name } == listOf("vendor_ramdisk", "dtb"))

    println("Ghostly patch-core self-test: PASS (boot/vendor header layout, newc/gzip round-trip, traversal and duplicate rejection)")
}

private fun put32(bytes: ByteArray, offset: Int, value: Int) {
    bytes[offset] = value.toByte()
    bytes[offset + 1] = (value ushr 8).toByte()
    bytes[offset + 2] = (value ushr 16).toByte()
    bytes[offset + 3] = (value ushr 24).toByte()
}
