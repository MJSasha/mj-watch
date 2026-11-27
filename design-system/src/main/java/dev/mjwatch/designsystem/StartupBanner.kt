package dev.mjwatch.designsystem

import android.animation.ValueAnimator
import android.app.Activity
import android.os.Build
import android.view.ViewTreeObserver
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
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
private const val LightBarsProgress = .77f
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
    val finished by remember { derivedStateOf { reveal.value >= 1f } }

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
            reveal.animateTo(1f, tween(RevealMillis, easing = FastOutSlowInEasing))
        }
    }
    val darkBackdrop by remember { derivedStateOf { reveal.value < LightBarsProgress } }
    SideEffect { onDarkBackdropChanged(animate && darkBackdrop) }
    if (!waiting && (!animate || finished)) return

    val graphite = colorResource(R.color.mj_icon_background)
    BoxWithConstraints(
        Modifier.fillMaxSize().testTag("startup-banner")
            .drawBehind {
                drawRect(graphite, alpha = reveal.value.backdropOpacity())
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
        if (animate) {
            StartupOrbit(mark, entrance.asState(), reveal.asState())
            StartupLightPass(reveal.asState())
        }
        Box(mark.graphicsLayer {
            val zoom = 1f + .08f * reveal.value.smoothPhase(0f, .28f)
            alpha = reveal.value.identityOpacity()
            transformOrigin = TransformOrigin(.5f, .365f)
            scaleX = zoom
            scaleY = zoom
        }) {
            val plane = Modifier.fillMaxSize()
            val nativePlane = plane.graphicsLayer { alpha = 1f - entrance.value.smoothPhase(0f, 150f) }
            val rear = painterResource(R.drawable.startup_rear)
            val front = painterResource(R.drawable.startup_front)
            Image(rear, null, nativePlane)
            Image(front, null, nativePlane)
            Image(rear, null, plane.playPlane(entrance.asState(), fromRight = true))
            Image(front, null, plane.playPlane(entrance.asState(), fromRight = false))
        }
        Image(painterResource(R.drawable.startup_brand), null, mark.graphicsLayer {
            alpha = reveal.value.identityOpacity()
        })
    }
}

private fun Modifier.playPlane(entrance: State<Float>, fromRight: Boolean) = graphicsLayer {
    val time = entrance.value
    val converge = if (fromRight) time.smoothPhase(206f, 540f) else time.smoothPhase(180f, 508f)
    val separation = 1f - converge
    val bounce = time.smoothPhase(410f, 540f) * (1f - time.smoothPhase(540f, EntranceMillis.toFloat()))
    alpha = (1.35f * converge).coerceAtMost(1f)
    translationX = size.width * (if (fromRight) .103f else -.112f) * separation
    translationY = size.height * (if (fromRight) -.041f else .043f) * separation
    rotationZ = (if (fromRight) 15f else -13f) * separation
    transformOrigin = TransformOrigin(.5f, .365f)
    scaleX = 1f + .04f * bounce
    scaleY = scaleX
}

@Composable
private fun StartupOrbit(modifier: Modifier, entrance: State<Float>, reveal: State<Float>) {
    Spacer(modifier.drawWithCache {
        val lens = Offset(size.width * .5f, size.height * .365f)
        val halo = size.minDimension * .327f * 1.78f
        val haloBrush = Brush.radialGradient(listOf(OrbitEmerald, Color.Transparent), lens, halo)
        val emeraldArc = orbitArcBrush(OrbitEmerald)
        val cyanArc = orbitArcBrush(OrbitCyan)

        onDrawBehind {
            val time = entrance.value
            val arrive = time.smoothPhase(0f, 180f)
            val rotation = time.smoothPhase(80f, 540f)
            val settled = time.smoothPhase(540f, EntranceMillis.toFloat())
            val opacity = reveal.value.backdropOpacity()
            val radius = size.minDimension * (.327f - .057f * rotation)
            val glow = (.07f + .15f * arrive - .045f * settled) * arrive * opacity
            if (glow > 0f) scale(radius * 1.78f / halo, pivot = lens) {
                drawCircle(haloBrush, halo, lens, alpha = glow)
            }

            val trace = arrive * (1f - .51f * settled) * opacity
            val drift = Offset(size.width * .163f, size.height * .097f) * (1f - rotation)
            drawLightArc(lens - drift, radius, 158f + 117f * rotation, emeraldArc, trace)
            drawLightArc(lens + drift, radius * .94f, -45f + 123f * rotation, cyanArc, trace * .9f)

            for (index in 0 until 9) {
                val age = time - index % 3 * 24f
                val fragment = age.smoothPhase(20f, 160f) * (1f - age.smoothPhase(325f, 540f))
                if (fragment == 0f) continue
                val angle = (29f + index * 40f + 54f * rotation) * PI / 180
                val distance = radius * (1.47f + .13f * (index % 3) - .86f * rotation)
                val position = lens + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * distance
                val color = if (index % 2 == 0) OrbitEmerald else OrbitCyan
                val dot = (if (index % 3 == 0) 1.8.dp else 1.dp).toPx()
                drawCircle(color, dot * 2.1f, position, alpha = fragment * .22f)
                drawCircle(color, dot, position, alpha = fragment * .85f)
            }
        }
    })
}

private fun orbitArcBrush(color: Color): Brush {
    val transparent = color.copy(alpha = 0f)
    return Brush.sweepGradient(0f to transparent, .17f to color,
        .34f to transparent, 1f to transparent, center = Offset.Zero)
}

private fun DrawScope.drawLightArc(lens: Offset, radius: Float, rotation: Float, brush: Brush, opacity: Float) {
    if (opacity <= 0f) return
    val topLeft = Offset(-radius, -radius)
    val bounds = Size(radius * 2, radius * 2)
    translate(lens.x, lens.y) {
        rotate(rotation, Offset.Zero) {
            drawArc(brush, 0f, 124f, false, topLeft, bounds, alpha = opacity * .15f,
                style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
            drawArc(brush, 0f, 124f, false, topLeft, bounds, alpha = opacity * .90f,
                style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun StartupLightPass(reveal: State<Float>) {
    Spacer(Modifier.fillMaxSize().drawWithCache {
        val spread = size.width * .24f
        val gradient = Brush.horizontalGradient(
            0f to Color.Transparent, .38f to OrbitEmerald.copy(alpha = .09f),
            .56f to OrbitCyan.copy(alpha = .23f), 1f to Color.Transparent,
            startX = -spread, endX = spread,
        )
        onDrawBehind {
            val progress = reveal.value
            val sweep = progress.smoothPhase(0f, .78f)
            if (sweep > 0f && sweep < 1f) {
                val x = size.width * (sweep * 1.45f - .23f)
                translate(x) {
                    rotate(-23f, Offset(0f, center.y)) {
                        drawRect(gradient, Offset(-spread, -size.height), Size(spread * 2, size.height * 3),
                            alpha = sin(PI * sweep).toFloat() * progress.backdropOpacity())
                    }
                }
            }
        }
    })
}

// The backdrop starts clearing at low contrast just before the identity disappears at .35.
private fun Float.backdropOpacity() = ((1f - this) / .68f).coerceAtMost(1f)
private fun Float.identityOpacity() = 1f - smoothPhase(.035f, .35f)

private fun Float.smoothPhase(start: Float, end: Float): Float {
    val t = ((this - start) / (end - start)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
