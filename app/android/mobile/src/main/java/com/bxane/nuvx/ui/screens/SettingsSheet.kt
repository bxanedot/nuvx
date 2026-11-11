package com.bxane.nuvx.ui.screens

import com.bxane.nuvx.ui.icons.SfIcons
import com.bxane.nuvx.wear.WatchInstaller
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import com.bxane.nuvx.data.lyrics.translationLanguageName
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.bxane.nuvx.ui.components.IosSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import kotlin.math.abs
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import com.bxane.nuvx.AppIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import com.bxane.nuvx.ui.components.PillSegmentedControl
import com.bxane.nuvx.ui.components.isGlassSupported
import com.bxane.nuvx.ui.components.languageDisplayNameRes
import com.bxane.nuvx.ui.components.MessageState
import com.bxane.nuvx.ui.components.SearchField
import com.bxane.nuvx.ui.components.thumbnailBorder
import com.bxane.nuvx.ui.performance.resolvePerformanceRefreshRate
import com.bxane.nuvx.ui.performance.supportedPerformanceRefreshRates
import com.bxane.nuvx.data.model.Account
import com.bxane.nuvx.data.LocalMediaRepository
import com.bxane.nuvx.data.NerdStats
import com.bxane.nuvx.data.scrobbling.LastFM
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.ui.components.bounceOnReveal
import com.bxane.nuvx.ui.components.RevealIcon
import com.bxane.nuvx.data.settings.OutputPcmMode
import com.bxane.nuvx.data.settings.BitPerfectMode
import com.bxane.nuvx.playback.AudioOutputStatus
import com.bxane.nuvx.playback.audio.bitperfect.BitPerfectOutput
import com.bxane.nuvx.data.settings.AutomixPerformanceMode
import com.bxane.nuvx.R
import com.bxane.nuvx.data.sources.DeviceCodecs
import com.bxane.nuvx.data.settings.AudioQuality
import com.bxane.nuvx.data.settings.DownloadQuality
import com.bxane.nuvx.data.settings.ThemeMode
import com.bxane.nuvx.data.settings.AppFont
import com.bxane.nuvx.data.settings.PlayerTheme
import com.bxane.nuvx.data.stats.Backup
import com.bxane.nuvx.playback.AudioCache
import com.bxane.nuvx.ui.player.fullBleedArtworkAvailable
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import java.util.Locale
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onVisibilityChanged

internal fun Context.countOfMonths(months: Int): String = if (months == 0) {
    getString(R.string.no_listening_history)
} else {
    resources.getQuantityString(R.plurals.listening_month_count, months, months)
}

@Composable
internal fun AudioQuality.settingsChoiceLabel(): String = stringResource(
    when (this) {
        AudioQuality.LOW -> R.string.low
        AudioQuality.MEDIUM -> R.string.medium
        AudioQuality.HIGH -> R.string.high
        AudioQuality.LOSSLESS -> R.string.lossless
    },
)

@Composable
internal fun DownloadQuality.localizedLabel(): String = stringResource(
    when (this) {
        DownloadQuality.STANDARD -> R.string.standard
        DownloadQuality.HIGH -> R.string.high
        DownloadQuality.LOSSLESS -> R.string.lossless
    },
)

@Composable
internal fun ThemeMode.localizedLabel(): String = stringResource(
    when (this) {
        ThemeMode.SYSTEM -> R.string.system
        ThemeMode.LIGHT -> R.string.light
        ThemeMode.DARK -> R.string.dark
    },
)

@Composable
internal fun PlayerTheme.localizedLabel(): String = stringResource(
    when (this) {
        PlayerTheme.DEFAULT -> R.string.player_theme_default
        PlayerTheme.LEGACY -> R.string.player_theme_legacy
        PlayerTheme.SIMPLE -> R.string.player_theme_simple
    },
)

@Composable
internal fun PlayerTheme.description(): String = stringResource(
    when (this) {
        PlayerTheme.DEFAULT -> R.string.player_theme_default_subtitle
        PlayerTheme.LEGACY -> R.string.player_theme_legacy_subtitle
        PlayerTheme.SIMPLE -> R.string.player_theme_simple_subtitle
    },
)

@Composable
internal fun AppFont.localizedLabel(): String = stringResource(
    when (this) {
        AppFont.DEFAULT -> R.string.font_default
        AppFont.LEGACY -> R.string.font_legacy
    },
)

@Composable
internal fun AppFont.description(): String = stringResource(
    when (this) {
        AppFont.DEFAULT -> R.string.font_default_subtitle
        AppFont.LEGACY -> R.string.font_legacy_subtitle
    },
)

@Composable
internal fun AutomixPerformanceMode.localizedLabel(): String = stringResource(
    when (this) {
        AutomixPerformanceMode.EFFICIENT -> R.string.automix_mode_efficient
        AutomixPerformanceMode.BALANCED -> R.string.automix_mode_balanced
        AutomixPerformanceMode.PERFORMANCE -> R.string.automix_mode_performance
    },
)

