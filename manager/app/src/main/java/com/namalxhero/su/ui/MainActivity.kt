package com.namalxhero.su.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.namalxhero.su.daemon.AllowlistRepository
import com.namalxhero.su.daemon.SuDaemonService
import com.namalxhero.su.native.SuNative
import com.namalxhero.su.util.AppResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 3: lists currently-granted apps (resolved to a label/icon, not
 * raw UIDs), a device status card backed by SuNative.getVersionInfo(),
 * and a manual "start daemon now" button for testing before
 * BootReceiver wiring is verified on a real device.
 *
 * TODO(Phase 3 continued): navigation to a settings screen.
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

/** installed == false also covers "module not loaded" and "/dev/su_ctl
 * missing" - SuNative.getVersionInfo() returns [-1, -1] in both cases,
 * and there is no way from userspace to tell those two apart. */
private data class DeviceStatus(val installed: Boolean, val apiVersion: Int, val maxAllowlist: Int)

@Composable
private fun AllowlistScreen(allowlist: AllowlistRepository, onStartDaemon: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(allowlist.grantedUids()) }
    var status by remember { mutableStateOf<DeviceStatus?>(null) }

    LaunchedEffect(Unit) {
        val info = withContext(Dispatchers.IO) { SuNative.getVersionInfo() }
        status = DeviceStatus(
            installed = info.size == 2 && info[0] >= 0,
            apiVersion = info.getOrElse(0) { -1 },
            maxAllowlist = info.getOrElse(1) { -1 }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Su Manager", style = MaterialTheme.typography.headlineSmall)

        Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(16.dp)) {
                when (val st = status) {
                    null -> Text("Checking kernel module...")
                    else -> if (st.installed) {
                        Text("Module active", fontWeight = FontWeight.Bold)
                        Text("API version ${st.apiVersion} · allowlist capacity ${st.maxAllowlist}", fontSize = 12.sp)
                    } else {
                        Text("Module not detected", fontWeight = FontWeight.Bold)
                        Text("/dev/su_ctl did not respond - is the kernel module loaded?", fontSize = 12.sp)
                    }
                }
            }
        }

        Button(modifier = Modifier.padding(top = 12.dp), onClick = onStartDaemon) { Text("Start daemon") }

        Text("Granted apps:", modifier = Modifier.padding(top = 16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(granted) { uid ->
                GrantedAppRow(
                    context = context,
                    uid = uid,
                    grantedAt = allowlist.grantedAt(uid),
                    onRevoke = {
                        allowlist.revoke(uid)
                        granted = allowlist.grantedUids()
                    }
                )
            }
        }
    }
}

@Composable
private fun GrantedAppRow(
    context: android.content.Context,
    uid: Int,
    grantedAt: Long,
    onRevoke: () -> Unit
) {
    // Resolution is cheap (single PackageManager lookup) and this list
    // is short, so doing it inline per-row is fine for this skeleton.
    val resolved = remember(uid) { AppResolver.resolve(context, uid) }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        resolved.icon?.let {
            Image(
                bitmap = it.toBitmap().asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
            )
        }
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Text(resolved.label)
            Text(resolved.packageName ?: "uid $uid", fontSize = 11.sp)
        }
        Button(onClick = onRevoke) { Text("Revoke") }
    }
}
