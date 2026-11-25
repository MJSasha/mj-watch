package dev.mjwatch.playback

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage

internal data class PlaybackArtwork(
    val loader: ImageLoader,
    val backdrop: String?,
    val poster: String?,
    val logo: String?,
)

@Composable
internal fun PlaybackSplash(
    transition: Transition<PlaybackStatus?>,
    title: String,
    artwork: PlaybackArtwork?,
    tv: Boolean,
    metrics: PlaybackLoadingMetrics?,
    progress: StartupLoadingProgress?,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onAudioRecovery: () -> Unit,
) {
    transition.AnimatedContent(
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(200)) },
        contentKey = { it != null },
    ) { loading ->
        if (loading != null) {
            SplashContent(loading, title, artwork, tv, metrics, progress, transition.targetState != null, onRetry, onCancel, onAudioRecovery)
        } else Spacer(Modifier.fillMaxSize())
    }
}

@Composable
private fun SplashContent(
    status: PlaybackStatus,
    title: String,
    artwork: PlaybackArtwork?,
    tv: Boolean,
    metrics: PlaybackLoadingMetrics?,
    progress: StartupLoadingProgress?,
    active: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onAudioRecovery: () -> Unit,
) {
    var backdropFailed by remember(artwork?.backdrop) { mutableStateOf(false) }
    val background = if (backdropFailed) artwork?.poster else artwork?.backdrop ?: artwork?.poster

    BoxWithConstraints(
        Modifier.fillMaxSize().testTag("playback-splash").background(Color(0xFF10141E)),
    ) {
        if (artwork != null && background != null) {
            AsyncImage(
                model = background,
                imageLoader = artwork.loader,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                onError = { backdropFailed = true },
            )
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
            Color(0x44090C15), Color(0x66090C15), Color(0xF0090C15),
        ))))

        val short = maxHeight < 400.dp
        val displayTitle = title.ifBlank { "MJ Watch" }
        var logoAspect by remember(artwork?.logo) { mutableStateOf<Float?>(null) }
        val logoReady = logoAspect != null
        val logoHeight = if (short) 84.dp else 150.dp
        val reveal = progress.takeIf { status.loading }
        val progressFill = Modifier.titleProgress(status.loading,reveal?.fraction)
        Box(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        ) {
            Box(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(bottom = if (short) 40.dp else 56.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth(0.83f)
                        .heightIn(max = if (short) 101.dp else 164.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (artwork?.logo != null) {
                        AsyncImage(
                            model = artwork.logo,
                            imageLoader = artwork.loader,
                            contentDescription = null,
                            modifier = (logoAspect?.let { Modifier.widthIn(max = logoHeight * it).fillMaxWidth().aspectRatio(it) }
                                ?: Modifier.fillMaxWidth().height(logoHeight))
                                .then(progressFill).semantics { if (logoReady) contentDescription = displayTitle },
                            contentScale = ContentScale.Fit,
                            onSuccess = { result ->
                                val drawable = result.result.drawable
                                logoAspect = if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
                                    drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight else 1f
                            },
                            onError = { logoAspect = null },
                        )
                    }
                    if (!logoReady) Text(
                        displayTitle,
                        modifier = progressFill,
                        color = Color.White,
                        fontSize = if (short) 36.sp else 56.sp,
                        lineHeight = if (short) 38.sp else 60.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            PlaybackStatusOverlay(status,onRetry,onCancel,
                modifier=Modifier.align(Alignment.BottomCenter).padding(bottom = if (short) 14.dp else 24.dp),
                focusAction=tv,active=active,metrics=metrics,progress=reveal,onAudioRecovery=onAudioRecovery)
        }
    }
}

@Composable
private fun Modifier.titleProgress(loading:Boolean, progress:Float?): Modifier {
    if (!loading) return this
    val fill = animateFloatAsState(progress ?: 0f, tween(200), label = "Title progress")
    val motion = rememberInfiniteTransition(label = "Title illumination")
    val glow = motion.animateFloat(
        .38f, .55f, infiniteRepeatable(tween(1_200), RepeatMode.Reverse), label = "Title glow",
    )
    val sweep = if (progress == null) motion.animateFloat(
        -.25f, 1.25f, infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "Title sweep",
    ) else null
    val base = remember { Paint() }
    val mask = remember { Paint() }
    return drawWithContent {
        val bounds = Rect(0f, 0f, size.width, size.height)
        drawIntoCanvas { canvas ->
            base.alpha = glow.value
            canvas.saveLayer(bounds, base)
            drawContent()
            canvas.restore()
        }
        if (sweep == null) {
            clipRect(right = size.width * fill.value) { this@drawWithContent.drawContent() }
        } else {
            val center = sweep.value * size.width
            val halfBand = size.width * .23f
            drawIntoCanvas { canvas ->
                canvas.saveLayer(bounds, mask)
                drawContent()
                drawRect(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, Color.White, Color.Transparent),
                        startX = center - halfBand, endX = center + halfBand,
                    ),
                    blendMode = BlendMode.DstIn,
                )
                canvas.restore()
            }
        }
    }
}
