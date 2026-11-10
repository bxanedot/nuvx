package com.bxane.nuvx.recognition

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.bxane.nuvx.R
import com.bxane.nuvx.ui.components.NoticeAction
import com.bxane.nuvx.ui.components.NoticeAlert
import com.bxane.nuvx.ui.haptics.Haptic
import com.bxane.nuvx.ui.haptics.rememberHaptics
import com.bxane.nuvx.ui.icons.SfIcons
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch

@Composable
fun TopBarRecognizeButton(visible: Boolean, onClick: () -> Unit) {
    val haptics = rememberHaptics()
    AnimatedVisibility(
        visible = visible,
        enter = expandHorizontally(tween(260), expandFrom = Alignment.End) + fadeIn(tween(160, delayMillis = 100)),
        exit = fadeOut(tween(120)) + shrinkHorizontally(tween(220, delayMillis = 60), shrinkTowards = Alignment.End),
    ) {
        IconButton(onClick = { haptics.play(Haptic.Select); onClick() }) {
            Icon(SfIcons.Recognize, stringResource(R.string.recognize_song), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
fun RecognitionSheet(hazeState: HazeState, onSearch: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by MusicRecognitionService.recognitionStatus.collectAsState()
    val start = { scope.launch { MusicRecognitionService.recognize(context) }; Unit }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else onDismiss()
    }
    LaunchedEffect(Unit) {
        MusicRecognitionService.reset()
        if (MusicRecognitionService.hasRecordPermission(context)) start() else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    val close = { MusicRecognitionService.reset(); onDismiss() }
    val retry = NoticeAction(stringResource(R.string.try_again)) { start() }
    val cancel = NoticeAction(stringResource(R.string.cancel), onClick = close)
    when (val s = status) {
        is RecognitionStatus.Success -> {
            val r = s.result
            Box {
                NoticeAlert(
                    title = r.title,
                    body = r.artist,
                    actions = listOf(
                        NoticeAction(stringResource(R.string.recognize_find), emphasised = true) {
                            close(); onSearch("${r.title} ${r.artist}")
                        },
                        retry,
                        cancel,
                    ),
                    hazeState = hazeState,
                    onDismiss = close,
                )
                (r.coverArtHqUrl ?: r.coverArtUrl)?.let { cover ->
                    AsyncImage(
                        cover,
                        null,
                        Modifier.align(Alignment.Center).graphicsLayer { translationY = -130.dp.toPx() }
                            .size(120.dp).clip(RoundedCornerShape(10.dp)),
                    )
                }
            }
        }
        is RecognitionStatus.NoMatch, is RecognitionStatus.Error -> NoticeAlert(
            title = stringResource(R.string.recognize_no_match),
            body = (s as? RecognitionStatus.Error)?.message ?: stringResource(R.string.recognize_no_match_body),
            actions = listOf(retry.copy(emphasised = true), cancel),
            hazeState = hazeState,
            onDismiss = close,
        )
        else -> Box {
            NoticeAlert(
                title = stringResource(if (s is RecognitionStatus.Processing) R.string.recognize_matching else R.string.recognize_listening),
                body = stringResource(R.string.recognize_listening_body),
                actions = listOf(cancel),
                hazeState = hazeState,
                onDismiss = close,
            )
            ListeningPulse(Modifier.align(Alignment.Center).graphicsLayer { translationY = -120.dp.toPx() })
        }
    }
}

@Composable
private fun ListeningPulse(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "listen")
    val scale by t.animateFloat(1f, 1.35f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    Box(modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(72.dp).graphicsLayer { scaleX = scale; scaleY = scale; alpha = 1.6f - scale }
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), CircleShape),
        )
        Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(SfIcons.Recognize, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
        }
    }
}
