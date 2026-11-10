package com.bxane.nuvx.ui.screens

import com.bxane.nuvx.data.model.artworkAt
import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.bxane.nuvx.R
import com.bxane.nuvx.data.listentogether.ListenTogether
import com.bxane.nuvx.data.listentogether.PartyPreview
import com.bxane.nuvx.ui.components.PillTextField
import com.bxane.nuvx.ui.components.QrCode

internal sealed interface PartySheet {
    data object Create : PartySheet

    data object JoinCode : PartySheet

    data class Confirm(val preview: PartyPreview, val server: String?) : PartySheet

    data object Invite : PartySheet
}

@Composable
internal fun GlowingAvatar(
    url: String?,
    modifier: Modifier = Modifier,
    size: Dp = 104.dp,
    glow: Color = MaterialTheme.colorScheme.primary,
) {
    val breathe = rememberInfiniteTransition(label = "partyGlow")
    val spread by breathe.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(2_600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "partyGlowSpread",
    )
    val intensity by breathe.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(3_400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "partyGlowIntensity",
    )

    Box(
        modifier = modifier.size(size * 2.1f),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size * 1.75f)
                .scale(spread)
                .blur(28.dp, BlurredEdgeTreatment.Unbounded)
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glow.copy(alpha = intensity),
                            glow.copy(alpha = intensity * 0.35f),
                            Color.Transparent,
                        ),
                    ),
                    shape = CircleShape,
                ),
        )
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .border(2.dp, Color.White.copy(alpha = 0.12f), CircleShape),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(2.dp, Color.White.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SfIcons.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(size * 0.45f),
                )
            }
        }
    }
}

@Composable
internal fun PartyLanding(
    avatarUrl: String?,
    enabled: Boolean,
    onCreate: () -> Unit,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        GlowingAvatar(url = avatarUrl)
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.listen_together_landing_title),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.listen_together_landing_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onCreate,
            enabled = enabled,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                text = stringResource(R.string.listen_together_create_action),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        TextButton(
            onClick = onJoin,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(
                text = stringResource(R.string.listen_together_join_existing),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SheetHeader(title: String, subtitle: String? = null) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    if (subtitle != null) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SheetError(message: String?) {
    if (message.isNullOrBlank()) return
    Spacer(Modifier.height(14.dp))
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun CreatePartySheet(
    avatarUrl: String?,
    nickname: String,
    onNicknameChange: (String) -> Unit,
    maxMembers: Int,
    onMaxMembersChange: (Int) -> Unit,
    busy: Boolean,
    enabled: Boolean,
    error: String?,
    onCreate: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SheetHeader(
            title = stringResource(R.string.listen_together_create),
            subtitle = stringResource(R.string.listen_together_create_sheet_subtitle),
        )
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PartySheetAvatar(url = avatarUrl)
            Spacer(Modifier.width(ICON_GAP))
            PillTextField(
                value = nickname,
                onValueChange = onNicknameChange,
                placeholder = stringResource(R.string.listen_together_nickname_hint),
                container = MaterialTheme.colorScheme.surfaceVariant,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            )
        }
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.listen_together_party_size),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.listen_together_party_size_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StepperButton("−", enabled = enabled && maxMembers > 2) {
                onMaxMembersChange(maxMembers - 1)
            }
            Text(
                "$maxMembers",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(32.dp),
                textAlign = TextAlign.Center,
            )
            StepperButton("+", enabled = enabled && maxMembers < 10) {
                onMaxMembersChange(maxMembers + 1)
            }
        }
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onCreate,
            enabled = enabled && !busy,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                text = stringResource(
                    if (busy) R.string.listen_together_creating else R.string.listen_together_create_action,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        SheetError(error)
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(label, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PartySheetAvatar(url: String?, size: Dp = 40.dp) {
    if (url != null) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier.size(size).clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                SfIcons.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

@Composable
internal fun JoinPartySheet(
    code: String,
    onCodeChange: (String) -> Unit,
    busy: Boolean,
    enabled: Boolean,
    error: String?,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SheetHeader(
            title = stringResource(R.string.listen_together_join),
            subtitle = stringResource(R.string.listen_together_code_hint),
        )
        Spacer(Modifier.height(22.dp))
        PartyCodeField(
            code = code,
            onCodeChange = onCodeChange,
            enabled = enabled && !busy,
            onSubmit = onSubmit,
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onSubmit,
            enabled = enabled && !busy && code.length == ListenTogether.CODE_LENGTH,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                text = stringResource(
                    if (busy) R.string.listen_together_joining else R.string.listen_together_join_action,
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        SheetError(error)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
internal fun MemberAvatarStack(
    members: List<Pair<String, String?>>,
    total: Int,
    modifier: Modifier = Modifier,
    faceSize: Dp = 52.dp,
    shown: Int = 3,
) {
    val faces = members.take(shown)
    val extra = (total - faces.size).coerceAtLeast(0)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy((-12).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        faces.forEach { (name, avatar) ->
            Box(
                modifier = Modifier
                    .size(faceSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (avatar != null) {
                    AsyncImage(
                        model = avatar.artworkAt(512),
                        contentDescription = null,
                        modifier = Modifier.size(faceSize).clip(CircleShape),
                    )
                } else {
                    Text(
                        text = name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (extra > 0) {
            Box(
                modifier = Modifier
                    .size(faceSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(2.dp, MaterialTheme.colorScheme.background, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "+$extra",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
internal fun JoinConfirmSheet(
    preview: PartyPreview,
    avatarUrl: String?,
    nickname: String,
    onNicknameChange: (String) -> Unit,
    busy: Boolean,
    error: String?,
    onJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MemberAvatarStack(
            members = preview.members.map { it.displayName to it.avatarUrl },
            total = preview.memberCount,
        )
        Spacer(Modifier.height(18.dp))
        Text(
            text = preview.hostName.takeIf { it.isNotBlank() }
                ?.let { stringResource(R.string.listen_together_join_question, it) }
                ?: stringResource(R.string.listen_together_join_question_unnamed),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (preview.memberCount > 0) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    SfIcons.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        R.string.listen_together_listening,
                        preview.memberCount,
                        preview.maxMembers,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PartySheetAvatar(url = avatarUrl)
            Spacer(Modifier.width(ICON_GAP))
            PillTextField(
                value = nickname,
                onValueChange = onNicknameChange,
                placeholder = stringResource(R.string.listen_together_nickname_hint),
                container = MaterialTheme.colorScheme.surfaceVariant,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            )
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onJoin,
            enabled = !busy && !preview.isFull,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                text = stringResource(
                    when {
                        preview.isFull -> R.string.listen_together_full
                        busy -> R.string.listen_together_joining
                        else -> R.string.listen_together_join_action
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        SheetError(error)
        Spacer(Modifier.height(4.dp))
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) {
            Text(
                text = stringResource(R.string.listen_together_not_now),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.listen_together_preview_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
internal fun InviteSheet(
    code: String,
    link: String,
    onShareLink: () -> Unit,
    onCopyCode: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SheetHeader(title = stringResource(R.string.listen_together_invite_title))
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onShareLink,
            shape = RoundedCornerShape(percent = 50),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(SfIcons.Link, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.listen_together_share_link),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(
                    text = stringResource(R.string.listen_together_scan_qr),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.listen_together_scan_qr_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                TextButton(
                    onClick = onCopyCode,
                    contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
                    modifier = Modifier.offset(x = (-12).dp),
                ) {
                    Text(
                        text = code,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 3.sp,
                        ),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            QrCode(content = link, size = 132.dp)
        }
        Spacer(Modifier.height(16.dp))
    }
}
