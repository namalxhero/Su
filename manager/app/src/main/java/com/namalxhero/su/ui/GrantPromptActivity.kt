package com.namalxhero.su.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.namalxhero.su.daemon.AllowlistRepository

/**
 * Phase 1 design: the manual-grant prompt. Shown by SuDaemonService
 * whenever an unknown UID calls the su client for the first time.
 *
 * TODO(Phase 3 continued): resolve `uid` to an app label/icon via
 * PackageManager (getPackagesForUid) instead of showing a bare number -
 * a raw UID means nothing to the end user making the Allow/Deny call.
 */
class GrantPromptActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uid = intent.getIntExtra(EXTRA_UID, -1)
        val allowlist = AllowlistRepository(this)

        setContent {
            MaterialTheme {
                Surface {
                    GrantPromptScreen(
                        uid = uid,
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
private fun GrantPromptScreen(uid: Int, onAllow: () -> Unit, onDeny: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Root access requested")
        // TODO: replace "UID $uid" with the resolved app name/icon.
        Text("An app (uid $uid) is requesting root access. Allow it?")
        Button(onClick = onAllow) { Text("Allow") }
        Button(onClick = onDeny) { Text("Deny") }
    }
}
