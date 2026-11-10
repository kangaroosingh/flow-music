package io.github.aedev.flow.ui.screens.music

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.dao.DownloadDao
import io.github.aedev.flow.data.local.entity.DownloadItemStatus
import io.github.aedev.flow.data.music.smart.SmartDownloadResult
import io.github.aedev.flow.data.music.smart.SmartDownloadSettings
import io.github.aedev.flow.data.music.smart.SmartDownloadStore
import io.github.aedev.flow.data.music.smart.SmartDownloadWorker
import io.github.aedev.flow.data.music.smart.isSmartDownload
import io.github.aedev.flow.data.video.downloader.work.DownloadController
import io.github.aedev.flow.data.video.downloader.work.DownloadPowerPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SmartDownloadUiState(
    val settings: SmartDownloadSettings,
    val wifiOnly: Boolean,
    val chargingOnly: Boolean,
    val used: Int,
    val ready: Int,
    val running: Boolean,
)

@HiltViewModel
class SmartDownloadsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: SmartDownloadStore,
        private val preferences: PlayerPreferences,
        private val controller: DownloadController,
        dao: DownloadDao,
        private val power: DownloadPowerPolicy,
    ) : ViewModel() {
        private val work = WorkManager.getInstance(context)
        private val jobs =
            combine(
                work.getWorkInfosForUniqueWorkFlow(SmartDownloadWorker.PERIODIC),
                work.getWorkInfosForUniqueWorkFlow(SmartDownloadWorker.ONCE),
            ) { a, b ->
                (a + b).any { it.state == WorkInfo.State.RUNNING }
            }.distinctUntilChanged()
        val state =
            combine(
                store.state,
                preferences.downloadOverWifiOnly,
                dao.getAllDownloadsWithItems(),
                jobs,
                power.chargingOnly,
            ) { settings, wifi, rows, running, charging ->
                val automatic = rows.filter { it.isSmartDownload() }
                val ready = automatic.count { it.overallStatus == DownloadItemStatus.COMPLETED }
                SmartDownloadUiState(settings, wifi, charging, automatic.size, ready, running)
            }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        fun enabled(value: Boolean) {
            viewModelScope.launch {
                store.update {
                    it.copy(
                        enabled = value && it.networkConfigured,
                        lastResult = if (value) SmartDownloadResult.WAITING else SmartDownloadResult.DISABLED,
                    )
                }
            }
        }

        fun network(wifiOnly: Boolean) {
            viewModelScope.launch {
                preferences.setDownloadOverWifiOnly(wifiOnly)
                controller.applyNetworkPolicy().join()
                store.update { it.copy(networkConfigured = true) }
            }
        }

        fun charging(value: Boolean) {
            viewModelScope.launch {
                power.setChargingOnly(value)
                controller.applyNetworkPolicy().join()
            }
        }

        fun limit(count: Int) {
            viewModelScope.launch { store.update { it.copy(maxSongs = count) } }
        }

        fun checkNow() {
            val current = state.value ?: return
            if (current.settings.enabled &&
                current.settings.networkConfigured
            ) {
                SmartDownloadWorker.checkNow(context, current.wifiOnly, current.chargingOnly)
            }
        }
    }
