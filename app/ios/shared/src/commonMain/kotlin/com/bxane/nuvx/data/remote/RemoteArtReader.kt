package com.bxane.nuvx.data.remote

import com.bxane.nuvx.data.http.Http











object RemoteArtReader {






















    fun http(url: String, authHeader: String? = null): EmbeddedArt.Reader = object : EmbeddedArt.Reader {



        override val size: Long = EmbeddedArt.UNKNOWN_SIZE

        override suspend fun read(offset: Long, length: Int): ByteArray {
            if (length <= 0 || offset < 0) return ByteArray(0)
            val headers = buildMap {
                if (authHeader != null) put("Authorization", authHeader)
                put("Range", "bytes=$offset-${offset + length - 1}")
            }
            val response = Http.getBytesRaw(url, headers, maxBytes = length)
            val body = response.body ?: return ByteArray(0)
            return when (response.status) {


                206 -> body.take(length).toByteArray()




                200 -> if (offset == 0L) body else ByteArray(0)

                416 -> ByteArray(0)
                else -> throw WebDavException("Range read failed with ${response.status}")
            }
        }
    }









    suspend fun picture(url: String, authHeader: String? = null): EmbeddedArt.Picture? =
        EmbeddedArt.extract(http(url, authHeader))
}
