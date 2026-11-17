package dev.mjwatch.designsystem

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** One entrance, then a quiet orbit while initialization finishes. All motion uses Compose's duration scale. */
@Composable fun StartupBanner(modifier:Modifier=Modifier,progress:Float=1f,reveal:Float=0f) {
    val ambient=rememberInfiniteTransition(label="projector")
    val orbit by ambient.animateFloat(0f,360f,infiniteRepeatable(tween(6000,easing=LinearEasing)),label="light orbit")
    val ink=Color(0xFF0C0E15)
    val white=Color(0xFFF0F2EC)
    val logo=FastOutSlowInEasing.transform((progress/.65f).coerceIn(0f,1f))
    val title=FastOutSlowInEasing.transform(((progress-.28f)/.52f).coerceIn(0f,1f))
    val caption=((progress-.55f)/.45f).coerceIn(0f,1f)
    BoxWithConstraints(modifier.fillMaxSize().testTag("startup-banner").graphicsLayer {alpha=1f-reveal}
        .background(ink).semantics(mergeDescendants=true) {
            contentDescription="MJWatch. Запускаем приложение"
            progressBarRangeInfo=ProgressBarRangeInfo.Indeterminate
        },contentAlignment=Alignment.Center) {
        val compact=maxHeight<400.dp
        Canvas(Modifier.fillMaxSize()) {
            val radius=size.minDimension*.7f
            drawCircle(Brush.radialGradient(listOf(Lime.copy(alpha=.09f*logo),Color.Transparent),center,radius),radius)
            // A pair of soft projector beams converge behind the emblem.
            val beamY=center.y-if(compact) 40.dp.toPx() else 58.dp.toPx()
            for(side in listOf(-1,1)) {
                val start=Offset(center.x+side*size.width*.55f,beamY-size.height*.16f)
                val end=Offset(center.x,beamY)
                drawLine(Brush.linearGradient(listOf(Color.Transparent,Lime.copy(alpha=.08f*logo)),start,end),start,end,1.dp.toPx())
            }
        }
        Column(Modifier.padding(horizontal=24.dp).graphicsLayer {translationY=-8.dp.toPx()},
            horizontalAlignment=Alignment.CenterHorizontally) {
            Box(Modifier.size(if(compact) 144.dp else 184.dp).graphicsLayer {
                scaleX=(.72f+.28f*logo)*(1f+reveal*.8f);scaleY=scaleX
            },contentAlignment=Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val radius=size.minDimension*.42f
                    drawCircle(Lime.copy(alpha=.10f*logo),radius,style=Stroke(1.dp.toPx()))
                    val arcSize=Size(radius*2,radius*2)
                    val topLeft=center-Offset(radius,radius)
                    repeat(2) {index->
                        drawArc(Brush.sweepGradient(listOf(Color.Transparent,Lime.copy(alpha=.8f*logo),Color.Transparent)),
                            orbit+index*180f-120f*(1f-logo),115f*logo,false,topLeft,arcSize,
                            style=Stroke(2.dp.toPx(),cap=StrokeCap.Round))
                    }
                    // Light fragments settle into the lens instead of looping as a busy spinner.
                    repeat(12) {index->
                        val angle=index*PI/6+orbit*PI/180*.12
                        val distance=radius*(1f+(1f-logo)*.65f)
                        val point=center+Offset(cos(angle).toFloat(),sin(angle).toFloat())*distance
                        drawCircle(Lime.copy(alpha=(.16f+.5f*(1f-logo))*logo),if(index%3==0) 2.dp.toPx() else 1.dp.toPx(),point)
                    }
                }
                Icon(painterResource(R.drawable.ic_launcher_foreground),null,
                    Modifier.size(if(compact) 120.dp else 152.dp).graphicsLayer {
                        alpha=logo;rotationZ=-12f*(1f-logo)
                    },tint=Lime)
            }
            Spacer(Modifier.height(if(compact) 8.dp else 16.dp))
            Text(buildAnnotatedString {
                withStyle(SpanStyle(color=Lime)) {append("MJ")}
                withStyle(SpanStyle(color=white)) {append("Watch")}
            },fontSize=if(compact) 36.sp else 44.sp,fontWeight=FontWeight.Bold,letterSpacing=(-1.8).sp,
                modifier=Modifier.graphicsLayer {alpha=title;translationY=18.dp.toPx()*(1f-title)})
            Spacer(Modifier.height(10.dp))
            Text("Ваше кино на любом экране",color=white.copy(alpha=.65f),fontSize=14.sp,
                textAlign=TextAlign.Center,modifier=Modifier.graphicsLayer {alpha=caption})
        }
    }
}
