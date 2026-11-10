package com.bxane.nuvx.ui.components

import com.bxane.nuvx.ui.icons.SfIcons
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.R
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.data.sources.addon.AddonAuth
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import java.util.Locale

data class AppLanguage(val tag: String, val nameRes: Int)

val SUPPORTED_LANGUAGES = listOf(
    AppLanguage("en", R.string.english),
    AppLanguage("es", R.string.spanish),
    AppLanguage("fr", R.string.french),
    AppLanguage("de", R.string.german),
    AppLanguage("pl", R.string.polish),
    AppLanguage("pt", R.string.portuguese),
    AppLanguage("id", R.string.indonesian),
    AppLanguage("hi", R.string.hindi),
    AppLanguage("ja", R.string.japanese),
    AppLanguage("ru", R.string.russian),
    AppLanguage("zh-CN", R.string.chinese_simplified),
    AppLanguage("zh-TW", R.string.chinese_traditional),
    AppLanguage("he", R.string.hebrew),
    AppLanguage("it", R.string.italian),
    AppLanguage("tr", R.string.turkish),
    AppLanguage("th", R.string.thai),
    AppLanguage("vi", R.string.vietnamese),
    AppLanguage("ko", R.string.korean),
)

fun languageDisplayNameRes(languageTag: String): Int {
    val norm = com.bxane.nuvx.data.LocaleTags.normalizeAppTag(languageTag)
    return SUPPORTED_LANGUAGES.firstOrNull {
        com.bxane.nuvx.data.LocaleTags.normalizeAppTag(it.tag) == norm
    }?.nameRes ?: R.string.english
}

private val LANGUAGE_LIST_MAX_HEIGHT = 340.dp

private const val TESTER_TAPS = 10

private const val TESTER_TAP_GAP_MS = 2_000L

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun AppLanguageDialog(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()

    var titleTaps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var showTesterCode by remember { mutableStateOf(false) }
    if (showTesterCode) {
        TesterCodeAlert(hazeState = hazeState, onDismiss = onDismiss)
        return
    }

    val shape = RoundedCornerShape(ALERT_CORNER)
    val currentLanguage = com.bxane.nuvx.data.LocaleTags.normalizeAppTag(
        AppCompatDelegate.getApplicationLocales().get(0)?.toLanguageTag()
            ?: Locale.getDefault().toLanguageTag(),
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SCRIM_COLOR)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(ALERT_WIDTH)
                .clip(shape)
                .then(
                    if (reduceDynamicBlur) {
                        Modifier.background(MaterialTheme.colorScheme.surface)
                    } else {
                        Modifier.optimizedHazeEffect(
                            state = hazeState,
                            style = HazeMaterials.regular(MaterialTheme.colorScheme.surface),
                        )
                    },
                )
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 19.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.app_language),
                    modifier = Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                    ) {
                        val now = System.currentTimeMillis()
                        titleTaps = if (now - lastTapAt > TESTER_TAP_GAP_MS) 1 else titleTaps + 1
                        lastTapAt = now
                        if (titleTaps >= TESTER_TAPS) showTesterCode = true
                    },
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.W600,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.app_language_description),
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }

            LazyColumn(modifier = Modifier.heightIn(max = LANGUAGE_LIST_MAX_HEIGHT)) {
                items(SUPPORTED_LANGUAGES, key = { it.tag }) { language ->
                    AlertRule()
                    LanguageRow(
                        language = language,
                        selected = com.bxane.nuvx.data.LocaleTags.normalizeAppTag(language.tag) == currentLanguage,
                        onClick = {
                            AppCompatDelegate.setApplicationLocales(
                                LocaleListCompat.forLanguageTags(language.tag),
                            )
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageRow(language: AppLanguage, selected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ACTION_HEIGHT)
            .background(
                if (pressed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.09f) else Color.Transparent,
            )
            .clickable(
                indication = null,
                interactionSource = interactionSource,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(language.nameRes),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = SfIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

@Composable
private fun TesterCodeAlert(hazeState: HazeState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val saved = remember { AddonAuth.hasTesterCode() }
    var input by remember { mutableStateOf("") }
    TextValueAlert(
        hazeState = hazeState,
        title = stringResource(R.string.tester_code_title),
        message = stringResource(if (saved) R.string.tester_code_message_saved else R.string.tester_code_message),
        placeholder = stringResource(R.string.tester_code_placeholder),
        value = input,
        onValueChange = { input = it },
        saveEnabled = AddonAuth.normalizeTesterCode(input).isNotEmpty(),
        onSave = {
            if (AddonAuth.normalizeTesterCode(input).isNotEmpty()) {
                AddonAuth.setTesterCode(input)
                android.widget.Toast.makeText(context, R.string.tester_code_saved, android.widget.Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        },
        onRemove = if (saved) {
            {
                AddonAuth.setTesterCode(null)
                android.widget.Toast.makeText(context, R.string.tester_code_removed, android.widget.Toast.LENGTH_SHORT).show()
                onDismiss()
            }
        } else {
            null
        },
        onDismiss = onDismiss,
    )
}
