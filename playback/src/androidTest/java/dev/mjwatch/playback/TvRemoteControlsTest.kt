@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.playback

import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.BackHandler
import androidx.test.platform.app.InstrumentationRegistry
import android.view.KeyEvent
import android.os.SystemClock
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import java.lang.reflect.Proxy

/** Fake engine: tests send only D-pad/OK/Back actions, never clicks. */
class TvRemoteControlsTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun preferences() {InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("playback-preferences",0).edit().clear().commit()}
    private var frame by mutableStateOf(Color.Black)
    private var clock=0L
    private var position=50_000L
    private var playing=true
    private var toggles=0
    private var exits=0
    private var selected=0
    private var transitions=0
    private var retries=0
    private var status by mutableStateOf<PlaybackStatus?>(null)
    private var back:(()->Unit)?=null
    private var panel by mutableStateOf(false)
    private var visible by mutableStateOf(false)
    private var queue=listOf(PlaybackEpisode("asset:///demo.mp4","1",1),PlaybackEpisode("asset:///demo.mp4","2",2))
    private val fake=Proxy.newProxyInstance(Player::class.java.classLoader,arrayOf(Player::class.java)) {proxy,method,args -> when(method.name) {
        "getCurrentPosition" -> position
        "getDuration" -> 1_000_000L
        "isPlaying","getPlayWhenReady" -> playing
        "getPlaybackState" -> Player.STATE_READY
        "isCurrentMediaItemSeekable" -> true
        "pause" -> {playing=false;toggles++;null}
        "play" -> {playing=true;toggles++;null}
        "seekTo" -> {position=args!![0] as Long;null}
        "equals" -> proxy===args?.get(0)
        "hashCode" -> 1
        "toString" -> "FakePlayer"
        else -> null
    }} as Player
    private fun start(shown:Boolean=false) {
        visible=shown
        compose.setContent {
            Box(Modifier.fillMaxSize().background(frame)) {
            TvPlayerControls({fake},"Test",visible,false,status,{retries++}, {visible=it},{exits++},{panel=true},{panel=true},queue,0,{selected=it;transitions++},panel,{back=it},now={clock})
            BackHandler {if(panel)panel=false else back?.invoke()}
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }
    private fun key(key:Key) {compose.onRoot().performKeyInput {pressKey(key)};compose.waitForIdle()}
    private fun pressBack() {compose.runOnIdle {compose.activity.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle()}
    private fun focus(tag:String)=compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus)
    private fun captureBuffering(name:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val directory=java.io.File(context.getExternalFilesDir(null),"buffering-proof").apply {mkdirs()}
        java.io.File(directory,"$name.png").outputStream().use {output->
            assertTrue(compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))
        }
    }
    @Test fun hiddenDirectionsSelectCorrectRowAndSeekWithoutShowingControls() {
        start();key(Key.DirectionLeft);assertEquals(40_000L,position);assertFalse(visible)
        key(Key.DirectionRight);assertEquals(50_000L,position);assertFalse(visible)
        key(Key.DirectionUp);compose.onNodeWithTag("player-back").assertIsFocused()
        key(Key.DirectionUp);compose.onNodeWithTag("player-back").assertIsFocused()
        key(Key.DirectionDown);compose.onNodeWithTag("player-play").assertIsFocused()
        key(Key.DirectionUp);compose.onNodeWithTag("player-timeline").assertIsFocused()
        key(Key.DirectionUp);assertFalse(visible)
        key(Key.DirectionDown);compose.onNodeWithTag("player-play").assertIsFocused()
    }
    @Test fun hiddenOkTogglesOnceAndPreviewRequiresConfirmationOrCancelsOnBackAndBlur() {
        start();key(Key.DirectionCenter);assertEquals(1,toggles);assertFalse(playing)
        key(Key.DirectionUp);key(Key.DirectionRight);assertEquals(50_000L,position)
        key(Key.DirectionCenter);assertEquals(60_000L,position)
        key(Key.DirectionRight);pressBack();assertEquals(60_000L,position)
        key(Key.DirectionCenter);assertEquals(60_000L,position)
        key(Key.DirectionRight);key(Key.DirectionDown);assertEquals(60_000L,position)
        pressBack();assertEquals(1,exits)
    }
    @Test fun disabledPreviousIsSkippedAndModalsRestoreFocusAndBlockTimeout() {
        start(true);focus("player-play")
        key(Key.DirectionLeft);key(Key.DirectionLeft)
        compose.onNodeWithContentDescription("Назад на 10 секунд").assertIsFocused()
        focus("player-tracks");key(Key.DirectionCenter);assertTrue(panel)
        compose.runOnIdle {clock+=8000};compose.mainClock.advanceTimeBy(8000);assertTrue(visible)
        compose.runOnIdle {panel=false};compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("player-tracks").assertIsFocused()
        compose.runOnIdle {clock+=6500};compose.mainClock.advanceTimeBy(6500);assertFalse(visible)
        pressBack();assertEquals(1,exits)
    }
    @Test fun episodePanelBackRestoresItsOpeningButton() {
        start(true);focus("player-episodes");key(Key.DirectionCenter)
        compose.onNodeWithText("Выбор серии").assertExists()
        pressBack();compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("player-episodes").assertIsFocused()
        assertEquals(0,selected)
    }
    @Test fun heldOkDoesNotToggleMultipleTimesOrClickTheNewlyFocusedButton() {
        start()
        val time=SystemClock.uptimeMillis()
        compose.runOnIdle {compose.activity.dispatchKeyEvent(KeyEvent(time,time,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,0))}
        compose.runOnIdle {compose.activity.dispatchKeyEvent(KeyEvent(time,time+500,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,1))}
        compose.runOnIdle {compose.activity.dispatchKeyEvent(KeyEvent(time,time+1000,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER,20))}
        compose.runOnIdle {compose.activity.dispatchKeyEvent(KeyEvent(time,time+1100,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER,0))}
        compose.waitForIdle();assertEquals(1,toggles);assertFalse(playing)
    }
    @Test fun pendingPromptWaitsForModalAndStayCancelsAutomaticAdvance() {
        start(true);focus("player-tracks");key(Key.DirectionCenter)
        compose.runOnIdle {position=970_000}
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("player-prompt").assertDoesNotExist()
        compose.onNodeWithTag("player-tracks").assertIsFocused()
        pressBack();compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("player-prompt").assertIsFocused()
        compose.runOnIdle {playing=false;clock+=15_000}
        compose.mainClock.advanceTimeBy(15_000);assertEquals(0,transitions)
        pressBack();compose.onNodeWithTag("player-prompt").assertDoesNotExist()
        compose.onNodeWithTag("player-play").assertIsFocused()
        compose.runOnIdle {playing=true;position=990_000}
        compose.mainClock.advanceTimeBy(500);assertEquals(0,transitions)
    }
    @Test fun playbackProgressTriggersExactlyOneAutomaticAdvance() {
        position=970_000;start(true)
        compose.onNodeWithTag("player-prompt").assertIsFocused()
        compose.runOnIdle {position=975_000};compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle {position=980_000};compose.mainClock.advanceTimeBy(500)
        assertEquals(1,selected);assertEquals(1,transitions)
        compose.runOnIdle {position=990_000};compose.mainClock.advanceTimeBy(500)
        assertEquals(1,transitions)
    }
    @Test fun realOpeningMarkersOfferSkipAndErrorsFocusAnAction() {
        queue=listOf(queue.first().copy(openingStartMs=40_000,openingEndMs=90_000),queue.last())
        assertEquals(queue,PlaybackEpisode.decode(PlaybackEpisode.encode(queue)))
        start(true);compose.onNodeWithTag("player-prompt").assertIsFocused()
        key(Key.DirectionCenter);assertEquals(90_000L,position)
        compose.runOnIdle {status=PlaybackStatus("Ошибка","Повторите загрузку",retry=true)}
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Повторить").assertIsFocused()
        captureBuffering("tv-error-focus")
        key(Key.DirectionRight);compose.onNodeWithText("К раздаче").assertIsFocused()
        compose.onAllNodes(isFocused()).assertCountEquals(1)
        captureBuffering("tv-error-secondary-focus")
        key(Key.DirectionLeft);compose.onNodeWithText("Повторить").assertIsFocused()
        key(Key.DirectionCenter);assertEquals(1,retries)
        pressBack();assertEquals(1,exits)
    }

    @Test fun previewKeepsFocusWhenItTemporarilyHidesAnOpeningOffer() {
        queue=listOf(queue.first().copy(openingStartMs=40_000,openingEndMs=90_000),queue.last())
        start(true);focus("player-play");key(Key.DirectionUp);key(Key.DirectionRight)
        compose.onNodeWithTag("player-timeline").assertIsFocused()
        compose.onNodeWithTag("player-prompt").assertDoesNotExist()
        assertEquals(50_000L,position)
    }
    @Test fun automaticSkipUsesOnlyMarkersAndDoesNotFlashAnOpeningOffer() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("playback-preferences",0).edit().putBoolean("auto-skip",true).commit()
        queue=listOf(queue.first().copy(openingStartMs=40_000,openingEndMs=90_000),queue.last())
        start(true);assertEquals(90_000L,position)
        compose.onNodeWithTag("player-prompt").assertDoesNotExist()
    }

    @Test fun loadingCompletionRestoresFocusToAVisiblePlaybackAction() {
        status=PlaybackStatus("Готовим видео",loading=true)
        start(true)
        compose.onNodeWithText("Отменить").assertIsFocused()
        compose.runOnIdle {status=null}
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("player-play").assertIsFocused()
    }

    @Test fun briefRebufferingKeepsFocusAndBackAvailable() {
        start(true);focus("player-tracks")
        compose.runOnIdle {status=bufferingStatus(firstFrame=true)}
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("playback-buffering").assertExists()
        compose.onNodeWithTag("playback-status").assertDoesNotExist()
        compose.onNodeWithTag("player-tracks").assertIsFocused()
        captureBuffering("tv-compact-spinner-fake-engine")
        compose.runOnIdle {status=null}
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("player-tracks").assertIsFocused()
        compose.runOnIdle {status=bufferingStatus(firstFrame=true)}
        pressBack();assertEquals(1,exits)
    }

    @Test fun prolongedBufferingExplainsWaitAndFocusesCancel() {
        start(true);focus("player-tracks")
        compose.runOnIdle {status=bufferingStatus(firstFrame=true,elapsedMs=30_000)}
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("playback-buffering").assertDoesNotExist()
        compose.onNodeWithText("Отменить").assertIsFocused()
        captureBuffering("tv-prolonged-buffering-focus")
        key(Key.DirectionCenter);assertEquals(1,exits)
    }

    @Test fun mobileUsesTheSameCompactBufferingFeedback() {
        status=bufferingStatus(firstFrame=true)
        compose.setContent {
            PlayerControls(player={fake},title="Test",visible=true,mobile=true,keepVisible=false,status=status,
                onRetry={},onVisibility={},onBack={exits++},onTracks={},onFit={},onPip={})
            BackHandler {exits++}
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("playback-buffering").assertExists()
        compose.onNodeWithTag("playback-status").assertDoesNotExist()
        captureBuffering("mobile-compact-spinner-fake-engine")
        compose.runOnIdle {compose.activity.onBackPressedDispatcher.onBackPressed()}
        assertEquals(1,exits)
    }

    @Test fun approvedChromeRemainsLegibleOnWhiteAndBlackFrames() {
        start(true);focus("player-play")
        compose.runOnIdle {frame=Color.White}
        captureBuffering("tv-white-frame")
        compose.runOnIdle {frame=Color.Black}
        captureBuffering("tv-black-frame")
        compose.onNodeWithContentDescription("Пауза").assertExists()
        compose.onAllNodesWithText("Пауза",useUnmergedTree=true).assertCountEquals(0)
    }

}
