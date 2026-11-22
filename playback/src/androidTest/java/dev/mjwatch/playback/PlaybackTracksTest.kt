@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package dev.mjwatch.playback

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class PlaybackTracksTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun subtitlesMenuSelectsExactTrackAndCanTurnItOff() {
        val context = instrumentation.targetContext
        val subtitle = File.createTempFile("tracks-test", ".vtt", context.cacheDir)
        try {
            subtitle.writeText("WEBVTT\n\n00:00.000 --> 00:09.000\nTest subtitles\n")
            val intent = Intent(context, PlaybackActivity::class.java).putExtra("mobile", false)
                .putExtra("path", "demo.mp4").putExtra("uri", "asset:///demo.mp4")
            ActivityScenario.launch<PlaybackActivity>(intent).use { scenario ->
                lateinit var activity: PlaybackActivity
                scenario.onActivity { activity = it }
                val playerField = PlaybackActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
                val panelField = PlaybackActivity::class.java.getDeclaredField("panel").apply { isAccessible = true }
                fun await(condition: () -> Boolean) = compose.waitUntil(10_000) {
                    var ready = false
                    instrumentation.runOnMainSync { ready = condition() }
                    ready
                }
                await { (playerField.get(activity) as? ExoPlayer)?.playbackState == Player.STATE_READY }
                val player = playerField.get(activity) as ExoPlayer
                scenario.onActivity {
                    player.pause()
                    val tracks = listOf("Subtitle Alpha", "Subtitle Beta").mapIndexed { index, label ->
                        MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(subtitle)).setMimeType(MimeTypes.TEXT_VTT)
                            .setId("subtitle-$index").setLanguage("en").setLabel(label).build()
                    }
                    player.setMediaItem(checkNotNull(player.currentMediaItem).buildUpon()
                        .setSubtitleConfigurations(tracks).build(), 3_000L)
                    player.prepare()
                }
                await {
                    player.playbackState == Player.STATE_READY &&
                        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.sumOf { it.length } == 2
                }
                fun choose(label: String) {
                    compose.onNodeWithTag("player-tracks").performClick()
                    scenario.onActivity {
                        val dialog = panelField.get(it) as AlertDialog
                        val list = dialog.listView
                        val row = (0 until list.count).first { index -> list.adapter.getItem(index).toString().contains(label) }
                        list.performItemClick(null, row, list.getItemIdAtPosition(row))
                    }
                }
                choose("Subtitle Alpha")
                await { player.selectedSubtitleLabel() == "Subtitle Alpha" }
                choose("Subtitle Beta")
                await { player.selectedSubtitleLabel() == "Subtitle Beta" }
                choose("Субтитры выключены")
                await { !player.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT) }
                scenario.onActivity {
                    assertFalse(player.trackSelectionParameters.overrides.values.any { it.type == C.TRACK_TYPE_TEXT })
                }
            }
        } finally {
            subtitle.delete()
        }
    }

    private fun Player.selectedSubtitleLabel(): String? =
        currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.firstNotNullOfOrNull { group ->
            (0 until group.length).firstOrNull(group::isTrackSelected)?.let { group.getTrackFormat(it).label }
        }
}
