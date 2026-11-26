package dev.mjwatch.designsystem

import android.animation.ValueAnimator
import android.app.Activity
import android.os.Build
import android.view.ViewTreeObserver
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private var startupShownInProcess = false

fun Activity.coldStartupWindow(restored: Boolean): State<Boolean>? {
    val cold = !restored && !startupShownInProcess
    startupShownInProcess = true
    if (!cold || !ValueAnimator.areAnimatorsEnabled()) return null
    val ready = mutableStateOf(Build.VERSION.SDK_INT < 31)
    if (Build.VERSION.SDK_INT >= 31) {
        val decor = window.decorView
        lateinit var focusListener: ViewTreeObserver.OnWindowFocusChangeListener
        fun reveal() {
            ready.value = true
            val observer = decor.viewTreeObserver
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(focusListener)
        }
        focusListener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) decor.postOnAnimation { if (!ready.value) reveal() }
        }
        decor.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        splashScreen.setOnExitAnimationListener { cover ->
            cover.remove()
            reveal()
        }
    }
    return ready
}

private const val EntranceMillis = 700
private const val RevealMillis = 400
private const val BackdropFadeStartMillis = 165f
private const val MarkGoneMillis = 215f
private const val LightBarsMillis = 310f
private val OrbitEmerald = Color(0xFF3BDA71)
private val OrbitCyan = Color(0xFF00CED3)

@Composable
fun StartupBanner(
    waiting: Boolean = true,
    animateMark: Boolean = true,
    windowReady: Boolean = true,
    onDarkBackdropChanged: (Boolean) -> Unit = {},
) {
    val animate = remember(animateMark) { animateMark && ValueAnimator.areAnimatorsEnabled() }
    val entrance = remember(animate) { Animatable(if (animate) 0f else EntranceMillis.toFloat()) }
    val reveal = remember { Animatable(0f) }
    val assembled by remember(entrance) { derivedStateOf { entrance.value >= EntranceMillis } }
    val finished by remember { derivedStateOf { reveal.value >= RevealMillis } }

    LaunchedEffect(animate, windowReady) {
        if (animate && windowReady) {
            // The native icon and the first Compose frame share the same lockup.
            withFrameNanos {}
            entrance.animateTo(EntranceMillis.toFloat(), tween(EntranceMillis, easing = LinearEasing))
        }
    }
    LaunchedEffect(waiting, assembled, windowReady) {
        if (!waiting && assembled && animate && windowReady) {
            withFrameNanos {}
            reveal.animateTo(RevealMillis.toFloat(), tween(RevealMillis, easing = LinearEasing))
        }
    }
    val darkBackdrop by remember { derivedStateOf { reveal.value < LightBarsMillis } }
    SideEffect { onDarkBackdropChanged(animate && darkBackdrop) }
    if (!waiting && (!animate || finished)) return

    val graphite = colorResource(R.color.mj_icon_background)
    BoxWithConstraints(
        Modifier.fillMaxSize().testTag("startup-banner")
            .drawBehind {
                // Start below readable contrast so the mark can go dark without an empty frame.
                val clear = reveal.value.smoothPhase(BackdropFadeStartMillis, RevealMillis.toFloat())
                drawRect(graphite, alpha = 1f - clear)
            }
            .clearAndSetSemantics {
                if (waiting) {
                    contentDescription = "MJWatch. Запускаем приложение"
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                } else hideFromAccessibility()
            },
        contentAlignment = Alignment.Center,
    ) {
        val mark = Modifier.size(minOf(288.dp, maxWidth, maxHeight))
        if (animate) StartupOrbit(mark, entrance.asState(), reveal.asState())

        Box(mark.graphicsLayer {
            val zoom = 1f + .08f * reveal.value.smoothPhase(0f, 210f)
            alpha = 1f - reveal.value.smoothPhase(95f, MarkGoneMillis)
            transformOrigin = TransformOrigin(.5f, .365f)
            scaleX = zoom
            scaleY = zoom
        }) {
            Image(painterResource(R.drawable.startup_rear), null,
                Modifier.fillMaxSize().playPlane(entrance.asState(), fromRight = true))
            Image(painterResource(R.drawable.startup_front), null,
                Modifier.fillMaxSize().playPlane(entrance.asState(), fromRight = false))
        }
        Image(painterResource(R.drawable.startup_brand), null, mark.graphicsLayer {
            alpha = 1f - reveal.value.smoothPhase(95f, MarkGoneMillis)
        })
    }
}

