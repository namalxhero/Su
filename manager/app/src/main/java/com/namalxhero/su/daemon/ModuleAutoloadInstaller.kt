package com.namalxhero.su.daemon

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The in-app "tap to install" counterpart to
 * ../../../../../../../tools/module_autoload/install_vendor_dlkm.sh -
 * same vendor_dlkm/modules.load mechanism, same steps, just run
 * through su_client from inside the app instead of a manual adb/root
 * shell session. See that script's own comments for *why* this
 * mechanism (native GKI module autoload via init) rather than a boot
 * script or daemon - not repeated here.
 *
 * Follows the same pattern as modules/ModuleRepository.kt: this app's
 * own process has no permission to touch /vendor_dlkm, so every step
 * shells out through su_client - same binary, same allow/deny flow
 * (first use here prompts the grant dialog exactly like the first
 * Modules-tab action does, for the same reason).
 *
 * su_core.ko itself is never bundled in the APK (it's built per-device
 * from kernel/ - see the top-level README's "Integration") - the
 * caller is expected to have already copied whatever file the user
 * picked (via Storage Access Framework) into [koFile], a location this
 * app's own process CAN read, before calling [install]; su_client then
 * reads it from there with root.
 */
class ModuleAutoloadInstaller {

    data class Status(
        val installed: Boolean,
        /** Which of /vendor_dlkm or /vendor su_core.ko would be (or
         * is) installed under - null if root/su_client couldn't even
         * be reached to check, distinct from a confirmed "not
         * installed". */
        val modulesDir: String?,
    )

    sealed class InstallResult {
        object Success : InstallResult()
        /** su_client missing, not granted, or the script itself
         * exited non-zero (e.g. neither vendor_dlkm nor vendor/lib/
         * modules exists on this device - same case
         * install_vendor_dlkm.sh itself refuses rather than guesses). */
        object Error : InstallResult()
    }

    suspend fun checkStatus(): Status = withContext(Dispatchers.IO) {
        val output = runRootShell(CHECK_SCRIPT) ?: return@withContext Status(installed = false, modulesDir = null)
        val lines = output.lines()
        val dir = lines.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() }
        val installed = lines.getOrNull(1)?.trim() == "1"
        Status(installed = installed, modulesDir = dir)
    }

    /** @param koFile a `su_core.ko` this app's own process can already
     * read (e.g. copied from a Storage Access Framework pick into
     * context.cacheDir) - NOT necessarily where it ends up on-device. */
    suspend fun install(koFile: File): InstallResult = withContext(Dispatchers.IO) {
        if (!koFile.canRead()) return@withContext InstallResult.Error
        // koFile's path is this app's own cache dir, not user input -
        // still quoted below the same as everything else that reaches
        // the shell script, on principle (see runRootShell's own note).
        val script = INSTALL_SCRIPT_TEMPLATE.replace("__KO_SRC__", koFile.absolutePath)
        if (runRootShell(script) != null) InstallResult.Success else InstallResult.Error
    }

    private fun runRootShell(script: String): String? {
        return try {
            val process = ProcessBuilder(SU_CLIENT_PATH, "-c", script)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exited = process.waitFor()
            if (exited != 0) null else output
        } catch (_: Exception) {
            null // su_client not present at SU_CLIENT_PATH, or not executable
        }
    }

    companion object {
        // Mirrors kernel/su_hook.c's SU_CLIENT_PATH - see
        // ModuleRepository.kt's own companion comment on this being an
        // un-deduplicated hand-copy, same gap, not solved here either.
        private const val SU_CLIENT_PATH = "/system/bin/su_client"

        private const val MODULE_NAME = "su_core.ko"

        // Prints the modules dir this device actually has (first line)
        // and whether su_core.ko is both present there AND listed in
        // modules.load (second line, "1" or "0") - one su_client
        // round-trip, mirrors install_vendor_dlkm.sh's own directory
        // detection exactly (vendor_dlkm preferred, vendor fallback).
        private val CHECK_SCRIPT = """
            if [ -d /vendor_dlkm/lib/modules ]; then d=/vendor_dlkm/lib/modules
            elif [ -d /vendor/lib/modules ]; then d=/vendor/lib/modules
            else d=""; fi
            echo "${'$'}d"
            if [ -n "${'$'}d" ] && [ -f "${'$'}d/$MODULE_NAME" ] && grep -qx "$MODULE_NAME" "${'$'}d/modules.load" 2>/dev/null; then
                echo 1
            else
                echo 0
            fi
        """.trimIndent()

        // Same steps as install_vendor_dlkm.sh, condensed to one
        // su_client round-trip - that script's own comments explain
        // each one (directory detection, remount rw, append-not-
        // duplicate into modules.load, remount back ro).
        // __KO_SRC__ is substituted with this app's own cache-dir path
        // before this ever reaches the shell - never user-typed text,
        // but still shell-quoted the same as everything else here.
        private val INSTALL_SCRIPT_TEMPLATE = """
            set -e
            if [ -d /vendor_dlkm/lib/modules ]; then d=/vendor_dlkm/lib/modules
            elif [ -d /vendor/lib/modules ]; then d=/vendor/lib/modules
            else echo "no vendor_dlkm or vendor/lib/modules on this device" >&2; exit 1; fi
            mp=${'$'}(echo "${'$'}d" | grep -o '^/[^/]*')
            mount -o remount,rw "${'$'}mp"
            mkdir -p "${'$'}d"
            cp "__KO_SRC__" "${'$'}d/$MODULE_NAME"
            chmod 644 "${'$'}d/$MODULE_NAME"
            if [ -f "${'$'}d/modules.load" ] && grep -qx "$MODULE_NAME" "${'$'}d/modules.load"; then
                :
            else
                echo "$MODULE_NAME" >> "${'$'}d/modules.load"
            fi
            mount -o remount,ro "${'$'}mp" || true
        """.trimIndent()
    }
}
