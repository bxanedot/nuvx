package com.bxane.nuvx.data.remote

































object EmbeddedArt {








    data class Picture(val bytes: ByteArray, val mime: String) {


        override fun equals(other: Any?): Boolean =
            other is Picture && mime == other.mime && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + mime.hashCode()
    }














    interface Reader {

        val size: Long









        suspend fun read(offset: Long, length: Int): ByteArray
    }

    const val UNKNOWN_SIZE: Long = Long.MAX_VALUE







    const val MAX_PICTURE_BYTES: Int = 8 * 1024 * 1024


    internal const val MAX_WALK_BYTES: Long = 16L * 1024 * 1024








    suspend fun extract(reader: Reader): Picture? = runCatching { extractOrNull(reader) }.getOrNull()

    private suspend fun extractOrNull(reader: Reader): Picture? {
        val magic = reader.read(0, 12)
        if (magic.size < 12) return null
        return when {
            magic[0] == 'I'.code.toByte() && magic[1] == 'D'.code.toByte() && magic[2] == '3'.code.toByte() ->
                id3(reader, magic)

            magic[0] == 'f'.code.toByte() && string(magic, 1, 4) == "LaC" -> flac(reader)
            string(magic, 4, 8) == "ftyp" -> mp4(reader)
            string(magic, 0, 3) == "OggS" -> ogg(reader)
            else -> null
        }
    }











    private suspend fun flac(reader: Reader): Picture? {
        val cursor = Cursor(reader)
        cursor.skip(4)
        var walked = 0L
        while (true) {
            val head = cursor.bytes(4)
            if (head.size < 4) return null
            val isLast = (head[0].toInt() and 0x80) != 0
            val type = head[0].toInt() and 0x7F
            val length = u24(head, 1).toLong()
            if (length < 0 || length > MAX_PICTURE_BYTES + 1024L) return null
            walked += 4
            if (walked > MAX_WALK_BYTES) return null
            if (type == 6) return flacPicture(reader, cursor.position, length.toInt())
            cursor.skip(length)
            if (isLast) return null
        }
    }









    internal suspend fun flacPicture(reader: Reader, at: Long, length: Int): Picture? {
        val head = reader.read(at, minOf(length, 64))
        if (head.size < 8) return null
        var pos = 0
        pos += 4
        val mimeLen = u32(head, pos); pos += 4
        if (mimeLen < 0 || pos + mimeLen > head.size) return null
        val declaredMime = string(head, pos, pos + mimeLen); pos += mimeLen
        val descLen = u32(head, pos); pos += 4
        if (descLen < 0) return null





        val lengthAt = at + pos + descLen + 16
        val dataLen = if (pos + descLen + 20 <= head.size) u32(head, pos + descLen + 16)
        else u32(reader.read(lengthAt, 4), 0)
        if (dataLen <= 0 || dataLen > MAX_PICTURE_BYTES) return null
        val bytes = reader.read(lengthAt + 4, dataLen)
        if (bytes.size != dataLen) return null
        return picture(bytes, declaredMime)
    }










    private suspend fun id3(reader: Reader, magic: ByteArray): Picture? {
        val version = magic[3].toInt() and 0xFF
        if (version !in 2..4) return null
        val unsynchronised = (magic[5].toInt() and 0x80) != 0
        val tagSize = syncsafe(magic, 6)
        if (tagSize <= 0 || tagSize > MAX_WALK_BYTES) return null
        var tag = reader.read(10, tagSize)
        if (tag.size < tagSize) return null
        if (unsynchronised) tag = deunsync(tag)

        var pos = 0



        if ((magic[5].toInt() and 0x40) != 0) {
            val declared = if (version == 4) syncsafe(tag, 0) else u32(tag, 0)
            pos = listOf(declared, declared - 4, 6, 10)
                .firstOrNull { it in 0..tag.size && framesStart(tag, it, version) } ?: return null
        }

        val idLength = if (version == 2) 3 else 4
        val headLength = if (version == 2) 6 else 10
        while (pos + headLength <= tag.size) {
            val id = string(tag, pos, pos + idLength)
            if (id.isEmpty() || id.all { it == '\u0000' }) break
            val isPicture = if (version == 2) id == "PIC" else id == "APIC"
            val size = when {
                version == 2 -> u24(tag, pos + 3)
                version == 4 -> syncsafe(tag, pos + 4)
                else -> u32(tag, pos + 4)
            }


            if (size <= 0 || pos + headLength + size > tag.size || !id.all { it.isLetterOrDigit() }) {
                if (!isPicture) break
                return null
            }
            if (isPicture) {




                val payload = tag.copyOfRange(pos + headLength, pos + headLength + size)
                return pictureFromApic(payload, version)
            }
            pos += headLength + size
        }
        return null
    }


