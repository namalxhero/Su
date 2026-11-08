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
// Deliberately only Home/Lock/List/Settings - these ship in
// material-icons-core (pulled in transitively by material3), unlike
// Shield/Extension which need the much heavier material-icons-extended
// artifact this project doesn't depend on. Lock stands in for
// "Superuser" (root/security), List for "Modules".
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
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
import com.namalxhero.su.modules.ModuleRepository
import com.namalxhero.su.native.SuNative
import com.namalxhero.su.ui.theme.GhostMascot
import com.namalxhero.su.ui.theme.GhostState
import com.namalxhero.su.ui.theme.GlassBackground
import com.namalxhero.su.ui.theme.GlassSurface
import com.namalxhero.su.ui.theme.SuGreen
import com.namalxhero.su.ui.theme.SuGrey
import com.namalxhero.su.ui.theme.SuManagerTheme
import com.namalxhero.su.ui.theme.SuRed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * UI/UX redesign pass: replaces the earlier flat single-style screens
 * with Su's own "liquid glass" look (GlassBackground/GlassSurface - see
 * ui/theme/GlassComponents.kt) and an animated ghost mascot (own
 * character, not a copy of any other root manager's - see
 * ui/theme/GhostMascot.kt) as the primary working/not-working signal on
 * Home, replacing the old static status dot.
 *
 * Also changes daemon startup from manual ("Start daemon" button a
 * person had to remember to tap) to automatic: MainActivity now starts
 * SuDaemonService on every app open in addition to BootReceiver's
 * existing BOOT_COMPLETED start, so a fresh install doesn't need a
 * button press before anything works. A manual "Restart daemon" action
 * still exists, moved to Settings, for troubleshooting rather than
 * routine use.
 *
 * Home now also distinguishes two different failure states that used
 * to be collapsed into one "Not detected": whether su_client/sud are
 * even installed as files (checked without needing root - /system/bin
 * is world-readable for stat() on stock Android) vs. whether the
 * kernel module is actually loaded and responding. A device with the
 * daemon installed but the module not loaded, and a device where
 * nothing has been installed at all, are different problems and now
 * get different displayed states.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val allowlist = AllowlistRepository(this)
        val moduleRepository = ModuleRepository()

        fun startDaemon() {
            ContextCompat.startForegroundService(this, Intent(this, SuDaemonService::class.java))
        }

        // Auto-start on every open, not just after a reboot (BootReceiver
        // already covers that case) - see class doc above.
        startDaemon()

        setContent {
            SuManagerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScaffold(
                        allowlist = allowlist,
                        moduleRepository = moduleRepository,
                        onRestartDaemon = ::startDaemon,
                    )
                }
            }
        }
    }
}

private enum class Destination(val labelRes: Int) {
    HOME(R.string.nav_home),
    SUPERUSER(R.string.nav_superuser),
    MODULES(R.string.nav_modules),
    SETTINGS(R.string.nav_settings),
}

data class ModuleStatus(val connected: Boolean, val apiVersion: Int, val capacity: Int)

