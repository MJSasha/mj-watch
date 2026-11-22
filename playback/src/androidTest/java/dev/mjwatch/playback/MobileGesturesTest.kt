package dev.mjwatch.playback

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
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
        "isPlaying","getPlayWhenReady"->false
        "isCurrentMediaItemSeekable"->seekable
        "seekTo"->{position=args!![0] as Long;null}
        "equals"->proxy===args?.get(0)
        "hashCode"->1
        else->null
    }} as Player

    @Test fun timelineSitsBetweenBothTimes() {
        compose.setContent {Box(Modifier.width(360.dp).fillMaxHeight().background(Color.Black).testTag("mobile-timeline-preview")) {PlayerControls({player},"Test",shown,true,false,null,{}, {shown=it},{},{},{})}}
        compose.waitForIdle()
        val start=compose.onNodeWithTag("player-position").fetchSemanticsNode().boundsInRoot
        val timeline=compose.onNodeWithTag("player-timeline").fetchSemanticsNode().boundsInRoot
        val end=compose.onNodeWithTag("player-duration").fetchSemanticsNode().boundsInRoot
        assertTrue(start.right<timeline.left)
        assertTrue(timeline.right<end.left)
        assertEquals(start.center.y,timeline.center.y,1f)
        assertEquals(end.center.y,timeline.center.y,1f)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=java.io.File(context.getExternalFilesDir(null),"tv-polish/mobile-timeline.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use {compose.onNodeWithTag("mobile-timeline-preview").captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }

    @Test fun timelineDrawsSkipRangesAtTheirTimestampsOnPhoneAndTv() {
        var mobile by mutableStateOf(true)
        var segments by mutableStateOf(listOf(
            SkipSegment(SegmentKind.RECAP,0,10_000),
            SkipSegment(SegmentKind.INTRO,20_000,30_000),
            SkipSegment(SegmentKind.OUTRO,65_000,75_000),
            SkipSegment(SegmentKind.PREVIEW,90_000,100_000),
        ))
        compose.setContent {
            PlayerControls({player},"Test",shown,mobile,false,null,{}, {shown=it},{},{},{},skipSegments=segments)
        }
        val timeline=compose.onNodeWithTag("player-timeline")
        fun assertRanges() {
            compose.mainClock.advanceTimeBy(300)
            timeline.assertContentDescriptionContains("Опенинг, 0:20 – 0:30",substring=true)
            val pixels=compose.onNodeWithTag("player-timeline-track",useUnmergedTree=true).captureToImage().toPixelMap()
            fun isBlue(fraction:Float):Boolean {
                val x=(pixels.width*fraction).toInt()
                return (pixels.height/2-3..pixels.height/2+3).any {y->
                    val color=pixels[x,y]
                    color.red<.5f&&color.green>.65f&&color.blue>.8f
                }
            }
            for(fraction in listOf(.05f,.25f,.70f,.95f))assertTrue("Missing range at $fraction",isBlue(fraction))
            for(fraction in listOf(.15f,.35f,.80f))assertFalse("Range leaked into gap at $fraction",isBlue(fraction))
        }
        compose.waitUntil(5000) {compose.onAllNodesWithTag("player-timeline").fetchSemanticsNodes().isNotEmpty()}
        assertRanges()
        compose.runOnIdle {mobile=false}
        compose.waitUntil(5000) {compose.onAllNodesWithTag("player-timeline").fetchSemanticsNodes().isNotEmpty()}
        assertRanges()
        compose.runOnIdle {segments=emptyList()}
        compose.onNodeWithContentDescription("Опенинг, 0:20 – 0:30",substring=true).assertDoesNotExist()
    }

    @Test fun controlsFadeThroughAnIntermediateFrameInBothDirections() {
        compose.setContent {
            Box(Modifier.size(80.dp).background(Color.Black).testTag("chrome")) {
                PlayerChrome(shown, Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Color.White))
                }
            }
        }
        val chrome = compose.onNodeWithTag("chrome")
        fun brightness(): Float {
            val pixels = chrome.captureToImage().toPixelMap()
            return pixels[pixels.width / 2, pixels.height / 2].red
        }

        assertEquals(1f, brightness(), .01f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { shown = false }
        compose.mainClock.advanceTimeBy(80)
        assertTrue(brightness() in 0.05f..0.95f)
        compose.mainClock.advanceTimeBy(240)
        assertEquals(0f, brightness(), .01f)

        compose.runOnIdle { shown = true }
        compose.mainClock.advanceTimeBy(100)
        assertTrue(brightness() in 0.05f..0.95f)
        compose.mainClock.advanceTimeBy(240)
        assertEquals(1f, brightness(), .01f)
    }

    @Test fun skipDbSegmentsOfferManualSkipWithAutomaticDisabled() {
        val raw=org.json.JSONObject("""{"segments":{
            "recap":{"start_ms":0,"end_ms":20000,"match":"exact","confidence":0.9},
            "intro":{"start_ms":40000,"end_ms":60000,"match":"exact","confidence":0.9},
            "outro":{"start_ms":70000,"end_ms":100000,"match":"exact","confidence":0.9},
            "preview":{"start_ms":90000,"end_ms":100000,"match":"exact","confidence":0.9}
        }}""")
        val markers=parseSkipSegments(raw,100_000)
        assertEquals(listOf(SegmentKind.RECAP,SegmentKind.INTRO,SegmentKind.OUTRO,SegmentKind.PREVIEW),markers.map {it.kind})
        assertEquals(90_000L,markers[2].endMs)
        raw.getJSONObject("segments").getJSONObject("recap").put("match","out-of-range")
        assertFalse(parseSkipSegments(raw,100_000).any {it.kind==SegmentKind.RECAP})

        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("playback-preferences",0)
        prefs.edit().putBoolean(AUTO_SKIP,false).commit()
        try {
            compose.setContent {
                PlayerControls({player},"Test",shown,true,false,null,{}, {shown=it},{},{},{},skipSegments=markers)
            }
            compose.onNodeWithText("Пропустить опенинг").assertIsDisplayed().performClick()
            compose.runOnIdle {assertEquals(60_000L,position)}
        } finally {
            prefs.edit().remove(AUTO_SKIP).commit()
        }
    }

    @Test fun walkingDeadShiftedIntroAndOutroAreAvailableForManualSkip() {
        val response=org.json.JSONObject("""{
            "segments":{
                "intro":{"start_ms":230000,"end_ms":265000,"offset_ms":-5000,"match":"shifted","confidence":0.82},
                "outro":{"start_ms":2659000,"end_ms":2694000,"offset_ms":-5000,"match":"shifted","confidence":0.82}
            }}""")
        val markers=parseSkipSegments(response,2_694_000)
        assertEquals(listOf(
            SkipSegment(SegmentKind.INTRO,230_000,265_000,allowAutoSkip=false),
            SkipSegment(SegmentKind.OUTRO,2_659_000,2_694_000,allowAutoSkip=false),
        ),markers)
        val state=SegmentSkipState()
        repeat(24) {state.update(markers,235_000,2_694_000,true,false,true,it*250L)}
        assertEquals("Пропустить опенинг",state.offer?.label)
        assertNull(state.offer?.remainingMs)
        assertEquals(265_000L,state.takeTarget(235_000,2_694_000))
        response.getJSONObject("segments").getJSONObject("intro").put("offset_ms",-16_000)
        assertEquals(listOf(SegmentKind.OUTRO),parseSkipSegments(response,2_694_000).map {it.kind})
    }

    @Test fun doubleTapSeeksWithVisibleAndHiddenControlsAndClampsToVideo() {
        compose.setContent {PlayerControls({player},"Test",shown,true,false,null,{}, {shown=it},{},{},{})}
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