    private fun framesStart(tag: ByteArray, at: Int, version: Int): Boolean {
        if (at + 4 > tag.size) return false
        val next = tag.copyOfRange(at, at + 4)

        if (next.all { it.toInt() == 0 }) return true
        return next.all { it.toInt().toChar().isLetterOrDigit() }
    }










    private fun deunsync(bytes: ByteArray): ByteArray {
        if (bytes.size < 2) return bytes
        val out = ByteArray(bytes.size)
        var read = 0
        var written = 0
        while (read < bytes.size) {
            val byte = bytes[read]
            out[written++] = byte
            read++

            if (byte.toInt() and 0xFF == 0xFF && read < bytes.size && bytes[read].toInt() == 0) read++
        }
        return out.copyOf(written)
    }









    private fun pictureFromApic(payload: ByteArray, version: Int): Picture? {
        if (payload.size < 6) return null
        return if (version == 2) {

            val format = string(payload, 1, 4).uppercase()
            val mime = if (format == "PNG") "image/png" else "image/jpeg"
            var at = 5
            at = skipNull(payload, at, 1) ?: return null
            picture(payload.copyOfRange(at, payload.size), mime)
        } else {
            var at = 1
            val mimeEnd = payload.indexOfFirstNul(from = at) ?: return null
            val mime = string(payload, at, mimeEnd)
            at = mimeEnd + 1
            if (at >= payload.size) return null
            at += 1
            val encoding = payload[0].toInt() and 0xFF
            at = skipNull(payload, at, if (encoding == 1 || encoding == 2) 2 else 1) ?: return null
            picture(payload.copyOfRange(at, payload.size), mime)
        }
    }









    private fun skipNull(bytes: ByteArray, from: Int, width: Int): Int? {
        var i = from
        while (i + width <= bytes.size) {
            var allZero = true
            for (k in 0 until width) {
                if (bytes[i + k].toInt() != 0) { allZero = false; break }
            }
            if (allZero) return i + width
            i += if (width == 2 && bytes[i].toInt() != 0) 2 else 1
        }
        return null
    }

    private fun ByteArray.indexOfFirstNul(from: Int): Int? {
        for (i in from until size) if (this[i].toInt() == 0) return i
        return null
    }












    private suspend fun mp4(reader: Reader): Picture? {
        val cursor = Cursor(reader)
        val moov = findBox(cursor, 0, reader.size.takeIf { it != UNKNOWN_SIZE } ?: MAX_WALK_BYTES * 4, setOf("moov"), 0)
            ?: return null
        val udta = findBox(cursor, moov.first, moov.second, setOf("udta"), 0) ?: return null
        val meta = findBox(cursor, udta.first, udta.second, setOf("meta"), 0) ?: return null

        val ilst = findBox(cursor, meta.first + 4, meta.second, setOf("ilst"), 0) ?: return null
        val covr = findBox(cursor, ilst.first, ilst.second, setOf("covr"), 0) ?: return null
        val data = findBox(cursor, covr.first, covr.second, setOf("data"), 0) ?: return null

        val head = cursor.bytesAt(data.first, 8)
        if (head.size < 8) return null




        val length = (data.second - data.first - 8).toInt()
        if (length <= 0 || length > MAX_PICTURE_BYTES) return null
        val mime = when (u32(head, 0)) {
            13 -> "image/jpeg"
            14 -> "image/png"
            27 -> "image/bmp"

            else -> null
        }
        val bytes = reader.read(data.first + 8, length)
        if (bytes.size != length) return null
        return picture(bytes, mime)
    }