private fun Modifier.playPlane(entrance: State<Float>, fromRight: Boolean) = graphicsLayer {
    val time = entrance.value
    val converge = time.smoothPhase(180f, 540f)
    val settle = time.smoothPhase(540f, 700f)
    val direction = if (fromRight) 1f else -1f

    alpha = time.smoothPhase(180f, 345f)
    translationX = direction * size.width * .20f * (1f - converge)
    translationY = size.height * (-.153f - direction * .12f * (1f - converge) - .014f * (converge - settle))
    rotationZ = direction * (12f * (1f - converge) - 2.5f * (converge - settle))
    scaleX = .68f + .15f * converge - .03f * settle
    scaleY = scaleX
}

@Composable
private fun StartupOrbit(modifier: Modifier, entrance: State<Float>, reveal: State<Float>) {
    Canvas(modifier) {
        val time = entrance.value
        val exit = reveal.value
        val lens = center - Offset(0f, size.height * .135f)
        val radius = size.minDimension * .27f
        val arrive = time.smoothPhase(0f, 180f)
        val converge = time.smoothPhase(180f, 540f)
        val settle = time.smoothPhase(540f, 700f)
        val trace = arrive * (1f - settle)
        val glow = arrive * (.14f - .08f * settle) * (1f - exit.smoothPhase(140f, MarkGoneMillis))
        if (glow > 0f) {
            val halo = radius * 1.6f
            drawCircle(
                Brush.radialGradient(listOf(OrbitEmerald.copy(alpha = glow), Color.Transparent), lens, halo),
                halo, lens,
            )
        }
        if (trace > 0f) {
            val drift = radius * (.55f * (1f - arrive) + .08f * (1f - converge))
            val trailRadius = radius * (1.32f - .32f * converge)
            drawLightArc(lens - Offset(drift, drift * .5f), trailRadius,
                185f + 97f * converge, 30f + 73f * arrive, OrbitEmerald, trace, 1.35.dp.toPx())
            drawLightArc(lens + Offset(drift, drift * .5f), trailRadius * 1.09f,
                7f + 97f * converge, 23f + 75f * arrive, OrbitCyan, trace * .9f, 1.15.dp.toPx())

            val sparkle = arrive * (1f - time.smoothPhase(285f, 540f))
            repeat(8) { index ->
                val angle = (index * 43f + 16f + converge * 47f) * PI / 180
                val distance = radius * (1.48f - .72f * converge + (index % 3) * .16f)
                val position = lens + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * distance
                val color = if (index % 2 == 0) OrbitEmerald else OrbitCyan
                drawCircle(color, (if (index % 3 == 0) 1.6.dp else .9.dp).toPx(), position,
                    alpha = sparkle * (.44f + (index % 3) * .17f))
            }
        }
        val sweep = exit.smoothPhase(0f, 75f) * (1f - exit.smoothPhase(90f, MarkGoneMillis))
        if (sweep > 0f) {
            val travel = exit.smoothPhase(0f, MarkGoneMillis)
            drawLightArc(lens, radius * 1.09f, 177f + 179f * travel, 54f,
                OrbitCyan, .8f * sweep, 1.7.dp.toPx())
            val glint = lens + Offset(radius * (2f * travel - 1f), radius * (.4f - .65f * travel))
            val lightRadius = radius * .94f
            drawCircle(
                Brush.radialGradient(listOf(OrbitCyan.copy(alpha = .23f * sweep), Color.Transparent), glint, lightRadius),
                lightRadius, glint,
            )
        }
    }
}

private fun DrawScope.drawLightArc(
    lens: Offset, radius: Float, start: Float, sweep: Float, color: Color, opacity: Float, width: Float,
) {
    val mid = (start + sweep * .5f) * PI / 180
    val axis = Offset(cos(mid).toFloat(), sin(mid).toFloat()) * radius
    val brush = Brush.linearGradient(listOf(Color.Transparent, color.copy(alpha = opacity)), lens, lens + axis)
    drawArc(brush, start, sweep, useCenter = false, lens - Offset(radius, radius), Size(radius * 2, radius * 2),
        style = Stroke(width, cap = StrokeCap.Round))
}

private fun Float.smoothPhase(start: Float, end: Float): Float =
    FastOutSlowInEasing.transform(((this - start) / (end - start)).coerceIn(0f, 1f))
