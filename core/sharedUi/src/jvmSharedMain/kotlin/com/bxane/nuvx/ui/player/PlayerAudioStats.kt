package com.bxane.nuvx.ui.player

import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.isSpecified
import com.bxane.nuvx.sharedui.resources.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.data.NerdStats
import com.bxane.nuvx.data.settings.TrackAnalysisState
import com.bxane.nuvx.data.settings.AudioQuality
import com.bxane.nuvx.data.model.Song
import java.util.Locale

private const val UPGRADING_PULSE_LOW = 0.4f
private const val UPGRADING_PULSE_MS = 900

@Composable
internal fun PlaybackQualityLabel(
    song: Song,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    val wifiQuality by PlayerSettings.audioQualityWifi.collectAsStateWithLifecycle()
    val cellularQuality by PlayerSettings.audioQualityCellular.collectAsStateWithLifecycle()
    val metered by PlayerSettings.meteredConnection.collectAsStateWithLifecycle()
    val ceilingOverride by PlayerSettings.streamCeilingOverride.collectAsStateWithLifecycle()
    val nerdStats by NerdStats.current.collectAsStateWithLifecycle()
    val chosenQuality = if (metered == true) cellularQuality else wifiQuality
    val effectiveQuality = ceilingOverride
        ?.takeIf { it.ordinal < chosenQuality.ordinal }
        ?: chosenQuality
    val racingLossless by NerdStats.racingLossless.collectAsStateWithLifecycle()
    LosslessOrStats(
        isLoading = isLoading,
        stillRacing = song.videoId in racingLossless,
        losslessRequested = effectiveQuality == AudioQuality.LOSSLESS,
        nerdStats = nerdStats,
        modifier = modifier,
    )
}

