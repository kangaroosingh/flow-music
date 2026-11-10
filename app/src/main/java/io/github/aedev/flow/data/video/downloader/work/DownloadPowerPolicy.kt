package io.github.aedev.flow.data.video.downloader.work

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.devicePreferencesDataStore
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadPowerPolicy
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val key = booleanPreferencesKey("download_only_while_charging")
        val chargingOnly = context.devicePreferencesDataStore.data.map { it[key] ?: false }

        suspend fun setChargingOnly(value: Boolean) {
            context.devicePreferencesDataStore.edit { it[key] = value }
        }
    }
