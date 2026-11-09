package io.github.aedev.flow.data.recommendation.music

import kotlinx.serialization.Serializable

@Serializable
data class MusicRecommendationProfile(
    val version: Int = 1,
    val enabled: Boolean = true,
    val setupComplete: Boolean = false,
    val languages: Set<String> = setOf("pa"),
    val eras: Set<String> = setOf("all"),
    val discovery: Double = 0.4,
    val disliked: Map<String, String> = emptyMap(),
    val recent: List<String> = emptyList(),
    val sessionModes: Map<String, String> = emptyMap(),
    val earlySkips: Map<String, Int> = emptyMap(),
    val evidence: Map<String, MusicCandidateEvidence> = emptyMap(),
    val metrics: Map<String, MusicRecommendationMetrics> = emptyMap(),
)

@Serializable
data class MusicCandidateEvidence(
    val genre: String? = null,
    val language: String? = null,
    val year: Int? = null,
    val source: String? = null,
)

@Serializable
data class MusicRecommendationMetrics(
    val starts: Int = 0,
    val completions: Int = 0,
    val earlyManualSkips: Int = 0,
    val discoveryLikes: Int = 0,
    val repeats: Int = 0,
)

enum class MusicRecommendationReason { FAMILIAR, RELATED, DISCOVERY }

data class MusicRankedCandidate(
    val id: String,
    val artist: String,
    val reason: MusicRecommendationReason,
    val evidence: MusicCandidateEvidence = MusicCandidateEvidence(),
)

object MusicRecommendationPolicy {
    fun earlyManualSkip(
        manual: Boolean,
        failed: Boolean,
        playedMs: Long,
        fraction: Double,
    ): Boolean = manual && !failed && playedMs in 3_000L..30_000L && fraction < 0.2

    fun candidateQueries(
        profile: MusicRecommendationProfile,
        artists: List<String>,
        year: Int,
    ): List<String> {
        val names = mapOf("pa" to "Punjabi", "hi" to "Hindi", "en" to "English", "ur" to "Urdu")
        val languages =
            profile.languages
                .sortedBy { if (it == "pa") "0" else it }
                .mapNotNull(names::get)
                .ifEmpty { listOf("Punjabi") }
        val era =
            when (profile.eras.singleOrNull()) {
                "classic" -> "classic old songs"
                "recent" -> "new songs $year"
                else -> "songs"
            }
        return (
            artists.filter(String::isNotBlank).take(2).map { "$it songs" } +
                languages.map { "$it $era" } + languages.map { "$it new releases $year" }
        ).distinct().take(4)
    }

    fun rank(
        ordered: List<MusicRankedCandidate>,
        profile: MusicRecommendationProfile,
        autoplay: Boolean,
        year: Int,
    ): List<MusicRankedCandidate> {
        val recent = if (autoplay) profile.recent.takeLast(20).toSet() else emptySet()
        val eligible =
            ordered.distinctBy { it.id }.filter { item ->
                val metadata = item.evidence
                item.id !in profile.disliked && item.id !in recent &&
                    (metadata.language == null || metadata.language in profile.languages) &&
                    (
                        metadata.year == null || "all" in profile.eras ||
                            ("classic" in profile.eras && metadata.year < year - 10) ||
                            ("recent" in profile.eras && metadata.year >= year - 10)
                    )
            }
        if (!profile.enabled) return eligible
        val discovery = profile.discovery.coerceIn(0.0, 1.0)
        val targets = listOf(1 - discovery, discovery * 0.75, discovery * 0.25)
        val buckets =
            MusicRecommendationReason.entries.map { kind ->
                eligible.filter { it.reason == kind }.sortedBy { profile.earlySkips[it.id] ?: 0 }.toMutableList()
            }
        val used = IntArray(3)
        val result = mutableListOf<MusicRankedCandidate>()
        while (buckets.any { it.isNotEmpty() }) {
            val alternative = buckets.flatten().any { it.artist.isNotBlank() && it.artist != result.lastOrNull()?.artist }
            val choices =
                buckets.indices
                    .filter { index ->
                        buckets[index].any { !alternative || result.isEmpty() || it.artist != result.last().artist }
                    }.ifEmpty { buckets.indices.filter { buckets[it].isNotEmpty() } }
            val bucket = choices.maxBy { targets[it] * (result.size + 1) - used[it] }
            val at = buckets[bucket].indexOfFirst { !alternative || result.isEmpty() || it.artist != result.last().artist }.coerceAtLeast(0)
            result += buckets[bucket].removeAt(at)
            used[bucket]++
        }
        return result
    }
}