@Composable
internal fun SleeveNerdStats(song: Song, modifier: Modifier = Modifier) {
    val showNerdStats by PlayerSettings.showNerdStats.collectAsStateWithLifecycle()
    if (!showNerdStats) return
    val nerdStats by NerdStats.current.collectAsStateWithLifecycle()
    val smartFadeOn by PlayerSettings.smartFadeEnabled.collectAsStateWithLifecycle()
    val smartAnalysis by PlayerSettings.smartAnalysis.collectAsStateWithLifecycle()
    val automixDetail by PlayerSettings.automixDetail.collectAsStateWithLifecycle()
    val inParty by remember {
        PlayerPlatform.host.party.map { it.inParty }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = PlayerPlatform.host.party.value.inParty)
    val nerdStyle = MaterialTheme.typography.labelSmall.copy(
        shadow = Shadow(
            color = Color.Black.copy(alpha = 0.55f),
            offset = Offset(0f, 1f),
            blurRadius = 4f,
        ),
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        nerdStats?.describe()?.let { stats ->
            Text(
                text = stats,
                style = nerdStyle,
                color = Color.White.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        if (smartFadeOn) {
            Text(
                text = when {
                    inParty -> stringResource(Res.string.automix_stopped_in_party)
                    song.isVideoOrigin ->
                        stringResource(Res.string.automix_not_supported_video)
                    automixDetail != null -> automixDetail!!
                    else -> stringResource(
                        Res.string.automix_analysis_status,
                        smartAnalysis.current.localizedLabel(),
                        smartAnalysis.next.localizedLabel(),
                    )
                },
                style = nerdStyle,
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LosslessOrStats(
    isLoading: Boolean,
    stillRacing: Boolean,
    losslessRequested: Boolean,
    nerdStats: NerdStats.Snapshot?,
    modifier: Modifier = Modifier,
) {
    when {
        (stillRacing && nerdStats?.isLossless != true) ||
            (isLoading && losslessRequested && nerdStats == null) -> LosslessLabel(
            text = stringResource(Res.string.upgrading_quality),
            iconPainter = painterResource(Res.drawable.ic_lossless),
            pulsing = true,
            animated = false,
            modifier = modifier,
        )
        nerdStats?.isLossless == true -> LosslessLabel(
            text = stringResource(if (nerdStats.isHiRes) Res.string.hi_res_lossless else Res.string.lossless),
            iconPainter = painterResource(Res.drawable.ic_lossless),
            animated = true,
            modifier = modifier,
        )
        nerdStats?.isDolbyAtmos == true -> LosslessLabel(
            text = "Dolby Atmos",
            animated = true,
            iconPainter = painterResource(Res.drawable.ic_dolby_atmos),
            modifier = modifier,
        )
        nerdStats?.isHiQuality == true -> LosslessLabel(
            text = stringResource(Res.string.high_quality),
            animated = false,
            iconPainter = rememberVectorPainter(SfIcons.Headphones),
            iconSize = 13.dp,
            modifier = modifier,
        )
        else -> {}
    }
}

@Composable
private fun LosslessLabel(
    text: String,
    animated: Boolean,
    iconPainter: Painter,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
    iconSize: Dp = Dp.Unspecified,
) {
    val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()
    val pulse = if (pulsing && !reduceAnimation) {
        rememberInfiniteTransition(label = "upgradingPulse").animateFloat(
            initialValue = 1f,
            targetValue = UPGRADING_PULSE_LOW,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = UPGRADING_PULSE_MS, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "upgradingPulseAlpha",
        )
    } else {
        null
    }
    Row(
        modifier = modifier
            .graphicsLayer {
                alpha = pulse?.value ?: 1f
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .additiveChrome(LocalPlayerChrome.current),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = chromeWhite(if (animated) 0.7f else 0.45f)
        Icon(
            painter = iconPainter,
            contentDescription = null,
            tint = tint,
            modifier = if (iconSize.isSpecified) Modifier.size(iconSize) else Modifier,
        )
        Spacer(Modifier.width(4.dp))
        if (animated) {
            ShimmerText(text = text)
        } else {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = (MaterialTheme.typography.labelMedium.fontSize.value + BADGE_SIZE_BUMP).sp,
                ),
                color = chromeWhite(0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
internal fun ShimmerText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelMedium.copy(
        fontWeight = FontWeight.Medium,
        fontSize = (MaterialTheme.typography.labelMedium.fontSize.value + BADGE_SIZE_BUMP).sp,
    ),
    baseAlpha: Float = 0.55f,
) {
    val transition = rememberInfiniteTransition(label = "lossless-shimmer")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "lossless-shimmer-progress",
    )
    val tint = Color.White.copy(alpha = if (LocalPlayerChrome.current) CHROME_FULL_ALPHA else 1f)
    val baseColor = chromeWhite(baseAlpha)
    Text(
        text = text,
        style = style,
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val width = size.width
                val band = width * 0.6f
                val center = -band + progress.value * (width + 2 * band)
                drawRect(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(0f to baseColor, 0.5f to tint, 1f to baseColor),
                        start = Offset(center - band, 0f),
                        end = Offset(center + band, 0f),
                    ),
                    blendMode = BlendMode.SrcIn,
                )
            },
    )
}

@Composable
private fun NerdStats.Snapshot.describe(): String? {
    val parts = buildList {
        codecLabel(mimeType)?.let(::add)
        bitDepth?.let { add(stringResource(Res.string.bit_depth, it)) }
        sampleRateHz?.let { add(NerdStats.megahertzLabel(it) ?: "%.1f kHz".format(Locale.ROOT, it / 1000f)) }
        bitrateKbps?.let { add("$it kbps") }
        channels?.let {
            add(
                when (it) {
                    1 -> stringResource(Res.string.mono)
                    2 -> stringResource(Res.string.stereo)
                    else -> stringResource(Res.string.channel_count, it)
                },
            )
        }
    }
    return parts.joinToString(" · ").takeIf { it.isNotEmpty() }
}

private fun codecLabel(mimeType: String?): String? = when {
    mimeType == null -> null
    mimeType.endsWith("opus") -> "Opus"
    mimeType.endsWith("mp4a-latm") -> "AAC"
    mimeType.endsWith("vorbis") -> "Vorbis"
    mimeType.endsWith("mpeg") -> "MP3"
    mimeType.endsWith("flac") -> "FLAC"
    mimeType.endsWith("alac") -> "ALAC"
    else -> mimeType.substringAfter('/').uppercase(Locale.ROOT)
}

@Composable
private fun TrackAnalysisState.localizedLabel(): String = when (this) {
    TrackAnalysisState.ANALYSED -> stringResource(Res.string.analysis_complete)
    TrackAnalysisState.REFINING -> stringResource(Res.string.analysis_refining)
    TrackAnalysisState.ANALYSING -> stringResource(Res.string.analysis_in_progress)
    TrackAnalysisState.WAITING -> stringResource(Res.string.waiting)
    TrackAnalysisState.FAILED -> stringResource(Res.string.failed)
}
