package com.bxane.nuvx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.bxane.nuvx.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.data.settings.AppSettings
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/** One stacked action row: label, whether it is the default (bold) choice, and what it does. */
data class NoticeAction(val label: String, val emphasised: Boolean = false, val onClick: () -> Unit)

/**
 * A plain message in the same iOS-alert card as [UpdateAvailableDialog]: title,
 * body, then full-width actions. Tapping the scrim is [onDismiss].
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun NoticeAlert(
    title: String,
    body: String,
    actions: List<NoticeAction>,
    hazeState: HazeState,
    onDismiss: () -> Unit,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()
    Box(
        Modifier
            .fillMaxSize()
            .background(SCRIM_COLOR)
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = onDismiss),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Column(
            Modifier
                .width(ALERT_WIDTH)
                .clip(RoundedCornerShape(ALERT_CORNER))
                .then(
                    if (reduceDynamicBlur) Modifier.background(MaterialTheme.colorScheme.surface)
                    else Modifier.optimizedHazeEffect(state = hazeState, style = HazeMaterials.regular(MaterialTheme.colorScheme.surface)),
                )
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = {}),
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 19.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.W600),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = body,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
            actions.forEach { action ->
                AlertRule()
                AlertAction(label = action.label, emphasised = action.emphasised, onClick = action.onClick)
            }
        }
    }
}

/**
 * The app's own pop-ups: Radio's and Podcasts' first visits (offer to hide the tab, else say
 * where that lives), and the rare tip / GitHub-star ask. [held] keeps the
 * latter back while the welcome, a greeting or an update is up.
 */
@Composable
internal fun AppPrompts(radioOpen: Boolean, podcastsOpen: Boolean, held: Boolean, hazeState: HazeState) {
    val context = LocalContext.current
    var radioIntroStep by rememberSaveable { mutableIntStateOf(0) }
    var supportPrompt by rememberSaveable { mutableIntStateOf(AppSettings.nextSupportPrompt()) }
    var podcastsIntroStep by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(radioOpen) {
        if (radioOpen && !AppSettings.radioIntroShown.value) radioIntroStep = 1
    }
    LaunchedEffect(podcastsOpen) {
        if (podcastsOpen && !AppSettings.podcastsIntroShown.value) podcastsIntroStep = 1
    }
    if (radioIntroStep == 1) {
        NoticeAlert(
            title = stringResource(R.string.radio_intro_title),
            body = stringResource(R.string.radio_intro_body),
            actions = listOf(
                NoticeAction(stringResource(R.string.radio_intro_keep), emphasised = true) { radioIntroStep = 2 },
                NoticeAction(stringResource(R.string.hide_radio_tab)) {
                    AppSettings.setRadioIntroShown()
                    AppSettings.setHideRadioTab(true)
                    radioIntroStep = 0
                },
            ),
            hazeState = hazeState,
            onDismiss = { radioIntroStep = 2 },
        )
    } else if (radioIntroStep == 2) {
        val done = { AppSettings.setRadioIntroShown(); radioIntroStep = 0 }
        NoticeAlert(
            title = stringResource(R.string.radio_intro_title),
            body = stringResource(R.string.radio_intro_later),
            actions = listOf(NoticeAction(stringResource(android.R.string.ok), emphasised = true, onClick = done)),
            hazeState = hazeState,
            onDismiss = done,
        )
    }

    // Podcasts' first visit, the same two steps as Radio's.
    if (podcastsIntroStep == 1) {
        NoticeAlert(
            title = stringResource(R.string.podcasts),
            body = stringResource(R.string.podcasts_intro_body),
            actions = listOf(
                NoticeAction(stringResource(R.string.podcasts_intro_keep), emphasised = true) { podcastsIntroStep = 2 },
                NoticeAction(stringResource(R.string.hide_podcasts_tab)) {
                    AppSettings.setPodcastsIntroShown()
                    AppSettings.setHidePodcastsTab(true)
                    podcastsIntroStep = 0
                },
            ),
            hazeState = hazeState,
            onDismiss = { podcastsIntroStep = 2 },
        )
    } else if (podcastsIntroStep == 2) {
        val done = { AppSettings.setPodcastsIntroShown(); podcastsIntroStep = 0 }
        NoticeAlert(
            title = stringResource(R.string.podcasts),
            body = stringResource(R.string.podcasts_intro_later),
            actions = listOf(NoticeAction(stringResource(android.R.string.ok), emphasised = true, onClick = done)),
            hazeState = hazeState,
            onDismiss = done,
        )
    }

    // Now and then: a tip or a GitHub star (see AppSettings.nextSupportPrompt).
    if (supportPrompt != 0 && !held && radioIntroStep == 0 && podcastsIntroStep == 0) {
        val tip = supportPrompt == 1
        val url = if (tip) "https://ko-fi.com/bxane" else "https://github.com/bxanedot/nuvx"
        NoticeAlert(
            title = stringResource(if (tip) R.string.support_tip_title else R.string.support_star_title),
            body = stringResource(if (tip) R.string.support_tip_body else R.string.support_star_body),
            actions = listOf(
                NoticeAction(stringResource(if (tip) R.string.support_tip_action else R.string.support_star_action), emphasised = true) {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    supportPrompt = 0
                },
                NoticeAction(stringResource(R.string.not_now)) { supportPrompt = 0 },
                NoticeAction(stringResource(R.string.dont_show_again)) {
                    AppSettings.setSupportPromptsOff()
                    supportPrompt = 0
                },
            ),
            hazeState = hazeState,
            onDismiss = { supportPrompt = 0 },
        )
    }
}
