package com.bxane.nuvx.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.bxane.nuvx.ui.graphics.FADE_CURVE_STOPS
import com.bxane.nuvx.ui.graphics.smootherStep
import com.bxane.nuvx.ui.rememberIsForeground
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.bxane.nuvx.data.Http
import com.bxane.nuvx.data.canvas.CanvasArtwork
import com.bxane.nuvx.data.canvas.CanvasCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.roundToInt
import java.util.Locale

private const val TAG = "CanvasArtworkPlayer"

private const val REPAINT_TIMEOUT_MS = 700L

private const val DEFERRED_RELEASE_MS = 1_000L

private val mainHandler = Handler(Looper.getMainLooper())

private class ResumeGate {
    var heldForTransition = true

    var holdingStill = false
}

private class Teardown {
    var disposed = false

    var orphan: SurfaceTexture? = null

    fun releaseOrphan() {
        orphan?.release()
        orphan = null
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun AndroidCanvasArtworkPlayer(
    canvas: CanvasArtwork,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    contentMode: CanvasContentMode = CanvasContentMode.CROP,
    alignPortraitTop: Boolean = false,
    onAspectRatioChanged: (Float) -> Unit = {},
    portraitRevealBounds: IntSize = IntSize.Zero,
    presentationAlpha: () -> Float = { 1f },
    onRenderedChanged: (Boolean) -> Unit = {},
    onFrameCaptured: (ImageBitmap) -> Unit = {},
    refreshFrameEveryMs: Long? = null,
    frameCapturePx: Int = FRAME_CAPTURE_PX,
    onCoverChanged: (Float) -> Unit = {},
    bottomFade: Float = 0f,
    bottomFadeEndPx: Float? = null,
    pausedForTransition: Boolean = false,
    resumeDelayMs: Long = 0L,
    startPositionMs: Long = 0L,
    revealInstantly: Boolean = false,
    captureStill: Boolean = false,
    onStillCaptured: (ImageBitmap?, Long) -> Unit = { _, _ -> },
    onResumed: () -> Unit = {},
) {
    val context = LocalContext.current

    var url by remember(canvas) { mutableStateOf(canvas.url) }
    var rendered by remember(canvas) { mutableStateOf(false) }
    var clipAspect by remember(canvas) { mutableFloatStateOf(0f) }
    var bounds by remember { mutableStateOf(IntSize.Zero) }
    var textureView by remember(canvas) { mutableStateOf<TextureView?>(null) }
    var frameTick by remember(canvas) { mutableIntStateOf(0) }
    var surfaceGeneration by remember(canvas) { mutableIntStateOf(0) }
    val currentContentMode by rememberUpdatedState(contentMode)
    val currentAlignPortraitTop by rememberUpdatedState(alignPortraitTop)
    val currentPortraitRevealBounds by rememberUpdatedState(portraitRevealBounds)
    val currentPresentationAlpha by rememberUpdatedState(presentationAlpha)
    val currentPausedForTransition by rememberUpdatedState(pausedForTransition)
    val reportAspect by rememberUpdatedState(onAspectRatioChanged)
    val currentStartPositionMs by rememberUpdatedState(startPositionMs)
    val currentResumeDelayMs by rememberUpdatedState(resumeDelayMs)
    val reportResumed by rememberUpdatedState(onResumed)
    val teardown = remember { Teardown() }
    val gate = remember { ResumeGate() }

    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(CanvasCache.dataSourceFactory(OkHttpDataSource.Factory(Http.client))),
            )
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ONE
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .apply { CanvasPlaybackLimits.maxFrameRate?.let(::setMaxVideoFrameRate) }
                    .build()
            }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val width = videoSize.width * videoSize.pixelWidthHeightRatio
                val aspect = if (width.isFinite() && width > 0f && videoSize.height > 0) {
                    width / videoSize.height
                } else 0f
                if (aspect != clipAspect) rendered = false
                clipAspect = aspect
                reportAspect(aspect)
                textureView?.applyContentTransform(clipAspect, currentContentMode, currentAlignPortraitTop)
            }

            override fun onPlayerError(error: PlaybackException) {
                val alternate = canvas.fallbackUrl
                if (alternate != null && alternate != url) {
                    url = alternate
                } else {
                    rendered = false
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            teardown.disposed = true
            player.stop()
            mainHandler.postDelayed(
                {
                    player.release()
                    teardown.releaseOrphan()
                },
                DEFERRED_RELEASE_MS,
            )
        }
    }

    LaunchedEffect(url) {
        rendered = false
        clipAspect = 0f
        reportAspect(0f)
        val item = MediaItem.Builder().setUri(url)
        mimeTypeOf(url)?.let { item.setMimeType(it) }
        val startAt = currentStartPositionMs
        gate.holdingStill = startAt > 0L
        player.setMediaItem(item.build(), startAt)
        player.prepare()
    }

    val foreground = rememberIsForeground()
    LaunchedEffect(foreground, pausedForTransition) {
        val play = foreground && !pausedForTransition
        val afterTransition = gate.heldForTransition
        gate.heldForTransition = pausedForTransition
        if (play && afterTransition && currentResumeDelayMs > 0L) delay(currentResumeDelayMs)
        player.playWhenReady = play
    }

    val reportStill by rememberUpdatedState(onStillCaptured)
    LaunchedEffect(captureStill) {
        if (!captureStill) return@LaunchedEffect
        withFrameMillis { }
        withFrameMillis { }
        val view = textureView
        if (!rendered || view == null) return@LaunchedEffect
        val position = player.currentPosition
        val still = view.copyStill(player.videoSize, clipAspect, contentMode, alignPortraitTop)
        if (still != null) gate.holdingStill = true
        Log.d(TAG, "still captured: ${still?.let { "${it.width}x${it.height}" }} at $position")
        reportStill(still?.asImageBitmap(), position)
    }

    LaunchedEffect(surfaceGeneration) {
        if (surfaceGeneration == 0) return@LaunchedEffect
        rendered = false
        val before = frameTick
        if (player.playbackState != Player.STATE_IDLE) {
            player.seekTo(player.currentPosition)
        }
        delay(REPAINT_TIMEOUT_MS)
        if (frameTick == before) {
            rendered = false
        }
    }

    val reportRendered by rememberUpdatedState(onRenderedChanged)
    LaunchedEffect(rendered) {
        reportRendered(rendered)
        if (!rendered) return@LaunchedEffect
        if (frameCapturePx <= 0) return@LaunchedEffect
        withFrameMillis { }
        val view = textureView ?: return@LaunchedEffect
        val bitmap = view.captureAt(frameCapturePx, clipAspect, contentMode, alignPortraitTop)
        if (bitmap != null) {
            Log.d(TAG, "frame captured after rendered=true, size=${bitmap.width}x${bitmap.height}")
            onFrameCaptured(bitmap.asImageBitmap())
        } else {
            Log.w(TAG, "frame capture returned null after rendered=true")
        }
    }

    LaunchedEffect(
        rendered, refreshFrameEveryMs, frameCapturePx, clipAspect, contentMode, alignPortraitTop,
        isPlaying, pausedForTransition,
    ) {
        val interval = refreshFrameEveryMs ?: return@LaunchedEffect
        if (frameCapturePx <= 0) return@LaunchedEffect
        Log.d(TAG, "periodic frame refresh started, interval=$interval")
        if (!rendered || !isPlaying || pausedForTransition) return@LaunchedEffect
        while (isActive) {
            delay(interval)
            val view = textureView ?: continue
            val bitmap = view.captureAt(frameCapturePx, clipAspect, contentMode, alignPortraitTop)
            if (bitmap != null) {
                Log.d(TAG, "periodic frame captured, size=${bitmap.width}x${bitmap.height}")
                onFrameCaptured(bitmap.asImageBitmap())
            } else {
                Log.w(TAG, "periodic frame capture returned null")
            }
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (rendered) 1f else 0f,
        animationSpec = if (revealInstantly) snap() else tween(durationMillis = 320),
        label = "canvasAlpha",
    )

    val reportCover by rememberUpdatedState(onCoverChanged)
    LaunchedEffect(Unit) {
        snapshotFlow { alpha * currentPresentationAlpha() }.collect { reportCover(it) }
    }
    DisposableEffect(Unit) {
        onDispose {
            reportRendered(false)
            reportCover(0f)
            reportAspect(0f)
        }
    }

    AndroidView(
        factory = { viewContext ->
            val texture = TextureView(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                isOpaque = false
                this.alpha = 0f
                player.setVideoTextureView(this)
                val delegate = surfaceTextureListener
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    private var replacing = false

                    override fun onSurfaceTextureAvailable(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        delegate?.onSurfaceTextureAvailable(surface, width, height)
                        teardown.releaseOrphan()
                        if (!replacing) return
                        replacing = false
                        Log.d(TAG, "surface recreated (gen $surfaceGeneration), waiting for frame")
                        surfaceGeneration++
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        delegate?.onSurfaceTextureSizeChanged(surface, width, height)
                        if (currentContentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect in 0f..1f) {
                            rendered = false
                        }
                    }

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        replacing = true
                        Log.d(TAG, "surface destroyed, rendered=$rendered")
                        rendered = false
                        if (currentPausedForTransition || teardown.disposed) {
                            teardown.orphan = surface
                            return false
                        }
                        return delegate?.onSurfaceTextureDestroyed(surface) ?: true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        delegate?.onSurfaceTextureUpdated(surface)
                        if (gate.holdingStill && player.playWhenReady) {
                            gate.holdingStill = false
                            reportResumed()
                        }
                        if (!rendered) {
                            val transformed = textureView?.applyContentTransform(
                                clipAspect, currentContentMode, currentAlignPortraitTop,
                            ) == true
                            val portrait = currentContentMode == CanvasContentMode.FIT_PORTRAIT &&
                                clipAspect > 0f && clipAspect < 1f
                            val expected = currentPortraitRevealBounds
                            val view = textureView
                            val layoutReady = !portrait ||
                                (expected != IntSize.Zero && view?.width == expected.width &&
                                    view?.height == expected.height)
                            if ((currentContentMode == CanvasContentMode.CROP || transformed) && layoutReady) {
                                rendered = true
                                frameTick++
                                Log.d(TAG, "first frame on surface (tick $frameTick, gen $surfaceGeneration)")
                            }
                        }
                    }
                }
            }
            textureView = texture
            FadingBottomFrame(viewContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                addView(texture)
            }
        },
        update = { frame ->
            val view = frame.getChildAt(0) as TextureView
            val applied = frame.applied
            view.alpha = if (contentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect <= 0f) {
                0f
            } else {
                alpha * presentationAlpha()
            }
            if (applied.transformDiffers(clipAspect, contentMode, alignPortraitTop, view.width, view.height)) {
                view.applyContentTransform(clipAspect, contentMode, alignPortraitTop)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (applied.fadeDiffers(bottomFade, bounds, bottomFadeEndPx)) {
                    view.setBottomFade(bottomFade, bounds, bottomFadeEndPx)
                }
            } else {
                frame.fadeFraction = bottomFade
                frame.fadeEndPx = bottomFadeEndPx
            }
        },
        modifier = modifier.onSizeChanged { bounds = it },
    )
}

