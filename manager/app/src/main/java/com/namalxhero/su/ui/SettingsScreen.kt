package com.namalxhero.su.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.namalxhero.su.R
import com.namalxhero.su.daemon.AutoStartPrefs
import com.namalxhero.su.ui.theme.GlassSurface
import com.namalxhero.su.ui.theme.SuBlack
import com.namalxhero.su.ui.theme.SuGreen
import com.namalxhero.su.ui.theme.SuGrey

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
