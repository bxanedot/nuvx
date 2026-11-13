package com.bxane.nuvx.data.innertube


















internal expect object PreprocessedPlayerStore {
    fun read(key: String): String?


    fun write(key: String, value: String?)
}
