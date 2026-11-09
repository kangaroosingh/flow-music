package io.github.aedev.flow.data.recommendation.music

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class MusicRecommendationPolicyTest {
    private fun candidate(
        id: String,
        kind: MusicRecommendationReason,
        artist: String = id,
    ) = MusicRankedCandidate(id, artist, kind)

    @Test fun `defaults are Punjabi first and all eras`() {
        val profile = MusicRecommendationProfile()
        assertThat(profile.languages).containsExactly("pa")
        assertThat(profile.eras).containsExactly("all")
        assertThat(MusicRecommendationPolicy.candidateQueries(profile, emptyList(), 2026)).contains("Punjabi songs")
    }

    @Test fun `balanced first twenty meet the twelve six two target`() {
        val pool = MusicRecommendationReason.entries.flatMap { kind -> (0..49).map { candidate("${kind}_$it", kind) } }
        val ranked = MusicRecommendationPolicy.rank(pool, MusicRecommendationProfile(), false, 2026).take(20)
        assertThat(ranked.count { it.reason == MusicRecommendationReason.FAMILIAR }).isEqualTo(12)
        assertThat(ranked.count { it.reason == MusicRecommendationReason.RELATED }).isEqualTo(6)
        assertThat(ranked.count { it.reason == MusicRecommendationReason.DISCOVERY }).isEqualTo(2)
    }

    @Test fun `shortages fill without duplicates or invented tracks`() {
        val pool = listOf(candidate("one", MusicRecommendationReason.FAMILIAR), candidate("two", MusicRecommendationReason.DISCOVERY))
        assertThat(
            MusicRecommendationPolicy.rank(pool + pool, MusicRecommendationProfile(), false, 2026).map {
                it.id
            },
        ).containsExactly("one", "two")
    }

    @Test fun `unknown metadata stays eligible while known other language does not`() {
        val pool =
            listOf(
                candidate("unknown", MusicRecommendationReason.DISCOVERY),
                candidate("english", MusicRecommendationReason.DISCOVERY).copy(evidence = MusicCandidateEvidence(language = "en")),
            )
        assertThat(MusicRecommendationPolicy.rank(pool, MusicRecommendationProfile(), false, 2026).map { it.id }).containsExactly("unknown")
    }

    @Test fun `classics respects known dates without guessing missing dates`() {
        val pool =
            listOf(
                candidate("old", MusicRecommendationReason.FAMILIAR).copy(evidence = MusicCandidateEvidence(year = 2002)),
                candidate("new", MusicRecommendationReason.DISCOVERY).copy(evidence = MusicCandidateEvidence(year = 2026)),
                candidate("unknown", MusicRecommendationReason.DISCOVERY),
            )
        assertThat(
            MusicRecommendationPolicy.rank(pool, MusicRecommendationProfile(eras = setOf("classic")), false, 2026).map {
                it.id
            },
        ).containsExactly("old", "unknown")
    }

    @Test fun `twenty recent autoplay tracks excluded but manual lists remain playable`() {
        val pool = (0..21).map { candidate("$it", MusicRecommendationReason.FAMILIAR) }
        val profile = MusicRecommendationProfile(recent = (0..19).map(Int::toString))
        assertThat(MusicRecommendationPolicy.rank(pool, profile, true, 2026).map { it.id }).containsExactly("20", "21")
        assertThat(MusicRecommendationPolicy.rank(pool, profile, false, 2026)).hasSize(22)
    }

    @Test fun `dislike is a track exclusion and undo restores only that track`() {
        val pool =
            listOf(
                candidate("bad", MusicRecommendationReason.FAMILIAR, "artist"),
                candidate("good", MusicRecommendationReason.FAMILIAR, "artist"),
            )
        val profile = MusicRecommendationProfile(disliked = mapOf("bad" to "Song"))
        assertThat(MusicRecommendationPolicy.rank(pool, profile, false, 2026).map { it.id }).containsExactly("good")
        assertThat(MusicRecommendationPolicy.rank(pool, profile.copy(disliked = emptyMap()), false, 2026)).hasSize(2)
    }

    @Test fun `fallback preserves original order and explicit exclusions`() {
        val pool =
            listOf(
                candidate("a", MusicRecommendationReason.DISCOVERY),
                candidate("b", MusicRecommendationReason.FAMILIAR),
                candidate("c", MusicRecommendationReason.RELATED),
            )
        assertThat(
            MusicRecommendationPolicy
                .rank(
                    pool,
                    MusicRecommendationProfile(enabled = false, disliked = mapOf("b" to "B")),
                    false,
                    2026,
                ).map {
                    it.id
                },
        ).containsExactly("a", "c").inOrder()
    }

    @Test fun `original live and remix recordings with different ids remain distinct`() {
        val pool = listOf("original", "live", "remix").map { candidate(it, MusicRecommendationReason.FAMILIAR, "same artist") }
        assertThat(MusicRecommendationPolicy.rank(pool, MusicRecommendationProfile(), false, 2026)).hasSize(3)
    }

    @Test fun `artist adjacency avoided while alternatives remain`() {
        val pool =
            (0..5).flatMap { i ->
                listOf(
                    candidate("a$i", MusicRecommendationReason.FAMILIAR, "a"),
                    candidate("b$i", MusicRecommendationReason.RELATED, "b"),
                    candidate("c$i", MusicRecommendationReason.DISCOVERY, "c"),
                )
            }
        val ranked = MusicRecommendationPolicy.rank(pool, MusicRecommendationProfile(), false, 2026)
        assertThat(ranked.take(12).zipWithNext().all { (a, b) -> a.artist != b.artist }).isTrue()
    }

    @Test fun `only audible manual early skips teach a negative`() {
        assertThat(MusicRecommendationPolicy.earlyManualSkip(true, false, 10_000, 0.05)).isTrue()
        assertThat(MusicRecommendationPolicy.earlyManualSkip(false, false, 10_000, 0.05)).isFalse()
        assertThat(MusicRecommendationPolicy.earlyManualSkip(true, true, 10_000, 0.05)).isFalse()
        assertThat(MusicRecommendationPolicy.earlyManualSkip(true, false, 0, 0.0)).isFalse()
        assertThat(MusicRecommendationPolicy.earlyManualSkip(true, false, 90_000, 0.5)).isFalse()
    }

    @Test fun `profile round trip preserves dislikes and discovery settings`() {
        val profile = MusicRecommendationProfile(disliked = mapOf("id" to "Song"), discovery = 0.7, recent = listOf("id"))
        val encoded = Json.encodeToString(MusicRecommendationProfile.serializer(), profile)
        assertThat(Json.decodeFromString(MusicRecommendationProfile.serializer(), encoded)).isEqualTo(profile)
    }

    @Test fun `empty exhausted pools terminate deterministically`() {
        assertThat(MusicRecommendationPolicy.rank(emptyList(), MusicRecommendationProfile(), true, 2026)).isEmpty()
        assertThat(
            MusicRecommendationPolicy.rank(
                listOf(candidate("id", MusicRecommendationReason.FAMILIAR)),
                MusicRecommendationProfile(recent = listOf("id")),
                true,
                2026,
            ),
        ).isEmpty()
    }
}
