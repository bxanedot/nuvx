package com.bxane.nuvx.data.settings

enum class TrackAnalysisState {
    WAITING,

    ANALYSING,

    ANALYSED,

    REFINING,

    FAILED,
}

data class SmartAnalysis(
    val current: TrackAnalysisState = TrackAnalysisState.WAITING,
    val next: TrackAnalysisState = TrackAnalysisState.WAITING,
)

data class TransitionWindow(val start: Float, val end: Float)

data class MixBlend(
    val beatMs: Float,
    val beatAnchorNanos: Long,
    val playing: Boolean,
)

class MixDeck(
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val bpm: Double,
    val key: String,
    val durationMs: Long,
    val waveStart: Double,
    val waveEnd: Double,
    val low: FloatArray = FloatArray(0),
    val mid: FloatArray = FloatArray(0),
    val high: FloatArray = FloatArray(0),
    val analysing: Boolean = false,
    val decoding: Boolean = false,
) {
    fun withWave(low: FloatArray, mid: FloatArray, high: FloatArray, start: Double, end: Double) = MixDeck(
        title, artist, artworkUrl, bpm, key, durationMs, start, end, low, mid, high, analysing, decoding,
    )

    fun withDecoding(decoding: Boolean) = if (decoding == this.decoding) this else MixDeck(
        title, artist, artworkUrl, bpm, key, durationMs, waveStart, waveEnd, low, mid, high, analysing, decoding,
    )
}

class MixTransitionView(
    val outgoing: MixDeck,
    val incoming: MixDeck,
    val outStart: Double,
    val outEnd: Double,
    val inStart: Double,
    val inEnd: Double,
    val beats: Int,
    val beatMarks: FloatArray,
    val fadeOut: FloatArray,
    val fadeIn: FloatArray,
    val bassSwapAt: Float = -1f,
) {
    fun withDecks(outgoing: MixDeck, incoming: MixDeck) = MixTransitionView(
        outgoing, incoming, outStart, outEnd, inStart, inEnd, beats, beatMarks, fadeOut, fadeIn, bassSwapAt,
    )
}