internal fun openEqualizer(context: Context, sessionId: Int) {
    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, context.getString(R.string.no_equalizer), Toast.LENGTH_SHORT).show()
    }
}

internal const val CACHE_WARNING_MB = 2048

internal const val CACHE_UNLIMITED_STOP_MB = 10 * 1024 + 512

internal fun formatCacheSize(mb: Int): String {
    if (mb < 1024) return "$mb MB"
    val gb = mb / 1024f
    return if (gb == gb.toInt().toFloat()) "${gb.toInt()} GB" else "%.1f GB".format(Locale.ROOT, gb)
}

@Composable
internal fun AccountCard(
    signedIn: Boolean,
    account: Account?,
    onSignIn: () -> Unit,
    onClick: (() -> Unit)? = null,
    grouped: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (grouped) {
                    Modifier
                } else {
                    Modifier
                        .padding(horizontal = GROUP_INSET)
                        .clip(GroupShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                },
            )
            .then(
                when {
                    signedIn && onClick != null -> Modifier.clickable(onClick = onClick)
                    !signedIn -> Modifier.clickable(onClick = onSignIn)
                    else -> Modifier
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (account?.thumbnailUrl != null) {
            AsyncImage(
                model = account.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(52.dp).clip(CircleShape).thumbnailBorder(CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outline),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SfIcons.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = account?.name
                    ?: stringResource(if (signedIn) R.string.signed_in else R.string.not_signed_in),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = account?.email?.takeIf { it.isNotBlank() }
                    ?: stringResource(
                        if (signedIn) R.string.youtube_music_account else R.string.tap_to_sign_in_google,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!signedIn) {
            Spacer(Modifier.width(8.dp))
            Chevron()
        }
    }
}

@Composable
internal fun BypassedByBitPerfect(bypassed: Boolean, content: @Composable () -> Unit) {
    val alpha by animateFloatAsState(if (bypassed) 0.38f else 1f, label = "bypassed")
    Column(
        Modifier
            .alpha(alpha)
            .then(
                if (bypassed) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
                            var sideways: Boolean? = null
                            var travel = Offset.Zero
                            do {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { change ->
                                    when {
                                        change.changedToUpIgnoreConsumed() -> change.consume()
                                        change.positionChanged() -> {
                                            travel += change.positionChange()
                                            if (sideways == null && travel.getDistance() > viewConfiguration.touchSlop / 2) {
                                                sideways = abs(travel.x) > abs(travel.y)
                                            }
                                            if (sideways == true) change.consume()
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        content()
    }
}

@Composable
internal fun BitPerfectMode.localizedLabel(): String = stringResource(
    when (this) {
        BitPerfectMode.OFF -> R.string.off
        BitPerfectMode.USB_ANDROID -> R.string.bit_perfect_mode_usb_android
        BitPerfectMode.DAP_EXCLUSIVE -> R.string.bit_perfect_mode_dap_exclusive
        BitPerfectMode.USB_DIRECT -> R.string.bit_perfect_mode_usb_direct
    },
)

internal val BitPerfectMode.isSupported: Boolean
    get() = when (this) {
        BitPerfectMode.OFF -> true
        BitPerfectMode.USB_ANDROID -> Build.VERSION.SDK_INT >= 34
        BitPerfectMode.DAP_EXCLUSIVE -> Build.VERSION.SDK_INT >= 27
        BitPerfectMode.USB_DIRECT -> true
    }

@Composable
internal fun BitPerfectMode.description(): String = stringResource(
    when {
        this == BitPerfectMode.USB_ANDROID && !isSupported -> R.string.bit_perfect_requires_android_14
        this == BitPerfectMode.DAP_EXCLUSIVE && !isSupported -> R.string.bit_perfect_requires_android_8_1
        this == BitPerfectMode.OFF -> R.string.bit_perfect_mode_off_subtitle
        this == BitPerfectMode.USB_ANDROID -> R.string.bit_perfect_mode_usb_android_subtitle
        this == BitPerfectMode.USB_DIRECT -> R.string.bit_perfect_mode_usb_direct_subtitle
        else -> R.string.bit_perfect_mode_dap_exclusive_subtitle
    },
)

@Composable
internal fun bitPerfectSubtitle(mode: BitPerfectMode, status: BitPerfectOutput.Status): String = when {
    mode == BitPerfectMode.OFF || !mode.isSupported -> mode.description()
    status.active && status.dacLimited -> stringResource(
        R.string.bit_perfect_status_dac_limited,
        status.deviceName.orEmpty(),
        "%.1f".format(status.sampleRateHz / 1000.0),
        status.outputBits,
    )
    status.active && !status.exact -> stringResource(
        R.string.bit_perfect_status_inexact,
        status.deviceName.orEmpty(),
    )
    status.active -> stringResource(
        R.string.bit_perfect_status_active,
        status.deviceName.orEmpty(),
        "%.1f".format(status.sampleRateHz / 1000.0),
        status.outputBits,
    )
    status.fallbackReason != null -> stringResource(R.string.bit_perfect_status_unavailable, status.fallbackReason)
    else -> stringResource(R.string.bit_perfect_status_waiting)
}
