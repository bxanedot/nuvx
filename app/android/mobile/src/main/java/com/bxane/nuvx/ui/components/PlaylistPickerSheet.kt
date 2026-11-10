package com.bxane.nuvx.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.bxane.nuvx.R
import com.bxane.nuvx.data.model.PlaylistPrivacy
import com.bxane.nuvx.data.model.ROW_ART_PX
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.model.UserPlaylist
import com.bxane.nuvx.data.model.artworkAt
import com.bxane.nuvx.ui.components.thumbnailBorder
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPickerSheet(
    playlists: List<UserPlaylist>,
    loading: Boolean,
    onAdd: (List<UserPlaylist>) -> Unit,
    onCreate: (String, PlaylistPrivacy) -> Unit,
    onDismiss: () -> Unit,
    song: Song? = null,
    startCreating: Boolean = false,
    offline: Boolean = false,
) {
    var creating by remember { mutableStateOf(startCreating) }
    var selected by remember { mutableStateOf(emptySet<String>()) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val closeAfter: (() -> Unit) -> Unit = { action ->
        if (!closing) {
            closing = true
            action()
            scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RectangleShape,
        containerColor = MaterialTheme.colorScheme.background,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        BackHandler {
            if (creating && !startCreating) creating = false else closeAfter {}
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            if (creating) {
                NewPlaylistForm(
                    onBack = if (startCreating) null else ({ creating = false }),
                    onClose = { closeAfter {} },
                    onCreate = { name, privacy -> closeAfter { onCreate(name, privacy) } },
                    offline = offline,
                )
            } else {
                SheetTopBar(
                    leadingIcon = SfIcons.Close,
                    leadingDescription = stringResource(R.string.close),
                    onLeading = { closeAfter {} },
                    confirmDescription = if (selected.isEmpty()) {
                        stringResource(R.string.add_to_playlist)
                    } else {
                        pluralStringResource(R.plurals.add_to_playlists_count, selected.size, selected.size)
                    },
                    confirmEnabled = selected.isNotEmpty(),
                    onConfirm = { closeAfter { onAdd(playlists.filter { it.playlistId in selected }) } },
                )
                if (song != null) {
                    SheetTrackHeader(song)
                    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)
                }
                SheetHeading(
                    stringResource(if (song != null) R.string.add_to_playlist else R.string.your_playlists)
                        .uppercase(Locale.getDefault()),
                )

                ActionRow(
                    icon = SfIcons.Add,
                    label = stringResource(R.string.new_playlist),
                    onClick = { creating = true },
                )

                when {
                    loading && playlists.isEmpty() -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            strokeWidth = 2.5.dp,
                            modifier = Modifier.size(26.dp),
                        )
                    }

                    playlists.isEmpty() -> Text(
                        text = stringResource(R.string.no_playlists_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp),
                    )

                    else -> {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 6.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
                        ) {
                            items(playlists, key = { it.playlistId }) { playlist ->
                                val ticked = playlist.playlistId in selected
                                PlaylistRow(
                                    playlist = playlist,
                                    selected = ticked,
                                    onClick = {
                                        selected = if (ticked) selected - playlist.playlistId else selected + playlist.playlistId
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTopBar(
    leadingIcon: ImageVector,
    leadingDescription: String,
    onLeading: () -> Unit,
    confirmDescription: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    title: @Composable RowScope.() -> Unit = { Spacer(Modifier.weight(1f)) },
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onLeading) {
            Icon(
                leadingIcon,
                contentDescription = leadingDescription,
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        title()
        IconButton(onClick = onConfirm, enabled = confirmEnabled) {
            Icon(
                SfIcons.Check,
                contentDescription = confirmDescription,
                tint = if (confirmEnabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
            )
        }
    }
}

@Composable
private fun PlaylistRow(playlist: UserPlaylist, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = playlist.thumbnailUrl.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(7.dp))
                .thumbnailBorder(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (playlist.subtitle.isNotBlank()) {
                Text(
                    text = playlist.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Icon(
            imageVector = if (selected) SfIcons.CheckCircle else SfIcons.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun NewPlaylistForm(
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
    onCreate: (String, PlaylistPrivacy) -> Unit,
    offline: Boolean,
) {
    var name by remember { mutableStateOf("") }
    var privacy by remember { mutableStateOf(PlaylistPrivacy.PRIVATE) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val submit: () -> Unit = {
        if (name.isNotBlank()) {
            focusManager.clearFocus()
            onCreate(name, privacy)
        }
    }

    Column(Modifier.fillMaxWidth()) {
        SheetTopBar(
            leadingIcon = if (onBack != null) SfIcons.ArrowBack else SfIcons.Close,
            leadingDescription = stringResource(if (onBack != null) R.string.back else R.string.close),
            onLeading = {
                focusManager.clearFocus()
                (onBack ?: onClose)()
            },
            confirmDescription = stringResource(R.string.create_playlist),
            confirmEnabled = name.isNotBlank(),
            onConfirm = submit,
            title = {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.new_playlist),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = stringResource(
                            if (offline) R.string.saved_on_this_device else R.string.saved_to_youtube_music_account,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (name.isEmpty()) {
                    Text(
                        text = stringResource(R.string.playlist_name),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onBackground,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
            if (name.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable { name = "" },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        SfIcons.Close,
                        contentDescription = stringResource(R.string.clear_name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        if (!offline) {
            SheetHeading(stringResource(R.string.who_can_see_it).uppercase(Locale.getDefault()))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaylistPrivacy.entries.forEach { option ->
                    PrivacyPill(
                        icon = option.icon,
                        label = option.label,
                        selected = option == privacy,
                        onClick = { privacy = option },
                    )
                }
            }
        }

    }
}

@Composable
internal fun RenamePlaylistForm(
    playlist: UserPlaylist,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(playlist.title) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val submit: () -> Unit = {
        if (name.isNotBlank()) {
            focusManager.clearFocus()
            onRename(name)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 22.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    SfIcons.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = stringResource(R.string.rename_playlist),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outline)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 16.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            )
        }
        Button(
            onClick = submit,
            enabled = name.isNotBlank() && name != playlist.title,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
        ) {
            Text(stringResource(R.string.save_name))
        }
        Spacer(Modifier.height(28.dp))
    }
}

private val PlaylistPrivacy.icon: ImageVector
    get() = when (this) {
        PlaylistPrivacy.PRIVATE -> SfIcons.Lock
        PlaylistPrivacy.UNLISTED -> SfIcons.Link
        PlaylistPrivacy.PUBLIC -> SfIcons.Public
    }

@Composable
private fun PrivacyPill(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .clickable(onClick = onClick)
            .padding(start = 11.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = content,
        )
    }
}
