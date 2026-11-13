package com.bxane.nuvx.data.remote
















internal object Percent {

    private const val HEX = "0123456789ABCDEF"


    fun decode(value: String): String {
        if ('%' !in value) return value
        val bytes = ArrayList<Byte>(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (ch == '%' && index + 2 < value.length) {
                val hex = value.substring(index + 1, index + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes.add(hex.toByte())
                    index += 3
                    continue
                }
            }
            for (byte in ch.toString().encodeToByteArray()) bytes.add(byte)
            index++
        }
        return bytes.toByteArray().decodeToString()
    }








    fun encodeSegment(segment: String): String {
        val safe = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@"
        val out = StringBuilder(segment.length)
        for (byte in segment.encodeToByteArray()) {
            val char = byte.toInt().toChar()
            if (byte >= 0 && safe.indexOf(char) >= 0) {
                out.append(char)
            } else {
                out.append('%')
                out.append(HEX[(byte.toInt() shr 4) and 0xF])
                out.append(HEX[byte.toInt() and 0xF])
            }
        }
        return out.toString()
    }
}
