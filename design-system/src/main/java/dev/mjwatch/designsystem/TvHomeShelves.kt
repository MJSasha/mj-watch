@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package dev.mjwatch.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

// Home moves whole shelves, including their headings, rather than centring individual card bounds.
private object HomeBringIntoView : BringIntoViewSpec {
    override fun calculateScrollDistance(offset:Float,size:Float,containerSize:Float)=0f
}

@Composable internal fun TvHomeShelves(
    rows:List<String>,focus:FocusRequester,modifier:Modifier=Modifier,
    content:@Composable (String)->Unit
) {
    val scroll=rememberScrollState()
    val heights=remember {mutableStateMapOf<String,Int>()}
    var viewport by remember {mutableIntStateOf(0)}
    var active by remember {mutableStateOf(rows.firstOrNull())}
    val density=LocalDensity.current
    val top=with(density) {16.dp.roundToPx()}
    val gap=with(density) {tvUnit(32f).roundToPx()}
    val clearance=with(density) {8.dp.roundToPx()}
    val offsets=rows.runningFold(top) {offset,row->offset+(heights[row]?:0)+gap}.dropLast(1)
    val index=rows.indexOf(active)
    val target=if(index>=0) (offsets[index]-(viewport-(heights[active]?:0))/2).coerceAtLeast(0) else 0
    val bottom=with(density) {maxOf(24.dp.roundToPx(),(viewport-(heights[rows.lastOrNull()]?:0))/2).toDp()}
    LaunchedEffect(active,rows,heights.toMap(),viewport) {
        if(viewport>0&&index>=0&&heights[active]!=null) {
            withFrameNanos {}
            scroll.animateScrollTo(target,animationSpec=tween(320))
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides HomeBringIntoView) {
        Column(modifier.fillMaxSize().testTag("home-list").onSizeChanged {viewport=it.height}
            .clipToBounds().verticalScroll(scroll).focusRequester(focus).focusGroup()
            .padding(top=16.dp,bottom=bottom),verticalArrangement=Arrangement.spacedBy(tvUnit(32f))) {
            rows.forEachIndexed {rowIndex,row->key(row) {
                val shift=if(rowIndex<index) minOf(0,target-offsets[rowIndex]-(heights[row]?:0)-clearance).toFloat() else 0f
                val translation by animateFloatAsState(shift,tween(320),label="home-shelf-position")
                Column(Modifier.fillMaxWidth().testTag("home-shelf-$row")
                    .onSizeChanged {heights[row]=it.height}.graphicsLayer {translationY=translation}
                    .onFocusChanged {if(it.hasFocus)active=row}.focusGroup(),
                    verticalArrangement=Arrangement.spacedBy(8.dp)) {content(row)}
            }}
        }
    }
}
