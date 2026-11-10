package io.github.aedev.flow.musicfork

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.data.local.AppDatabase
import io.github.aedev.flow.data.local.entity.PlaylistEntity
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.music.MusicPersonalization
import io.github.aedev.flow.data.recommendation.music.MusicRecommendationCatalog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MusicStorageSmokeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun playlistPersistsAcrossDatabaseReopen() =
        runBlocking {
            val name = "playlist-smoke-${UUID.randomUUID()}.db"
            var db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
            try {
                db.playlistDao().insertPlaylist(PlaylistEntity("test", "Music playlist", "", "", true, 1L, isMusic = true))
                db.close()
                db = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
                assertEquals("Music playlist", db.playlistDao().getPlaylist("test")?.name)
                assertTrue(db.playlistDao().getPlaylist("test")!!.isMusic)
            } finally {
                db.close()
                context.deleteDatabase(name)
            }
        }

    @Test fun feedbackPersistsAndUndoKeepsOtherSongs() =
        runBlocking {
            val dependencies =
                dagger.hilt.android.EntryPointAccessors
                    .fromApplication(context, MusicSmokeDependencies::class.java)
            val downloads = dagger.Lazy { dependencies.downloads() }
            val first = MusicPersonalization(context, MusicRecommendationCatalog(), downloads)
            val original = first.profile.first()
            val song = MusicTrack("feedback-fixture", "Fixture song", "Artist", "", 180)
            try {
                first.dislike(song)
                val reopened = MusicPersonalization(context, MusicRecommendationCatalog(), downloads)
                assertEquals("Fixture song", reopened.profile.first().disliked[song.videoId])
                reopened.undoDislike(song.videoId)
                assertFalse(
                    first.profile
                        .first()
                        .disliked
                        .containsKey(song.videoId),
                )
            } finally {
                first.update { original }
            }
        }

    @Test fun completedDiscoveryLikeUsesPlaybackModeAndOriginalExposure() =
        runBlocking {
            val dependencies =
                dagger.hilt.android.EntryPointAccessors
                    .fromApplication(context, MusicSmokeDependencies::class.java)
            val personal = dependencies.personalization()
            val original = personal.profile.first()
            val song = MusicTrack("discovery-fixture", "Discovery", "New artist", "", 180)
            try {
                personal.update {
                    io.github.aedev.flow.data.recommendation.music
                        .MusicRecommendationProfile()
                }
                personal.rank(
                    listOf(song),
                    io.github.aedev.flow.data.recommendation.music
                        .MusicBrain(),
                    "quick_picks",
                )
                personal.onStart(song.videoId)
                personal.onListen(song, 1.0, null, false)
                personal.update { it.copy(enabled = false) }
                personal.liked(song, discovery = false)
                assertEquals(
                    1,
                    personal.profile
                        .first()
                        .metrics["improved"]
                        ?.discoveryLikes,
                )
                assertEquals(null, personal.profile.first().metrics["original"])
            } finally {
                personal.update { original }
            }
        }
}