private fun TextureView.captureAt(
    maxPx: Int,
    clipAspect: Float,
    contentMode: CanvasContentMode,
    alignPortraitTop: Boolean,
): Bitmap? {
    val viewWidth = width
    val viewHeight = height
    if (viewWidth <= 0 || viewHeight <= 0) return null
    val scale = maxPx.toFloat() / maxOf(viewWidth, viewHeight)
    return runCatching {
        val frame = if (scale >= 1f) {
            getBitmap()
        } else {
            getBitmap(
                (viewWidth * scale).roundToInt().coerceAtLeast(1),
                (viewHeight * scale).roundToInt().coerceAtLeast(1),
            )
        } ?: return null
        if (contentMode != CanvasContentMode.FIT_PORTRAIT || clipAspect >= 1f || clipAspect <= 0f) {
            return frame
        }

        val viewAspect = viewWidth.toFloat() / viewHeight
        val contentWidth = if (clipAspect < viewAspect) frame.height * clipAspect else frame.width.toFloat()
        val contentHeight = if (clipAspect < viewAspect) frame.height.toFloat() else frame.width / clipAspect
        val left = ceil((frame.width - contentWidth) / 2f).toInt().coerceIn(0, frame.width - 1)
        val top = if (alignPortraitTop) 0 else
            ceil((frame.height - contentHeight) / 2f).toInt().coerceIn(0, frame.height - 1)
        val right = (frame.width - left).coerceAtLeast(left + 1)
        val bottom = if (alignPortraitTop) contentHeight.toInt().coerceIn(1, frame.height) else
            (frame.height - top).coerceAtLeast(top + 1)
        Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
    }.getOrNull()
}