    private suspend fun findBox(cursor: Cursor, from: Long, until: Long, want: Set<String>, depth: Int): Pair<Long, Long>? {
        if (depth > 8 || from >= until) return null
        var at = from
        var walked = 0L
        while (at + 8 <= until && walked < MAX_WALK_BYTES) {
            val head = cursor.bytesAt(at, 8)
            if (head.size < 8) return null
            var size = u32(head, 0).toLong()
            val type = string(head, 4, 8)
            var headerLength = 8L
            if (size == 1L) {
                val ext = cursor.bytesAt(at + 8, 8)
                if (ext.size < 8) return null
                size = u64(ext, 0)
                headerLength = 16L
            } else if (size == 0L) {

                size = MAX_WALK_BYTES
            }
            if (size < headerLength || size > MAX_WALK_BYTES * 4) return null
            val payloadEnd = at + size
            if (type in want) return (at + headerLength) to payloadEnd
            walked += payloadEnd - at
            at = payloadEnd
        }
        return null
    }











    private suspend fun ogg(reader: Reader): Picture? {
        val cursor = Cursor(reader)
        var packet = ByteArray(0)
        var pages = 0
        while (pages++ < 64) {
            if (cursor.position > MAX_WALK_BYTES) return null
            val head = cursor.bytes(27)
            if (head.size < 27 || string(head, 0, 3) != "OggS") return null
            val continued = (head[5].toInt() and 0x01) != 0
            val segmentCount = head[26].toInt() and 0xFF
            val table = cursor.bytes(segmentCount)
            if (table.size < segmentCount) return null
            var bodyLength = 0
            for (segment in table) bodyLength += segment.toInt() and 0xFF
            val body = cursor.bytes(bodyLength)
            packet = if (continued) packet + body else body



            if (table.isNotEmpty() && (table[table.size - 1].toInt() and 0xFF) < 255) {
                if (packet.size > 7 && packet[0] == 0x03.toByte() && string(packet, 1, 3) == "vorbis") {
                    return vorbisPicture(packet, opus = false)
                }
                if (packet.size > 8 && string(packet, 0, 8) == "OpusTags") {
                    return vorbisPicture(packet, opus = true)
                }
                packet = ByteArray(0)
                if (pages > 4) return null
            }
        }
        return null
    }








    private fun vorbisPicture(packet: ByteArray, opus: Boolean): Picture? {
        var pos = if (opus) 8 else 7
        val vendorLength = u32le(packet, pos); pos += 4
        if (vendorLength < 0 || pos + vendorLength > packet.size) return null
        pos += vendorLength
        val count = u32le(packet, pos); pos += 4
        if (count < 0 || count > 10_000) return null
        repeat(count) {
            if (pos + 4 > packet.size) return null
            val length = u32le(packet, pos); pos += 4
            if (length < 0 || pos + length > packet.size) return null
            val field = string(packet, pos, pos + length)
            pos += length
            if (field.startsWith(METADATA_BLOCK_PICTURE, ignoreCase = true)) {
                return flacPictureBytes(field.substringAfter('='))
            }
        }
        return null
    }







    private fun flacPictureBytes(payload: String): Picture? {
        val bytes = decodeBase64Lenient(payload) ?: return null
        if (bytes.size < 32) return null
        var pos = 4
        val mimeLen = u32(bytes, pos); pos += 4
        if (mimeLen < 0 || pos + mimeLen > bytes.size) return null
        val declaredMime = string(bytes, pos, pos + mimeLen); pos += mimeLen
        val descLen = u32(bytes, pos); pos += 4
        if (descLen < 0 || pos + descLen + 16 > bytes.size) return null
        pos += descLen + 16
        val dataLength = u32(bytes, pos); pos += 4
        if (dataLength <= 0 || dataLength > MAX_PICTURE_BYTES || pos + dataLength > bytes.size) return null
        return picture(bytes.copyOfRange(pos, pos + dataLength), declaredMime)
    }











