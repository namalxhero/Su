package com.namalxhero.su.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.namalxhero.su.R
import com.namalxhero.su.daemon.AllowlistRepository
import com.namalxhero.su.daemon.SuDaemonService
import com.namalxhero.su.db.AllowlistEntry
import com.namalxhero.su.db.GrantType
import com.namalxhero.su.native.SuNative
import com.namalxhero.su.ui.theme.SuGreen
import com.namalxhero.su.ui.theme.SuGrey
import com.namalxhero.su.ui.theme.SuManagerTheme
import com.namalxhero.su.ui.theme.SuRed
import com.namalxhero.su.ui.theme.SuSurfaceVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 3 (continued): status card wired to SuNative.getVersionInfo(),
 * app list resolved to real names/icons via AllowlistRepository (Room +
 * PackageManager under the hood), swipe-free revoke button per row.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val allowlist = AllowlistRepository(this)

        setContent {
            SuManagerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
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

private data class ModuleStatus(val connected: Boolean, val apiVersion: Int, val capacity: Int)

@Composable
private fun MainScreen(allowlist: AllowlistRepository, onStartDaemon: () -> Unit) {
    val scope = rememberCoroutineScope()
    val granted by allowlist.observeGranted().collectAsState(initial = emptyList())
    var status by remember { mutableStateOf<ModuleStatus?>(null) }

    LaunchedEffect(Unit) {
        status = withContext(Dispatchers.IO) {
            val info = SuNative.getVersionInfo()
            val connected = info.isNotEmpty() && info[0] >= 0
            ModuleStatus(
                connected = connected,
                apiVersion = info.getOrElse(0) { -1 },
                capacity = info.getOrElse(1) { -1 },
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            stringResource(R.string.main_title),
            style = MaterialTheme.typography.headlineMedium,
            color = SuGreen,
        )
        Text(
            stringResource(R.string.main_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = SuGrey,
        )

        Spacer(Modifier.height(20.dp))
        StatusCard(status = status, onStartDaemon = onStartDaemon)

        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.section_granted_apps),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))

        if (granted.isEmpty()) {
            Text(
                stringResource(R.string.empty_granted_apps),
                style = MaterialTheme.typography.bodyMedium,
                color = SuGrey,
                modifier = Modifier.padding(top = 8.dp),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(granted, key = { it.uid }) { entry ->
                    GrantedAppRow(
                        entry = entry,
                        onRevoke = { scope.launch { allowlist.revoke(entry.uid) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(status: ModuleStatus?, onStartDaemon: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SuSurfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.status_card_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(10.dp))

            when (status) {
                null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = SuGreen)
                    Spacer(Modifier.width(8.dp))
                    Text("Checking…", color = SuGrey, style = MaterialTheme.typography.bodyMedium)
                }
                else -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (status.connected) SuGreen else SuRed)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (status.connected) stringResource(R.string.status_connected)
                            else stringResource(R.string.status_disconnected),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (status.connected) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.status_api_version, status.apiVersion),
                            style = MaterialTheme.typography.labelSmall,
                            color = SuGrey,
                        )
                        Text(
                            stringResource(R.string.status_allowlist_capacity, status.capacity),
                            style = MaterialTheme.typography.labelSmall,
                            color = SuGrey,
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onStartDaemon,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SuGreen),
            ) {
                Text(stringResource(R.string.action_start_daemon))
            }
        }
    }
}

@Composable
private fun GrantedAppRow(entry: AllowlistEntry, onRevoke: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SuSurfaceVariant),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(uid = entry.uid, packageName = entry.packageName)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (entry.grantType == GrantType.PERMANENT)
                        stringResource(R.string.label_granted_permanent)
                    else stringResource(R.string.label_granted_session),
                    style = MaterialTheme.typography.labelSmall,
                    color = SuGrey,
                )
            }
            TextButton(onClick = onRevoke) {
                Text(stringResource(R.string.action_revoke), color = SuRed)
            }
        }
    }
}

@Composable
private fun AppIcon(uid: Int, packageName: String?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap = remember(packageName) {
        packageName?.let {
            runCatching {
                context.packageManager.getApplicationIcon(it).toBitmap(width = 96, height = 96).asImageBitmap()
            }.getOrNull()
        }
    }
    Box(
        modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFF1C1C1C)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
        } else {
            Text(uid.toString().take(2), color = SuGrey, style = MaterialTheme.typography.labelSmall)
        }
    }
}