private suspend fun TextureView.copyStill(
    video: VideoSize,
    clipAspect: Float,
    contentMode: CanvasContentMode,
    alignPortraitTop: Boolean,
): Bitmap? {
    val texture = surfaceTexture ?: return null
    val videoWidth = (video.width * video.pixelWidthHeightRatio).roundToInt()
    val videoHeight = video.height
    if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) return null
    val contained = contentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect > 0f && clipAspect < 1f
    val scale = minOf(
        1f,
        if (contained) {
            width.toFloat() / videoWidth
        } else {
            maxOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        },
    )
    val bitmap = Bitmap.createBitmap(
        (videoWidth * scale).roundToInt().coerceAtLeast(1),
        (videoHeight * scale).roundToInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    val result = runCatching {
        suspendCancellableCoroutine { continuation ->
            val surface = Surface(texture)
            PixelCopy.request(
                surface,
                bitmap,
                { result ->
                    surface.release()
                    if (continuation.isActive) continuation.resume(result)
                },
                mainHandler,
            )
        }
    }.getOrDefault(PixelCopy.ERROR_UNKNOWN)
    if (result == PixelCopy.SUCCESS) return bitmap
    Log.w(TAG, "still copy failed ($result), reading the view instead")
    bitmap.recycle()
    return captureAt(maxOf(width, height), clipAspect, contentMode, alignPortraitTop)
}

