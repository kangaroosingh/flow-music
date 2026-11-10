package io.github.aedev.flow.ui.screens.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.smart.SmartDownloadPolicy
import io.github.aedev.flow.data.music.smart.SmartDownloadResult

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SmartDownloadControls(vm: SmartDownloadsViewModel) {
    val current by vm.state.collectAsStateWithLifecycle()
    val state = current ?: return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.fm_smart_help), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.fm_smart_enabled), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = state.settings.enabled, onCheckedChange = vm::enabled, enabled = state.settings.networkConfigured)
        }
        Text(stringResource(R.string.fm_smart_network), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.settings.networkConfigured && state.wifiOnly, onClick = {
                vm.network(true)
            }, label = { Text(stringResource(R.string.fm_smart_wifi)) })
            FilterChip(selected = state.settings.networkConfigured && !state.wifiOnly, onClick = {
                vm.network(false)
            }, label = { Text(stringResource(R.string.fm_smart_any)) })
        }
        Text(stringResource(R.string.fm_smart_network_help), style = MaterialTheme.typography.bodySmall)
        val chargingLabel = stringResource(R.string.fm_smart_charging)
        Row(Modifier.fillMaxWidth()) {
            Text(chargingLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = state.chargingOnly,
                onCheckedChange = vm::charging,
                modifier = Modifier.semantics { contentDescription = chargingLabel },
            )
        }
        Text(stringResource(R.string.fm_smart_limit), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmartDownloadPolicy.songLimits.forEach { count ->
                FilterChip(selected = state.settings.maxSongs == count, onClick = {
                    vm.limit(count)
                }, label = { Text(stringResource(R.string.fm_smart_song_count, count)) })
            }
        }
        Text(
            stringResource(R.string.fm_smart_counts, state.ready, state.used, state.settings.maxSongs),
            style = MaterialTheme.typography.bodyMedium,
        )
        val status =
            when {
                state.running -> {
                    R.string.fm_smart_checking
                }

                !state.settings.networkConfigured -> {
                    R.string.fm_smart_choose_network
                }

                else -> {
                    when (state.settings.lastResult) {
                        SmartDownloadResult.DISABLED -> R.string.fm_smart_off
                        SmartDownloadResult.WAITING -> R.string.fm_smart_waiting
                        SmartDownloadResult.QUEUED -> R.string.fm_smart_queued
                        SmartDownloadResult.FULL -> R.string.fm_smart_full
                        SmartDownloadResult.EMPTY -> R.string.fm_smart_empty
                        SmartDownloadResult.LOW_STORAGE -> R.string.fm_smart_low_storage
                        SmartDownloadResult.PRIVATE -> R.string.fm_smart_private
                        SmartDownloadResult.COOLDOWN -> R.string.fm_smart_recent
                        SmartDownloadResult.ERROR -> R.string.fm_smart_error
                    }
                }
            }
        Text(stringResource(status), style = MaterialTheme.typography.bodySmall)
        Button(onClick = vm::checkNow, enabled = state.settings.enabled && !state.running) { Text(stringResource(R.string.fm_smart_find)) }
        Text(stringResource(R.string.fm_smart_limits_help), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.fm_smart_keep_files), style = MaterialTheme.typography.bodySmall)
    }
}
