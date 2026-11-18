@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.mjwatch.tv

import android.graphics.BitmapFactory
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.mjwatch.core.Config
import dev.mjwatch.core.Settings
import dev.mjwatch.designsystem.TvActionButton
import dev.mjwatch.designsystem.WatchTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class TvButtonsUiTest {
    @get:Rule(order=0) val seed=object:org.junit.rules.ExternalResource() {
        override fun before()=runBlocking {
            val c=InstrumentationRegistry.getInstrumentation().targetContext
            c.getSharedPreferences("updates",0).edit().putBoolean("automatic",false).commit()
            Settings(c).handleLegacyHistory();Settings(c).save(Config(demo=true,theme="dark"))
        }
    }
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()

    @Test fun dpadPressKeepsBoundsAndLoadingBlocksActivation() {
        var loading by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        var clicks=0
        val focus=FocusRequester()
        compose.runOnUiThread {compose.activity.setContent {
            WatchTheme("dark") {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(40.dp)) {
                    TvActionButton(if(loading)"Скачиваем…" else "Продолжить",Modifier.width(220.dp).focusRequester(focus).testTag("action"),enabled=enabled,loading=loading) {clicks++}
                }
            }
        }}
        val button=compose.onNodeWithTag("action")
        button.assertHeightIsAtLeast(52.dp)
        val bounds=button.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {focus.requestFocus()}
        button.assertIsFocused()
        Assert.assertEquals(bounds,button.fetchSemanticsNode().boundsInRoot)
        button.performKeyInput {keyDown(Key.DirectionCenter)}
        Assert.assertEquals(bounds,button.fetchSemanticsNode().boundsInRoot)
        button.performKeyInput {keyUp(Key.DirectionCenter)}
        compose.runOnIdle {Assert.assertEquals(1,clicks);loading=true}
        button.assertIsNotEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,ProgressBarRangeInfo.Indeterminate))
        Assert.assertEquals(bounds,button.fetchSemanticsNode().boundsInRoot)
        button.performKeyInput {pressKey(Key.DirectionCenter)}
        compose.runOnIdle {Assert.assertEquals(1,clicks);loading=false}
        compose.runOnIdle {focus.requestFocus()}
        button.performKeyInput {pressKey(Key.DirectionCenter)}
        compose.runOnIdle {Assert.assertEquals(2,clicks);enabled=false}
        button.assertIsNotEnabled().performKeyInput {pressKey(Key.DirectionCenter)}
        compose.runOnIdle {Assert.assertEquals(2,clicks)}
    }

    @Test fun focusStrokeFitsAtBothEndsOfAClippedList() {
        val first=FocusRequester()
        val last=FocusRequester()
        compose.runOnUiThread {compose.activity.setContent {
            WatchTheme("dark") {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(40.dp)) {
                    LazyColumn(Modifier.width(320.dp).height(180.dp)) {
                        item {TvActionButton("Первая",Modifier.fillMaxWidth().focusRequester(first).testTag("edge-first")) {}}
                        item {Spacer(Modifier.height(300.dp))}
                        item {TvActionButton("Последняя",Modifier.fillMaxWidth().focusRequester(last).testTag("edge-last")) {}}
                    }
                }
            }
        }}
        val density=compose.activity.resources.displayMetrics.density
        for((tag,requester) in listOf("edge-first" to first,"edge-last" to last)) {
            if(tag=="edge-last") compose.onNodeWithTag(tag).performScrollTo()
            compose.runOnIdle {requester.requestFocus()}
            compose.onNodeWithTag(tag).assertIsFocused().assertIsDisplayed()
            compose.waitForIdle()
            val bounds=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val pixels=compose.onRoot().captureToImage().toPixelMap()
            val x=bounds.center.x.roundToInt()
            for(y in listOf(bounds.top-5*density,bounds.bottom+5*density)) {
                val ring=pixels[x,y.roundToInt()].luminance()
                val gapY=if(y<bounds.top)bounds.top-2*density else bounds.bottom+2*density
                val fill=pixels[x,gapY.roundToInt()].luminance()
                Assert.assertTrue("The external stroke must survive viewport clipping",kotlin.math.abs(ring-fill)>.05f)
            }
        }
    }

    @Test fun captureReferenceStatesInBothThemes() {
        var theme by mutableStateOf("dark")
        val focus=FocusRequester()
        val pressed=MutableInteractionSource()
        compose.runOnUiThread {compose.activity.setContent {
            WatchTheme(theme) {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(40.dp),verticalArrangement=Arrangement.spacedBy(40.dp)) {
                    val labels=listOf("Обычная","Фокус","Нажатие","Недоступно","Загрузка","Вторичное")
                    labels.chunked(3).forEachIndexed {row,group->
                        Row(horizontalArrangement=Arrangement.spacedBy(48.dp)) {
                            group.forEachIndexed {column,label->
                                val index=row*3+column
                                Column(Modifier.width(280.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                                    Text(label,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    Box(Modifier.padding(8.dp)) {
                                        TvActionButton(when(index){4->"Скачиваем…";5->"С начала";else->"Продолжить"},
                                            modifier=if(index==1)Modifier.focusRequester(focus).testTag("focused") else Modifier,
                                            enabled=index!=3,secondary=index==5,loading=index==4,interactionSource=if(index==2)pressed else null) {}
                                    }
                                }
                            }
                        }
                    }
                    LaunchedEffect(Unit) {focus.requestFocus();pressed.emit(PressInteraction.Press(Offset.Zero))}
                }
            }
        }}
        val i=InstrumentationRegistry.getInstrumentation()
        compose.onNodeWithTag("focused").assertIsFocused()
        val dir=File(i.targetContext.getExternalFilesDir(null),"tv-buttons").apply {mkdirs()}
        for(value in listOf("dark","light")) {
            compose.runOnIdle {theme=value}
            compose.mainClock.advanceTimeBy(900);compose.waitForIdle();Thread.sleep(500)
            val file=File(dir,"$value.png")
            Assert.assertTrue(UiDevice.getInstance(i).takeScreenshot(file))
            val bitmap=BitmapFactory.decodeFile(file.absolutePath)
            val bounds=compose.onNodeWithTag("focused").fetchSemanticsNode().boundsInRoot
            val density=compose.activity.resources.displayMetrics.density
            val x=bounds.center.x.roundToInt()
            val ring=Color(bitmap.getPixel(x,(bounds.top-5*density).roundToInt())).luminance()
            val gap=Color(bitmap.getPixel(x,(bounds.top-2*density).roundToInt())).luminance()
            Assert.assertTrue("Focus must be visible outside the fill",(maxOf(ring,gap)+.05f)/(minOf(ring,gap)+.05f)>=3f)
            bitmap.recycle()
        }
    }
}
