package com.namalxhero.su.ui

import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.namalxhero.su.daemon.AllowlistRepository
import com.namalxhero.su.util.AppResolver

/**
 * Phase 1 design: the manual-grant prompt. Shown by SuDaemonService
 * whenever an unknown UID calls the su client for the first time.
 */
class GrantPromptActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uid = intent.getIntExtra(EXTRA_UID, -1)
        val allowlist = AllowlistRepository(this)

        val resolved = AppResolver.resolve(this, uid)

        setContent {
            MaterialTheme {
                Surface {
                    GrantPromptScreen(
                        uid = uid,
                        label = resolved.label,
                        packageName = resolved.packageName,
                        icon = resolved.icon,
                        onAllow = {
                            allowlist.grant(uid)
                            finish()
                        },
                        onDeny = { finish() }
                    )
                }
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
    label: String,
    packageName: String?,
    icon: Drawable?,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Root access requested")
        icon?.let {
            Image(
                bitmap = it.toBitmap().asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp))
            )
        }
        Text(label, fontSize = 18.sp)
        Text(
            (packageName ?: "uid $uid") + " is requesting root access. Allow it?",
            fontSize = 13.sp
        )
        Button(onClick = onAllow) { Text("Allow") }
        Button(onClick = onDeny) { Text("Deny") }
    }
}
