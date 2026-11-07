package com.namalxhero.su.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.namalxhero.su.daemon.AllowlistRepository
import com.namalxhero.su.daemon.SuDaemonService

/**
 * Phase 3 skeleton UI: just lists currently-granted UIDs and lets you
 * revoke them, plus a manual "start daemon now" button for testing
 * before BootReceiver wiring is verified on a real device.
 *
 * TODO(Phase 3 continued): proper app list (PackageManager-resolved
 * names/icons, not raw UIDs), module/device status card (hook
 * installed? kernel API version via SuNative.getVersionInfo()), and
 * navigation to a settings screen.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val allowlist = AllowlistRepository(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AllowlistScreen(
                        allowlist = allowlist,
                        onStartDaemon = {
                            ContextCompat.startForegroundService(
                                this, Intent(this, SuDaemonService::class.java)
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AllowlistScreen(allowlist: AllowlistRepository, onStartDaemon: () -> Unit) {
    var granted by remember { mutableStateOf(allowlist.grantedUids()) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Su Manager", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = onStartDaemon) { Text("Start daemon") }

        Text("Granted apps (by UID):", modifier = Modifier.padding(top = 16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(granted) { uid ->
                Column {
                    Text("uid $uid")
                    Button(onClick = {
                        allowlist.revoke(uid)
                        granted = allowlist.grantedUids()
                    }) { Text("Revoke") }
                }
            }
        }
    }
}
