package com.bxane.nuvx.ui.player

import com.bxane.nuvx.ui.icons.SfIcons
import com.bxane.nuvx.sharedui.resources.*

import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.request.ImageRequest
import com.bxane.nuvx.ui.components.ExplicitBadge
import com.bxane.nuvx.ui.components.menuAnchor
import com.bxane.nuvx.ui.haptics.Haptic
import com.bxane.nuvx.ui.theme.stableStatusBarInsets
import com.bxane.nuvx.data.canvas.CanvasArtwork
import com.bxane.nuvx.data.model.LikeStatus
import com.bxane.nuvx.data.model.Song

private val LANDSCAPE_PLAYER_MAX_WIDTH = 1100.dp

private val LANDSCAPE_COMPACT_HEIGHT = 440.dp

private val LANDSCAPE_GUTTER_COMPACT = 20.dp

private const val LANDSCAPE_PANE_FADE_IN_MS = 220
private const val LANDSCAPE_PANE_FADE_IN_DELAY_MS = 90
private const val LANDSCAPE_PANE_FADE_OUT_MS = 140

internal enum class PlayerPane { Main, Lyrics, Queue }

internal val LANDSCAPE_ART_CORNER = 10.dp

private val LandscapeArtShape = RoundedCornerShape(LANDSCAPE_ART_CORNER)

private val LANDSCAPE_HANDLE_STRIP = 24.dp

@Composable
internal fun LandscapePlayerLayout(
    pane: PlayerPane,
    background: @Composable (Modifier) -> Unit,
    artwork: @Composable (Modifier) -> Unit,
    actions: @Composable () -> Unit,
    mainPane: @Composable (compact: Boolean) -> Unit,
    lyricsPane: @Composable () -> Unit,
    queuePane: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        background(Modifier.fillMaxSize())

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.union(stableStatusBarInsets())),
            contentAlignment = Alignment.TopCenter,
        ) {
            val compact = maxHeight < LANDSCAPE_COMPACT_HEIGHT
            val gutter = if (compact) LANDSCAPE_GUTTER_COMPACT else PLAYER_GUTTER

            Row(
                modifier = Modifier
                    .widthIn(max = LANDSCAPE_PLAYER_MAX_WIDTH)
                    .fillMaxSize()
                    .padding(
                        top = LANDSCAPE_HANDLE_STRIP,
                        bottom = if (compact) 8.dp else 20.dp,
                    ),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(horizontal = gutter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        artwork(Modifier.size(minOf(maxWidth, maxHeight)))
                    }
                    Spacer(Modifier.height(if (compact) 12.dp else 24.dp))
                    actions()
                }

                AnimatedContent(
                    targetState = pane,
                    transitionSpec = {
                        fadeIn(
                            tween(
                                LANDSCAPE_PANE_FADE_IN_MS,
                                delayMillis = LANDSCAPE_PANE_FADE_IN_DELAY_MS,
                            ),
                        ) togetherWith fadeOut(tween(LANDSCAPE_PANE_FADE_OUT_MS))
                    },
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    label = "landscapePane",
                ) { shown ->
                    when (shown) {
                        PlayerPane.Main -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = gutter),
                            contentAlignment = Alignment.Center,
                        ) {
                            mainPane(compact)
                        }
                        PlayerPane.Lyrics -> Box(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = PLAYER_GUTTER),
                        ) {
                            lyricsPane()
                        }
                        PlayerPane.Queue -> Box(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = PLAYER_GUTTER),
                        ) {
                            queuePane()
                        }
                    }
                }
            }

            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = 8.dp)
                    .width(38.dp)
                    .height(5.dp)
                    .shadow(2.dp, RoundedCornerShape(3.dp), clip = false)
                    .clip(RoundedCornerShape(3.dp))
                    .additiveChrome(LocalPlayerChrome.current)
                    .background(chromeWhite(0.70f)),
            )
        }
    }
}

@Composable
internal fun LandscapeArtwork(
    artRequest: ImageRequest,
    artLoaded: Boolean,
    onArtState: (AsyncImagePainter.State) -> Unit,
    canvas: CanvasArtwork?,
    canvasRendered: Boolean,
    isPlaying: Boolean,
    onCanvasRenderedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    pausedForTransition: Boolean = false,
    sleeveShape: () -> Shape = { LandscapeArtShape },
    shadowFraction: () -> Float = { 1f },
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    val casts = artLoaded || canvasRendered
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    shadowElevation = if (casts) 14.dp.toPx() * shadowFraction() else 0f
                    shape = sleeveShape()
                    clip = casts
                }
                .graphicsLayer {
                    shape = sleeveShape()
                    clip = true
                }
                .background(Color.Black.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            if (!artLoaded && !canvasRendered) {
                Icon(
                    imageVector = SfIcons.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.35f),
                    modifier = Modifier.fillMaxSize(0.36f),
                )
            }
            AsyncImage(
                model = artRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = onArtState,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent { if (artLoaded || !canvasRendered) drawContent() },
            )
            canvas?.let { clip ->
                CanvasArtworkPlayer(
                    canvas = clip,
                    isPlaying = isPlaying,
                    pausedForTransition = pausedForTransition,
                    onRenderedChanged = onCanvasRenderedChange,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        overlay()
    }
}

