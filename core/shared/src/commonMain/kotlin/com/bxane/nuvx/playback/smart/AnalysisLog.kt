package com.bxane.nuvx.playback.smart

object AnalysisLog {

    private var sink: ((String, Throwable?) -> Unit)? = null

    fun install(sink: (String, Throwable?) -> Unit) {
        this.sink = sink
    }

    fun warn(message: String, error: Throwable? = null) {
        sink?.invoke(message, error)
    }
}
