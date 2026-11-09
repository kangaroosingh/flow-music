package io.github.aedev.flow.data.recommendation.music

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.profile.StoreScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.Year
import javax.inject.Inject
import javax.inject.Singleton

private val Context.musicRecommendations by safePreferencesDataStore(
    name = "music_recommendations_v1",
    scope = StoreScope.PROFILE_PRIVATE,
)

@Singleton
class MusicPersonalization
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val catalog: MusicRecommendationCatalog,
        private val downloads: dagger.Lazy<io.github.aedev.flow.data.music.DownloadManager>,
    ) {
        private val key = stringPreferencesKey("profile")
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
        val reasons = MutableStateFlow<Map<String, Pair<String, MusicRecommendationReason>>>(emptyMap())
        val hiddenTracks = MutableStateFlow<Set<String>>(emptySet())
        val profile =
            context.musicRecommendations.data.map { prefs ->
                decode(prefs[key]).also { hiddenTracks.value = it.disliked.keys }
            }

        private fun decode(text: String?): MusicRecommendationProfile =
            text?.let { runCatching { json.decodeFromString<MusicRecommendationProfile>(it) }.getOrNull() }
                ?: MusicRecommendationProfile()

        suspend fun update(change: (MusicRecommendationProfile) -> MusicRecommendationProfile) {
            context.musicRecommendations.edit { prefs ->
                val current = decode(prefs[key])
                require(current.version == 1) { "Unsupported music recommendation profile" }
                val next = change(current)
                prefs[key] = json.encodeToString(MusicRecommendationProfile.serializer(), next)
                hiddenTracks.value = next.disliked.keys
                if (!next.enabled) reasons.value = emptyMap()
            }
        }

        suspend fun dislike(track: MusicTrack) = update { it.copy(disliked = it.disliked + (track.videoId to track.title)) }

        suspend fun undoDislike(id: String) = update { it.copy(disliked = it.disliked - id) }

        suspend fun reset() {
            catalog.invalidate()
            reasons.value = emptyMap()
            update {
                MusicRecommendationProfile(
                    languages = it.languages,
                    eras = it.eras,
                    discovery = it.discovery,
                    setupComplete = true,
                    enabled = it.enabled,
                )
            }
        }

        suspend fun onStart(id: String) =
            update { old ->
                val mode = if (old.enabled) "improved" else "original"
                val stats = old.metrics[mode] ?: MusicRecommendationMetrics()
                old.copy(
                    recent = (old.recent + id).takeLast(20),
                    sessionModes =
                        (old.sessionModes + (id to mode))
                            .entries
                            .toList()
                            .takeLast(20)
                            .associate { it.toPair() },
                    metrics =
                        old.metrics + (
                            mode to
                                stats.copy(
                                    starts = stats.starts + 1,
                                    repeats = stats.repeats + if (id in old.recent) 1 else 0,
                                )
                        ),
                )
            }

        suspend fun onListen(
            track: MusicTrack,
            fraction: Double,
            genre: String?,
            manualSkip: Boolean,
        ) = update { old ->
            val mode = old.sessionModes[track.videoId] ?: if (old.enabled) "improved" else "original"
            val stats = old.metrics[mode] ?: MusicRecommendationMetrics()
            val skips = old.earlySkips.toMutableMap()
            if (manualSkip) skips[track.videoId] = (skips[track.videoId] ?: 0) + 1
            val evidence = old.evidence.toMutableMap()
            if (!genre.isNullOrBlank()) {
                evidence[track.videoId] = (evidence[track.videoId] ?: MusicCandidateEvidence()).copy(genre = genre.trim().lowercase())
            }
            old.copy(
                earlySkips =
                    skips.entries
                        .toList()
                        .takeLast(1000)
                        .associate { it.toPair() },
                evidence =
                    evidence.entries
                        .toList()
                        .takeLast(1000)
                        .associate { it.toPair() },
                metrics =
                    old.metrics + (
                        mode to
                            stats.copy(
                                completions = stats.completions + if (fraction >= 0.9) 1 else 0,
                                earlyManualSkips = stats.earlyManualSkips + if (manualSkip) 1 else 0,
                            )
                    ),
            )
        }

        suspend fun liked(
            track: MusicTrack,
            discovery: Boolean,
        ) = update { old ->
            val mode = if (old.enabled) "improved" else "original"
            val stats = old.metrics[mode] ?: MusicRecommendationMetrics()
            old.copy(
                disliked = old.disliked - track.videoId,
                earlySkips = old.earlySkips - track.videoId,
                metrics =
                    old.metrics + (
                        mode to
                            stats.copy(
                                discoveryLikes = stats.discoveryLikes + if (discovery) 1 else 0,
                            )
                    ),
            )
        }

        suspend fun augment(
            tracks: List<MusicTrack>,
            surface: String,
            artists: List<String>,
        ): List<MusicTrack> {
            if (surface !in setOf("quick_picks", "discover", "daily_discover", "radio")) return tracks
            val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
            if (connectivity.activeNetwork == null) {
                return downloads
                    .get()
                    .downloadedTracks
                    .first()
                    .map { it.track }
                    .distinctBy { it.videoId }
            }
            if (surface == "radio") return tracks
            val extra = catalog.candidates(profile.first(), artists)
            return (extra + tracks).distinctBy { it.videoId }
        }

        suspend fun rank(
            tracks: List<MusicTrack>,
            brain: MusicBrain,
            surface: String,
        ): List<MusicTrack> {
            val prefs = profile.first()
            val blocked = MusicBrainLearn.hiddenArtistKeys(brain, System.currentTimeMillis())
            val eligible = tracks.filterNot { it.isHiddenArtist(blocked) }
            val inputs = eligible.map { MusicRankInput(it.videoId, it.primaryArtistKey(), prefs.evidence[it.videoId]?.genre) }
            val original = MusicBrainRanker.rank(brain, inputs, surface, System.currentTimeMillis()).map { eligible[it] }
            val related =
                brain.artistRelated.values
                    .flatten()
                    .toSet()
            val candidates =
                original.map { track ->
                    val artist = track.primaryArtistKey()
                    val familiar = track.videoId in brain.trackPlays || (brain.artistAffinity[artist]?.score ?: 0.0) >= 0.5
                    val reason =
                        when {
                            familiar -> MusicRecommendationReason.FAMILIAR
                            artist in related -> MusicRecommendationReason.RELATED
                            else -> MusicRecommendationReason.DISCOVERY
                        }
                    MusicRankedCandidate(track.videoId, artist, reason, prefs.evidence[track.videoId] ?: MusicCandidateEvidence())
                }
            val byId = tracks.associateBy { it.videoId }
            if (prefs.enabled) {
                val labels = candidates.associate { it.id to (byId[it.id]?.artist.orEmpty() to it.reason) }
                reasons.value =
                    (reasons.value + labels)
                        .entries
                        .toList()
                        .takeLast(1000)
                        .associate { it.toPair() }
            }
            return MusicRecommendationPolicy
                .rank(candidates, prefs, surface == "radio", Year.now().value)
                .mapNotNull { byId[it.id] }
        }
    }