@Composable
private fun AppScaffold(
    allowlist: AllowlistRepository,
    moduleRepository: ModuleRepository,
    onRestartDaemon: () -> Unit,
) {
    var selected by remember { mutableStateOf(Destination.HOME) }
    val granted by allowlist.observeGranted().collectAsState(initial = emptyList())
    var status by remember { mutableStateOf<ModuleStatus?>(null) }
    var daemonInstalled by remember { mutableStateOf<Boolean?>(null) }

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
        daemonInstalled = withContext(Dispatchers.IO) {
            // No root needed - /system/bin is readable (stat only, not
            // content) by any app on stock Android; this just checks
            // the client binary exists, not that it works.
            File("/system/bin/su_client").exists()
        }
    }

    GlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                GlassSurface(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    cornerRadius = 28.dp,
                ) {
                    NavigationBar(containerColor = Color.Transparent) {
                        NavigationBarItem(
                            selected = selected == Destination.HOME,
                            onClick = { selected = Destination.HOME },
                            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                            label = { Text(stringResource(Destination.HOME.labelRes)) },
                            colors = navItemColors(),
                        )
                        NavigationBarItem(
                            selected = selected == Destination.SUPERUSER,
                            onClick = { selected = Destination.SUPERUSER },
                            icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
                            label = { Text(stringResource(Destination.SUPERUSER.labelRes)) },
                            colors = navItemColors(),
                        )
                        NavigationBarItem(
                            selected = selected == Destination.MODULES,
                            onClick = { selected = Destination.MODULES },
                            icon = { Icon(Icons.Filled.List, contentDescription = null) },
                            label = { Text(stringResource(Destination.MODULES.labelRes)) },
                            colors = navItemColors(),
                        )
                        NavigationBarItem(
                            selected = selected == Destination.SETTINGS,
                            onClick = { selected = Destination.SETTINGS },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            label = { Text(stringResource(Destination.SETTINGS.labelRes)) },
                            colors = navItemColors(),
                        )
                    }
                }
            },
        ) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                when (selected) {
                    Destination.HOME -> HomeScreen(
                        status = status,
                        daemonInstalled = daemonInstalled,
                        grantedCount = granted.size,
                    )
                    Destination.SUPERUSER -> SuperuserScreen(allowlist = allowlist, granted = granted)
                    Destination.MODULES -> ModulesScreen(repository = moduleRepository)
                    Destination.SETTINGS -> SettingsScreen(onRestartDaemon = onRestartDaemon)
                }
            }
        }
    }
}

@Composable
private fun navItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = SuGreen,
    selectedTextColor = SuGreen,
    unselectedIconColor = SuGrey,
    unselectedTextColor = SuGrey,
    indicatorColor = Color.White.copy(alpha = 0.10f),
)

@Composable
private fun HomeScreen(status: ModuleStatus?, daemonInstalled: Boolean?, grantedCount: Int) {
    val ghostState = when {
        status == null || daemonInstalled == null -> GhostState.CHECKING
        status.connected -> GhostState.WORKING
        else -> GhostState.NOT_WORKING
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

        GlassSurface(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GhostMascot(state = ghostState, size = 64.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        when (ghostState) {
                            GhostState.WORKING -> stringResource(R.string.status_working)
                            GhostState.NOT_WORKING -> stringResource(R.string.status_not_working)
                            GhostState.CHECKING -> "…"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = when (ghostState) {
                            GhostState.WORKING -> SuGreen
                            GhostState.NOT_WORKING -> SuRed
                            GhostState.CHECKING -> SuGrey
                        },
                    )
                    Text(
                        if (ghostState == GhostState.WORKING)
                            stringResource(R.string.status_working_sub)
                        else stringResource(R.string.status_not_working_sub),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SuGrey,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                modifier = Modifier.weight(1f),
                label = if (daemonInstalled == true)
                    stringResource(R.string.status_daemon_installed)
                else stringResource(R.string.status_daemon_not_installed),
                valueColor = if (daemonInstalled == true) SuGreen else SuRed,
            )
            StatTile(
                modifier = Modifier.weight(1f),
                label = stringResource(R.string.home_summary_granted, grantedCount),
                valueColor = SuGreen,
            )
        }
    }
}

@Composable
private fun StatTile(modifier: Modifier = Modifier, label: String, valueColor: Color) {
    GlassSurface(modifier = modifier) {
        Box(modifier = Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = valueColor)
        }
    }
}

@Composable
private fun SuperuserScreen(allowlist: AllowlistRepository, granted: List<AllowlistEntry>) {
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            stringResource(R.string.section_granted_apps),
            style = MaterialTheme.typography.headlineMedium,
            color = SuGreen,
        )
        Text(
            stringResource(R.string.superuser_visibility_note),
            style = MaterialTheme.typography.labelSmall,
            color = SuGrey,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        if (granted.isEmpty()) {
            Text(
                stringResource(R.string.empty_granted_apps),
                style = MaterialTheme.typography.bodyMedium,
                color = SuGrey,
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
private fun GrantedAppRow(entry: AllowlistEntry, onRevoke: () -> Unit) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
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
        modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.size(40.dp))
        } else {
            Text(uid.toString().take(2), color = SuGrey, style = MaterialTheme.typography.labelSmall)
        }
    }
}
