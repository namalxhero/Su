package com.namalxhero.su.ui

import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.graphics.drawable.toBitmap
import com.namalxhero.su.R
import com.namalxhero.su.daemon.AllowlistRepository
import com.namalxhero.su.ui.theme.SuGreen
import com.namalxhero.su.ui.theme.SuManagerTheme
import com.namalxhero.su.ui.theme.SuRed
import com.namalxhero.su.ui.theme.SuSurfaceVariant
import com.namalxhero.su.util.AppInfoResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 1 design: the manual-grant prompt. Shown by SuDaemonService
 * whenever an unknown UID calls the su client for the first time.
 *
 * Phase 3 continued: now resolves `uid` to an app label/icon via
 * AppInfoResolver (PackageManager) instead of showing a bare number.
 */
class GrantPromptActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uid = intent.getIntExtra(EXTRA_UID, -1)
        val allowlist = AllowlistRepository(this)
        val resolver = AppInfoResolver(this)

        setContent {
            SuManagerTheme {
                GrantPromptScreen(
                    uid = uid,
                    resolver = resolver,
                    allowlist = allowlist,
                    onDismiss = { finish() },
                )
            }
        }
    }

    companion object {
        const val EXTRA_UID = "extra_uid"
    }
}

@Composable
private fun GrantPromptScreen(
    uid: Int,
    resolver: AppInfoResolver,
    allowlist: AllowlistRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf<String?>(null) }
    var icon by remember { mutableStateOf<Drawable?>(null) }

    LaunchedEffect(uid) {
        val resolved = withContext(Dispatchers.IO) { resolver.resolve(uid) }
        label = resolved.packageName?.let { resolved.label }
        icon = resolved.icon
    }

    Surface(color = Color.Transparent) {
        Box(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SuSurfaceVariant),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val bitmap = remember(icon) {
                            icon?.let { runCatching { it.toBitmap().asImageBitmap() }.getOrNull() }
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap, contentDescription = null,
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Text(stringResource(R.string.grant_prompt_title), style = MaterialTheme.typography.titleMedium)
                    }

                    Text(
                        label?.let { stringResource(R.string.grant_prompt_body, it) }
                            ?: stringResource(R.string.grant_prompt_body_unknown, uid),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.action_deny), color = SuRed)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    allowlist.grant(uid)
                                    onDismiss()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SuGreen, contentColor = Color.Black),
                        ) {
                            Text(stringResource(R.string.action_allow))
                        }
                    }
                }
            }
        }
    }
}
