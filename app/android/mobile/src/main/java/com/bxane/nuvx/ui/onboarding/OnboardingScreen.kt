package com.bxane.nuvx.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bxane.nuvx.R
import com.bxane.nuvx.sharedui.resources.Res
import com.bxane.nuvx.sharedui.resources.ic_google_g
import com.bxane.nuvx.sharedui.resources.ic_logo
import org.jetbrains.compose.resources.painterResource
import com.bxane.nuvx.ui.player.MeshGradientBackground
import com.bxane.nuvx.ui.player.MESH_CYCLE_MILLIS
import com.bxane.nuvx.ui.player.MeshPalette
import com.bxane.nuvx.ui.player.WelcomeMeshPalettes
import com.bxane.nuvx.ui.player.cyclingMeshPalette
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.Spring
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bxane.nuvx.data.settings.AppSettings
import com.bxane.nuvx.sharedui.resources.spotify_logo
import com.bxane.nuvx.ui.icons.SfIcons

@Composable
fun OnboardingScreen(
    signedIn: Boolean,
    initialName: String,
    onNameChosen: (String) -> Unit,
    onSignIn: () -> Unit,
    onSpotifyLogin: (() -> Unit)? = null,
    onDiscordLogin: (() -> Unit)? = null,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by rememberSaveable { mutableIntStateOf(PAGE_NAME) }
    var name by rememberSaveable { mutableStateOf(initialName) }
    val focusManager = LocalFocusManager.current

    BackHandler(enabled = page == PAGE_CONNECT) { page = PAGE_NAME }
    LaunchedEffect(signedIn, page) {
        if (signedIn && page == PAGE_CONNECT) onFinish()
    }

    val palette = cyclingMeshPalette(if (page == PAGE_NAME) WelcomeMeshPalettes else ConnectPalettes)

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
    ) {
        MeshGradientBackground(
            palette = palette,
            trackKey = page,
            continuous = true,
            driftMillis = 6_000,
            colorFadeMillis = MESH_CYCLE_MILLIS,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.05f),
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.45f),
                    ),
                ),
        )

        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState > initialState
                val slide = tween<IntOffset>(420)
                (slideInHorizontally(slide) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(320, 100)))
                    .togetherWith(
                        slideOutHorizontally(slide) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(200)),
                    )
            },
            label = "onboardingPage",
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
        ) { shown ->
            OnboardingPage {
                when (shown) {
                    PAGE_NAME -> NamePage(
                        name = name,
                        onNameChange = { name = it.take(NAME_LIMIT) },
                        onNext = next@{
                            val chosen = name.trim()
                            if (chosen.isEmpty()) return@next
                            focusManager.clearFocus()
                            onNameChosen(chosen)
                            if (signedIn) onFinish() else page = PAGE_CONNECT
                        },
                    )
                    else -> ConnectPage(
                        onBack = { page = PAGE_NAME },
                        onSignIn = onSignIn,
                        onSpotifyLogin = onSpotifyLogin,
                        onDiscordLogin = onDiscordLogin,
                        onSkip = onFinish,
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingPage(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxSize()
                .widthIn(max = 520.dp)
                .padding(horizontal = 28.dp),
            content = content,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.NamePage(
    name: String,
    onNameChange: (String) -> Unit,
    onNext: () -> Unit,
) {
    val keyboardUp = WindowInsets.isImeVisible
    AnimatedVisibility(
        visible = !keyboardUp,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Column {
            Spacer(Modifier.height(56.dp))
            Image(
                painter = painterResource(Res.drawable.ic_logo),
                contentDescription = null,
                modifier = Modifier.size(width = 46.dp, height = 30.dp),
            )
        }
    }
    Spacer(Modifier.height(24.dp))
    Title(stringResource(R.string.onboarding_title))

    Spacer(Modifier.weight(1f))

    Text(
        text = stringResource(R.string.onboarding_name_prompt),
        style = MaterialTheme.typography.titleMedium,
        fontSize = 17.sp,
        color = Color.White.copy(alpha = 0.78f),
        modifier = Modifier.padding(start = 4.dp),
    )
    Spacer(Modifier.height(12.dp))
    NameField(value = name, onValueChange = onNameChange, onDone = onNext)
    Spacer(Modifier.height(10.dp))
    Text(
        text = stringResource(R.string.onboarding_name_footer),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.55f),
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(28.dp))
    PillButton(
        label = stringResource(R.string.onboarding_next),
        onClick = onNext,
        enabled = name.isNotBlank(),
        primary = true,
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun ColumnScope.ConnectPage(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSpotifyLogin: (() -> Unit)?,
    onDiscordLogin: (() -> Unit)?,
    onSkip: () -> Unit,
) {
    val spotifyConnected = AppSettings.spotifySpdcToken.collectAsStateWithLifecycle().value.isNotBlank()
    val discordConnected = AppSettings.discordToken.collectAsStateWithLifecycle().value.isNotBlank()
    Spacer(Modifier.height(16.dp))
    BackButton(onBack)
    Spacer(Modifier.height(20.dp))
    Title(stringResource(R.string.onboarding_connect_title))
    Spacer(Modifier.height(14.dp))
    Text(
        text = stringResource(R.string.onboarding_connect_body),
        style = MaterialTheme.typography.bodyLarge,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        color = Color.White.copy(alpha = 0.72f),
    )

    Spacer(Modifier.weight(1f))

    PillButton(
        label = stringResource(R.string.onboarding_sign_in_google),
        onClick = onSignIn,
        primary = true,
        leading = {
            Image(
                painter = painterResource(Res.drawable.ic_google_g),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        },
    )
    onSpotifyLogin?.let { open ->
        Spacer(Modifier.height(12.dp))
        PillButton(
            label = stringResource(R.string.onboarding_connect_spotify),
            onClick = open,
            primary = false,
            leading = {
                ConnectMark(spotifyConnected) {
                    Icon(
                        painter = painterResource(Res.drawable.spotify_logo),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
    }
    onDiscordLogin?.let { open ->
        Spacer(Modifier.height(12.dp))
        PillButton(
            label = stringResource(R.string.onboarding_connect_discord),
            onClick = open,
            primary = false,
            leading = {
                ConnectMark(discordConnected) {
                    Icon(
                        imageVector = ImageVector.vectorResource(R.drawable.ic_discord),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
    }
    Spacer(Modifier.height(12.dp))
    PillButton(
        label = stringResource(
            if (spotifyConnected || discordConnected) R.string.done else R.string.onboarding_skip_sign_in,
        ),
        onClick = onSkip,
        primary = false,
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun ConnectMark(connected: Boolean, mark: @Composable () -> Unit) {
    AnimatedContent(
        targetState = connected,
        transitionSpec = {
            (scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.4f) + fadeIn())
                .togetherWith(scaleOut(targetScale = 0.4f) + fadeOut())
        },
        label = "connectMark",
    ) { done ->
        if (done) {
            Icon(
                imageVector = SfIcons.Check,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        } else {
            mark()
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.displayLarge.copy(
            fontSize = 40.sp,
            lineHeight = 44.sp,
            letterSpacing = (-1.2).sp,
        ),
        color = Color.White,
    )
}

@Composable
private fun NameField(value: String, onValueChange: (String) -> Unit, onDone: () -> Unit) {
    val style = MaterialTheme.typography.displayLarge.copy(
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.6).sp,
        color = Color.White,
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = stringResource(R.string.onboarding_name_hint),
                style = style,
                color = Color.White.copy(alpha = 0.38f),
                maxLines = 1,
            )
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Next,
            ),
            keyboardActions = KeyboardActions(onNext = { onDone() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PillButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, spring(stiffness = 900f), label = "pillScale")
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, tween(180), label = "pillAlpha")
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .clip(CircleShape)
            .background(if (primary) Color.White else Color.White.copy(alpha = 0.16f))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontSize = 17.sp,
            fontWeight = FontWeight.W700,
            color = if (primary) Color.Black else Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = SfIcons.ChevronRight,
            contentDescription = stringResource(R.string.onboarding_back),
            tint = Color.White,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { scaleX = -1f },
        )
    }
}

private const val PAGE_NAME = 0
private const val PAGE_CONNECT = 1

private const val NAME_LIMIT = 40

private val ConnectPalettes = listOf(
    MeshPalette(listOf(Color(0xFFFF1E3C), Color(0xFFC2185B), Color(0xFFFF8F3D), Color(0xFF4A1C8C))),
    MeshPalette(listOf(Color(0xFFE53935), Color(0xFFFF6E40), Color(0xFFAD1457), Color(0xFF311B92))),
    MeshPalette(listOf(Color(0xFFFF3D00), Color(0xFFD81B60), Color(0xFF8E24AA), Color(0xFFB71C1C))),
    MeshPalette(listOf(Color(0xFFF50057), Color(0xFFFF9100), Color(0xFFC51162), Color(0xFF4527A0))),
)
