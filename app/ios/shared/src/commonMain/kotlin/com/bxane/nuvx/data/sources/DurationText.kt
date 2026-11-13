package com.bxane.nuvx.data.sources














internal fun mmss(totalSeconds: Int?): String? {
    if (totalSeconds == null || totalSeconds < 0) return null
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (seconds < 10) "$minutes:0$seconds" else "$minutes:$seconds"
}








internal fun percent(byte: Int): String {
    val digits = "0123456789ABCDEF"
    return "%" + digits[byte shr 4] + digits[byte and 0x0F]
}
