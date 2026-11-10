package com.bxane.nuvx.data

object DebugLog {
    fun interface Sink {
        fun log(level: Char, tag: String, message: String, error: Throwable?)
    }

    @Volatile
    var sink: Sink? = null

    fun d(tag: String, message: String) = sink?.log('D', tag, message, null) ?: Unit

    fun i(tag: String, message: String) = sink?.log('I', tag, message, null) ?: Unit

    fun w(tag: String, message: String) = sink?.log('W', tag, message, null) ?: Unit

    fun w(tag: String, message: String, error: Throwable) = sink?.log('W', tag, message, error) ?: Unit

    fun e(tag: String, message: String) = sink?.log('E', tag, message, null) ?: Unit

    fun e(tag: String, message: String, error: Throwable) = sink?.log('E', tag, message, error) ?: Unit
}
