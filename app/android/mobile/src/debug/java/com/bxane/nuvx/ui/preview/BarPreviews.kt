package com.bxane.nuvx.ui.preview

import com.bxane.nuvx.ui.icons.SfIcons
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bxane.nuvx.data.model.Song
import com.bxane.nuvx.ui.components.BottomFadeScrim
import com.bxane.nuvx.ui.components.BottomTab
import com.bxane.nuvx.ui.components.FloatingBottomBar
import com.bxane.nuvx.ui.components.FrostedTopBar
import com.bxane.nuvx.ui.components.MiniPlayer
import com.bxane.nuvx.ui.components.TopFadeBlur
import com.bxane.nuvx.ui.components.TopBarBlur
import com.bxane.nuvx.ui.theme.NuvxTheme
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

@Composable
private fun MockFeed(modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(12) { i ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (i % 2 == 0) Color(0xFF3A3A3C) else Color(0xFF8E8E93)),
                )
                Column(Modifier.padding(start = 12.dp)) {
                    Text("Track title $i", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Artist name",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val PreviewSong = Song(
    videoId = "preview",
    title = "Rains Again",
    artist = "Solji",
    thumbnailUrl = null,
)

private val PreviewTabs = listOf(
    BottomTab("Play", SfIcons.PlayArrow),
    BottomTab("Explore", SfIcons.TabExplore),
    BottomTab("Library", SfIcons.TabLibrary),
    BottomTab("Search", SfIcons.Search),
)

@Composable
private fun ChromeStack(scrolled: Boolean, withMiniPlayer: Boolean) {
    val hazeState = remember { HazeState() }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        MockFeed(Modifier.hazeSource(hazeState))

        TopBarBlur(
            hazeState = hazeState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
        FrostedTopBar(
            title = "Listen Now",
            scrolled = { scrolled },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        BottomFadeScrim(
            withMiniPlayer = withMiniPlayer,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (withMiniPlayer) {
                MiniPlayer(
                    song = PreviewSong,
                    isPlaying = true,
                    isLoading = false,
                    hazeState = hazeState,
                    onPlayPause = {},
                    onNext = {},
                    onPrevious = {},
                    onExpand = {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FloatingBottomBar(
                tabs = PreviewTabs,
                selectedIndex = 0,
                onTabSelected = {},
                hazeState = hazeState,
            )
        }
    }
}

@Preview(name = "Chrome · dark", device = "id:pixel_8", showBackground = true)
@Composable
private fun ChromeDarkPreview() {
    NuvxTheme(darkTheme = true) { ChromeStack(scrolled = true, withMiniPlayer = false) }
}

@Preview(name = "Chrome · light", device = "id:pixel_8", showBackground = true)
@Composable
private fun ChromeLightPreview() {
    NuvxTheme(darkTheme = false) { ChromeStack(scrolled = true, withMiniPlayer = false) }
}

@Preview(name = "Chrome · mini player", device = "id:pixel_8", showBackground = true)
@Composable
private fun ChromeMiniPlayerPreview() {
    NuvxTheme(darkTheme = true) { ChromeStack(scrolled = true, withMiniPlayer = true) }
}

@Preview(name = "Mini player · dark", widthDp = 400, heightDp = 260)
@Preview(
    name = "Mini player · light",
    widthDp = 400,
    heightDp = 260,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
private fun MiniPlayerPreview() {
    NuvxTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val haze = remember { HazeState() }
            MiniPlayer(
                song = PreviewSong,
                isPlaying = true,
                isLoading = false,
                hazeState = haze,
                onPlayPause = {}, onNext = {}, onPrevious = {}, onExpand = {},
                modifier = Modifier.fillMaxWidth(),
            )
            MiniPlayer(
                song = PreviewSong.copy(title = "A considerably longer track title that has to truncate"),
                isPlaying = false,
                isLoading = false,
                hazeState = haze,
                onPlayPause = {}, onNext = {}, onPrevious = {}, onExpand = {},
                modifier = Modifier.fillMaxWidth(),
            )
            MiniPlayer(
                song = PreviewSong,
                isPlaying = false,
                isLoading = true,
                hazeState = haze,
                onPlayPause = {}, onNext = {}, onPrevious = {}, onExpand = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Preview(name = "Scrim ramp · dark", widthDp = 300, heightDp = 260)
@Preview(
    name = "Scrim ramp · light",
    widthDp = 300,
    heightDp = 260,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
private fun ScrimRampPreview() {
    NuvxTheme {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF7A7A7E)),
            )
            BottomFadeScrim(modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Preview(name = "Top bar · backdrops", device = "id:pixel_8", showBackground = true)
@Composable
private fun TopBarBackdropPreview() {
    NuvxTheme(darkTheme = true) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Box(Modifier.height(160.dp)) {
                FrostedTopBar(title = "At rest", scrolled = { false })
            }
            Box(Modifier.height(160.dp)) {
                FrostedTopBar(title = "Scrolled", scrolled = { true })
            }
            Box(Modifier.height(160.dp)) {
                FrostedTopBar(title = "Pushed page", scrolled = { true }, onBack = {})
            }
        }
    }
}
