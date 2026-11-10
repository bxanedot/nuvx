// SPDX-License-Identifier: GPL-3.0-or-later
package com.bxane.nuvx.ui.screens

import com.bxane.nuvx.ui.icons.SfIcons
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.bxane.nuvx.R
import com.bxane.nuvx.data.model.LIVE_RADIO_PREFIX
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.data.radio.RadioBrowser
import com.bxane.nuvx.data.radio.RadioCountry
import com.bxane.nuvx.data.radio.RadioStation
import com.bxane.nuvx.ui.components.PAGE_GUTTER
import com.composables.sfsymbols.SfEffectRepeat
import com.composables.sfsymbols.SfRenderingMode
import com.composables.sfsymbols.SfSymbol
import com.composables.sfsymbols.SfSymbolEffect
import com.composables.sfsymbols.SfSymbolIcon
import com.composables.sfsymbols.SfWeight
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

val RadioSymbol: SfSymbol by lazy {
    SfSymbol.named("dot.radiowaves.left.and.right") ?: SfSymbol.named("antenna.radiowaves.left.and.right")!!
}

fun RadioStation.toSong(): Song = Song(
    videoId = LIVE_RADIO_PREFIX + id,
    title = name.trim(),
    artist = country.ifBlank { subtitle },
    thumbnailUrl = favicon.takeIf { it.startsWith("http") },
    localUri = streamUrl,
)

private object RadioDirectory {
    val countries = MutableStateFlow<List<RadioCountry>?>(null)
    val stations = MutableStateFlow<Map<String, List<RadioStation>>>(emptyMap())
    var selected: String? = null
}

@Composable
fun RadioScreen(
    playingId: String?,
    isPlaying: Boolean,
    onPlay: (RadioStation) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val countries by RadioDirectory.countries.collectAsStateWithLifecycle()
    val cache by RadioDirectory.stations.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(RadioDirectory.selected) }
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableStateOf(0) }
    val focus = LocalFocusManager.current

    LaunchedEffect(retry) {
        if (countries != null) return@LaunchedEffect
        runCatching { RadioBrowser.countries() }
            .onSuccess { list ->
                val home = Locale.getDefault().country.uppercase()
                RadioDirectory.countries.value = list.sortedByDescending { it.code.equals(home, true) }
                if (selected == null) selected = (list.firstOrNull { it.code.equals(home, true) } ?: list.firstOrNull())?.code
            }
            .onFailure { error = it.message }
    }
    val key = if (submitted.isNotBlank()) "search:$submitted" else selected
    LaunchedEffect(key, retry) {
        RadioDirectory.selected = selected
        if (key == null || key in cache) return@LaunchedEffect
        error = null
        runCatching {
            if (submitted.isNotBlank()) RadioBrowser.search(submitted) else RadioBrowser.topStations(key)
        }
            .onSuccess { RadioDirectory.stations.value += key to it }
            .onFailure { error = it.message }
    }
    val stations = key?.let { cache[it] }

    LazyColumn(state = listState, contentPadding = contentPadding, modifier = modifier.fillMaxSize()) {
        item(key = "title") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, top = 8.dp, bottom = 14.dp),
            ) {
                Text(
                    text = stringResource(R.string.radio),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.width(12.dp))
                SfSymbolIcon(
                    symbol = RadioSymbol,
                    size = 30.dp,
                    weight = SfWeight.Semibold,
                    mode = SfRenderingMode.Hierarchical,
                    tint = MaterialTheme.colorScheme.primary,
                    effect = SfSymbolEffect.VariableColor(),
                    repeat = SfEffectRepeat.Continuous,
                    active = isPlaying && playingId?.startsWith(LIVE_RADIO_PREFIX) == true,
                    contentDescription = null,
                )
            }
        }
        item(key = "search") {
            SearchPill(
                query = query,
                onQueryChange = {
                    query = it
                    if (it.isBlank()) submitted = ""
                },
                onSubmit = {
                    submitted = query.trim()
                    focus.clearFocus()
                },
            )
        }
        if (submitted.isBlank()) item(key = "countries") {
            val list = countries
            if (list == null) {
                Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
                    if (error == null) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            } else {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = PAGE_GUTTER),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 10.dp),
                ) {
                    items(list, key = { it.code }) { country ->
                        CountryChip(country, country.code == selected) { selected = country.code }
                    }
                }
            }
        }
        when {
            error != null && stations == null -> item(key = "error") {
                Column(
                    Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        stringResource(R.string.couldnt_load_radio),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.retry),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clip(CircleShape).clickable {
                            error = null
                            retry++
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            stations == null -> item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                }
            }
            stations.isEmpty() -> item(key = "empty") {
                Text(
                    stringResource(R.string.radio_no_stations),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
            else -> items(stations, key = { "$key/${it.id}" }) { station ->
                StationRow(
                    station = station,
                    onAir = playingId == LIVE_RADIO_PREFIX + station.id,
                    isPlaying = isPlaying,
                    onClick = {
                        focus.clearFocus()
                        onPlay(station)
                    },
                )
            }
        }
    }
}

@Composable
private fun SearchPill(query: String, onQueryChange: (String) -> Unit, onSubmit: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = PAGE_GUTTER)
            .fillMaxWidth()
            .height(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 14.dp),
    ) {
        Icon(SfIcons.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(stringResource(R.string.search_stations), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CountryChip(country: RadioCountry, selected: Boolean, onClick: () -> Unit) {
    val fill by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.surfaceVariant,
        label = "chip",
    )
    val ink by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
        label = "chipInk",
    )
    Text(
        text = "${country.flag} ${country.name}".trim(),
        color = ink,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(fill)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

@Composable
private fun StationRow(station: RadioStation, onAir: Boolean, isPlaying: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = PAGE_GUTTER, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            SfSymbolIcon(
                symbol = RadioSymbol,
                size = 22.dp,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = null,
            )
            if (station.favicon.startsWith("http")) {
                AsyncImage(
                    model = station.favicon,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                station.name.trim(),
                style = MaterialTheme.typography.bodyLarge,
                color = if (onAir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            station.subtitle.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        AnimatedVisibility(onAir, enter = fadeIn() + slideInVertically { it / 2 }) {
            SfSymbolIcon(
                symbol = SfSymbol.named("waveform") ?: RadioSymbol,
                size = 20.dp,
                tint = MaterialTheme.colorScheme.primary,
                effect = SfSymbolEffect.VariableColor(),
                repeat = SfEffectRepeat.Continuous,
                active = isPlaying,
                contentDescription = null,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}