    private fun picture(bytes: ByteArray, mime: String?): Picture? {
        if (bytes.isEmpty() || bytes.size > MAX_PICTURE_BYTES) return null
        return Picture(bytes, mime?.takeIf { it.isNotBlank() } ?: sniffMime(bytes) ?: "image/jpeg")
    }









    fun sniff(bytes: ByteArray): String? = sniffMime(bytes)

    private fun sniffMime(bytes: ByteArray): String? {


        if (bytes.size < 2) return null
        return when {
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
            bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "image/gif"
            bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "image/bmp"

            bytes.size >= 12 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() -> "image/webp"
            else -> null
        }
    }








    private fun decodeBase64Lenient(value: String): ByteArray? {
        val cleaned = StringBuilder(value.length)
        for (ch in value) if (!ch.isWhitespace()) cleaned.append(ch)
        return runCatching { kotlin.io.encoding.Base64.Default.decode(cleaned.toString()) }.getOrNull()
    }

    private const val METADATA_BLOCK_PICTURE = "METADATA_BLOCK_PICTURE"













    private class Cursor(private val reader: Reader) {
        var position: Long = 0
            private set

        private var windowStart = 0L
        private var window = ByteArray(0)

        suspend fun bytes(length: Int): ByteArray {
            if (length <= 0) return ByteArray(0)
            val from = (position - windowStart).toInt()
            if (position < windowStart || from + length > window.size) {


                val wanted = maxOf(length.toLong(), 64L * 1024)
                    .coerceAtMost((reader.size - position).coerceAtLeast(0L))
                window = reader.read(position, wanted.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                windowStart = position
            }
            val start = (position - windowStart).toInt()
            if (start < 0 || start >= window.size) return ByteArray(0)
            val end = (start + length).coerceAtMost(window.size)
            position += (end - start)
            return window.copyOfRange(start, end)
        }

        suspend fun bytesAt(offset: Long, length: Int): ByteArray {
            val here = position
            position = offset
            val result = bytes(length)
            position = here
            return result
        }


        fun skip(length: Long) {
            position += length
        }
    }







    private fun syncsafe(bytes: ByteArray, at: Int): Int {
        if (at + 4 > bytes.size) return -1
        return ((bytes[at].toInt() and 0x7F) shl 21) or
            ((bytes[at + 1].toInt() and 0x7F) shl 14) or
            ((bytes[at + 2].toInt() and 0x7F) shl 7) or
            (bytes[at + 3].toInt() and 0x7F)
    }








    private fun u32(bytes: ByteArray, at: Int): Int {
        if (at + 4 > bytes.size) return -1
        val value = ((bytes[at].toLong() and 0xFF) shl 24) or
            ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or
            (bytes[at + 3].toLong() and 0xFF)
        return if (value > Int.MAX_VALUE) Int.MAX_VALUE else value.toInt()
    }

    private fun u24(bytes: ByteArray, at: Int): Int {
        if (at + 3 > bytes.size) return -1
        return ((bytes[at].toInt() and 0xFF) shl 16) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            (bytes[at + 2].toInt() and 0xFF)
    }

    private fun u64(bytes: ByteArray, at: Int): Long {
        if (at + 8 > bytes.size) return -1L
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }

    private fun u32le(bytes: ByteArray, at: Int): Int {
        if (at + 4 > bytes.size) return -1
        val value = ((bytes[at + 3].toLong() and 0xFF) shl 24) or
            ((bytes[at + 2].toLong() and 0xFF) shl 16) or
            ((bytes[at + 1].toLong() and 0xFF) shl 8) or
            (bytes[at].toLong() and 0xFF)
        return if (value > Int.MAX_VALUE) Int.MAX_VALUE else value.toInt()
    }

    private fun string(bytes: ByteArray, from: Int, to: Int): String {
        if (from < 0 || to > bytes.size || to <= from) return ""
        return bytes.copyOfRange(from, to).decodeToString()
    }
}
