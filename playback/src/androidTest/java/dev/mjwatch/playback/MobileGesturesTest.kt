package dev.mjwatch.playback

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.lang.reflect.Proxy

class MobileGesturesTest {
    @get:Rule val compose=createComposeRule()
    private var position=50_000L
    private var seekable=true
    private var shown by mutableStateOf(true)
    private val player=Proxy.newProxyInstance(Player::class.java.classLoader,arrayOf(Player::class.java)) {proxy,method,args->when(method.name) {
        "getCurrentPosition"->position
        "getDuration"->100_000L
        "getPlaybackState"->Player.STATE_READY
        "isPlaying"->false
        "isCurrentMediaItemSeekable"->seekable
        "seekTo"->{position=args!![0] as Long;null}
        "equals"->proxy===args?.get(0)
        "hashCode"->1
        else->null
    }} as Player

    @Test fun doubleTapSeeksWithVisibleAndHiddenControlsAndClampsToVideo() {
        compose.setContent {PlayerControls({player},"Test",shown,true,false,null,{}, {shown=it},{},{},{},{})}
        val video=compose.onNodeWithTag("player-gestures")
        video.performTouchInput {doubleClick(Offset(width*.8f,height*.5f))}
        compose.runOnIdle {assertEquals(60_000L,position);assertTrue(shown)}
        compose.onNodeWithText("+10 секунд").assertIsDisplayed()
        compose.onNodeWithTag("seek-forward-feedback").assertIsDisplayed()
        video.performTouchInput {click(center)}
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {assertFalse(shown)}
        video.performTouchInput {doubleClick(Offset(width*.2f,height*.5f))}
        compose.runOnIdle {assertEquals(50_000L,position);assertFalse(shown);position=3000}
        video.performTouchInput {advanceEventTime(400);doubleClick(Offset(width*.2f,height*.5f))}
        compose.runOnIdle {assertEquals(0L,position);position=96_000}
        video.performTouchInput {advanceEventTime(400);doubleClick(Offset(width*.8f,height*.5f))}
        compose.runOnIdle {assertEquals(100_000L,position);seekable=false}
        video.performTouchInput {advanceEventTime(400);doubleClick(Offset(width*.2f,height*.5f))}
        compose.runOnIdle {assertEquals(100_000L,position)}
    }
}
