package io.github.aedev.flow.ui.screens.music

import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.recommendation.MusicSection

internal fun MusicUiState.withHiddenTrackIds(hidden: Set<String>): MusicUiState {
    if (hidden.isEmpty()) return this

    fun List<MusicTrack>.clean() = filterNot { it.videoId in hidden }

    fun List<MusicSection>.sections() =
        mapNotNull { section ->
            section.tracks
                .clean()
                .takeIf {
                    it.isNotEmpty()
                }?.let { section.copy(tracks = it) }
        }
    return copy(
        forYouTracks = forYouTracks.clean(),
        recommendedTracks = recommendedTracks.clean(),
        onRepeatTracks = onRepeatTracks.clean(),
        rediscoverTracks = rediscoverTracks.clean(),
        deepCutTracks = deepCutTracks.clean(),
        rotationTracks = rotationTracks.clean(),
        speedDialTracks = speedDialTracks.clean(),
        listenAgain = listenAgain.clean(),
        trendingSongs = trendingSongs.clean(),
        newReleases = newReleases.clean(),
        musicVideos = musicVideos.clean(),
        musicVideosForYou = musicVideosForYou.clean(),
        livePerformances = livePerformances.clean(),
        longListens = longListens.clean(),
        genreTracks = genreTracks.mapValues { it.value.clean() },
        dynamicSections = dynamicSections.sections(),
        dailyMixSections = dailyMixSections.sections(),
        similarToSections = similarToSections.sections(),
        otherPerformanceSections = otherPerformanceSections.sections(),
        moreFromArtistSections = moreFromArtistSections.sections(),
        dailyDiscover = dailyDiscover.filterNot { it.recommendation.videoId in hidden },
        homeChipPage = homeChipPage?.let { it.copy(sections = it.sections.sections()) },
    )
}
