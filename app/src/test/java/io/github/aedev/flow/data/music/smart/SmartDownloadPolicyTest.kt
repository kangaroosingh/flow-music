package io.github.aedev.flow.data.music.smart

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.video.downloader.request.DownloadRequest
import io.github.aedev.flow.data.video.downloader.request.toDownloadRequest
import kotlinx.serialization.json.Json
import org.junit.Test

class SmartDownloadPolicyTest {
    private val settings = SmartDownloadSettings(enabled = true, networkConfigured = true)
    private val now = 2_000_000_000_000L

    private fun song(i: Int) = MusicTrack("song%07d".format(i), "Song $i", "Artist $i", "", 180)

    private val pool = (1..30).map(::song)

    private fun select(
        tracks: List<MusicTrack> = pool,
        config: SmartDownloadSettings = settings,
        used: Int = 0,
        pending: Int = 0,
        free: Long = 10_000_000_000L,
        existing: Set<String> = emptySet(),
        disliked: Set<String> = emptySet(),
        skips: Map<String, Int> = emptyMap(),
    ) = SmartDownloadPolicy.select(tracks, config, existing, used, pending, free, disliked, skips, now)

    @Test fun `off by default and requires an explicit network choice`() {
        assertThat(SmartDownloadSettings().enabled).isFalse()
        assertThat(select(config = settings.copy(enabled = false))).isEmpty()
        assertThat(select(config = settings.copy(networkConfigured = false))).isEmpty()
    }

    @Test fun `each check is bounded and keeps the recommendation order`() {
        assertThat(select()).containsExactlyElementsIn(pool.take(5)).inOrder()
        assertThat(select(used = 19)).containsExactly(pool.first())
        assertThat(select(used = 20)).isEmpty()
    }

    @Test fun `the selected cap is honored for ten twenty and fifty songs`() {
        for (limit in SmartDownloadPolicy.songLimits) {
            assertThat(select(config = settings.copy(maxSongs = limit), used = limit)).isEmpty()
            assertThat(select(config = settings.copy(maxSongs = limit), used = limit - 2)).hasSize(2)
        }
    }

    @Test fun `manual downloads dislikes frequent skips and duplicates are not selected`() {
        val out =
            select(
                pool + pool,
                existing = setOf(pool[0].videoId),
                disliked = setOf(pool[1].videoId),
                skips =
                    mapOf(pool[2].videoId to 2),
            )
        assertThat(out.map { it.videoId }).containsNoneOf(pool[0].videoId, pool[1].videoId, pool[2].videoId)
        assertThat(out.map { it.videoId }.toSet()).hasSize(out.size)
    }

    @Test fun `unknown duration long recordings and local files do not enter unattended transfers`() {
        val invalid = listOf(pool[0].copy(duration = 0), pool[1].copy(duration = 601), pool[2].copy(videoId = "local_123"))
        assertThat(select(invalid)).isEmpty()
        assertThat(select(listOf(pool[0].copy(duration = 600)))).hasSize(1)
    }

    @Test fun `storage reserves include downloads already pending`() {
        val free = SmartDownloadPolicy.MIN_FREE_BYTES + SmartDownloadPolicy.RESERVE_PER_SONG * 3
        assertThat(select(pending = 2, free = free)).hasSize(1)
        assertThat(select(pending = 3, free = free)).isEmpty()
        assertThat(select(free = SmartDownloadPolicy.MIN_FREE_BYTES - 1)).isEmpty()
    }

    @Test fun `a removed suggestion has a seven day readdition delay`() {
        val recent = settings.copy(recentAdditions = mapOf(pool.first().videoId to (now - 1)))
        assertThat(select(config = recent)).doesNotContain(pool.first())
        val expired = settings.copy(recentAdditions = mapOf(pool.first().videoId to (now - SmartDownloadPolicy.READDITION_DELAY_MS)))
        assertThat(select(config = expired)).contains(pool.first())
    }

    @Test fun `original live and remix recordings remain distinct`() {
        val variants = pool.take(3).map { it.copy(artist = "Same artist", title = "Same song") }
        assertThat(select(variants)).hasSize(3)
    }

    @Test fun `settings and automatic request ownership survive serialization`() {
        val config = settings.copy(maxSongs = 50, recentAdditions = mapOf("id" to now))
        assertThat(
            Json.decodeFromString<SmartDownloadSettings>(Json.encodeToString(SmartDownloadSettings.serializer(), config)),
        ).isEqualTo(config)
        val request = pool.first().toDownloadRequest().copy(smartDownload = true)
        assertThat(DownloadRequest.decode(request.encode())?.smartDownload).isTrue()
        val manual = pool.first().toDownloadRequest()
        assertThat(DownloadRequest.decode(manual.encode())?.smartDownload).isFalse()
    }
}
