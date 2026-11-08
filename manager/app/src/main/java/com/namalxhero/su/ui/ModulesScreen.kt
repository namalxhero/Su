package com.namalxhero.su.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.namalxhero.su.R
import com.namalxhero.su.modules.ModuleRepository
import com.namalxhero.su.ui.theme.GlassSurface
import com.namalxhero.su.ui.theme.SuGreen
import com.namalxhero.su.ui.theme.SuGrey
import com.namalxhero.su.ui.theme.SuRed
import kotlinx.coroutines.launch

private sealed class ModulesUiState {
    object Loading : ModulesUiState()
    object Error : ModulesUiState()
    data class Loaded(val modules: List<ModuleRepository.ModuleInfo>) : ModulesUiState()
}

@Composable
fun ModulesScreen(repository: ModuleRepository) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ModulesUiState>(ModulesUiState.Loading) }

    fun reload() {
        scope.launch {
            state = ModulesUiState.Loading
            state = when (val result = repository.listModules()) {
                is ModuleRepository.ListResult.Success -> ModulesUiState.Loaded(result.modules)
                ModuleRepository.ListResult.Error -> ModulesUiState.Error
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text(
            stringResource(R.string.modules_title),
            style = MaterialTheme.typography.headlineMedium,
            color = SuGreen,
        )
        Text(
            stringResource(R.string.modules_first_use_note),
            style = MaterialTheme.typography.labelSmall,
            color = SuGrey,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )

        when (val s = state) {
            ModulesUiState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = SuGreen)
            }
            ModulesUiState.Error -> Column {
                Text(
                    stringResource(R.string.modules_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SuRed,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
                Button(onClick = { reload() }) { Text(stringResource(R.string.action_retry)) }
            }
            is ModulesUiState.Loaded -> {
                if (s.modules.isEmpty()) {
                    Text(
                        stringResource(R.string.modules_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = SuGrey,
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(s.modules, key = { it.id }) { module ->
                            ModuleRow(
                                module = module,
                                onToggle = { enabled ->
                                    scope.launch {
                                        repository.setEnabled(module.id, enabled)
                                        reload()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModuleRow(module: ModuleRepository.ModuleInfo, onToggle: (Boolean) -> Unit) {
    GlassSurface(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(module.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.modules_version_label, module.version),
                    style = MaterialTheme.typography.labelSmall,
                    color = SuGrey,
                )
                if (module.description.isNotBlank()) {
                    Text(
                        module.description,
                        style = MaterialTheme.typography.labelSmall,
                        color = SuGrey,
                    )
                }
            }
            Switch(
                checked = module.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = SuGreen),
            )
        }
    }
}