private const val FRAME_CAPTURE_PX = 128

private fun TextureView.applyContentTransform(
    clipAspect: Float,
    contentMode: CanvasContentMode,
    alignPortraitTop: Boolean,
): Boolean {
    val bounds = IntSize(width, height)
    if (bounds.width <= 0 || bounds.height <= 0 || !clipAspect.isFinite() || clipAspect <= 0f) {
        setTransform(Matrix())
        return false
    }
    val viewAspect = bounds.width.toFloat() / bounds.height
    val pivotX = bounds.width / 2f
    val pivotY = if (alignPortraitTop && contentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect < 1f) {
        0f
    } else bounds.height / 2f
    val matrix = Matrix().apply {
        val fit = contentMode == CanvasContentMode.FIT_PORTRAIT && clipAspect < 1f
        if (fit && clipAspect > viewAspect) {
            setScale(1f, viewAspect / clipAspect, pivotX, pivotY)
        } else if (fit) {
            setScale(clipAspect / viewAspect, 1f, pivotX, pivotY)
        } else if (clipAspect > viewAspect) {
            setScale(clipAspect / viewAspect, 1f, pivotX, pivotY)
        } else {
            setScale(1f, viewAspect / clipAspect, pivotX, pivotY)
        }
    }
    setTransform(matrix)
    return true
}

@RequiresApi(Build.VERSION_CODES.S)
private fun TextureView.setBottomFade(fraction: Float, bounds: IntSize, endPx: Float?) {
    val endY = endPx?.coerceIn(0f, bounds.height.toFloat()) ?: bounds.height.toFloat()
    if (fraction <= 0.001f || endY <= 0f) {
        setRenderEffect(null)
        return
    }
    val gradient = LinearGradient(
        0f,
        endY * (1f - fraction.coerceAtMost(1f)),
        0f,
        endY,
        IntArray(FADE_CURVE_STOPS + 1) { i ->
            android.graphics.Color.argb(1f - smootherStep(i / FADE_CURVE_STOPS.toFloat()), 0f, 0f, 0f)
        },
        FloatArray(FADE_CURVE_STOPS + 1) { i -> i / FADE_CURVE_STOPS.toFloat() },
        Shader.TileMode.CLAMP,
    )
    setRenderEffect(
        RenderEffect.createBlendModeEffect(
            RenderEffect.createOffsetEffect(0f, 0f),
            RenderEffect.createShaderEffect(gradient),
            BlendMode.DST_IN,
        ),
    )
}

