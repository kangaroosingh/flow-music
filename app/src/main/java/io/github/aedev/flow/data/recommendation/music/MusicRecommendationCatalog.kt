package io.github.aedev.flow.data.recommendation.music

import io.github.aedev.flow.data.music.YouTubeMusicService
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.innertube.YouTube
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Year
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRecommendationCatalog
    @Inject
    constructor() {
        private val mutex = Mutex()
        private var cacheKey = ""
        private var expires = 0L
        private var cached = emptyList<MusicTrack>()

        suspend fun candidates(
            profile: MusicRecommendationProfile,
            artists: List<String>,
        ): List<MusicTrack> {
            if (!profile.enabled) return emptyList()
            val queries = MusicRecommendationPolicy.candidateQueries(profile, artists, Year.now().value)
            val key = queries.joinToString("|") + YouTube.locale.toString()
            return mutex.withLock {
                val now = System.currentTimeMillis()
                if (key == cacheKey && now < expires) return@withLock cached
                cached =
                    coroutineScope {
                        queries
                            .map { query ->
                                async {
                                    try {
                                        withTimeoutOrNull(12_000) { YouTubeMusicService.searchMusic(query, 20) }.orEmpty()
                                    } catch (
                                        e: CancellationException,
                                    ) {
                                        throw e
                                    } catch (_: Exception) {
                                        emptyList()
                                    }
                                }
                            }.awaitAll()
                            .flatten()
                            .distinctBy { it.videoId }
                            .take(80)
                    }
                cacheKey = key
                expires = now + if (cached.isEmpty()) 60_000L else 600_000L
                cached
            }
        }

        suspend fun invalidate() =
            mutex.withLock {
                cacheKey = ""
                cached = emptyList()
                expires = 0
            }
    }