@Composable
internal fun LandscapeMainPane(
    compact: Boolean,
    caption: String?,
    onOpenCaption: () -> Unit,
    credits: @Composable () -> Unit,
    lyricStrip: (@Composable () -> Unit)?,
    scrubber: @Composable () -> Unit,
    transport: @Composable () -> Unit,
    volume: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = PLAYER_MAX_WIDTH)
            .fillMaxWidth(),
    ) {
        if (caption != null) {
            PlaybackOriginCaption(
                text = caption,
                onClick = onOpenCaption,
                textAlign = TextAlign.Start,
                contentPadding = PaddingValues(vertical = 2.dp),
            )
            Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
        }
        credits()
        Spacer(Modifier.height(if (compact) 2.dp else 10.dp))
        lyricStrip?.invoke()
        scrubber()
        Spacer(Modifier.height(if (compact) 0.dp else 8.dp))
        transport()
        if (volume != null) {
            Spacer(Modifier.height(if (compact) 0.dp else 12.dp))
            volume()
        }
    }
}

@Composable
internal fun LandscapeCredits(
    song: Song,
    signedIn: Boolean,
    likeStatus: LikeStatus,
    showRevertCue: Boolean,
    onToggleLike: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Crossfade(
            targetState = song.title to song.artist,
            animationSpec = tween(durationMillis = 300),
            modifier = Modifier.weight(1f).additiveChrome(LocalPlayerChrome.current),
            label = "landscapeCredits",
        ) {
            Column {
                var titleOverflowing by remember { mutableStateOf(false) }
                val artistColor = chromeWhite(0.55f)
                MarqueeText(
                    text = song.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    onOverflowChange = { titleOverflowing = it },
                    leading = if (song.isExplicit == true) {
                        { ExplicitBadge(artistColor) }
                    } else {
                        null
                    },
                    modifier = Modifier.opensPage(song.albumId, onOpenAlbum),
                )
                Spacer(Modifier.height(2.dp))
                ArtistCreditsMarquee(
                    text = song.artist,
                    credits = remember(song.artist, song.artists, song.artistId) {
                        artistCredits(song.artist, song.artists, song.artistId)
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W500),
                    color = artistColor,
                    startDelayMillis = if (titleOverflowing) MARQUEE_ARTIST_STAGGER_MS else 0L,
                    onOpenArtist = onOpenArtist,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (signedIn || song.localUri != null) {
            val liked = likeStatus == LikeStatus.LIKE
            CircleGlyph(
                icon = if (liked) SfIcons.StarFill else SfIcons.Star,
                contentDescription = stringResource(
                    if (liked) Res.string.remove_from_liked else Res.string.like,
                ),
                onClick = onToggleLike,
                active = liked,
                haptic = if (liked) Haptic.ToggleOff else Haptic.ToggleOn,
            )
            Spacer(Modifier.width(8.dp))
        }
        CircleGlyph(
            icon = if (showRevertCue) SfIcons.Undo else SfIcons.MoreHoriz,
            contentDescription = stringResource(Res.string.more),
            onClick = onOpenMenu,
            modifier = Modifier.menuAnchor(),
        )
    }
}

@Composable
internal fun LandscapeLyricsPane(
    hasLyrics: Boolean,
    placeholder: String,
    status: String,
    onStatusClick: () -> Unit,
    picking: Boolean = false,
    pickBar: @Composable () -> Unit = {},
    romanizationToggle: @Composable () -> Unit,
    translationToggle: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    panel: @Composable (Modifier) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (hasLyrics) {
                panel(Modifier.fillMaxSize())
            } else {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        if (picking) {
            Box(Modifier.fillMaxWidth()) { pickBar() }
        } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp)) { if (hasLyrics) romanizationToggle() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                LyricsStatusWithChange(
                    status = status,
                    onStatusClick = onStatusClick,
                )
            }
            Box(Modifier.size(34.dp)) { if (hasLyrics) translationToggle() }
        }
        }
    }
}