private class AppliedLook {
    private var aspect = Float.NaN
    private var mode: CanvasContentMode? = null
    private var alignTop = false
    private var width = -1
    private var height = -1

    private var fade = Float.NaN
    private var fadeBounds = IntSize(-1, -1)
    private var fadeEnd: Float? = Float.NaN

    fun transformDiffers(
        clipAspect: Float,
        contentMode: CanvasContentMode,
        alignPortraitTop: Boolean,
        viewWidth: Int,
        viewHeight: Int,
    ): Boolean {
        if (clipAspect == aspect && contentMode == mode && alignPortraitTop == alignTop &&
            viewWidth == width && viewHeight == height
        ) return false
        aspect = clipAspect
        mode = contentMode
        alignTop = alignPortraitTop
        width = viewWidth
        height = viewHeight
        return true
    }

    fun fadeDiffers(fraction: Float, bounds: IntSize, endPx: Float?): Boolean {
        if (fraction == fade && bounds == fadeBounds && endPx == fadeEnd) return false
        fade = fraction
        fadeBounds = bounds
        fadeEnd = endPx
        return true
    }
}

private class FadingBottomFrame(context: Context) : FrameLayout(context) {
    val applied = AppliedLook()

    var fadeFraction: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped == field) return
            field = clamped
            gradient = null
            invalidate()
        }
    var fadeEndPx: Float? = null
        set(value) {
            if (value == field) return
            field = value
            gradient = null
            invalidate()
        }

    private val maskPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
    private var gradient: LinearGradient? = null
    private var gradientHeight = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradient = null
    }

    override fun dispatchDraw(canvas: Canvas) {
        val fade = fadeFraction
        val endY = fadeEndPx?.coerceIn(0f, height.toFloat()) ?: height.toFloat()
        if (fade <= 0.001f || endY <= 0f) {
            super.dispatchDraw(canvas)
            return
        }
        val shader = gradient?.takeIf { gradientHeight == height } ?: LinearGradient(
            0f,
            endY * (1f - fade),
            0f,
            endY,
            IntArray(FADE_CURVE_STOPS + 1) { i ->
                android.graphics.Color.argb(1f - smootherStep(i / FADE_CURVE_STOPS.toFloat()), 0f, 0f, 0f)
            },
            FloatArray(FADE_CURVE_STOPS + 1) { i -> i / FADE_CURVE_STOPS.toFloat() },
            Shader.TileMode.CLAMP,
        ).also {
            gradient = it
            gradientHeight = height
        }
        maskPaint.shader = shader
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.restoreToCount(layer)
    }
}

private fun mimeTypeOf(url: String): String? {
    val path = url.substringBefore('?').lowercase(Locale.ROOT)
    return when {
        path.endsWith(".m3u8") -> MimeTypes.APPLICATION_M3U8
        path.endsWith(".mp4") -> MimeTypes.VIDEO_MP4
        else -> null
    }
}

@Composable
internal fun AndroidCanvasVideo(spec: CanvasVideoSpec, modifier: Modifier) {
    AndroidCanvasArtworkPlayer(
        canvas = spec.canvas,
        isPlaying = spec.isPlaying,
        modifier = modifier,
        contentMode = spec.contentMode,
        alignPortraitTop = spec.alignPortraitTop,
        onAspectRatioChanged = spec.onAspectRatioChanged,
        portraitRevealBounds = spec.portraitRevealBounds,
        presentationAlpha = spec.presentationAlpha,
        onRenderedChanged = spec.onRenderedChanged,
        onFrameCaptured = spec.onFrameCaptured,
        refreshFrameEveryMs = spec.refreshFrameEveryMs,
        frameCapturePx = spec.frameCapturePx,
        onCoverChanged = spec.onCoverChanged,
        bottomFade = spec.bottomFade,
        bottomFadeEndPx = spec.bottomFadeEndPx,
        pausedForTransition = spec.pausedForTransition,
        resumeDelayMs = spec.resumeDelayMs,
        startPositionMs = spec.startPositionMs,
        revealInstantly = spec.revealInstantly,
        captureStill = spec.captureStill,
        onStillCaptured = spec.onStillCaptured,
        onResumed = spec.onResumed,
    )
}

object CanvasPlaybackLimits {
    @Volatile
    var maxFrameRate: Int? = null
}
