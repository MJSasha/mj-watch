package dev.mjwatch.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable fun StartupBanner() {
    var showPlayMark by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { showPlayMark = true }
    val progress by animateFloatAsState(if(showPlayMark) 1f else 0f, tween(320), label="startup mark")
    BoxWithConstraints(
        Modifier.fillMaxSize().testTag("startup-banner").background(colorResource(R.color.mj_icon_background))
            .clearAndSetSemantics {
                contentDescription = "MJWatch. Запускаем приложение"
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
            },
        contentAlignment=Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius=size.minDimension*.82f
            val emerald=Offset(-radius*.19f,-radius*.4f)
            val turquoise=Offset(size.width+radius*.19f,size.height+radius*.4f)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF087653),Color(0xFF0D3830)),emerald,radius),radius,emerald)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF007D8C),Color(0xFF103540)),turquoise,radius),radius,turquoise)
        }
        val markFrame = minOf(288.dp, maxWidth, maxHeight * .8f)
        val play = painterResource(R.drawable.ic_launcher_foreground)
        val playFrame = Modifier.size(markFrame).drawWithContent {
            // The launcher vector also contains the signature. The launch lockup draws it separately.
            clipRect(bottom = size.height * .695f) { this@drawWithContent.drawContent() }
        }
        Image(play, null, Modifier.graphicsLayer {
            alpha = .32f * 4f * progress * (1f - progress)
            translationX = -markFrame.toPx() * .14f * (1f - progress)
            translationY = -markFrame.toPx() * .30f * (1f - progress)
            scaleX = .9f
            scaleY = scaleX
        }.then(playFrame))
        Image(play, null, Modifier.graphicsLayer {
            alpha = progress
            translationY = -markFrame.toPx() * .30f * (1f - progress)
            scaleX = .82f + .18f * progress
            scaleY = scaleX
        }.then(playFrame))
        Image(
            painterResource(R.drawable.startup_brand), null,
            Modifier.size(markFrame).graphicsLayer {
                translationY = markFrame.toPx() * .32f * (progress / .42f).coerceAtMost(1f)
            },
        )
    }
}
