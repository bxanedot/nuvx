package com.bxane.nuvx.data.sources

enum class SourceKind(
    val label: String,
    val detail: String,
    val labels: List<String>,
    val needsServer: Boolean,
    val canServeLossless: Boolean,
    val worthPrefetching: Boolean = false,
    val rank: Int,
) {
    ADDON(
        label = "Addon",
        detail = "An addon server you host or were given a link to. Searched and streamed over " +
            "plain HTTP — no code is downloaded or run.",
        labels = listOf("FLAC", "Lossless", "Hi-Res"),
        needsServer = true,
        canServeLossless = true,
        rank = 0,
    ),

    CUSTOM_MODULE(
        label = "Custom module",
        detail = "Your own compatible module index. Tried before the built-in one.",
        labels = listOf("FLAC", "Lossless", "Hi-Res", "Plugins"),
        needsServer = true,
        canServeLossless = true,
        rank = 0,
    ),

    MODULE(
        label = "Module source",
        detail = "A URL to a compatible module index. Modules are JS plugins that " +
            "can search and stream from services like Tidal, Qobuz, Apple Music and more.",
        labels = listOf("FLAC", "Lossless", "Hi-Res", "Plugins"),
        needsServer = true,
        canServeLossless = true,
        rank = 1,
    ),

    JIOSAAVN(
        label = "JioSaavn",
        detail = "Optional JioSaavn streams up to 320kbps AAC/MP4. Catalogue matching can select the wrong song.",
        labels = listOf("High Quality", "320kbps"),
        needsServer = false,
        canServeLossless = false,
        worthPrefetching = true,
        rank = 2,
    ),

    YOUTUBE(
        label = "YouTube Music",
        detail = "The full catalogue, at Opus up to about 171 kbps. Lossy — there is no " +
            "lossless rendition to ask for.",
        labels = listOf("Lossy", "Full catalogue", "Radio"),
        needsServer = false,
        canServeLossless = false,
        rank = 3,
    ),
    ;

    val isUserAdded: Boolean get() = rank == USER_ADDED

    companion object {
        const val USER_ADDED = 0
    }
}
