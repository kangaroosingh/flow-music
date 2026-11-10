package io.github.aedev.flow.data.music.smart

import io.github.aedev.flow.data.music.model.MusicTrack

object SmartDownloadPolicy {
    const val BATCH_SIZE = 5
    const val MIN_FREE_BYTES = 1_073_741_824L
    const val RESERVE_PER_SONG = 67_108_864L
    const val CHECK_INTERVAL_MS = 21_600_000L
    const val READDITION_DELAY_MS = 604_800_000L
    val songLimits = listOf(10, 20, 50)
    private val videoId = Regex("[A-Za-z0-9_-]{11}")

    fun select(
        ranked: List<MusicTrack>,
        settings: SmartDownloadSettings,
        existingIds: Set<String>,
        automaticCount: Int,
        pendingCount: Int,
        freeBytes: Long,
        dislikedIds: Set<String>,
        earlySkips: Map<String, Int>,
        now: Long,
    ): List<MusicTrack> {
        if (freeBytes <= MIN_FREE_BYTES) return emptyList()
        val capacity = (settings.maxSongs.coerceIn(10, 50) - automaticCount.coerceAtLeast(0)).coerceAtLeast(0)
        val space =
            (
                (freeBytes - MIN_FREE_BYTES) / RESERVE_PER_SONG -
                    pendingCount.coerceAtLeast(
                        0,
                    )
            ).coerceIn(0L, BATCH_SIZE.toLong()).toInt()
        if (!settings.enabled || !settings.networkConfigured || capacity == 0 || space == 0) return emptyList()
        return ranked
            .asSequence()
            .distinctBy { it.videoId }
            .filter { track ->
                videoId.matches(track.videoId) && track.duration in 1..600 &&
                    track.videoId !in existingIds && track.videoId !in dislikedIds &&
                    (earlySkips[track.videoId] ?: 0) < 2 &&
                    settings.recentAdditions[track.videoId]?.let { now - it < READDITION_DELAY_MS } != true
            }.take(minOf(capacity, space, BATCH_SIZE))
            .toList()
    }
}
