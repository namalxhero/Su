package com.namalxhero.su.modules

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lists and toggles Magisk-format modules under /data/adb/modules (see
 * docs/PHASE4_DESIGN.md's "Module layout"). This app's own process has
 * no filesystem permission to read that directory directly (root-owned,
 * 700 on a real device) - every operation here shells out through
 * su_client, the same binary daemon/sud.c and the kernel's execve hook
 * already assume exists at /system/bin/su_client.
 *
 * IMPORTANT, first-run behaviour to expect: this app's own UID is not
 * special-cased anywhere in the kernel allowlist (see kernel/su_ioctl.c)
 * - calling su_client from here goes through exactly the same
 * allow/deny flow as any other app's su request. The first time a
 * Modules-tab action runs, the person will see Su's own GrantPromptActivity
 * asking them to allow Su to have root. That is expected, not a bug;
 * ModulesScreen surfaces this via R.string.modules_first_use_note so it
 * isn't a surprise.
 */
class ModuleRepository {

    data class ModuleInfo(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val enabled: Boolean,
    )

    sealed class ListResult {
        data class Success(val modules: List<ModuleInfo>) : ListResult()
        /** su_client ran but returned nothing usable - no root, no
         * daemon, or /data/adb/modules genuinely doesn't exist. Can't
         * tell these apart from exit code alone without parsing
         * su_client's own stderr, which isn't structured - surfaced as
         * one generic error state (R.string.modules_error) rather than
         * guessing which. */
        object Error : ListResult()
    }

    suspend fun listModules(): ListResult = withContext(Dispatchers.IO) {
        // One shell command does the whole walk: for each module dir,
        // print a delimiter + module.prop's contents + whether a
        // `disable` marker file exists. Keeps this to a single
        // su_client round-trip instead of one per module.
        val script = """
            for d in /data/adb/modules/*/; do
                [ -d "${'$'}d" ] || continue
                id=${'$'}(basename "${'$'}d")
                echo "===MODULE ${'$'}id"
                cat "${'$'}d/module.prop" 2>/dev/null
                if [ -f "${'$'}d/disable" ]; then echo "DISABLED=1"; else echo "DISABLED=0"; fi
            done
        """.trimIndent()

        val output = runRootShell(script) ?: return@withContext ListResult.Error
        ListResult.Success(parseListing(output))
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val safeId = id.replace(Regex("[^A-Za-z0-9._-]"), "")
        if (safeId != id || safeId.isEmpty()) return@withContext false // reject anything that isn't a plain module id - no shell-metacharacter smuggling via a crafted id

        val cmd = if (enabled) {
            "rm -f /data/adb/modules/$safeId/disable"
        } else {
            "touch /data/adb/modules/$safeId/disable"
        }
        runRootShell(cmd) != null
    }

    /**
     * Returns null on any failure (su_client missing, exec failed, not
     * granted root, non-zero exit) - callers treat null as "couldn't
     * read/write", not as "empty result".
     */
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

    private fun parseListing(raw: String): List<ModuleInfo> {
        val modules = mutableListOf<ModuleInfo>()
        var id = ""
        var name = ""
        var version = ""
        var author = ""
        var description = ""
        var disabled = false
        var inModule = false

        fun flush() {
            if (inModule && id.isNotEmpty()) {
                modules += ModuleInfo(
                    id = id,
                    name = name.ifEmpty { id },
                    version = version,
                    author = author,
                    description = description,
                    enabled = !disabled,
                )
            }
        }

        for (line in raw.lineSequence()) {
            if (line.startsWith("===MODULE ")) {
                flush()
                id = line.removePrefix("===MODULE ").trim()
                name = ""; version = ""; author = ""; description = ""; disabled = false
                inModule = true
                continue
            }
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            when (key) {
                "name" -> name = value
                "version" -> version = value
                "author" -> author = value
                "description" -> description = value
                "DISABLED" -> disabled = value == "1"
            }
        }
        flush()
        return modules
    }

    companion object {
        // Mirrors kernel/su_hook.c's SU_CLIENT_PATH exactly - this is
        // the project's third copy of that literal path (kernel/,
        // daemon/sud.c's boot trigger is separate, and now here). No
        // shared config file exists yet, same hand-duplication gap
        // already flagged for daemon/sud.c's overlay-target list in
        // kernel/su_mount_isolate.c - not solved here either.
        private const val SU_CLIENT_PATH = "/system/bin/su_client"
    }
}
