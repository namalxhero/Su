package com.namalxhero.su.bootpatch

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.namalxhero.su.ui.theme.GlassSurface
import com.namalxhero.su.ui.theme.SuGrey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private val SuAccent = Color(0xFFCEB0FF)
private val SuError = Color(0xFFFF8A80)

@Composable
fun BootPatcherScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var imageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var imageName by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<BootImageInspector.Result?>(null) }
    var layout by remember { mutableStateOf<BootImageLayout.Layout?>(null) }
    var inspecting by remember { mutableStateOf(false) }
    var inspectError by remember { mutableStateOf<String?>(null) }

    var koUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var koName by remember { mutableStateOf<String?>(null) }

    var patching by remember { mutableStateOf(false) }
    var patchError by remember { mutableStateOf<String?>(null) }
    var patchNotes by remember { mutableStateOf<List<String>?>(null) }
    var pendingPatchedImage by remember { mutableStateOf<ByteArray?>(null) }
    var saved by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            imageUri = uri
            inspecting = true; inspectError = null; result = null; layout = null
            patchError = null; patchNotes = null; pendingPatchedImage = null; saved = false
            imageName = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected image"
            scope.launch {
                try {
                    val inspected = withContext(Dispatchers.IO) {
                        val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                        val header = context.contentResolver.openInputStream(uri)?.use { input ->
                            val out = ByteArray(4096); var count = 0
                            while (count < out.size) { val n = input.read(out, count, out.size - count); if (n <= 0) break; count += n }
                            out.copyOf(count)
                        } ?: throw IllegalStateException("Unable to read selected image")
                        val actualLength = if (length >= 0L) length else Long.MAX_VALUE
                        Pair(BootImageInspector.inspect(header, actualLength), BootImageLayout.inspect(header, actualLength))
                    }
                    result = inspected.first
                    layout = inspected.second
                } catch (t: Throwable) { inspectError = t.message ?: "Image inspection failed" }
                finally { inspecting = false }
            }
        }
    }

    val koPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            koUri = uri
            koName = uri.lastPathSegment?.substringAfterLast('/') ?: "su_core.ko"
            patchError = null; patchNotes = null; pendingPatchedImage = null; saved = false
        }
    }

    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val bytes = pendingPatchedImage
        if (uri != null && bytes != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                            ?: throw IllegalStateException("Unable to open output location")
                    }
                    saved = true
                } catch (t: Throwable) { patchError = t.message ?: "Failed to save patched image" }
            }
        }
    }

    fun runPatch() {
        val srcUri = imageUri ?: return
        val koSrcUri = koUri ?: return
        patching = true; patchError = null; patchNotes = null; pendingPatchedImage = null; saved = false
        scope.launch {
            try {
                val patchResult = withContext(Dispatchers.IO) {
                    val imageBytes = context.contentResolver.openInputStream(srcUri)?.use { it.readAllBytesCompat() }
                        ?: throw IllegalStateException("Unable to read the selected image")
                    val koBytes = context.contentResolver.openInputStream(koSrcUri)?.use { it.readAllBytesCompat() }
                        ?: throw IllegalStateException("Unable to read su_core.ko")
                    BootImageWriter.injectAutoload(imageBytes, koBytes)
                }
                patchNotes = patchResult.notes
                pendingPatchedImage = patchResult.image
                val suggestedName = (imageName ?: "boot.img").substringBeforeLast('.') + "_ghostly.img"
                savePicker.launch(suggestedName)
            } catch (t: Throwable) {
                patchError = t.message ?: "Patch failed"
            } finally {
                patching = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Ghostly Patcher", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Text("Embeds su_core.ko's autoload into a boot image - no daemon, no Magisk-style init replacement", style = MaterialTheme.typography.bodyMedium, color = SuGrey)

        GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
            Column(Modifier.fillMaxWidth().padding(18.dp)) {
                Text("1. Boot image", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(imageName ?: "No image selected (boot.img / init_boot.img / vendor_boot.img)", color = SuGrey)
                Spacer(Modifier.height(14.dp))
                Button(onClick = { imagePicker.launch(arrayOf("application/octet-stream", "*/*")) }, enabled = !inspecting && !patching) {
                    Text("Select image")
                }
            }
        }
        if (inspecting) Text("Inspecting image header…", color = SuGrey)
        inspectError?.let { GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 16.dp) { Text(it, Modifier.padding(16.dp), color = SuError) } }

        result?.let { r ->
            GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Detection", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text("Type: ${r.kind}", color = Color.White)
                    Text("Header version: ${r.headerVersion ?: "Unknown"}", color = SuGrey)
                    r.pageSize?.let { Text("Page size: $it", color = SuGrey) }
                    r.kernelSize?.let { Text("Kernel size: $it bytes", color = SuGrey) }
                    r.ramdiskSize?.let { Text("Ramdisk size: $it bytes", color = SuGrey) }
                    r.vendorRamdiskSize?.let { Text("Vendor ramdisk size: $it bytes", color = SuGrey) }
                    Text(r.message, color = if (r.supportedForInspection) SuAccent else SuError)
                }
            }
        }
        layout?.let { l ->
            GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Structural layout", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text("${l.kind} · header v${l.version} · page ${l.pageSize}", color = SuGrey)
                    l.sections.forEach { section ->
                        Text("${section.name}: offset ${section.offset} · ${section.size} bytes", color = SuAccent)
                    }
                    l.notes.forEach { note -> Text("• $note", color = SuGrey, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        if (l.structurallyComplete) "Structure fully validated - eligible for patching below" else "Not eligible for patching yet - structure not fully validated",
                        color = if (l.structurallyComplete) SuAccent else SuError,
                    )
                }
            }
        }

        if (layout?.structurallyComplete == true) {
            GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("2. su_core.ko", style = MaterialTheme.typography.titleMedium, color = Color.White)
                    Text("Built for this exact device's kernel - this screen can't verify that match itself.", color = SuGrey, style = MaterialTheme.typography.bodySmall)
                    Text(koName ?: "No module selected", color = SuGrey)
                    Button(onClick = { koPicker.launch(arrayOf("application/octet-stream", "*/*")) }, enabled = !patching) {
                        Text("Select su_core.ko")
                    }
                    if (koUri != null) {
                        Spacer(Modifier.height(4.dp))
                        Text("3. Patch", style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Button(
                            onClick = { runPatch() },
                            enabled = !patching,
                            colors = ButtonDefaults.buttonColors(containerColor = SuAccent, contentColor = Color.Black),
                        ) {
                            Text(if (patching) "Patching…" else "Patch and save")
                        }
                        if (patching) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp), color = SuAccent)
                                Text("Embedding su_core.ko and rebuilding the image…", color = SuGrey)
                            }
                        }
                    }
                }
            }
        }

        patchError?.let { GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 16.dp) { Text(it, Modifier.padding(16.dp), color = SuError) } }
        patchNotes?.let { notes ->
            GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 24.dp) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (saved) "Patched and saved" else "Patched - choose where to save", style = MaterialTheme.typography.titleMedium, color = if (saved) SuAccent else Color.White)
                    notes.forEach { Text("• $it", color = SuGrey, style = MaterialTheme.typography.bodySmall) }
                    Text(
                        "Test with \"fastboot boot <file>\" BEFORE \"fastboot flash boot/init_boot\" - same safety order as tools/boot_patch/.",
                        color = SuError, style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        GlassSurface(Modifier.fillMaxWidth(), cornerRadius = 20.dp) {
            Column(Modifier.padding(16.dp)) {
                Text("Patch engine status", style = MaterialTheme.typography.titleSmall, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Patches boot v0-v2, boot v3, boot v4 (GKI 2.0's own boot.img/init_boot.img format), and vendor_boot v3. " +
                        "vendor_boot v4 (vendor ramdisk table + bootconfig) and LZ4-compressed ramdisks are not supported yet - " +
                        "refused outright rather than guessed at.",
                    color = SuGrey,
                )
            }
        }
    }
}

private fun java.io.InputStream.readAllBytesCompat(): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(65536)
    while (true) {
        val n = read(buffer)
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
