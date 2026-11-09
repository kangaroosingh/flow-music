package io.github.aedev.flow.ui.components.music.item

import androidx.compose.runtime.compositionLocalOf
import io.github.aedev.flow.data.recommendation.music.MusicRecommendationReason

val LocalMusicRecommendationReasons = compositionLocalOf<Map<String, Pair<String, MusicRecommendationReason>>> { emptyMap() }
