package com.bxane.nuvx.data.scrobbling

internal fun String.primaryArtist(): String =
    split(PRIMARY_ARTIST_SEPARATOR, limit = 2).first().trim().ifBlank { this }

private val PRIMARY_ARTIST_SEPARATOR = Regex("""\s*,\s*|\s+&\s+|\s+＆\s+""")
