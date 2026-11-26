package dev.mjwatch.designsystem

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable fun StartupBanner(waiting:Boolean=true,animateMark:Boolean=true) {
    val animate=remember(animateMark) {animateMark && ValueAnimator.areAnimatorsEnabled()}
    val assembly=remember(animate) {Animatable(if(animate) 0f else 1f)}
    val backdrop=remember {Animatable(1f)}
    val markOpacity=remember {Animatable(1f)}
    var assembled by remember(animate) {mutableStateOf(!animate)}
    LaunchedEffect(animate) {
        if(animate) {
            // Draw the matching system and Compose lockups before the shapes begin to move.
            withFrameNanos {}
            assembly.animateTo(1f,tween(360))
            assembled=true
        }
    }
    LaunchedEffect(waiting) {
        if(!waiting && animate) {
            // The ready screen is already in the tree. Reveal it on the next frame.
            withFrameNanos {}
            backdrop.animateTo(0f,tween(150))
        }
    }
    LaunchedEffect(waiting,assembled) {
        if(!waiting && assembled && animate) markOpacity.animateTo(0f,tween(90))
    }
    if(!waiting && (!animate || (backdrop.value==0f && markOpacity.value==0f))) return

    val progress=assembly.value
    BoxWithConstraints(
        Modifier.fillMaxSize().testTag("startup-banner")
            .background(colorResource(R.color.mj_icon_background).copy(alpha=backdrop.value))
            .clearAndSetSemantics {
                if(waiting) {
                    contentDescription = "MJWatch. Запускаем приложение"
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                } else hideFromAccessibility()
            },
        contentAlignment=Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius=size.minDimension*.82f
            val drift=radius*.08f*(1f-progress)
            val emerald=Offset(-radius*.19f-drift,-radius*.4f)
            val turquoise=Offset(size.width+radius*.19f+drift,size.height+radius*.4f)
            val edgeOpacity=backdrop.value*progress
            drawCircle(Brush.radialGradient(listOf(Color(0xFF087653),Color(0xFF0D3830)),emerald,radius),radius,emerald,alpha=edgeOpacity)
            drawCircle(Brush.radialGradient(listOf(Color(0xFF007D8C),Color(0xFF103540)),turquoise,radius),radius,turquoise,alpha=edgeOpacity)
        }
        val markFrame=minOf(288.dp,maxWidth,maxHeight*.8f)
        val mark=Modifier.size(markFrame)
        Image(painterResource(R.drawable.startup_echo),null,mark.graphicsLayer {
            alpha=markOpacity.value*.32f*4f*progress*(1f-progress)
            translationX=-markFrame.toPx()*(.05f+.17f*(1f-progress))
            translationY=-markFrame.toPx()*(.14f-.08f*(1f-progress))
            scaleX=.8f
            scaleY=scaleX
        })
        Image(painterResource(R.drawable.startup_rear),null,mark.graphicsLayer {
            alpha=markOpacity.value*progress
            translationX=markFrame.toPx()*.14f*(1f-progress)
            translationY=-markFrame.toPx()*(.14f+.14f*(1f-progress))
            scaleX=.76f+.12f*progress
            scaleY=scaleX
        })
        Image(painterResource(R.drawable.startup_front),null,mark.graphicsLayer {
            alpha=markOpacity.value*progress
            translationX=-markFrame.toPx()*.15f*(1f-progress)
            translationY=-markFrame.toPx()*(.14f-.08f*(1f-progress))
            scaleX=.76f+.12f*progress
            scaleY=scaleX
        })
        Image(painterResource(R.drawable.startup_brand),null,mark.graphicsLayer {
            alpha=backdrop.value
            translationY=markFrame.toPx()*.17f*(progress/.42f).coerceAtMost(1f)
        })
    }
}
