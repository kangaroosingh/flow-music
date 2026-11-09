package io.github.aedev.flow.ui.screens.music

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import org.junit.Test

class MusicHiddenTracksTest {
    @Test fun `dislikes hide recommendation songs but preserve history and artist peers`() {
        val a = MusicTrack("a", "A", "Artist", "", 180)
        val b = a.copy(videoId = "b", title = "B")
        val state = MusicUiState(forYouTracks = listOf(a, b), history = listOf(a, b), onRepeatTracks = listOf(a))
        val filtered = state.withHiddenTrackIds(setOf("a"))
        assertThat(filtered.forYouTracks).containsExactly(b)
        assertThat(filtered.onRepeatTracks).isEmpty()
        assertThat(filtered.history).containsExactly(a, b)
    }
}
