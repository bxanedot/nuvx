package com.bxane.nuvx.data.innertube




object PlaybackTrackerBridge {
    fun onPlaying(videoId: String) = PlaybackTracker.onPlaying(videoId)
    fun onTrackChanged(positionSeconds: Long) = PlaybackTracker.onTrackChanged(positionSeconds)
    fun onProgress(videoId: String, positionSeconds: Long) =
        PlaybackTracker.onProgress(videoId, positionSeconds)
    fun onPlaybackFinished(positionSeconds: Long) =
        PlaybackTracker.onPlaybackFinished(positionSeconds)
}
