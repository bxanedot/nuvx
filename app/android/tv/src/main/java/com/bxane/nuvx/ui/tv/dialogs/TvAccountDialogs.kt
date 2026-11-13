package com.bxane.nuvx.ui.tv.dialogs

import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bxane.nuvx.auth.WebSessionMode
import com.bxane.nuvx.data.settings.TvSettings
import com.bxane.nuvx.ui.MainViewModel
import com.bxane.nuvx.ui.configureTvSignInWebView
import com.bxane.nuvx.ui.onSignedIn
import com.bxane.nuvx.ui.tv.auth.TvAuthServer
import com.bxane.nuvx.ui.tv.auth.TvQrCodeView
import com.bxane.nuvx.ui.tv.components.TvActivityIndicator
import com.bxane.nuvx.ui.tv.components.TvButton
import com.bxane.nuvx.ui.tv.components.TvDialog
import com.bxane.nuvx.ui.tv.components.TvDialogButton
import com.bxane.nuvx.ui.tv.components.TvListRow
import com.bxane.nuvx.ui.tv.components.tvInitialFocus
import com.bxane.nuvx.ui.tv.theme.TvGlass
import com.bxane.nuvx.ui.tv.theme.TvType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private enum class AccountDialogMode { Profiles, SignInChoose, SignInPhone, SignInTv }

@Composable
fun TvAccountDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onSignOut: () -> Unit = {},
    allowPhoneSignIn: Boolean = false,
) {
    val googleAccounts by viewModel.googleAccounts.collectAsState()
    val activeAccountId by viewModel.activeAccountId.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val signedIn by viewModel.signedIn.collectAsState()

    var mode by remember {
        mutableStateOf(
            if (googleAccounts.isNotEmpty() || signedIn) {
                AccountDialogMode.Profiles
            } else if (allowPhoneSignIn) {
                AccountDialogMode.SignInChoose
            } else {
                AccountDialogMode.SignInTv
            }
        )
    }

    val backFromSignIn: () -> Unit = {
        if (googleAccounts.isNotEmpty() || signedIn) {
            mode = AccountDialogMode.Profiles
        } else if (allowPhoneSignIn) {
            mode = AccountDialogMode.SignInChoose
        } else {
            onDismiss()
        }
    }

    when (mode) {
        AccountDialogMode.Profiles -> {
            TvProfileSwitcher(
                googleAccounts = googleAccounts,
                activeAccountId = activeAccountId,
                activeProfileId = activeProfileId,
                onSelectProfile = { account, profile ->
                    viewModel.selectProfile(account.accountId, profile.profileId, profile)
                    onDismiss()
                },
                onRemoveAccount = { account ->
                    TvSettings.wipeAllPins()
                    viewModel.removeAccount(account.accountId)
                    onSignOut()
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        AccountDialogMode.SignInChoose -> TvDialog(
            title = "Sign In to YouTube Music",
            message = "Your library, playlists and recommendations follow your account.",
            onDismissRequest = backFromSignIn,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                TvListRow(
                    title = "Sign In on This TV",
                    subtitle = "Use the remote to sign in to Google.",
                    leadingIcon = SfIcons.Tv,
                    modifier = Modifier.tvInitialFocus(),
                    onClick = { mode = AccountDialogMode.SignInTv },
                )
                TvListRow(
                    title = "Sign In with Your Phone",
                    subtitle = "Scan a code with a phone on the same Wi-Fi.",
                    leadingIcon = SfIcons.PhoneIphone,
                    onClick = { mode = AccountDialogMode.SignInPhone },
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            TvDialogButton(
                text = if (googleAccounts.isNotEmpty() || signedIn) "Back" else "Not Now",
                onClick = backFromSignIn,
            )
        }
        AccountDialogMode.SignInPhone -> TvPhoneSignIn(viewModel = viewModel, onBack = backFromSignIn)
        AccountDialogMode.SignInTv -> TvGoogleSignIn(
            viewModel = viewModel,
            onBack = backFromSignIn,
            onSignedIn = onDismiss,
        )
    }
}

@Composable
private fun TvPhoneSignIn(viewModel: MainViewModel, onBack: () -> Unit) {
    var url by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        scope.launch {
            TvAuthServer.start { cookie ->
                scope.launch(Dispatchers.Main) { viewModel.onSignedIn(cookie) }
            }?.let { (port, ip, token) -> url = "http://$ip:$port/?pair=$token" }
        }
        onDispose { TvAuthServer.stop() }
    }

    TvDialog(title = "Sign In with Your Phone", width = 576.dp, onDismissRequest = onBack) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(29.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                val link = url
                if (link != null) TvQrCodeView(content = link, size = 147.dp) else TvActivityIndicator(color = Color.Black)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                TvStep(1, "Connect your phone to the same Wi-Fi as this TV.")
                TvStep(2, "Scan the code, or open ${url ?: "the address shown here"}.")
                TvStep(3, "Follow the steps on your phone. This TV signs in by itself.")
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        TvDialogButton(text = "Back", onClick = onBack, initialFocus = true, modifier = Modifier.width(256.dp))
    }
}

@Composable
private fun TvStep(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(21.dp)
                .clip(CircleShape)
                .background(TvGlass.Fill),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = number.toString(), style = TvType.Caption, color = TvGlass.TextPrimary)
        }
        Text(text = text, style = TvType.Callout, color = TvGlass.TextSecondary, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TvGoogleSignIn(viewModel: MainViewModel, onBack: () -> Unit, onSignedIn: () -> Unit) {
    var capture by remember { mutableIntStateOf(0) }
    val doneFocus = remember { FocusRequester() }
    var headerHasFocus by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = {
            if (headerHasFocus) onBack() else runCatching { doneFocus.requestFocus() }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF141416)),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 14.dp)
                    .onFocusChanged { headerHasFocus = it.hasFocus },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Sign In to YouTube Music",
                        style = TvType.Headline,
                        color = TvGlass.TextPrimary,
                    )
                    Text(
                        text = "Press Back for Done or Cancel",
                        style = TvType.Caption,
                        color = TvGlass.TextTertiary,
                    )
                }
                TvButton(text = "Cancel", onClick = onBack)
                Spacer(modifier = Modifier.width(10.dp))
                TvButton(text = "Done", isPrimary = true, onClick = { capture++ }, modifier = Modifier.focusRequester(doneFocus))
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                com.bxane.nuvx.auth.YtMusicLoginScreen(
                    mode = WebSessionMode.SIGN_IN,
                    configureWebView = ::configureTvSignInWebView,
                    captureRequest = capture,
                    onPageReady = { ready -> if (ready) capture++ },
                    onCaptured = { session ->
                        viewModel.onWebSession(session, WebSessionMode.SIGN_IN) { success ->
                            if (success) onSignedIn()
                        }
                    },
                )
            }
        }
    }
}
