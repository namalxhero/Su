package com.namalxhero.su.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.namalxhero.su.R
import com.namalxhero.su.daemon.AutoStartPrefs
import com.namalxhero.su.daemon.ModuleAutoloadInstaller
import com.namalxhero.su.ui.theme.GlassSurface
import com.namalxhero.su.ui.theme.SuBlack
import com.namalxhero.su.ui.theme.SuGreen
import com.namalxhero.su.ui.theme.SuGrey
import com.namalxhero.su.ui.theme.SuRed
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsScreen(onRestartDaemon: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }
    // Read once on first composition (BootReceiver's own read happens
    // independently, straight from SharedPreferences, so this is just
    // this screen's display copy) - updated both locally and in the
    // backing prefs together on toggle, below.
    var autoStartEnabled by remember { mutableStateOf(AutoStartPrefs.isEnabled(context)) }

    // Kernel module autoload ("tap to install") - see
    // tools/module_autoload/README.md and ModuleAutoloadInstaller.kt's
    // own header for what this actually does under the hood (the same
    // vendor_dlkm/modules.load steps install_vendor_dlkm.sh does, run
    // through su_client instead of a manual root shell).
    val scope = rememberCoroutineScope()
    val installer = remember { ModuleAutoloadInstaller() }
    var autoloadStatus by remember { mutableStateOf<ModuleAutoloadInstaller.Status?>(null) }
    var autoloadInstalling by remember { mutableStateOf(false) }
    var autoloadErrored by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        autoloadStatus = installer.checkStatus()
    }
    val pickKoFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        autoloadInstalling = true
        autoloadErrored = false
        scope.launch {
            // Copy into this app's own cache dir first: su_client runs
            // as root and can read it from there, but can't resolve a
            // content:// URI itself (that's this app process's
            // permission grant, not a filesystem path at all).
            val cacheFile = File(context.cacheDir, "su_core.ko")
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)

            val result = if (copied) installer.install(cacheFile) else ModuleAutoloadInstaller.InstallResult.Error
            autoloadErrored = result == ModuleAutoloadInstaller.InstallResult.Error
            autoloadStatus = installer.checkStatus()
            autoloadInstalling = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium,
            color = SuGreen,
        )

        SettingsSection(title = stringResource(R.string.settings_section_about)) {
            SettingsRow(label = stringResource(R.string.settings_version_label), value = versionName)
        }

        SettingsSection(title = stringResource(R.string.settings_section_module)) {
            SettingsRow(label = stringResource(R.string.settings_root_path_label), value = "/system/bin/su_client")
            Button(
                onClick = onRestartDaemon,
                colors = ButtonDefaults.buttonColors(containerColor = SuGreen, contentColor = SuBlack),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Text(stringResource(R.string.action_restart_daemon))
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.settings_auto_start_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = autoStartEnabled,
                    onCheckedChange = { enabled ->
                        autoStartEnabled = enabled
                        AutoStartPrefs.setEnabled(context, enabled)
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = SuGreen),
                )
            }
        }

        SettingsSection(title = stringResource(R.string.settings_section_autoload)) {
            Text(
                stringResource(R.string.settings_autoload_description),
                style = MaterialTheme.typography.bodySmall,
                color = SuGrey,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            when {
                autoloadInstalling -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp), color = SuGreen)
                        Text(stringResource(R.string.settings_autoload_installing), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                autoloadStatus?.installed == true -> {
                    Text(
                        stringResource(R.string.settings_autoload_installed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SuGreen,
                    )
                }
                else -> {
                    Button(
                        onClick = { pickKoFile.launch(arrayOf("application/octet-stream", "*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = SuGreen, contentColor = SuBlack),
                    ) {
                        Text(stringResource(R.string.settings_autoload_tap_to_install))
                    }
                    if (autoloadErrored) {
                        Text(
                            stringResource(R.string.settings_autoload_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = SuRed,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        SettingsSection(title = stringResource(R.string.settings_section_links)) {
            Text(
                stringResource(R.string.settings_view_source),
                style = MaterialTheme.typography.bodyMedium,
                color = SuGreen,
                modifier = Modifier.clickable {
                    // Public landing page is fine to hardcode here even
                    // though the repo itself is private - signed-in
                    // owners land on the repo, everyone else on GitHub's
                    // normal "private repo" notice.
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/namalxhero/Su"))
                    )
                },
            )
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = SuGrey,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
            Column(modifier = Modifier.padding(14.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsRow(label: String, value: String) {
    Text(label, style = MaterialTheme.typography.labelSmall, color = SuGrey)
    Text(value, style = MaterialTheme.typography.bodyMedium)
}
